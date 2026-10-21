package com.nock

import androidx.compose.ui.graphics.toArgb
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.hardware.camera2.CaptureRequest
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phone side: camera on a tripod by the target, streaming a live view to the watch.
 *
 * Up to MAX_IN_FLIGHT frames are on the way at once; a new one goes out as soon as the watch acks
 * an earlier one (or one times out). The stream runs as fast as the link allows and no faster, and
 * the ack round trip is the real delay you see on the wrist.
 */
class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener, android.hardware.SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView // details card under the watch chip (tap the chip)
    private lateinit var numbers: LinearLayout
    private lateinit var roundPanel: LinearLayout
    private lateinit var roundTitle: TextView
    private lateinit var roundCount: TextView
    private lateinit var roundRows: LinearLayout
    private var chromeTouching = false
    // Ask before changing anything: a yes/no card.
    private val askText by lazy { label(15f, bold = true).apply { gravity = Gravity.CENTER; isSingleLine = false; maxLines = 3; maxWidth = dpi(300) } }
    private var askYes: () -> Unit = {}
    private val askYesLabel by lazy { label(14f, bold = true, color = Brand.Carbon) }

    /** A yes/no question: nothing changes unless you tap yes. */
    private fun ask(question: String, yes: String, onYes: () -> Unit) {
        askText.text = question; askYesLabel.text = yes; askYes = onYes
        askCard.fadeTo(true)
    }
    private val askCard: LinearLayout by lazy {
        fun choice(t: String, primary: Boolean, onTap: () -> Unit) = label(14f, bold = true, color = if (primary) Brand.Carbon else Brand.Smoke).apply {
            text = t; gravity = Gravity.CENTER
            background = pillBackground(context, if (primary) Brand.FernBright else 0x26F5F5F4)
            setPadding(dpi(20), dpi(10), dpi(20), dpi(10))
            setOnClickListener { onTap(); this@MainActivity.askCard.fadeTo(false) }
        }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(24f); setColor(Brand.Glass) }
            setPadding(dpi(20), dpi(18), dpi(20), dpi(18))
            addView(askText, LinearLayout.LayoutParams(-2, -2))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(choice("Not now", false) {})
                addView(askYesLabel.apply {
                    gravity = Gravity.CENTER
                    background = pillBackground(context, Brand.FernBright)
                    setPadding(dpi(20), dpi(10), dpi(20), dpi(10))
                    setOnClickListener { askYes(); this@MainActivity.askCard.fadeTo(false) }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(10) })
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(14) })
            visibility = View.GONE
        }
    }
    // Scoring only runs when you start it: the camera opens paused.
    private val playIcon by lazy { PlayPauseIcon(this).apply { color = Brand.Carbon } }
    private val playLabel by lazy { label(14f, bold = true).apply { text = "Start" } }
    // Bottom-right corner, same glass pill as the other controls: "→ Start", or a live dot + "Scoring" (tap to pause).
    private val startArrow by lazy { SleekArrow(this).apply { color = Brand.FernBright } }
    private val liveDot by lazy { View(this).apply { background = dot(context, Brand.FernBright); visibility = View.GONE } }
    private val playButton by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pillBackground(context)
            setPadding(dpi(14), dpi(10), dpi(16), dpi(10))
            addView(startArrow, LinearLayout.LayoutParams(dpi(26), dpi(12)).apply { marginEnd = dpi(10) })
            addView(liveDot, LinearLayout.LayoutParams(dpi(8), dpi(8)).apply { marginEnd = dpi(9) })
            addView(playLabel)
            setOnClickListener { if (shots.active) setScoring(false) else askStart() }
        }
    }

    // ---- Sides of the block: remembered, recognized when scoring starts ----
    private val memory by lazy { TargetMemory.shared(java.io.File(filesDir, "faces")) }
    @Volatile private var side: TargetMemory.Face? = null
    @Volatile private var inView: List<TargetMemory.Face> = emptyList() // every known target in sight right now
    @Volatile private var looking = false // lost sight (e.g. something in the way): looking again, nothing in view
    @Volatile private var wantLook = false // next frame: which side is this?
    @Volatile private var lookAsk = false  // and offer to mark it if unknown (on Start scoring)
    @Volatile private var relookAfterStill = false
    @Volatile private var lookHint: android.graphics.PointF? = null // where you held on the camera (view coords)
    private val held get() = SystemClock.elapsedRealtime() - heldAt < 1500 // the finger just held: its lift isn't a tap
    @Volatile private var holdPreview: androidx.camera.view.transform.OutputTransform? = null
    private var heldAt = 0L
    @Volatile private var zoomedAt = 0L
    private var trackZoom = 0f // zoom of the last frame the tracker followed (analysis thread)
    // Top left, always there and always the same size: target glyph + how many targets it sees, and a status dot like a
    // notification badge (green = found, pulsing amber = half-sees one, pulsing white = looking, grey = nothing in view).
    // Tap for what's in view; tap one of those for its details.
    private val sideTag by lazy { TargetPill(this).apply { setOnClickListener { inViewPopup() } } }
    @Volatile private var dark = false // the camera sees (nearly) black: covered, face down, or no light. No looks till it clears.
    private var darkCheckAt = 0L
    @Volatile private var unsureNow = false // the last look half-saw a target (picture match 0.6-0.9): "can't make it out yet"

    /**
     * What the camera sees, in one pop-up over the dimmed camera: each target in view (photo, size, bullseyes, who is
     * shooting it, this round), tap one for its details; then Mark new target and the marks on/off switch.
     */
    private var popScrim: FrameLayout? = null // the pop-up while it's open, redrawn when what's in view changes
    private var popShows: List<String>? = null
    private fun inViewPopup() {
        if (menu.visibility == View.VISIBLE) setMenu(false) // one thing open at a time
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val scrim = FrameLayout(this).apply { setBackgroundColor(0xB3000000.toInt()); isClickable = true }
        fun close() { root.removeView(scrim); if (popScrim === scrim) popScrim = null }
        scrim.setOnClickListener { close() }
        val all = inView
        popScrim = scrim; popShows = popKey()
        val metric = com.nock.data.Prefs(this).metric
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(26f); setColor(Brand.Carbon) }
            setPadding(dpi(18), dpi(18), dpi(18), dpi(14)); isClickable = true
            addView(label(18f, bold = true).apply { text = when { !xray -> "Highlights are off"; all.isEmpty() && dark -> "The camera can't see"; all.isEmpty() && manual && mine.isEmpty() -> "No targets picked"; all.isEmpty() && unsureNow -> "Can't make it out yet"; all.isEmpty() && looking -> "Looking…"; all.isEmpty() -> "No target in view"; all.size == 1 -> "1 target in view"; else -> "${all.size} targets in view" } })
            addView(label(13f, color = Brand.Dim).apply { isSingleLine = false
                text = when { !xray -> "Turn them on to see each target outlined."; all.isEmpty() && dark -> "It's covered or too dark. Uncover the camera or add light."; all.isEmpty() && unsureNow -> "Something looks like a saved target. Hold still, or move a little closer."; all.isEmpty() && looking -> "Hold the phone still for a moment."; all.isEmpty() && manual -> ""; all.isEmpty() -> "Point at a saved target, or mark a new one."; else -> "Tap one for its photo, sizes and bullseyes." }; if (text.isEmpty()) visibility = View.GONE },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(2) })
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (f in all) list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(8), 0, dpi(8))
            addView(android.widget.ImageView(context).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                clipToOutline = true; outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(10f)) }
                }
                hitExecutor.execute { val b = runCatching { memory.cutout(f, f.photo, 160) }.getOrNull(); runOnUiThread { setImageBitmap(b) } }
            }, LinearLayout.LayoutParams(dpi(52), dpi(52)).apply { marginEnd = dpi(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(15f, bold = true).apply { text = f.name })
                val bits = arrayListOf<String>()
                fun n(v: Double) = if (metric) "%.0f".format(v * 25.4) else if (v % 1.0 == 0.0) "%.0f".format(v) else "%.2f".format(v)
                if (f.marked) bits.add(n(f.widthIn) + " × " + n(f.heightIn) + if (metric) " mm" else " in")
                if (f.bulls.isNotEmpty()) bits.add("${f.bulls.size} " + if (f.bulls.size == 1) "bullseye" else "bullseyes")
                bits.add(personName(shooterFor[f.id] ?: com.nock.data.Prefs(context).active))
                roundTally[f.id]?.takeIf { it.isNotEmpty() }?.let { bits.add("${it.size} " + (if (it.size == 1) "arrow" else "arrows") + " \u00b7 ${it.sum()} pts") }
                addView(label(12f, color = Brand.Dim).apply { text = bits.joinToString(" \u00b7 "); isSingleLine = false })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ChevronIcon(context, right = true), LinearLayout.LayoutParams(dpi(24), dpi(24)))
            setOnClickListener { close(); openSheet(f) }
        })
        if (all.isNotEmpty()) card.addView(android.widget.ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8) })
        if (manual) card.addView(label(13f, color = Brand.Dim).apply { isSingleLine = false
            text = if (mine.isEmpty()) "Manual: pick the targets you're shooting (or hold one on the camera)."
                else "Manual: looking only for ${memory.faces().filter { it.id in mine }.joinToString(", ") { it.name }}." }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6) })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            if (manual) addView(label(14f, bold = true, color = Brand.Carbon).apply {
                text = "Pick targets"; gravity = Gravity.CENTER; background = pillBackground(context, Brand.FernBright)
                setPadding(dpi(18), dpi(10), dpi(18), dpi(10))
                setOnClickListener { close(); pickTargets() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(8) })
            addView(label(14f, bold = true).apply {
                text = "Mark new target"; gravity = Gravity.CENTER; background = pillBackground(context, 0x26F5F5F4)
                setPadding(dpi(18), dpi(10), dpi(18), dpi(10)); setOnClickListener { close(); wantMark = true }
            })
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(14); gravity = Gravity.CENTER_HORIZONTAL })
        scrim.addView(card, FrameLayout.LayoutParams(dpi(340), -2, Gravity.CENTER).apply { topMargin = dpi(24); bottomMargin = dpi(24) })
        root.addView(scrim, android.view.ViewGroup.LayoutParams(-1, -1))
    }
    private fun showSide() = runOnUiThread {
        // Exactly what's drawn: the count is the targets outlined right now (same list as the highlights), never a
        // target it can't see.
        val n = inView.size
        val state = when { !xray -> 0; n > 0 -> 3; dark -> 4; unsureNow -> 2; looking -> 1; else -> 4 }
        sideTag.count = n
        // the logo goes Amber while it half-sees a target ("can't make it out yet"); the number is dimmed at 0;
        // while it's still working (looking, or half-seeing) a white flash runs around the pill
        sideTag.tint = when { !xray -> Brand.Dim; state == 2 -> Brand.Amber; else -> Brand.Smoke }
        sideTag.textColor = if (xray && n > 0) Brand.Smoke else Brand.Dim
        sideTag.spinning = state == 1 || state == 2
        sideTag.contentDescription = when (state) { 0 -> "Highlights off"; 1 -> "Looking"; 2 -> "Can't make it out yet"; 3 -> "$n in view"; else -> "No target in view" }
        sideTag.visibility = View.VISIBLE; sideTag.alpha = 1f
        // pop-up open: show what's in view now (opened during the first look, it fills in when the look finishes)
        popScrim?.let { old -> if (popKey() != popShows) { (old.parent as? android.view.ViewGroup)?.removeView(old); popScrim = null; inViewPopup() } }
    }
    private fun popKey() = listOf(xray.toString(), dark.toString(), looking.toString(), unsureNow.toString()) + inView.map { it.id + ":" + (roundTally[it.id]?.size ?: 0) }

    /** Runs on the hit thread with an upright full-res frame: name the side, or offer to remember it. */
    @Volatile private var audited = false
    @Volatile private var lookBusy = false // one look at a time: a new one only once this one has landed
    @Volatile private var lookDoneAt = 0L
    @Volatile private var refreshLook = false // this look is only the regular re-align
    @Volatile private var fullAt = 0L // the last full search
    private val confirmedAt = java.util.concurrent.ConcurrentHashMap<String, Long>() // when each target was last found
    private fun lookAtTarget(frame: Bitmap, transform: androidx.camera.view.transform.OutputTransform?, key: Int = 0) {
        try { lookNow(frame, transform, key) } finally { lookDoneAt = SystemClock.elapsedRealtime(); lookBusy = false }
    }
    private fun lookNow(frame: Bitmap, transform: androidx.camera.view.transform.OutputTransform?, key: Int) {
        // Once per launch: make sure every learned view belongs to the right target (see TargetMemory.audit).
        if (!audited) { audited = true; runCatching { memory.audit() }.getOrNull()?.forEach { android.util.Log.d("NockShots", "audit: $it") } }
        // Dev aid: `adb shell run-as com.nock touch files/dump_look` saves the next look's frame as files/look.jpg.
        java.io.File(filesDir, "dump_look").takeIf { it.exists() }?.let { flag -> flag.delete()
            runCatching { java.io.File(filesDir, "look.jpg").outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 95, it) } } }
        // Targets that were on screen: snap them first (fast), then the full look below finds anything new.
        val tLook = android.os.SystemClock.elapsedRealtime()
        fun since() = android.os.SystemClock.elapsedRealtime() - tLook
        val expected = expect.toSet()
        val onScreen = (marks?.seen.orEmpty().map { it.face.id } + expected).distinct()
        // Targets already on screen: fine-aligned where tracking carried them and confirmed by picture (no search):
        // drawn at once, and the full look below only looks for the rest.
        val snapped = if (onScreen.isNotEmpty() && key != 0) runCatching { memory.quick(frame, onScreen) }.getOrNull().orEmpty() else emptyList()
        if (snapped.isNotEmpty()) {
            android.util.Log.d("NockShots", "snap: ${snapped.joinToString { it.face.name }} at ${since()} ms")
            // the ones on screen that didn't snap stay as tracked until the full look decides
            val rest = marks?.seen.orEmpty().filter { s -> snapped.none { it.face.id == s.face.id } }
            showOverlay(snapped + rest, marks?.unknown, transform, frame.copy(Bitmap.Config.ARGB_8888, false), key, final = false)
        }
        // A re-align (targets on screen, nothing new asked for): every target on screen snapped = done, no search. A full
        // search for anything new runs at most every FULL_EVERY_MS (a full look every 2 s kept the CPU busy for hours).
        val refresh = refreshLook; refreshLook = false
        if (refresh && snapped.size == onScreen.size && SystemClock.elapsedRealtime() - fullAt < FULL_EVERY_MS) {
            for (sn in snapped) confirmedAt[sn.face.id] = SystemClock.elapsedRealtime()
            showOverlay(snapped, marks?.unknown, transform, frame, key); return
        }
        fullAt = SystemClock.elapsedRealtime()
        // each target is drawn the moment it's found (the slow ones don't hold up the rest)
        val stillTracked = marks?.seen.orEmpty()
        // Manual: only the targets you've locked on are looked for (a hold looks for any, to lock the one you held)
        val only = if (manual && lookHint == null) HashSet(mine) else null
        var seen = runCatching { memory.findAll(frame, snapped, only) { sofar ->
            showOverlay(sofar + stillTracked.filter { t -> sofar.none { it.face.id == t.face.id } }, null, transform, frame.copy(Bitmap.Config.ARGB_8888, false), key, final = false)
        } }.getOrNull().orEmpty()
        // A target confirmed before and now partly off screen (zoomed in, phone turned) can't pass the whole-picture
        // check, so it stays where tracking carried it. One fully in view must be found again.
        val before = onScreen.toSet()
        seen = seen + before.filter { id -> seen.none { it.face.id == id } }.mapNotNull { id -> runCatching { memory.partlyInView(id, frame) }.getOrNull() }
        // Found again = confirmed now. A target that was on screen and isn't found, while the phone hasn't moved or
        // zoomed since it was last confirmed, is still there: something is in front of it (someone walking by made the
        // block or the rings drop out, 2026-10-08). It stays where it was for up to HOLD_MS.
        val tNow = SystemClock.elapsedRealtime(); val movedAt = maxOf(shots.phoneMovedAt, zoomedAt)
        for (s in seen) confirmedAt[s.face.id] = tNow
        for (id in before) if (seen.none { it.face.id == id }) {
            val conf = confirmedAt[id] ?: continue
            if (movedAt < conf && tNow - conf < HOLD_MS) runCatching { memory.atLast(id, frame) }.getOrNull()?.let { k ->
                seen = seen + k; android.util.Log.d("NockShots", "${k.face.name}: not seen, phone still - kept (something in front of it?)")
            }
        }
        // You held a spot on the camera: that's where the target is. Put the target there first, or look around it.
        val hint = lookHint?.let { h -> lookHint = null
            val pv = holdPreview; if (pv == null || transform == null) null else runCatching {
                val a = floatArrayOf(h.x, h.y); androidx.camera.view.transform.CoordinateTransform(pv, transform).mapPoints(a)
                org.opencv.core.Point(a[0].toDouble(), a[1].toDouble())
            }.getOrNull()
        }
        val held = hint?.let { p -> seen.firstOrNull { TargetMemory.inside(p, it.outline) } }
        if (held != null) seen = listOf(held) + (seen - held)
        // A target we don't know yet, besides the ones we do (a similar size, so a cupboard door doesn't count).
        val biggest = seen.maxOfOrNull { area(it.outline) } ?: 0.0
        val unknown = if (hint != null && held == null) runCatching { TargetGuess.findQuadNear(frame, android.graphics.PointF(hint.x.toFloat(), hint.y.toFloat())) }.getOrNull()
            else if (manual) null // locked on: no "New target?" guesses around the targets you're ignoring
            else runCatching { TargetGuess.findQuad(frame, seen.map { it.outline }) }.getOrNull()
            ?.takeIf { q -> biggest == 0.0 || area(q.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) }) > 0.4 * biggest }
        if (hint != null && held != null && manual) runOnUiThread { setMine(held.face, true); targetChip(held.face) } // held it = lock on it
        if (hint != null && held == null) {
            // couldn't make it out: an outline found there = which target is it (then line it up); nothing = draw it
            val still = frame.copy(Bitmap.Config.ARGB_8888, false)
            runOnUiThread { previewView.postDelayed({ if (overlayUnknown != null) unknownChoice() else ask("Can't make out a target there. Draw it?", "Draw it") { drawAt(still, hint) } }, 400) }
        }
        unknown?.let { q -> android.util.Log.d("NockShots", "new target? outline %.0f%% of the frame at %s".format(
            100 * area(q.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) }) / (frame.width.toDouble() * frame.height), q.joinToString { "(%.0f,%.0f)".format(it.x, it.y) })) }
        android.util.Log.d("NockShots", "look done at ${since()} ms (key $key)" + if (memory.unsure.isNotEmpty()) ", unsure about ${memory.unsure.size}" else "")
        // turned back to a remembered target: found = keep going; not where the gyro said 3 times = forget it
        for (id in expected) {
            expect.remove(id)
            if (seen.any { it.face.id == id }) { expectMiss.remove(id); android.util.Log.d("NockShots", "${memory.faces().firstOrNull { it.id == id }?.name}: back in view where the gyro said") }
            else if ((expectMiss.merge(id, 1, Int::plus) ?: 0) >= 3) {
                anchors.remove(id); expectMiss.remove(id); android.util.Log.d("NockShots", "$id: not where the gyro said - forgotten")
                // twice in one visit: the gyro's fit is likely off. Suggest calibrating (changes only on yes), once.
                if (++gyroMisses >= 2 && !askedCal && guided == null) { askedCal = true; runOnUiThread { ask("Target tags off when you turn? Calibrating the gyro fixes that.", "Calibrate") { startCal() } } }
            }
        }
        if (!showOverlay(seen, unknown, transform, frame, key, unsure = memory.unsure.isNotEmpty())) return // the view moved on: the next look decides
        if (seen.isNotEmpty()) {
            // Weak but real match (new light, angle or wear): keep this view so recognition stays strong. Only a
            // feature match in that range can teach anything (it re-matched every target after every look before).
            for (s in seen.filter { it.inliers in TargetMemory.LEARN_MIN until TargetMemory.LEARN_BELOW }) runCatching { memory.learn(s.face, frame) }.getOrNull()?.let { n ->
                android.util.Log.d("NockShots", "${s.face.name}: match $n points - learned a new view")
            }
            return
        }
        if (!lookAsk) return // opening the camera: just show what it sees, don't ask
        val spots = runCatching { hitFinder.face(frame) }.getOrNull()?.first
        runOnUiThread {
            ask("A target Nock doesn't know yet. Mark it so every arrow is measured in true inches?", "Mark it") {
                openMark(frame, spots ?: emptyList(), null)
            }
        }
    }

    private fun area(ps: List<org.opencv.core.Point>): Double {
        var a = 0.0; for (i in ps.indices) { val p = ps[i]; val q = ps[(i + 1) % ps.size]; a += p.x * q.y - q.x * p.y }; return kotlin.math.abs(a) / 2
    }

    // ---- Live target marks on the camera ----
    private val targetMarks: TargetOverlay by lazy {
        TargetOverlay(this, onKnown = { f -> side = f; showSide(); targetChip(f) }, onUnknown = { unknownChoice() })
    }
    // X-ray: show or hide the live target marks. Remembered.
    private var xray: Boolean
        get() = getSharedPreferences("nock", MODE_PRIVATE).getBoolean("xray", true)
        set(v) { getSharedPreferences("nock", MODE_PRIVATE).edit().putBoolean("xray", v).apply() }
    private val xrayIcon by lazy { XrayIcon(this) }
    private fun renderXrayOn() {
        xrayIcon.color = if (xray) Brand.Smoke else Brand.Dim // a neutral switch: white on, dimmed off (only the marks themselves are green)
        if (::xrayText.isInitialized) xrayText.text = if (xray) "Highlights on" else "Highlights off"
    }
    // HUD: the camera stays clean until you turn it on; then the level, zoom slider and speed stay up until you turn it off.
    private var hud = false
    private val hudIcon by lazy { LevelIcon(this).apply { color = Brand.Dim } }
    private fun renderHud() {
        hudIcon.color = if (hud) Brand.Smoke else Brand.Dim // a neutral tool: white when on
        if (::hudText.isInitialized) hudText.text = if (hud) "Level & zoom on" else "Level & zoom off"
        if (hud) { numbers.fadeTo(true); slider.fadeTo(true); slider.wake(); horizon.shown = true }
        else { numbers.fadeTo(false); slider.fadeTo(false); hidePresets(); qualityBar.fadeTo(false); horizon.shown = false }
    }
    private fun renderXray() { renderXrayOn(); showSide(); targetMarks.visibility = if (xray && targetMarks.childCount > 0) View.VISIBLE else View.GONE }
    // ---- Who's shooting which target, and this round's tally floating over each one ----
    private val shooterFor = java.util.concurrent.ConcurrentHashMap<String, String>() // target id -> person id
    private val roundTally = java.util.concurrent.ConcurrentHashMap<String, MutableList<Int>>() // target id -> scores
    private fun personName(id: String): String {
        val p = com.nock.data.Prefs(this)
        return if (id == com.nock.data.Prefs.GUEST) "Guest" else p.people.firstOrNull { it.first == id }?.second?.takeIf { it.isNotBlank() } ?: "You"
    }
    /** The tag on a target: who's shooting it once you've picked someone (else the target's name), then this round's tally. */
    private fun arLabel(f: TargetMemory.Face?): String? {
        f ?: return null
        val parts = arrayListOf(shooterFor[f.id]?.let { personName(it) } ?: f.name)
        roundTally[f.id]?.takeIf { it.isNotEmpty() }?.let { parts.add("${it.size} ${if (it.size == 1) "arrow" else "arrows"} · ${it.sum()} pts") }
        return parts.joinToString(" · ")
    }
    /** Who shoots which target stays picked (across sessions) until you change it. */
    private fun loadShooters() = runCatching {
        val j = org.json.JSONObject(getSharedPreferences("nock", MODE_PRIVATE).getString("shooters", "{}") ?: "{}")
        for (k in j.keys()) shooterFor[k] = j.getString(k)
    }
    private fun saveShooters() = getSharedPreferences("nock", MODE_PRIVATE).edit().putString("shooters", org.json.JSONObject(shooterFor as Map<*, *>).toString()).apply()
    private fun tally(face: TargetMemory.Face?, score: Int) {
        face ?: return
        roundTally.getOrPut(face.id) { java.util.Collections.synchronizedList(ArrayList()) }.add(score)
        runOnUiThread { targetMarks.relabel { arLabel(it.face) } }
    }

    /** Tap a target's label: who's shooting it (pick a person), or open its details. */
    private fun targetChip(f: TargetMemory.Face) {
        val p = com.nock.data.Prefs(this)
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val scrim = FrameLayout(this).apply { setBackgroundColor(0xB3000000.toInt()); isClickable = true }
        fun close() = root.removeView(scrim)
        scrim.setOnClickListener { close() }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(26f); setColor(Brand.Carbon) }
            setPadding(dpi(20), dpi(18), dpi(20), dpi(16))
            addView(label(18f, bold = true).apply { text = f.name })
            addView(label(13f, color = Brand.Dim).apply { text = "Who's shooting this target?" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(2); bottomMargin = dpi(8) })
        }
        // Tap a name to pick it (the check moves); Done saves it and closes.
        var picked = shooterFor[f.id] ?: p.active
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun drawRows() {
            rows.removeAllViews()
            for ((id, n) in p.people + (com.nock.data.Prefs.GUEST to "Guest")) rows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dpi(9), 0, dpi(9))
                addView(label(15f, bold = true, color = if (id == picked) Brand.Smoke else Brand.Dim).apply { text = n.ifBlank { "You" } }, LinearLayout.LayoutParams(0, -2, 1f))
                if (id == picked) addView(CheckIcon(this@MainActivity), LinearLayout.LayoutParams(dpi(20), dpi(20)))
                setOnClickListener { picked = id; it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); drawRows() }
            })
        }
        drawRows()
        card.addView(rows)
        // Add someone new right here: they're kept for good and picked
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dpi(9), 0, dpi(9))
            addView(PlusIcon(this@MainActivity), LinearLayout.LayoutParams(dpi(16), dpi(16)).apply { marginEnd = dpi(10) })
            addView(label(15f, bold = true, color = Brand.TealBright).apply { text = "Add person" })
        }
        val nameField = android.widget.EditText(this).apply {
            hint = "Name"; setHintTextColor(Brand.Dim); setTextColor(Brand.Smoke); textSize = 15f; isSingleLine = true
            background = pillBackground(context, 0x26F5F5F4); setPadding(dpi(14), dpi(10), dpi(14), dpi(10))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE; visibility = View.GONE
            setOnEditorActionListener { v, _, _ ->
                val n = v.text.toString().trim()
                if (n.isNotEmpty()) { picked = p.addPerson(n, makeActive = false); v.text = ""; drawRows(); performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM) }
                visibility = View.GONE; addRow.visibility = View.VISIBLE
                getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
                true
            }
        }
        addRow.setOnClickListener {
            addRow.visibility = View.GONE; nameField.visibility = View.VISIBLE; nameField.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(nameField, 0)
        }
        card.addView(addRow); card.addView(nameField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4) })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label(14f, bold = true).apply {
                text = "Target details"; gravity = Gravity.CENTER; background = pillBackground(context, 0x26F5F5F4)
                setPadding(dpi(18), dpi(10), dpi(18), dpi(10)); setOnClickListener { close(); openSheet(f) }
            })
            if (manual && f.id in mine) addView(label(14f, bold = true).apply {
                text = "Remove"; gravity = Gravity.CENTER; background = pillBackground(context, 0x26F5F5F4)
                setPadding(dpi(18), dpi(10), dpi(18), dpi(10)); setOnClickListener { close(); setMine(f, false); drawMarks() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(8) })
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(10); gravity = Gravity.CENTER_HORIZONTAL })
        card.addView(label(15f, bold = true, color = Brand.Carbon).apply {
            text = "Done"; gravity = Gravity.CENTER; background = pillBackground(context, Brand.FernBright)
            setPadding(0, dpi(12), 0, dpi(12))
            setOnClickListener {
                shooterFor[f.id] = picked; saveShooters()
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                close(); targetMarks.relabel { x -> arLabel(x.face) }; toastCard("Saved")
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12) })
        scrim.addView(card, FrameLayout.LayoutParams(dpi(300), -2, Gravity.CENTER))
        root.addView(scrim, android.view.ViewGroup.LayoutParams(-1, -1))
    }

    /** An outline Nock doesn't know: mark it as new, or say which of your targets it is (that teaches it). */
    private fun unknownChoice() {
        if (trackedUnknown != null) { wantFresh = true; return } // the camera's next frame comes back through freshChoice
        val frame = overlayFrame?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false) ?: return // copy now: the live one can be recycled
        val q = overlayUnknown ?: return
        chooseFor(frame, q)
    }

    /** The current frame, with the new outline where it is now (snapped to its edges when it can be). */
    private fun freshChoice(frame: Bitmap) {
        val now = trackedUnknown ?: return
        val c = android.graphics.PointF(now.map { it.x }.average().toFloat(), now.map { it.y }.average().toFloat())
        val q = runCatching { TargetGuess.findQuadNear(frame, c) }.getOrNull() ?: now
        runOnUiThread { chooseFor(frame, q) }
    }

    private fun chooseFor(frame: Bitmap, q: List<android.graphics.PointF>) {
        choose("Which target is this?", memory.faces(), newLabel = "New target") { picked ->
            if (picked == null) hitExecutor.execute {
                val g = runCatching { TargetGuess.guess(frame, memory, q) }.getOrNull() ?: TargetGuess.Result(q, emptyList())
                runOnUiThread { openMark(frame, emptyList(), null, null, g) }
            } else lineUp(picked, frame, q)
        }
    }

    /** Line up a known target on this photo (corners start on [q]), then keep it as a view you vouched for. */
    /** Nothing found where you held: pick which of your targets it is, then draw it there (corners start around the spot). */
    private fun drawAt(frame: Bitmap, at: org.opencv.core.Point) {
        choose("Which target is it?", memory.faces(), newLabel = "New target") { picked ->
            val s = 0.12f * minOf(frame.width, frame.height); val x = at.x.toFloat(); val y = at.y.toFloat()
            val q = listOf(android.graphics.PointF(x - s, y - s), android.graphics.PointF(x + s, y - s), android.graphics.PointF(x + s, y + s), android.graphics.PointF(x - s, y + s))
            if (picked == null) openMark(frame, emptyList(), null, null, TargetGuess.Result(q, emptyList())) else lineUp(picked, frame, q)
        }
    }

    private fun lineUp(face: TargetMemory.Face, frame: Bitmap, q: List<android.graphics.PointF>?) {
        val shot = frame.copy(Bitmap.Config.ARGB_8888, false) // our own copy: the live frame gets recycled by the next look
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        var view: MarkSide? = null
        view = MarkSide(this, shot, emptyList(), tapeRadius, com.nock.data.Prefs(this).metric,
            onSave = { r ->
                root.removeView(view)
                hitExecutor.execute {
                    val f = memory.addView(face, shot, r.outline.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) })
                    if (manual) runOnUiThread { setMine(f, true) } // drawn it = locked on it
                    side = f; showSide()
                    runOnUiThread { toastCard("Saved a new view of ${f.name}") }
                    lookAsk = false; wantLook = true
                }
            },
            onCancel = { root.removeView(view) },
            guess = TargetGuess.Result(q, emptyList()), alignName = face.name,
        )
        root.addView(view, android.view.ViewGroup.LayoutParams(-1, -1))
    }

    /** A pop-up list of your targets (photo + name) to pick one; [newLabel] adds a "make a new one" row (null pick). */
    /**
     * Manual: which targets you're shooting. Tap to tick (several at once), Done saves; it then looks only for these
     * and remembers where each one is.
     */
    private fun pickTargets() {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val scrim = FrameLayout(this).apply { setBackgroundColor(0xB3000000.toInt()); isClickable = true }
        fun close() = root.removeView(scrim)
        scrim.setOnClickListener { close() }
        val picked = HashSet(mine)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(26f); setColor(Brand.Carbon) }
            setPadding(dpi(18), dpi(18), dpi(18), dpi(14)); isClickable = true
            addView(label(18f, bold = true).apply { text = "Your targets" })
            addView(label(13f, color = Brand.Dim).apply { text = "Tick the ones you're shooting. Nock looks only for these."; isSingleLine = false }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(2) })
            addView(android.widget.ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dpi(8) })
        }
        for (f in memory.faces()) {
            val check = CheckIcon(this).apply { visibility = if (f.id in picked) View.VISIBLE else View.INVISIBLE }
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpi(6), 0, dpi(6))
                addView(android.widget.ImageView(context).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true; outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(10f)) }
                    }
                    hitExecutor.execute { val b = runCatching { memory.cutout(f, f.photo, 160) }.getOrNull(); runOnUiThread { setImageBitmap(b) } }
                }, LinearLayout.LayoutParams(dpi(48), dpi(48)).apply { marginEnd = dpi(12) })
                addView(label(15f, bold = true).apply { text = f.name }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(check, LinearLayout.LayoutParams(dpi(20), dpi(20)))
                setOnClickListener {
                    if (!picked.remove(f.id)) picked.add(f.id)
                    check.visibility = if (f.id in picked) View.VISIBLE else View.INVISIBLE
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
            })
        }
        card.addView(label(15f, bold = true, color = Brand.Carbon).apply {
            text = "Done"; gravity = Gravity.CENTER; background = pillBackground(context, Brand.FernBright)
            setPadding(0, dpi(12), 0, dpi(12))
            setOnClickListener {
                for (f in memory.faces()) setMine(f, f.id in picked)
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                close(); drawMarks(); lookAsk = false; wantLook = true; toastCard("Saved")
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12) })
        scrim.addView(card, FrameLayout.LayoutParams(dpi(320), -2, Gravity.CENTER).apply { topMargin = dpi(40); bottomMargin = dpi(40) })
        root.addView(scrim, android.view.ViewGroup.LayoutParams(-1, -1))
    }

    private fun choose(title: String, faces: List<TargetMemory.Face>, newLabel: String?, onPick: (TargetMemory.Face?) -> Unit) {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val scrim = FrameLayout(this).apply { setBackgroundColor(0xB3000000.toInt()); isClickable = true }
        fun close() = root.removeView(scrim)
        scrim.setOnClickListener { close() }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(26f); setColor(Brand.Carbon) }
            setPadding(dpi(18), dpi(18), dpi(18), dpi(14)); isClickable = true
            addView(label(18f, bold = true).apply { text = title })
            addView(android.widget.ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10) })
        }
        for (f in faces) list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(6), 0, dpi(6))
            addView(android.widget.ImageView(context).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                clipToOutline = true; outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(10f)) }
                }
                hitExecutor.execute { val b = runCatching { memory.cutout(f, f.photo, 160) }.getOrNull() ?: android.graphics.BitmapFactory.decodeFile(f.photo.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }); runOnUiThread { setImageBitmap(b) } }
            }, LinearLayout.LayoutParams(dpi(52), dpi(52)).apply { marginEnd = dpi(12) })
            addView(label(15f, bold = true).apply { text = f.name })
            setOnClickListener { close(); onPick(f) }
        })
        newLabel?.let { n -> card.addView(label(14f, bold = true, color = Brand.Carbon).apply {
            text = n; gravity = Gravity.CENTER; background = pillBackground(context, Brand.FernBright)
            setPadding(dpi(18), dpi(10), dpi(18), dpi(10)); setOnClickListener { close(); onPick(null) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(12); gravity = Gravity.CENTER_HORIZONTAL }) }
        scrim.addView(card, FrameLayout.LayoutParams(dpi(320), -2, Gravity.CENTER).apply { topMargin = dpi(24); bottomMargin = dpi(24) })
        root.addView(scrim, android.view.ViewGroup.LayoutParams(-1, -1))
    }

    // ---- Adding views of a target (from its page on Home) ----
    @Volatile private var addingTo: TargetMemory.Face? = null
    @Volatile private var wantCapture = false
    private val addBar by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pillBackground(context); setPadding(dpi(18), dpi(8), dpi(8), dpi(8)); visibility = View.GONE
            addView(label(14f, bold = true).apply { tag = "title" })
            addView(label(14f, bold = true, color = Brand.Carbon).apply {
                text = "Capture"; gravity = Gravity.CENTER; background = pillBackground(context, Brand.FernBright)
                setPadding(dpi(18), dpi(9), dpi(18), dpi(9)); setOnClickListener { wantCapture = true }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(14) })
            addView(label(14f, bold = true).apply {
                text = "Done"; gravity = Gravity.CENTER; setPadding(dpi(14), dpi(9), dpi(14), dpi(9)); setOnClickListener { finish() }
            })
        }
    }
    private fun startAddViews(id: String) {
        val f = memory.faces().firstOrNull { it.id == id } ?: return
        addingTo = f
        (addBar.findViewWithTag<TextView>("title")).text = "Adding views of ${f.name}"
        addBar.visibility = View.VISIBLE; playButton.visibility = View.GONE
    }
    /** Capture: find the target in this photo (or the best outline), let you line it up, keep it as a view. */
    private fun captureView(frame: Bitmap) {
        val f = addingTo ?: return
        val found = runCatching { memory.areaIn(f, frame) }.getOrNull()?.map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }
            ?: runCatching { TargetGuess.findQuad(frame) }.getOrNull()
        runOnUiThread { lineUp(f, frame, found) }
    }

    /** A short note in the middle of the screen. */
    private fun toastCard(msg: String) {
        zoomBadge.text = msg; zoomBadge.fadeTo(true)
        zoomBadge.removeCallbacks(hideBadge); zoomBadge.postDelayed(hideBadge, 1800)
    }
    private val hideBadge = Runnable { zoomBadge.fadeTo(false) }

    @Volatile private var overlayFrame: Bitmap? = null
    @Volatile private var overlayUnknown: List<android.graphics.PointF>? = null
    @Volatile private var trackedUnknown: List<android.graphics.PointF>? = null // the new outline where it is in the current frame
    @Volatile private var wantFresh = false

    /** Map photo points onto the camera preview (same transform CameraX uses to draw it) and show them. */
    /** What the last look found, in its own frame's coordinates (the tracker moves it into the frame on screen). */
    private class Marks(val key: Int, val seen: List<TargetMemory.Seen>, val unknown: List<android.graphics.PointF>?)
    @Volatile private var marks: Marks? = null
    @Volatile private var trackedH: org.opencv.core.Mat? = null // key frame -> now (null = not moved yet / identity)
    @Volatile private var trackedTransform: androidx.camera.view.transform.OutputTransform? = null

    /**
     * A look's result becomes what's drawn and what the target button says, in the same step (they never disagree).
     * [final] = the whole look (a snap first is only the targets already on screen). False = dropped: tracking was lost
     * while it ran, so its places are stale (the next look decides).
     */
    private fun showOverlay(seen: List<TargetMemory.Seen>, unknown: List<android.graphics.PointF>?, transform: androidx.camera.view.transform.OutputTransform?,
                            frame: Bitmap, key: Int, final: Boolean = true, unsure: Boolean = false): Boolean {
        if (key != 0 && (key != tracker.key || !tracker.active)) { frame.takeIf { it !== overlayFrame }?.recycle(); return false }
        overlayFrame?.takeIf { it !== frame }?.recycle(); overlayFrame = frame; overlayUnknown = unknown
        marks = Marks(key, seen, unknown)
        if (trackedTransform == null) trackedTransform = transform
        inView = seen.map { it.face }.filter { isMine(it) }; side = inView.firstOrNull()
        if (key != 0) anchor(seen, key)
        if (final) { looking = false; unsureNow = inView.isEmpty() && (unsure || unknown != null) }
        showSide()
        if (key == 0) drawMarks() // not tracking: draw now; otherwise the next camera frame draws them, moved
        return true
    }

    /** Draw the last look's marks where they are now: key frame -> (tracked move) -> this frame -> screen; watch too. */
    private fun drawMarks() {
        val m = marks ?: return
        val global = trackedH
        var h = global
        fun pf(ps: List<org.opencv.core.Point>): List<android.graphics.PointF> {
            val q = if (h == null || ps.isEmpty()) ps else org.opencv.core.MatOfPoint2f().also { org.opencv.core.Core.perspectiveTransform(org.opencv.core.MatOfPoint2f(*ps.toTypedArray()), it, h) }.toArray().toList()
            return q.map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }
        }
        val unknown = m.unknown?.let { u -> pf(u.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) }) }
        trackedUnknown = unknown
        // shapes are built in the look's frame (on the face plane there), then moved by that target's own fit
        val shapes = m.seen.map { s ->
            h = if (global == null) null else runCatching { tracker.hFor(s.outline) }.getOrNull() ?: global
            fun raw(ps: List<org.opencv.core.Point>) = ps.map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }
            Triple(s, pf(s.outline), s.bulls.map { (t, ps) -> pf(Shapes.polygon(t, raw(ps), raw(s.outline)).map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) }) })
        }
        // the watch's live view gets the same marks
        streamMarks = shapes.map { (s, o, b) -> StreamMark(o, b, true, arLabel(s.face)) } + listOfNotNull(unknown?.let { StreamMark(it, emptyList(), false, null) })
        val transform = trackedTransform
        runOnUiThread {
            val target = previewView.outputTransform
            if (marks !== m) return@runOnUiThread
            if (transform == null || target == null) { targetMarks.clear(); return@runOnUiThread }
            val ct = androidx.camera.view.transform.CoordinateTransform(transform, target)
            fun map(ps: List<android.graphics.PointF>): List<android.graphics.PointF> {
                val a = FloatArray(ps.size * 2); ps.forEachIndexed { i, p -> a[2 * i] = p.x; a[2 * i + 1] = p.y }
                ct.mapPoints(a); return ps.indices.map { android.graphics.PointF(a[2 * it], a[2 * it + 1]) }
            }
            val items = shapes.filter { isMine(it.first.face) }.map { (s, o, b) -> TargetOverlay.Item(s.face, map(o), b.map { map(it) }, arLabel(s.face)) } +
                listOfNotNull(unknown?.let { TargetOverlay.Item(null, map(it), emptyList()) })
            if (items.isEmpty()) targetMarks.clear() else { targetMarks.show(items); if (!xray) targetMarks.visibility = View.GONE }
        }
    }

    private fun moved(s: TargetMemory.Seen, h: org.opencv.core.Mat): TargetMemory.Seen {
        fun m(ps: List<org.opencv.core.Point>) = if (ps.isEmpty()) ps else org.opencv.core.MatOfPoint2f().also {
            org.opencv.core.Core.perspectiveTransform(org.opencv.core.MatOfPoint2f(*ps.toTypedArray()), it, h) }.toArray().toList()
        return TargetMemory.Seen(s.face, m(s.outline), m(s.spots), s.bulls.map { (t, ps) -> t to m(ps) }, s.inliers)
    }
    private fun movedPts(ps: List<android.graphics.PointF>, h: org.opencv.core.Mat): List<android.graphics.PointF> =
        org.opencv.core.MatOfPoint2f().also { org.opencv.core.Core.perspectiveTransform(org.opencv.core.MatOfPoint2f(*ps.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()), it, h) }
            .toArray().map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }

    /** Tracking lost (or the view jumped): hide the marks rather than show them in the wrong place, and look again. */
    private fun dropMarks() {
        marks = null; trackedH = null; trackedUnknown = null; streamMarks = emptyList(); relookAfterStill = true
        inView = emptyList(); side = null; looking = true; unsureNow = false; showSide()
        runOnUiThread { targetMarks.clear() }
    }

    /** The Mark screen over the camera, on a frozen frame. [replace] = re-marking a known target. */
    /** The target's menu (name, photo with marks, sizes, Edit marks, hold to delete) over the dimmed camera. */
    private fun openSheet(face: TargetMemory.Face) {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        var sheet: TargetSheet? = null
        sheet = TargetSheet(this, memory, face, com.nock.data.Prefs(this).metric,
            onChanged = { f -> side = f; showSide() },
            onEdit = { f ->
                root.removeView(sheet)
                // Edit on the saved reference photo, starting from the marks you made.
                hitExecutor.execute {
                    val ref = android.graphics.BitmapFactory.decodeFile(f.photo.path) ?: return@execute
                    runOnUiThread { openMark(ref, emptyList(), f, existing = f) }
                }
            },
            onNew = { root.removeView(sheet); wantMark = true; markAsNew = true },
            onClose = { root.removeView(sheet) },
        )
        root.addView(sheet, android.view.ViewGroup.LayoutParams(-1, -1))
    }

    private fun openMark(frame: Bitmap, tapeSpots: List<org.opencv.core.Point>, replace: TargetMemory.Face?, existing: TargetMemory.Face? = null, guess: TargetGuess.Result? = null) {
        val shot = frame.copy(Bitmap.Config.ARGB_8888, false) // our own copy: the live frame gets recycled by the next look
        // A new target: first work out what it looks like (outline + suggestions), then open on that.
        if (existing == null && guess == null) {
            hitExecutor.execute {
                val g = runCatching { TargetGuess.guess(shot, memory) }.getOrNull() ?: TargetGuess.Result(null, emptyList())
                runOnUiThread { openMark(shot, tapeSpots, replace, null, g) }
            }
            return
        }
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        var view: MarkSide? = null
        view = MarkSide(this, shot, tapeSpots.map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) },
            tapeRadius, com.nock.data.Prefs(this).metric,
            onSave = { r ->
                root.removeView(view)
                hitExecutor.execute {
                    val f = memory.saveMarked(shot, r, replace); side = f
                    showSide()
                }
            },
            onCancel = { root.removeView(view) },
            existing = existing,
            guess = guess,
        )
        root.addView(view, android.view.ViewGroup.LayoutParams(-1, -1))
    }
    private val tapeRadius get() = 40f // px; a starting size for circles placed on tape spots (you resize them)

    /** Tap the side chip: mark the target again (new photo, your new outline and bullseyes replace the old). */
    @Volatile private var wantMark = false
    @Volatile private var markAsNew = false // "New target" from the menu: a new one, not a replacement
    private fun markAgain(frame: Bitmap) {
        val spots = runCatching { hitFinder.face(frame) }.getOrNull()?.first ?: emptyList()
        val replace = if (markAsNew) null else side
        markAsNew = false
        runOnUiThread { openMark(frame, spots, replace) }
    }

    /** Start / pause watching for arrows. Paused, nothing is scored and the watch doesn't buzz. */
    @Volatile private var roundId = 0L // the round being shot: set when you press Start (every arrow carries it)
    @Volatile private var roundYd = 0 // distance asked at Start, saved with every arrow (0 = not asked)

    /**
     * Start: two quick answers, already filled in with last time's (distance, who's shooting), then Start. The card
     * opens from the Start button with its own Start in the same spot, so a double tap starts with the same answers.
     * Tap outside to cancel. The watch's button starts straight away with the last answers.
     */
    private fun askStart() {
        val p = com.nock.data.Prefs(this)
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val scrim = FrameLayout(this).apply { setBackgroundColor(0x80000000.toInt()); isClickable = true }
        fun close() = root.removeView(scrim)
        scrim.setOnClickListener { close() }
        var yd = p.distanceYd
        val everyone = p.people + (com.nock.data.Prefs.GUEST to "Guest")
        val who = linkedSetOf(p.active)
        fun chip(text: String, on: Boolean, onTap: () -> Unit) = label(14f, bold = true, color = if (on) Brand.Carbon else Brand.Smoke).apply {
            this.text = text; gravity = Gravity.CENTER
            background = pillBackground(context, if (on) Brand.Smoke else 0x26F5F5F4)
            setPadding(dpi(14), dpi(8), dpi(14), dpi(8))
            setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onTap() }
        }
        val dists = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val people = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun render() {
            dists.removeAllViews()
            for (d in (DISTANCES_YD + p.distanceYd).distinct().sorted()) dists.addView(chip("$d", d == yd) { yd = d; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6) })
            people.removeAllViews()
            for ((id, n) in everyone) people.addView(chip(n.ifBlank { "You" }, id in who) {
                if (id in who) { if (who.size > 1) who.remove(id) } else who.add(id); render()
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6) })
        }
        render()
        fun section(title: String, row: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(12f, bold = true, color = Brand.Dim).apply { text = title })
            addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(6) })
        }
        val go = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pillBackground(context, Brand.FernBright)
            setPadding(dpi(14), dpi(10), dpi(16), dpi(10))
            addView(SleekArrow(context).apply { color = Brand.Carbon }, LinearLayout.LayoutParams(dpi(26), dpi(12)).apply { marginEnd = dpi(10) })
            addView(label(14f, bold = true, color = Brand.Carbon).apply { text = "Start" })
            setOnClickListener {
                p.distanceYd = yd
                if (who.size == 1) p.active = who.first()
                roundPeople = who.toList(); roundYd = yd
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                close(); setScoring(true)
            }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(24f); setColor(Brand.Carbon) }
            setPadding(dpi(16), dpi(14), dpi(12), dpi(12))
            addView(section("DISTANCE, YARDS", dists))
            addView(section("SHOOTING", people), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(12) })
            addView(go, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(14); gravity = Gravity.END })
        }
        val edge = dpi(20)
        scrim.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            // the card's Start lands where the Start button is (card padding 12 dp around it)
            setMargins(edge, edge, edge - dpi(12), edge - dpi(12)); width = minOf(dpi(380), resources.displayMetrics.widthPixels - 2 * edge)
        })
        root.addView(scrim, android.view.ViewGroup.LayoutParams(-1, -1))
        card.alpha = 0f; card.scaleX = 0.92f; card.scaleY = 0.92f; card.pivotX = card.layoutParams.width.toFloat(); card.post { card.pivotX = card.width.toFloat(); card.pivotY = card.height.toFloat() }
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }
    @Volatile private var roundPeople: List<String> = emptyList()

    private fun setScoring(on: Boolean) {
        if (on && !shots.active) {
            lookAsk = true; wantLook = true
            // Start = a new round: everything shot until you pause belongs to it.
            roundId = System.currentTimeMillis()
            if (roundYd == 0) roundYd = com.nock.data.Prefs(this).distanceYd // started from the watch: last distance
            roundTally.clear(); targetMarks.relabel { arLabel(it.face) }
            if (round.isNotEmpty()) { round.clear(); roundNo++; renderRound() }
        }
        shots.active = on
        playLabel.text = if (on) "Scoring" else "Start"
        startArrow.visibility = if (on) View.GONE else View.VISIBLE
        liveDot.visibility = if (on) View.VISIBLE else View.GONE
        playButton.contentDescription = if (on) "Pause scoring" else "Start scoring"
    }
    // End-of-round card: the score big, then the group. Tap it to see the round.
    private val endTitle by lazy { label(15f, bold = true, color = Brand.Dim).apply { gravity = Gravity.CENTER } }
    private val endPoints by lazy { label(56f, bold = true).apply { gravity = Gravity.CENTER } }
    private val endOf by lazy { label(15f, bold = true, color = Brand.Dim).apply { gravity = Gravity.CENTER } }
    private val endDetail by lazy { label(15f, bold = true).apply { gravity = Gravity.CENTER } }
    private val endCard by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(28f); setColor(Brand.Glass) }
            setPadding(dpi(36), dpi(22), dpi(36), dpi(24))
            addView(endTitle); addView(endPoints, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(6) })
            addView(endOf); addView(endDetail, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(12) })
            visibility = View.GONE
            setOnClickListener { fadeTo(false); reviewRoundNo(summarisedRound) }
        }
    }
    // Shown while a guest is shooting, so their arrows don't land in your stats by surprise.
    private val guestTag by lazy {
        label(13f, bold = true, color = Brand.Amber).apply {
            text = "Guest"; background = pillBackground(context); setPadding(dpi(12), dpi(7), dpi(12), dpi(7)); visibility = View.GONE
        }
    }
    private lateinit var fpsText: TextView
    private lateinit var delayText: TextView
    private lateinit var zoomBadge: TextView
    private lateinit var qualityPills: List<TextView>
    private lateinit var blocked: TextView
    private lateinit var slider: ZoomSlider
    private lateinit var presets: LinearLayout
    private lateinit var review: ShotReview
    private lateinit var qualityBar: LinearLayout
    private val hideQuality = Runnable { qualityBar.fadeTo(false) }
    private var camera: Camera? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val hitExecutor = HeavyExecutor()

    /**
     * The heavy jobs (looks, hit finding, marking) on one thread. OpenCV uses every core only while one of them runs, and
     * one core otherwise: its worker threads kept spinning between the small tracking jobs every camera frame. Measured
     * 2026-10-08, camera open, phone still: all cores always 160% of a core, one core 116%; cold look median 1.9 s with all
     * cores vs 2.4 s with one, so looks keep all of them.
     */
    private class HeavyExecutor : java.util.concurrent.Executor {
        private val inner = Executors.newSingleThreadExecutor()
        private val waiting = java.util.concurrent.atomic.AtomicInteger()
        private var all = 0 // OpenCV's own thread count (one per core), read once it's loaded
        override fun execute(job: Runnable) {
            waiting.incrementAndGet()
            inner.execute {
                if (all == 0) { org.opencv.android.OpenCVLoader.initLocal(); all = org.opencv.core.Core.getNumThreads() }
                org.opencv.core.Core.setNumThreads(all)
                try { job.run() } finally { if (waiting.decrementAndGet() == 0) org.opencv.core.Core.setNumThreads(1) }
            }
        }
        fun shutdown() = inner.shutdown()
    }
    @Volatile private var pendingMotion: Pair<Long, org.json.JSONObject>? = null // (received at, motion) waiting for its arrow

    private fun motionJson(data: ByteArray): org.json.JSONObject {
        val b = ByteBuffer.wrap(data)
        b.long // release wall time
        val hold = b.float; val n = b.int
        val xs = org.json.JSONArray(); val ys = org.json.JSONArray()
        repeat(n) { xs.put(b.float.toDouble()); ys.put(b.float.toDouble()) }
        val j = org.json.JSONObject().put("hold", hold.toDouble()).put("x", xs).put("y", ys)
        if (b.remaining() >= 8) j.put("last", b.float.toDouble()).put("follow", b.float.toDouble()) // newer watch
        return j
    }
    private val hitFinder by lazy { HitFinder() }
    private val messageClient by lazy { Wearable.getMessageClient(this) }

    @Volatile private var watch: Node? = null
    private val seq = AtomicInteger(0)
    private val sentAt = ConcurrentHashMap<Int, Long>()
    private val stream = StreamStats()
    private val shots by lazy { ShotDetector(java.io.File(filesDir, "shots"), ::onShot) }

    private val prefs by lazy { getSharedPreferences("nock", MODE_PRIVATE) }
    private var quality = 1 // Balanced; the last pick is restored in onCreate
    @Volatile private var frameWidth = QUALITY_WIDTHS[quality]

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else blocked.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        quality =prefs.getInt(KEY_QUALITY, 1).coerceIn(0, QUALITY_WIDTHS.size - 1)
        loadShooters()
        setContentView(buildLayout())
        // Back closes the review pop-up first (and only then leaves the app).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (review.visibility == View.VISIBLE) review.close()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        selfTest()
        setScoring(false) // opens paused: you start scoring when you're ready
        demo()
        intent.getStringExtra(EXTRA_ADD_VIEW)?.let { id -> previewView.post { startAddViews(id) } }
        if (intent.getBooleanExtra(EXTRA_REVIEW, false)) { // from Home: review, and closing it goes straight back
            review.onClose = { finish() }
            val at = intent.getStringExtra(EXTRA_PHOTO)?.let { java.io.File(it) }
            previewView.post { review.open(toCheck = intent.getBooleanExtra(EXTRA_CHECK, false), startAt = at) }
        }
        if (intent.getBooleanExtra(EXTRA_REVIEW, false)) return // only looking at old shots: leave the camera off
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
        sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)?.let { sensors.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
        sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sensors.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
        loadGyro()
        guestTag.visibility = if (com.nock.data.Prefs(this).active == com.nock.data.Prefs.GUEST) View.VISIBLE else View.GONE
        hitExecutor.execute { runCatching { memory.warm() } } // targets loaded before the first look needs them
        lookAsk = false; wantLook = true; looking = true; showSide() // name the target in view as soon as the camera's up ("Looking…" till then)
        messageClient.addListener(this)
        // Dev aid: `adb shell run-as com.nock touch files/preview_last` shows the newest saved arrow in the corner and
        // sends it to the watch (once the watch is found), as if it had just been shot.
        java.io.File(filesDir, "preview_last").takeIf { it.exists() }?.let { flag -> flag.delete()
            java.io.File(filesDir, "shots").listFiles { f -> f.name.endsWith("_hit.json") }?.maxByOrNull { it.name.split('_')[2].toLongOrNull() ?: 0L }
                ?.let { val after = java.io.File(it.path.replace("_hit.json", "_after.jpg"))
                    previewView.postDelayed({ previewShot(after); hitExecutor.execute { sendShotToWatch(after) } }, 3000) } }
        previewView.post(findWatch)
        previewView.post(refreshStatus)
    }

    // The phone's own gyro: on a tripod it reads ~0; in a hand or when bumped it jumps. Feeds the shot detector.
    private val sensors by lazy { getSystemService(android.hardware.SensorManager::class.java) }
    private val horizon by lazy { HorizonLevel(this) } // level: shown with the controls (tap the camera)
    override fun onSensorChanged(e: android.hardware.SensorEvent) {
        if (e.sensor.type == android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR) { orient.add(e.timestamp, e.values); return }
        if (e.sensor.type == android.hardware.Sensor.TYPE_GRAVITY) {
            horizon.onGravity(e.values[0], e.values[1], e.values[2], display?.rotation ?: 0); return
        }
        val dps = Math.toDegrees(kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
        if (dps > PHONE_MOVED_DPS) {
            shots.phoneMovedAt = SystemClock.elapsedRealtime()
            // The marks follow the move (MarkTracker); once still, look again to confirm what's in view.
            relookAfterStill = true
        }
    }
    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit

    override fun onPause() {
        super.onPause()
        if (guided != null) endCal()
        sensors.unregisterListener(this)
        messageClient.removeListener(this)
        previewView.removeCallbacks(findWatch)
        previewView.removeCallbacks(refreshStatus)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        hitExecutor.shutdown()
    }

    // ---- Layout: full-bleed camera, three glass pills, nothing else. ----

    private fun buildLayout(): FrameLayout {
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }

        // Top-left: Home, then a small watch chip (status dot + watch icon). Tap the chip for details;
        // tap again (while the details show) to open Nock on the watch.
        val home = ChevronIcon(this).apply {
            background = dot(context, Brand.Glass)
            contentDescription = "Home"
            setOnClickListener { finish() } // back to Home
        }
        // Top left: Home and the target button (count + status). Everything else is in the menu, top right.
        statusDot = View(this).apply { background = dot(context, Brand.Amber) }
        statusText = label(12f, bold = true).apply { text = "Looking for watch" }
        val topLeft = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
            addView(home, LinearLayout.LayoutParams(dpi(BTN), dpi(BTN)).apply { marginEnd = dpi(8) })
            addView(sideTag, LinearLayout.LayoutParams(-2, dpi(BTN)))
            addView(guestTag, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(10) })
        }

        // Top right: the menu. Tap it and its options drop down the right edge one after another (watch, highlights,
        // level & zoom; marking a target lives in the target button's pop-up); anything new goes in here too, so the top of the camera stays clear.
        menuIcon = MenuIcon(this)
        val menuButton = FrameLayout(this).apply {
            background = dot(context, Brand.Glass)
            contentDescription = "Menu"
            addView(menuIcon, FrameLayout.LayoutParams(dpi(16), dpi(16), Gravity.CENTER))
            setOnClickListener { setMenu(menu.visibility != View.VISIBLE) }
        }
        val watchGlyph = FrameLayout(this).apply {
            clipChildren = false
            addView(WatchIcon(context), FrameLayout.LayoutParams(dpi(10), dpi(16), Gravity.CENTER))
            addView(statusDot, FrameLayout.LayoutParams(dpi(7), dpi(7), Gravity.TOP or Gravity.END).apply { topMargin = dpi(5); marginEnd = dpi(5) })
        }
        xrayText = label(12f, bold = true)
        hudText = label(12f, bold = true)
        modeText = label(12f, bold = true)
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            clipChildren = false
            visibility = View.GONE
            addView(menuItem(watchGlyph, statusText) { openOnWatch() })
            addView(menuItem(xrayIcon, xrayText, keepOpen = true) { xray = !xray; renderXray(); if (xray) { lookAsk = false; wantLook = true } })
            addView(menuItem(hudIcon, hudText, keepOpen = true) { hud = !hud; renderHud() })
            addView(menuItem(GyroIcon(context), label(12f, bold = true).apply { text = "Calibrate gyro" }) { startCal() })
            addView(modeSwitch)
        }
        renderXrayOn(); renderMode(); hudText.text = "Level & zoom off" // the HUD itself is built below
        val topRight = FrameLayout(this).apply { addView(menuButton, FrameLayout.LayoutParams(dpi(BTN), dpi(BTN))) }

        // Left: this round, like the watch. Newest arrow on top (#N .. #1). Tap to review the round.
        roundTitle = label(18f, bold = true).apply { text = "Round 1" }
        roundCount = label(12f, bold = true, color = Brand.Dim)
        roundRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        roundPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20f); setColor(Brand.Glass) }
            setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
            addView(roundTitle); addView(roundCount)
            addView(roundRows, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(8) })
            setOnClickListener { reviewRound() }
            visibility = View.GONE // until the first arrow
        }

        // Top-right (shown on tap): stream speed. Tap it for stream quality.
        fpsText = label(22f, bold = true).apply { text = "–" }
        delayText = label(22f, bold = true).apply { text = "–" }
        numbers = LinearLayout(this).apply {
            visibility = View.GONE
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBackground(context)
            setPadding(dpi(18), dpi(8), dpi(18), dpi(8))
            addView(stat(fpsText, "FPS"))
            addView(View(context).apply { setBackgroundColor(0x33F5F5F4) }, LinearLayout.LayoutParams(dpi(1), dpi(26)).apply { setMargins(dpi(16), 0, dpi(16), 0) })
            addView(stat(delayText, "MS"))
            // Tap the stream numbers to change stream quality (hidden the rest of the time).
            setOnClickListener { qualityBar.fadeTo(qualityBar.visibility != View.VISIBLE); qualityBar.removeCallbacks(hideQuality); qualityBar.postDelayed(hideQuality, 4000) }
        }

        // Bottom-center: stream quality, Apple-camera style segmented pill.
        qualityPills = QUALITY_NAMES.mapIndexed { i, name ->
            label(13f, bold = true).apply {
                text = name
                gravity = Gravity.CENTER
                setOnClickListener { setQuality(i); qualityBar.removeCallbacks(hideQuality); qualityBar.postDelayed(hideQuality, 1500) }
            }
        }
        qualityBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pillBackground(context)
            setPadding(dpi(4), dpi(4), dpi(4), dpi(4))
            qualityPills.forEach { addView(it, LinearLayout.LayoutParams(dpi(64), dpi(36))) }
            visibility = View.GONE
        }
        setQuality(quality)

        // Center: zoom badge, only while pinching.
        zoomBadge = label(17f, bold = true).apply {
            background = pillBackground(context)
            setPadding(dpi(16), dpi(8), dpi(16), dpi(8))
            visibility = View.GONE
        }

        blocked = label(17f, bold = true).apply {
            text = "Nock needs the camera.\nAllow it in Settings › Apps › Nock."
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        // Pinch anywhere to zoom onto the target face. Zoom applies to the stream too.
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor)
                return true
            }
        })
        // A plain tap first closes anything open (watch details, zoom presets, quality); otherwise it shows the
        // controls (zoom, speed) for a few seconds, or hides them if they're up. A pinch just zooms.
        var pinched = false
        // Hold anywhere on the camera: look again, fresh, around that spot ("it's right here").
        val hold = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (pinched) return
                previewView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                targetMarks.clear(); marks = null; lookHint = android.graphics.PointF(e.x, e.y); heldAt = SystemClock.elapsedRealtime(); holdPreview = previewView.outputTransform; lookAsk = true; wantLook = true
                toastCard("Searching…")
            }
        })
        previewView.setOnTouchListener { v, e ->
            hold.onTouchEvent(e)
            pinch.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_DOWN) pinched = false
            if (pinch.isInProgress) pinched = true
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                // a plain tap only closes what's open; the HUD button shows the level, zoom and speed
                if (!pinched && !held) dismissPopups()
                v.performClick()
            }
            true
        }

        // Right edge: zoom slider, full height.
        // Hidden until you tap the screen, so the edge is free for Android's back swipe.
        slider = ZoomSlider(this) { setZoom(it) }.apply {
            setPadding(0, dpi(90), 0, dpi(90))
            onTap = { if (presets.visibility == View.VISIBLE) hidePresets() else showPresets() }
            onTouchState = { held -> chromeTouching = held; if (!held) scheduleHideChrome() }
            visibility = View.GONE
        }
        // Tap the slider: quick jumps, most zoom on top (same order as the slider).
        presets = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        review = ShotReview(this, java.io.File(filesDir, "shots"), onIgnored = { after, ignored ->
            round.firstOrNull { it.photo == after }?.let { it.ignored = ignored; renderRound() }
        }) { index, right, low ->
            // tell the watch so its list shows the fixed number
            val payload = ByteBuffer.allocate(13).putInt(index).put(1.toByte()).putFloat(right).putFloat(low).array()
            watch?.let { messageClient.sendMessage(it.id, PATH_HIT, payload) }
            shotPreview.shown?.let { previewShot(it) } // the corner preview shows your fix
        }

        val edge = dpi(20)
        return FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(targetMarks, FrameLayout.LayoutParams(-1, -1)) // live target marks, under every control
            addView(horizon, FrameLayout.LayoutParams(-1, -1))
            addView(topLeft, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(edge, edge, edge, edge) })
            addView(roundPanel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(edge, edge + dpi(BTN + 12), edge, 0) })
            addView(numbers, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(edge, edge, edge + dpi(BTN + 10), edge) })
            addView(playButton, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = edge; marginEnd = edge })
            addView(qualityBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = edge + dpi(72) })
            addView(askCard, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(110); marginStart = edge; marginEnd = edge })
            // up top, under the controls: your targets are usually mid-screen and need to stay in sight while you turn
            addView(calCard, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = edge + dpi(BTN + 12); marginStart = edge; marginEnd = edge })
            addView(zoomBadge, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(blocked, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(slider, FrameLayout.LayoutParams(dpi(64), -1, Gravity.END).apply { marginEnd = dpi(6) })
            addView(presets, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dpi(76) })
            addView(endCard, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(addBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = edge + dpi(8) })
            addView(menu, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(edge, edge + dpi(BTN + 10), edge, edge) })
            addView(topRight, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(edge, edge, edge, edge) })
            addView(shotPreview, FrameLayout.LayoutParams(dpi(150), dpi(150), Gravity.BOTTOM or Gravity.START).apply { setMargins(edge, edge, edge, edge) })
            addView(review, FrameLayout.LayoutParams(-1, -1))
        }
    }

    // ---- The arrow on the watch: a close-up it shows full screen, and a fix made there comes back here ----
    private class WatchCrop(val left: Int, val top: Int, val scale: Float) // close-up pixel -> photo pixel: left + x * scale
    private val watchCrops = java.util.concurrent.ConcurrentHashMap<Long, WatchCrop>() // per arrow (its photo's time stamp)

    /**
     * The arrow's close-up for the watch: 450 px square around the spot and the arrow (with room to zoom), JPEG. Payload:
     * stamp (long), index (int), found (byte), right, low (in), spot x, y, hit x, y (close-up px), px per inch, then the JPEG.
     */
    private fun sendShotToWatch(after: java.io.File) {
        val node = watch ?: return
        runCatching {
            val stamp = after.name.split('_')[2].toLong()
            val j = org.json.JSONObject(java.io.File(after.path.replace("_after.jpg", "_hit.json")).readText())
            val fixed = j.has("fixed")
            val sp = j.getJSONArray("spot"); val en = j.getJSONArray(if (fixed) "fixed" else "entry")
            val ppi = j.optDouble("ppi", 62.0).toFloat()
            val sx = sp.getDouble(0).toFloat(); val sy = sp.getDouble(1).toFloat(); val hx = en.getDouble(0).toFloat(); val hy = en.getDouble(1).toFloat()
            val r = (if (fixed) j.getDouble("fixed_right_in") else j.getDouble("right_in")).toFloat()
            val l = (if (fixed) j.getDouble("fixed_low_in") else j.getDouble("low_in")).toFloat()
            val found = j.optBoolean("found", true) || fixed
            val half = maxOf(2f * ppi, maxOf(kotlin.math.abs(hx - sx), kotlin.math.abs(hy - sy)) * 0.75f + ppi)
            val cx = (sx + hx) / 2; val cy = (sy + hy) / 2
            val dec = android.graphics.BitmapRegionDecoder.newInstance(after.path)
            // the spot and arrow sit in the top part (34% down): the watch covers the bottom 32% with the score
            val rect = android.graphics.Rect((cx - half).toInt().coerceAtLeast(0), (cy - 0.68f * half).toInt().coerceAtLeast(0), (cx + half).toInt().coerceAtMost(dec.width), (cy + 1.32f * half).toInt().coerceAtMost(dec.height))
            val region = dec.decodeRegion(rect, android.graphics.BitmapFactory.Options()); dec.recycle()
            val k = 450f / maxOf(rect.width(), rect.height())
            val small = Bitmap.createScaledBitmap(region, (rect.width() * k).toInt().coerceAtLeast(1), (rect.height() * k).toInt().coerceAtLeast(1), true); region.recycle()
            val jpg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 82, it); small.recycle() }.toByteArray()
            watchCrops[stamp] = WatchCrop(rect.left, rect.top, 1 / k)
            fun c(x: Float, o: Int) = (x - o) * k
            val b = ByteBuffer.allocate(8 + 4 + 1 + 4 * 7 + jpg.size).putLong(stamp).putInt(after.name.split('_')[1].toInt()).put((if (found) 1 else 0).toByte())
                .putFloat(r).putFloat(l).putFloat(c(sx, rect.left)).putFloat(c(sy, rect.top)).putFloat(c(hx, rect.left)).putFloat(c(hy, rect.top)).putFloat(ppi * k).put(jpg)
            messageClient.sendMessage(node.id, PATH_SHOT, b.array())
        }.onFailure { android.util.Log.d("NockShots", "close-up to the watch failed: $it") }
    }

    /** You placed the arrow on the watch: saved exactly as a fix in review (the ring's place, inches from the spot). */
    private fun fixFromWatch(data: ByteArray) {
        val b = ByteBuffer.wrap(data); val stamp = b.long; val x = b.float; val y = b.float
        val crop = watchCrops[stamp] ?: return
        val jf = java.io.File(filesDir, "shots").listFiles { f -> f.name.endsWith("_hit.json") && f.name.split('_').getOrNull(2) == stamp.toString() }?.firstOrNull() ?: return
        val j = org.json.JSONObject(jf.readText())
        val px = crop.left + x * crop.scale; val py = crop.top + y * crop.scale
        val sp = j.getJSONArray("spot"); val ppi = j.optDouble("ppi", 62.0)
        val r = (px - sp.getDouble(0)) / ppi; val l = (py - sp.getDouble(1)) / ppi
        j.put("fixed", org.json.JSONArray(listOf(px.toDouble(), py.toDouble()))).put("fixed_right_in", r).put("fixed_low_in", l)
        jf.writeText(j.toString())
        val index = jf.name.split('_')[1].toInt()
        android.util.Log.d("NockShots", "arrow $index fixed on the watch: %.2f in right, %.2f in low".format(r, l))
        watch?.let { messageClient.sendMessage(it.id, PATH_HIT, ByteBuffer.allocate(13).putInt(index).put(1.toByte()).putFloat(r.toFloat()).putFloat(l.toFloat()).array()) }
        val after = java.io.File(jf.path.replace("_hit.json", "_after.jpg"))
        runOnUiThread { if (shotPreview.shown == after) previewShot(after) }
    }

    // The last arrow, small in the bottom-left: tap it to open it full screen (drag the ring there to fix it).
    private val shotPreview by lazy { ShotPreview(this) { f -> review.open(only = listOf(f), label = "Last arrow", startAt = f) } }
    private fun previewShot(after: java.io.File) = runCatching {
        val j = org.json.JSONObject(java.io.File(after.path.replace("_after.jpg", "_hit.json")).readText())
        val fixed = j.has("fixed")
        val sp = j.getJSONArray("spot"); val en = j.getJSONArray(if (fixed) "fixed" else "entry")
        val r = (if (fixed) j.getDouble("fixed_right_in") else j.getDouble("right_in")).toFloat()
        val l = (if (fixed) j.getDouble("fixed_low_in") else j.getDouble("low_in")).toFloat()
        val found = j.optBoolean("found", true) || fixed
        shotPreview.show(after, sp.getDouble(0).toFloat(), sp.getDouble(1).toFloat(), en.getDouble(0).toFloat(), en.getDouble(1).toFloat(),
            j.optDouble("ppi", 62.0).toFloat(), r, l, if (found) com.nock.ui.Target.score(kotlin.math.hypot(r, l)) else null, found)
    }

    private lateinit var menu: LinearLayout
    private lateinit var menuIcon: MenuIcon
    private lateinit var xrayText: TextView
    private lateinit var hudText: TextView
    private lateinit var modeText: TextView
    private val autoSeg by lazy { label(12f, bold = true).apply { text = "Auto"; gravity = Gravity.CENTER; setPadding(dpi(14), 0, dpi(14), 0) } }
    private val manualSeg by lazy { label(12f, bold = true).apply { text = "Manual"; gravity = Gravity.CENTER; setPadding(dpi(14), 0, dpi(14), 0) } }
    /** Auto | Manual, a two-way switch on the menu's edge (the selected side lit). */
    private val modeSwitch by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, 0, 0, dpi(8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; background = pillBackground(context); setPadding(dpi(3), dpi(3), dpi(3), dpi(3))
                addView(autoSeg, LinearLayout.LayoutParams(-2, -1)); addView(manualSeg, LinearLayout.LayoutParams(-2, -1))
            }, LinearLayout.LayoutParams(-2, dpi(BTN)))
            autoSeg.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); switchMode(false) }
            manualSeg.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); switchMode(true) }
        }
    }

    // ---- Auto / Manual targets ----
    // Auto: every saved target in view is found, outlined and scored. Manual (a range full of targets, or one you want
    // it to hold on to): it looks only for the targets you lock on (hold one on the camera, or Lock on in the target
    // pop-up); if it can't make one out, you draw it and it locks on right there.
    private var manual: Boolean
        get() = getSharedPreferences("nock", MODE_PRIVATE).getBoolean("manual", false)
        set(v) { getSharedPreferences("nock", MODE_PRIVATE).edit().putBoolean("manual", v).apply() }
    private val mine: MutableSet<String> by lazy {
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(getSharedPreferences("nock", MODE_PRIVATE).getStringSet("mine", emptySet()).orEmpty()) }
    }
    private fun saveMine() = getSharedPreferences("nock", MODE_PRIVATE).edit().putStringSet("mine", HashSet(mine)).apply()
    private fun isMine(f: TargetMemory.Face) = !manual || f.id in mine
    private fun renderMode() {
        for ((seg, on) in listOf(autoSeg to !manual, manualSeg to manual)) {
            seg.background = if (on) pillBackground(this, Brand.Smoke) else null
            seg.setTextColor(if (on) Brand.Carbon else Brand.Dim)
        }
    }
    private fun switchMode(on: Boolean) {
        manual = on; renderMode()
        marks?.let { m -> inView = m.seen.map { it.face }.filter { isMine(it) }; side = inView.firstOrNull(); showSide(); if (m.key == 0) drawMarks() }
        lookAsk = false; wantLook = true
        if (on && mine.isEmpty()) pickTargets() // Manual: say which targets first
    }
    private fun setMine(f: TargetMemory.Face, yes: Boolean) {
        if (yes) mine.add(f.id) else mine.remove(f.id)
        saveMine()
        marks?.let { m -> inView = m.seen.map { it.face }.filter { isMine(it) }; side = inView.firstOrNull(); showSide() }
    }

    /** One menu row: what it is (a glass label) beside its round glass button, both tappable. */
    private fun menuItem(icon: View, text: TextView, keepOpen: Boolean = false, onTap: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END // buttons line up on the edge
        clipChildren = false
        setPadding(0, 0, 0, dpi(8))
        addView(text.apply { background = pillBackground(context); setPadding(dpi(12), dpi(7), dpi(12), dpi(7)) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(8) })
        addView(FrameLayout(context).apply {
            background = dot(context, Brand.Glass); clipChildren = false
            addView(icon, FrameLayout.LayoutParams(if (icon is FrameLayout) -1 else dpi(16), if (icon is FrameLayout) -1 else dpi(16), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dpi(BTN), dpi(BTN)))
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onTap()
            // toggles stay open a beat so you see them flip; actions close the menu at once
            if (keepOpen) menu.postDelayed({ setMenu(false) }, 450) else setMenu(false)
        }
    }

    /** Open: the options drop in one after another down the right edge. Close: they lift away together. */
    private fun setMenu(open: Boolean) {
        if (open == (menu.visibility == View.VISIBLE && menu.tag != "closing")) return
        menu.animate().cancel()
        if (open) {
            menu.tag = null; menu.visibility = View.VISIBLE; menu.alpha = 1f
            menu.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            for (i in 0 until menu.childCount) menu.getChildAt(i).apply {
                animate().cancel(); alpha = 0f; translationY = -dp(14f)
                animate().alpha(1f).translationY(0f).setStartDelay(35L * i).setDuration(170).start()
            }
            if (hud) slider.fadeTo(false) // the zoom slider shares the right edge
        } else {
            menu.tag = "closing"
            menu.animate().alpha(0f).setDuration(120).withEndAction { if (menu.tag == "closing") { menu.visibility = View.GONE; menu.tag = null } }.start()
            if (hud) slider.fadeTo(true)
            menuIcon.color = Brand.Smoke
        }
    }


    /** Closes any pop-up that's open; true if there was one (so that tap does nothing else). */
    private fun dismissPopups(): Boolean {
        val open = menu.visibility == View.VISIBLE || presets.visibility == View.VISIBLE || qualityBar.visibility == View.VISIBLE || endCard.visibility == View.VISIBLE
        if (endCard.visibility == View.VISIBLE) endCard.fadeTo(false)
        if (menu.visibility == View.VISIBLE) setMenu(false)
        if (presets.visibility == View.VISIBLE) hidePresets()
        if (qualityBar.visibility == View.VISIBLE) qualityBar.fadeTo(false)
        return open
    }

    /** Controls appear on a tap and fade after a few seconds, so the camera view stays clean. */
    private fun scheduleHideChrome() { numbers.removeCallbacks(hideChrome); numbers.postDelayed(hideChrome, 4000) }
    private val hideChrome = Runnable {
        if (chromeTouching || hud) return@Runnable
        numbers.fadeTo(false); slider.fadeTo(false); hidePresets(); qualityBar.fadeTo(false)
    }

    // ---- The round: arrows-per-end from Settings; a new round after that many, or after 2 min (pulling arrows).
    private class RoundShot(val index: Int, val photo: java.io.File, var right: Float = 0f, var low: Float = 0f, var state: Int = 0, var ignored: Boolean = false) // 0 pending, 1 hit, 2 no hit
    private val round = ArrayList<RoundShot>()
    private var roundNo = 1
    private var lastArrowAt = 0L
    private val perEnd get() = com.nock.data.Prefs(this).arrowsPerEnd

    private fun addToRound(index: Int, photo: java.io.File) {
        val now = SystemClock.elapsedRealtime()
        // Rounds start when you press Start (setScoring); nothing rolls over by itself.
        lastArrowAt = now
        round.add(RoundShot(index, photo))
        renderRound()
    }

    private var summarisedRound = 0

    /** The end is complete (every arrow placed or lost): show its score here and on the watch, once. */
    private fun maybeEndRound() {
        val counted = round.filter { !it.ignored }
        if (summarisedRound == roundNo || counted.size < perEnd || counted.any { it.state == 0 }) return
        summarisedRound = roundNo
        val scored = counted.filter { it.state == 1 }
        val pts = scored.sumOf { com.nock.ui.Target.score(kotlin.math.hypot(it.right, it.low)) }
        val cx = scored.map { it.right }.average().toFloat(); val cy = scored.map { it.low }.average().toFloat()
        val group = if (scored.size >= 2) scored.map { kotlin.math.hypot(it.right - cx, it.low - cy) }.average().toFloat() else 0f
        val metric = com.nock.data.Prefs(this).metric
        endTitle.text = "Round $roundNo"
        endPoints.text = "$pts"
        endOf.text = "of ${counted.size * 10}"
        endDetail.text = if (scored.size >= 2) "Group " + com.nock.data.Prefs.len(group, metric) else ""
        endCard.fadeTo(true)
        endCard.removeCallbacks(hideEnd); endCard.postDelayed(hideEnd, 6000)
        watch?.let { messageClient.sendMessage(it.id, PATH_END, ByteBuffer.allocate(12).putInt(roundNo).putInt(pts).putInt(counted.size).array()) }
    }
    private val hideEnd = Runnable { endCard.fadeTo(false) }

    private fun renderRound() {
        val metric = com.nock.data.Prefs(this).metric
        roundTitle.text = "Round $roundNo"
        val counted = round.filter { !it.ignored }
        val scored = counted.filter { it.state == 1 }
        val pts = scored.sumOf { com.nock.ui.Target.score(kotlin.math.hypot(it.right, it.low)) }
        roundCount.text = "${counted.size} of $perEnd" + if (scored.isNotEmpty()) "  ·  $pts pts" else ""
        roundRows.removeAllViews()
        counted.withIndex().reversed().forEach { (i, r) ->
            roundRows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpi(3), 0, dpi(3))
                val d = kotlin.math.hypot(r.right, r.low)
                addView(label(13f, bold = true).apply { text = "${i + 1}" }, LinearLayout.LayoutParams(dpi(18), -2))
                // the ring it landed in, in that ring's colour (standard 40 cm face); amber = still finding, brick = lost
                addView(label(13f, bold = true).apply {
                    text = when (r.state) { 1 -> com.nock.ui.Target.label(d); 2 -> "?"; else -> "·" }
                    setTextColor(when (r.state) { 1 -> com.nock.ui.Target.color(com.nock.ui.Target.score(d)).toArgb(); 2 -> Brand.Brick; else -> Brand.Amber })
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dpi(26), -2).apply { marginEnd = dpi(6) })
                addView(label(13f, color = if (r.state == 2) Brand.Dim else Brand.Smoke).apply {
                    text = when (r.state) {
                        1 -> com.nock.data.Prefs.len(d, metric)
                        2 -> "hit ?"
                        else -> "…"
                    }
                })
                if (r.state == 1) addView(MissArrow(this@MainActivity).apply { set(r.right, r.low) },
                    LinearLayout.LayoutParams(dpi(14), dpi(14)).apply { marginStart = dpi(6) })
            })
        }
        roundPanel.visibility = if (counted.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun reviewRound() = review.open(only = round.map { it.photo }, label = "Round $roundNo")
    private fun reviewRoundNo(n: Int) { if (n == roundNo) reviewRound() }

    private fun zoomBy(factor: Float) {
        val zoom = camera?.cameraInfo?.zoomState?.value ?: return
        setZoom(zoom.zoomRatio * factor)
    }

    /** One way in for every zoom source (slider, pinch, watch). Remembered across restarts. */
    private fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val zoom = cam.cameraInfo.zoomState.value ?: return
        val next = ratio.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
        unlockExposure()
        if (kotlin.math.abs(next - zoom.zoomRatio) > 0.01f) {
            shots.resetReference(); hitExecutor.execute { hitFinder.reset() }
            zoomedAt = SystemClock.elapsedRealtime(); relookAfterStill = true // marks follow the zoom; re-anchored once settled
        }
        cam.cameraControl.setZoomRatio(next)
        slider.setRange(zoom.minZoomRatio, zoom.maxZoomRatio)
        slider.setValue(next)
        previewView.removeCallbacks(lockExposure)
        previewView.postDelayed(lockExposure, LOCK_AFTER_MS)
    }

    private fun showPresets() {
        val zoom = camera?.cameraInfo?.zoomState?.value ?: return
        presets.removeAllViews()
        for (z in PRESETS) {
            if (z < zoom.minZoomRatio - 0.01f || z > zoom.maxZoomRatio + 0.01f) continue
            val on = kotlin.math.abs(z - zoom.zoomRatio) < 0.05f
            presets.addView(label(14f, bold = true, color = if (on) Brand.Carbon else Brand.Smoke).apply {
                text = if (z < 1f) "%.1f×".format(z) else "%.0f×".format(z)
                gravity = Gravity.CENTER
                background = pillBackground(context, if (on) Brand.Smoke else Brand.Glass)
                setOnClickListener { setZoom(z); hidePresets() }
            }, LinearLayout.LayoutParams(dpi(56), dpi(34)).apply { bottomMargin = dpi(8) })
        }
        presets.fadeTo(true)
        slider.wake()
        presets.removeCallbacks(hidePresetsLater)
        presets.postDelayed(hidePresetsLater, 3000)
    }

    private fun hidePresets() { presets.fadeTo(false); slider.sleepSoon() }
    private val hidePresetsLater = Runnable { hidePresets() }

    // ---- Exposure / focus lock ----
    // Once the framing has been left alone for a moment, freeze brightness, white balance and focus.
    // Auto-brightness drifting between the before and after photos looks like change and blurs the
    // arrow out of the difference; a fixed exposure keeps the only change the arrow itself.

    @OptIn(ExperimentalCamera2Interop::class)
    private val lockExposure = Runnable {
        val cam = camera ?: return@Runnable
        val center = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(center, FocusMeteringAction.FLAG_AF).disableAutoCancel().build()
        )
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            .build())
        locked = true
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun unlockExposure() {
        if (!locked) return
        val cam = camera ?: return
        Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions()
        cam.cameraControl.cancelFocusAndMetering()
        locked = false
    }

    private var locked = false

    private fun stat(value: TextView, unit: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(value)
        addView(label(11f, bold = true, color = Brand.Dim).apply { text = " $unit"; setPadding(0, 0, 0, dpi(2)) })
    }

    private fun setQuality(i: Int) {
        quality = i
        frameWidth = QUALITY_WIDTHS[i]
        prefs.edit().putInt(KEY_QUALITY, i).apply()
        qualityPills.forEachIndexed { j, pill ->
            pill.background = if (j == i) pillBackground(this, Brand.Smoke) else null
            pill.setTextColor(if (j == i) Brand.Carbon else Brand.Smoke)
        }
        stream.reset()
    }

    /** Opens Nock on the watch (the other app's nock://open link). */
    private fun openOnWatch() {
        val node = watch ?: run { statusText.text = "No watch connected"; return }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            .setData(android.net.Uri.parse("nock://open"))
        statusText.text = "Opening on ${node.displayName}…"
        // Fast path first; if the watch isn't live shortly after, use the slower Play-services route.
        messageClient.sendMessage(node.id, OpenService.PATH_OPEN, ByteArray(0))
        previewView.postDelayed({
            if (stream.live() == null) androidx.wear.remote.interactions.RemoteActivityHelper(this).startRemoteActivity(intent, node.id)
        }, 3000) // backup only if the fast path didn't get the app streaming
    }

    private val refreshStatus: Runnable = Runnable {
        val w = watch
        val live = stream.live()
        // Watch mode while the live view runs OR the watch's session says it's alive (its screen may be asleep).
        // Phone-only scoring only when neither has been heard for 12 s.
        val (color, text) = when {
            w == null -> Brand.Amber to "Looking for watch"
            live == null -> Brand.Amber to "Tap to open on ${w.displayName}"
            live.dropping -> Brand.Brick to "Weak signal"
            else -> Brand.TealBright to "Live on ${w.displayName}" // a live link: Teal
        }
        statusDot.background = dot(this, color)
        statusText.text = when {
            w == null -> "No watch connected"
            live == null -> "Open Nock on ${w.displayName}"
            live.dropping -> "${w.displayName} · weak signal"
            else -> "${w.displayName} · live"
        }
        if (text.isEmpty()) statusDot.alpha = 1f
        fpsText.text = live?.let { "%.0f".format(it.fps) } ?: "–"
        delayText.text = live?.let { "%d".format(it.delayMs) } ?: "–"
        previewView.postDelayed(refreshStatus, 400)
    }

    // ---- Camera and streaming ----

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor, ::onFrame)

            provider.unbindAll()
            // One viewport for both: the frames Nock looks at are cropped to exactly what the screen shows (the camera's
            // 4:3 frame reached past the tall screen, so it found targets you couldn't see, 2026-10-08).
            val vp = previewView.viewPort
            camera = if (vp != null) provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                androidx.camera.core.UseCaseGroup.Builder().setViewPort(vp).addUseCase(preview).addUseCase(analysis).build())
            else provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            // Zoom state arrives a moment after binding; restore the last zoom once it does.
            startGyroCal()
            camera?.cameraInfo?.zoomState?.observe(this) { z ->
                slider.setRange(z.minZoomRatio, z.maxZoomRatio)
                slider.setValue(z.zoomRatio)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(image: ImageProxy) {
        // Work on the sensor-oriented frame; only small or saved images get rotated. Rotating a full
        // 1080p frame every time cost about a third of the stream's frame rate.
        val f0 = SystemClock.elapsedRealtime()
        val rotation = image.imageInfo.rotationDegrees
        val frameR = orient.at(image.imageInfo.timestamp) // where the phone pointed when this frame was taken
        // How this frame (upright) maps onto the preview, for the live target marks.
        val upTransform = if (wantLook || relookAfterStill || marks != null || anchors.isNotEmpty()) runCatching {
            androidx.camera.view.transform.ImageProxyTransformFactory().apply { isUsingRotationDegrees = true; isUsingCropRect = true }.getOutputTransform(image)
        }.getOrNull() else null
        // only the visible part (the viewport crop): nothing off screen is looked at, scored or streamed
        val full = image.use { frameBitmap(it) }
        val f1 = SystemClock.elapsedRealtime()
        if (f1 - darkCheckAt > 500) { if (darkCheckAt == 0L) android.util.Log.d("NockShots", "camera luma ${"%.1f".format(luma(full))}"); darkCheckAt = f1; val d = luma(full) < DARK_LUMA; if (d != dark) { dark = d; android.util.Log.d("NockShots", "camera dark=$d (mean luma ${"%.1f".format(luma(full))})"); showSide() } }
        shots.onFrame(full, rotation)
        frameBmpMs += f1 - f0; frameShotMs += SystemClock.elapsedRealtime() - f1
        val fw = if (rotation % 180 == 0) full.width else full.height; val fh = if (rotation % 180 == 0) full.height else full.width
        val zNow = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        if (fw != frameW || fh != frameH) { frameW = fw; frameH = fh; if (anchors.isNotEmpty()) { anchors.clear(); expect.clear() } } // turned the screen: directions start over
        // The view changed (phone moved or zoomed): once it's been still for 1 s, quietly check which target it sees
        // (the marks follow the move meanwhile; this re-anchors them).
        if (relookAfterStill && SystemClock.elapsedRealtime() - maxOf(shots.phoneMovedAt, zoomedAt) > STILL_MS) { relookAfterStill = false; emptyLooks = 0; lookAsk = false; wantLook = true }
        if (wantFresh) { wantFresh = false; val up = scaledUpright(full, rotation, if (rotation % 180 == 0) full.width else full.height).let { if (it === full) full.copy(Bitmap.Config.ARGB_8888, false) else it }; hitExecutor.execute { freshChoice(up) } }
        if (wantCapture) { wantCapture = false; val up = scaledUpright(full, rotation, if (rotation % 180 == 0) full.width else full.height).let { if (it === full) full.copy(Bitmap.Config.ARGB_8888, false) else it }; hitExecutor.execute { captureView(up) } }
        if (wantMark) { wantMark = false; val up = scaledUpright(full, rotation, if (rotation % 180 == 0) full.width else full.height).let { if (it === full) full.copy(Bitmap.Config.ARGB_8888, false) else it }; hitExecutor.execute { markAgain(up) } }
        if (wantLook && !lookBusy && !dark) {
            wantLook = false; lookBusy = true
            val up = scaledUpright(full, rotation, if (rotation % 180 == 0) full.width else full.height).let { if (it === full) full.copy(Bitmap.Config.ARGB_8888, false) else it }
            // Targets on screen now: carried into this frame (each by its own tracked move), so they stay drawn and keep
            // following the camera while the look runs, instead of freezing until it lands. Their last place follows too
            // (the look checks there first).
            val m = marks?.takeIf { (it.key == tracker.key && tracker.active) || it.key == STATIC || it.key == GYRO }
            val g = if (m?.key == STATIC) null else trackedH // kept marks are already where they are now; gyro ones move by it
            val carried = m?.let { mk -> mk.seen.map { s ->
                val hh = if (m.key == STATIC) null else if (m.key == GYRO) g else runCatching { tracker.hFor(s.outline) }.getOrNull() ?: g
                if (hh != null) runCatching { memory.carry(s.face.id, hh) }
                if (hh == null) s else moved(s, hh)
            } }
            val carriedUnknown = m?.unknown?.let { u -> if (g == null) u else movedPts(u, g) }
            // this frame becomes the key frame: the marks this look finds are followed from here
            val key = runCatching { tracker.start(tracker.small(full, rotation)) }.getOrDefault(0)
            trackZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 0f
            trackedH = null; trackedTransform = upTransform
            if (frameR != null && key != 0) { keyR = frameR; keyZ = zNow; keyRs[key] = Triple(frameR, zNow, fw to fh); keyRs.keys.filter { it < key - 8 }.forEach { keyRs.remove(it) } }
            if (carried != null && key != 0) marks = Marks(key, carried, carriedUnknown)
            hitExecutor.execute { lookAtTarget(up, upTransform, key) }
        } else if (trackLost) { trackLost = false; tracker.reset() }
        // Targets on screen: re-align them on the real target every REFRESH_MS. Following the camera frame to frame
        // drifts over minutes (the outlines slid off with the phone still and no new look to correct them).
        if (marks?.seen?.isNotEmpty() == true) emptyLooks = 0
        if (!wantLook && !lookBusy && marks?.seen?.isNotEmpty() == true && SystemClock.elapsedRealtime() - lookDoneAt > REFRESH_MS) { lookAsk = false; refreshLook = true; wantLook = true }
        // Nothing on screen: look again after 2, 4, 8 s, then every 10 s. A look on a bad frame (the very first one, dark
        // or blurred) found nothing and, with the phone still, nothing ever looked again (seen 2026-10-08).
        else if (!wantLook && !lookBusy && marks?.seen.isNullOrEmpty() && lookDoneAt > 0 &&
            SystemClock.elapsedRealtime() - lookDoneAt > (EMPTY_RETRY_MS shl emptyLooks.coerceAtMost(3)).coerceAtMost(FULL_EVERY_MS)) { emptyLooks++; lookAsk = false; wantLook = true }
        else if (tracker.active) {
            val t0 = SystemClock.elapsedRealtime()
            val z = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
            val ok = runCatching { tracker.update(tracker.small(full, rotation), if (trackZoom > 0f) (z / trackZoom).toDouble() else 1.0) }.getOrDefault(false)
            trackZoom = z
            if (!ok) {
                val m = marks; val g = trackedH
                tracker.reset()
                if (m != null) {
                    if (SystemClock.elapsedRealtime() - maxOf(shots.phoneMovedAt, zoomedAt) > STILL_MS) {
                        // the phone didn't move, so the targets didn't either: something passed in front. Keep them.
                        android.util.Log.d("NockShots", "tracking lost, phone still - marks kept, looking again")
                        marks = Marks(STATIC, m.seen.map { s -> if (g == null) s else moved(s, g) }, m.unknown?.let { u -> if (g == null) u else movedPts(u, g) })
                        wantLook = true
                    } else if (gyroM != null && keyR != null && m.key != STATIC && m.key != GYRO) {
                        // a fast turn: the picture can't be followed, the gyro can. The marks keep moving with it.
                        android.util.Log.d("NockShots", "tracking lost while turning - marks follow the gyro")
                        gyroBase = keyR!! to keyZ
                        marks = Marks(GYRO, m.seen, m.unknown)
                    } else if (m.key != GYRO) { android.util.Log.d("NockShots", "tracking lost - marks hidden, looking again"); dropMarks() }
                }
            }
            else {
                if (marks?.key == tracker.key) { trackedH = tracker.h(); trackedTransform = upTransform; drawMarks() }
                calibrate(frameR, zNow, fw, fh)
            }
            trackMs += SystemClock.elapsedRealtime() - t0; trackN++
            if (t0 - trackLog > 5000) {
                android.util.Log.d("NockShots", "tracking: $trackN frames in 5 s, %.0f ms each; per frame: picture %.1f, arrow check %.1f, whole %.1f ms (%d frames)".format(
                    trackMs.toDouble() / trackN.coerceAtLeast(1), frameBmpMs.toDouble() / frameN.coerceAtLeast(1), frameShotMs.toDouble() / frameN.coerceAtLeast(1), frameAllMs.toDouble() / frameN.coerceAtLeast(1), frameN))
                trackLog = t0; trackMs = 0; trackN = 0; frameBmpMs = 0; frameShotMs = 0; frameAllMs = 0; frameN = 0
            }
        }
        // not tracking the picture: gyro-followed marks move by the phone's turn
        if (!tracker.active && marks?.key == GYRO) gyroH(gyroBase?.first, gyroBase?.second ?: 1f, frameR, zNow, fw, fh)?.let { hg ->
            trackedH = GyroGeom.toMat(hg); trackedTransform = upTransform; drawMarks()
        }
        if (++anchorTick % 3 == 0) followAnchors(frameR, zNow, fw, fh, upTransform)
        stream(full, rotation)
        frameAllMs += SystemClock.elapsedRealtime() - f0; frameN++
    }

    /**
     * The frame's visible part, copied straight from the camera's buffer into one bitmap kept for every frame. A new
     * bitmap per frame (toBitmap, then a cropped copy) was 15 ms of each frame's 26 ms (measured 2026-10-08). Only valid
     * until the next frame: anything kept or handed to another thread is copied first (as it already was).
     */
    private var frameBuf: Bitmap? = null
    private var rowBuf: ByteBuffer? = null
    /** Mean brightness (0-255) of a 16 x 16 grid of the frame's pixels: cheap enough to run twice a second. */
    private fun luma(b: Bitmap): Float {
        var sum = 0L
        for (j in 0 until 16) for (i in 0 until 16) {
            val c = b.getPixel((i * 2 + 1) * b.width / 32, (j * 2 + 1) * b.height / 32)
            sum += (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000
        }
        return sum / 256f
    }
    private fun frameBitmap(im: ImageProxy): Bitmap {
        val r = im.cropRect; val plane = im.planes[0]; val src = plane.buffer; val rs = plane.rowStride
        val w = r.width().coerceAtMost(im.width - r.left); val h = r.height().coerceAtMost(im.height - r.top)
        var b = frameBuf
        if (b == null || b.width != w || b.height != h) { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); frameBuf = b; rowBuf = null }
        if (r.left == 0 && rs == w * 4) { src.limit(src.capacity()); src.position(r.top * rs); b.copyPixelsFromBuffer(src) } // rows already back to back
        else {
            val rows = rowBuf ?: ByteBuffer.allocateDirect(w * h * 4).also { rowBuf = it }
            rows.clear()
            for (y in 0 until h) { val at = (r.top + y) * rs + r.left * 4; src.limit(src.capacity()); src.position(at); src.limit(at + w * 4); rows.put(src) }
            rows.flip(); b.copyPixelsFromBuffer(rows)
        }
        return b
    }

    // ---- Direction memory (gyro) ----
    // Every target found is remembered by where the phone was pointing when it was seen. Turning the phone moves it by
    // the gyro's turn (pure rotation: it doesn't move in the picture when the phone shifts a few cm), so a target off
    // screen gets a tag at the screen edge pointing to it, and turning back puts it where it is at once (a quick fine-align
    // there confirms it; three misses and it's forgotten).
    private val orient = Orientation()
    @Volatile private var gyroM: DoubleArray? = null
    @Volatile private var gyroFn = 0.0
    private var gyroCal: GyroCal? = null
    private var gyroFn0 = 0.8
    private var calAt = 0L
    @Volatile private var keyR: DoubleArray? = null
    @Volatile private var keyZ = 1f
    @Volatile private var gyroBase: Pair<DoubleArray, Float>? = null
    private val keyRs = java.util.concurrent.ConcurrentHashMap<Int, Triple<DoubleArray, Float, Pair<Int, Int>>>()
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    private var anchorTick = 0
    private class Anchor(val seen: TargetMemory.Seen, val r: DoubleArray, val z: Float, val refH: org.opencv.core.Mat?)
    private val anchors = java.util.concurrent.ConcurrentHashMap<String, Anchor>()
    private val expect: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val expectMiss = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startGyroCal() {
        if (gyroCal != null) return
        val info = camera?.cameraInfo ?: return
        val c = androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
        val fl = runCatching { c.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() }.getOrNull()
        val ss = runCatching { c.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) }.getOrNull()
        // the lens's focal length over the sensor's long side (Camera2 characteristics): the fit's starting point
        val fn0 = if (fl != null && ss != null && ss.width > 0) fl / maxOf(ss.width, ss.height).toDouble() else 0.8
        android.util.Log.d("NockGyro", "lens: focal $fl mm, sensor ${ss?.width} x ${ss?.height} mm -> start %.3f".format(fn0) + if (gyroM != null) ", already calibrated (focal %.3f)".format(gyroFn) else "")
        gyroFn0 = fn0; gyroCal = GyroCal(fn0)
    }

    private fun gyroKey() = "gyro_m_${display?.rotation ?: 0}"
    private fun loadGyro() {
        val p = getSharedPreferences("nock", MODE_PRIVATE)
        val mi = p.getInt(gyroKey(), -1); val fn = p.getFloat("gyro_fn", 0f).toDouble()
        if (mi in 0..3 && fn > 0) { gyroM = GyroGeom.CANDIDATES[mi]; gyroFn = fn } else { gyroM = null }
    }

    private fun gyroH(rk: DoubleArray?, zk: Float, rn: DoubleArray?, zn: Float, w: Int, h: Int): DoubleArray? {
        val m = gyroM ?: return null; if (rk == null || rn == null || w == 0) return null
        return GyroGeom.homography(rk, zk, rn, zn, m, gyroFn, w, h)
    }

    /** While the picture is followed and the phone turns 2-30 deg, pair the measured move with the gyro's (see GyroCal). */
    private fun calibrate(r: DoubleArray?, z: Float, w: Int, h: Int) {
        val g = guided
        if ((gyroM != null && g == null) || r == null) return
        val kr = keyR ?: return; val tracked = tracker.h() ?: return
        val cal = g ?: gyroCal ?: return
        val now = SystemClock.elapsedRealtime(); if (now - calAt < 250) return
        val a = GyroGeom.angle(kr, r); if (g != null) calTurn = a
        if (a < 2 || a > 30) return
        calAt = now
        cal.add(kr, keyZ, r, z, tracked, w, h)
        if (g != null) { guidedSample(g, tracked, w, h); return }
        if (cal.size % 10 == 0) Thread {
            cal.solve()?.let { (mi, fn, err) ->
                getSharedPreferences("nock", MODE_PRIVATE).edit().putInt(gyroKey(), mi).putFloat("gyro_fn", fn.toFloat()).apply()
                gyroM = GyroGeom.CANDIDATES[mi]; gyroFn = fn
                android.util.Log.d("NockGyro", "calibrated: axes m$mi, focal %.3f of the long side, mean error %.1f%% of the frame".format(fn, err * 100))
            }
        }.start()
    }

    // ---- Calibrate the gyro, guided ----
    // Nock learns how the gyro's turns line up with the camera from turns made with targets on screen. Normally that
    // happens by itself; this walks you through it: turn left, right, up and down (each way fills its arc), and when
    // every way is full it fits and saves. The old calibration stays in use until a new one fits.
    @Volatile private var guided: GyroCal? = null
    private val turns = IntArray(4) // turns collected per way: left, right, up, down
    @Volatile private var calTurn = 0.0 // how far the phone is turned from the last look, degrees
    @Volatile private var calSolving = false
    private var gyroMisses = 0
    private var askedCal = false

    private fun startCal() {
        if (gyroCal == null) { toastCard("Camera's still starting"); return }
        synchronized(turns) { turns.fill(0) }; calTurn = 0.0; calSolving = false; guided = GyroCal(gyroFn0)
        if (!xray) { xray = true; renderXray() } // the targets need to show while you turn
        calTitle.text = "Calibrate gyro"
        calCard.removeCallbacks(calTick); calCard.fadeTo(true); renderCal()
    }

    private fun endCal() { guided = null; calCard.removeCallbacks(calTick); calCard.fadeTo(false) }

    /** A turn with targets on screen: which way the picture slid says which way you turned. */
    private fun guidedSample(g: GyroCal, tracked: org.opencv.core.Mat, w: Int, h: Int) {
        val c = org.opencv.core.MatOfPoint2f(org.opencv.core.Point(w / 2.0, h / 2.0)); val out = org.opencv.core.MatOfPoint2f()
        org.opencv.core.Core.perspectiveTransform(c, out, tracked); val p = out.toArray()[0]
        val dx = p.x - w / 2.0; val dy = p.y - h / 2.0
        // the picture slides the opposite way: it moving right means you turned left
        val way = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) (if (dx > 0) 0 else 1) else (if (dy > 0) 2 else 3)
        val full = synchronized(turns) { turns[way]++; turns.all { it >= CAL_PER_WAY } }
        if (full && !calSolving) {
            calSolving = true
            Thread {
                val res = g.solve()
                runOnUiThread {
                    if (guided !== g) return@runOnUiThread
                    if (res != null) {
                        val (mi, fn, err) = res
                        getSharedPreferences("nock", MODE_PRIVATE).edit().putInt(gyroKey(), mi).putFloat("gyro_fn", fn.toFloat()).apply()
                        gyroM = GyroGeom.CANDIDATES[mi]; gyroFn = fn; gyroMisses = 0
                        android.util.Log.d("NockGyro", "calibrated (guided): axes m$mi, focal %.3f, mean error %.1f%%".format(fn, err * 100))
                        guided = null; calCard.removeCallbacks(calTick)
                        calTitle.text = "Calibrated"; calHint.text = "Tags now follow your targets as you turn"; calHint.setTextColor(Brand.Smoke)
                        calDial.fills = FloatArray(4) { 1f }; calDial.percent = 100; calDial.next = -1
                        calCard.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        calCard.postDelayed({ if (guided == null) calCard.fadeTo(false) }, 2200)
                    } else {
                        // it didn't settle: start the ways over (the old calibration is still in use)
                        android.util.Log.d("NockGyro", "guided fit did not settle - again")
                        synchronized(turns) { turns.fill(0) }; guided = GyroCal(gyroFn0); calSolving = false
                        calRetry = SystemClock.elapsedRealtime()
                        calCard.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
                    }
                }
            }.start()
        }
    }

    private var calRetry = 0L // when a fit last failed (the hint says so for a few seconds)
    private val calTick = Runnable { renderCal() }
    private fun renderCal() {
        if (guided == null) return
        val t = synchronized(turns) { turns.copyOf() }
        calDial.fills = FloatArray(4) { (t[it].toFloat() / CAL_PER_WAY).coerceAtMost(1f) }
        calDial.percent = 100 * t.sumOf { it.coerceAtMost(CAL_PER_WAY) } / (4 * CAL_PER_WAY)
        val next = t.indices.minByOrNull { t[it] } ?: 0
        val ways = arrayOf("left", "right", "up", "down")
        val sees = marks?.seen?.isNotEmpty() == true && tracker.active
        fun hint(text: String, color: Int, arrow: Int) { calHint.text = text; calHint.setTextColor(color); calDial.next = arrow }
        when {
            calSolving -> hint("Checking", Brand.Smoke, -1)
            !sees -> hint("Point at your targets", Brand.Amber, -1)
            calTurn > 30 -> hint("Too far. Come back a little", Brand.Amber, -1)
            SystemClock.elapsedRealtime() - calRetry < 4000 -> hint("Almost. Again, a bit slower", Brand.Amber, next)
            else -> hint("Slowly turn " + ways[next], Brand.Smoke, next)
        }
        calCard.postDelayed(calTick, 200)
    }

    private val calDial by lazy { CalDial(this) }
    private val calTitle by lazy { label(17f, bold = true) }
    private val calHint by lazy { label(15f, bold = true).apply { gravity = Gravity.CENTER } }
    private val calCard: LinearLayout by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(24f); setColor(Brand.Glass) }
            setPadding(dpi(14), dpi(12), dpi(20), dpi(12))
            addView(calDial, LinearLayout.LayoutParams(dpi(104), dpi(104)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(calTitle.apply { textSize = 13f; setTextColor(Brand.Dim) }, LinearLayout.LayoutParams(-2, -2))
                addView(calHint.apply { gravity = Gravity.START }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(4) })
                addView(label(12f, color = Brand.Dim).apply { text = "Hold it upright, targets on screen" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(4) })
                addView(label(14f, bold = true, color = Brand.Carbon).apply {
                    text = "Done"; gravity = Gravity.CENTER; background = pillBackground(context, Brand.FernBright)
                    setPadding(dpi(24), dpi(8), dpi(24), dpi(8))
                    setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); endCal() }
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(10) })
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(12) })
            visibility = View.GONE
        }
    }

    /** Remember each target found (where it was and where the phone pointed). */
    private fun anchor(seen: List<TargetMemory.Seen>, key: Int) {
        val (r, z, _) = keyRs[key] ?: return
        for (s in seen) if (isMine(s.face)) anchors[s.face.id] = Anchor(s, r, z, memory.placeOf(s.face.id))
    }

    private fun followAnchors(r: DoubleArray?, z: Float, w: Int, h: Int, transform: androidx.camera.view.transform.OutputTransform?) {
        val m = gyroM ?: return; if (r == null || anchors.isEmpty()) { runOnUiThread { targetMarks.setEdges(emptyList()); targetMarks.setGhosts(emptyList()) }; return }
        val shown = marks?.seen.orEmpty().map { it.face.id }.toSet()
        val edges = ArrayList<TargetOverlay.EdgeMark>()
        val ghosts = ArrayList<Pair<String, List<org.opencv.core.Point>>>() // frame coordinates, mapped onto the screen below
        for ((id, a) in anchors) {
            if (id in shown || !isMine(a.seen.face)) continue
            val c = org.opencv.core.Point(a.seen.outline.sumOf { it.x } / a.seen.outline.size, a.seen.outline.sumOf { it.y } / a.seen.outline.size)
            val hg = GyroGeom.homography(a.r, a.z, r, z, m, gyroFn, w, h)
            val p = GyroGeom.map(hg, c)
            val o = a.seen.outline.map { GyroGeom.map(hg, it) }
            val partlyIn = o.all { it != null } && o.any { it!!.x in 0.0..w.toDouble() && it.y in 0.0..h.toDouble() }
            if (partlyIn) ghosts.add((arLabel(a.seen.face) ?: a.seen.face.name) to o.map { it!! })
            if (p != null && p.x in 0.1 * w..0.9 * w && p.y in 0.1 * h..0.9 * h) {
                // back in view: put it where the gyro says, and have the next look (queued if one is running) fine-align it there
                if (id !in expect && a.refH != null) {
                    val place = org.opencv.core.Mat(); org.opencv.core.Core.gemm(GyroGeom.toMat(hg), a.refH, 1.0, org.opencv.core.Mat(), 0.0, place)
                    memory.setLast(id, w, h, place); expect.add(id); lookAsk = false; wantLook = true
                }
            } else if (!partlyIn) {
                val d = GyroGeom.direction(c, a.r, a.z, r, m, gyroFn, w, h)
                edges.add(TargetOverlay.EdgeMark(arLabel(a.seen.face) ?: a.seen.face.name, d[0].toFloat(), d[1].toFloat()))
            }
        }
        runOnUiThread {
            targetMarks.setEdges(if (xray) edges else emptyList())
            val target = previewView.outputTransform
            if (!xray || transform == null || target == null) { targetMarks.setGhosts(emptyList()); return@runOnUiThread }
            val ct = androidx.camera.view.transform.CoordinateTransform(transform, target)
            targetMarks.setGhosts(ghosts.map { (label, pts) ->
                val q = FloatArray(pts.size * 2); pts.forEachIndexed { i, p -> q[2 * i] = p.x.toFloat(); q[2 * i + 1] = p.y.toFloat() }
                ct.mapPoints(q); TargetOverlay.Ghost(label, pts.indices.map { android.graphics.PointF(q[2 * it], q[2 * it + 1]) })
            })
        }
    }

    private val tracker = MarkTracker()
    private var trackLog = 0L; private var trackMs = 0L; private var trackN = 0
    private var emptyLooks = 0 // looks in a row that found nothing (each waits longer)
    private var frameBmpMs = 0L; private var frameShotMs = 0L; private var frameAllMs = 0L; private var frameN = 0 // where each camera frame's time goes
    @Volatile private var trackLost = false // set off the camera thread: stop tracking on the next frame
    private var slowSince = 0L
    @Volatile private var askedSlow = false // asked once per session; "Not now" means not again

    /** The live view is slow: suggest Fast. Nothing changes unless you say yes. */
    private fun askSlow(live: StreamStats.Live) {
        android.util.Log.d("NockStream", "slow link: %d ms, %.0f fps - asked".format(live.delayMs, live.fps))
        ask("Live view is slow (${live.delayMs} ms). Switch to Fast?", "Yes, Fast") { setQuality(0) }
    }

    @Volatile private var lastAckAt = 0L    // the watch answered a frame: its live view is open
    @Volatile private var viewingAt = 0L    // the watch said its live view just opened

    private fun stream(full: Bitmap, rotation: Int) {
        val target = watch ?: return
        val now = SystemClock.elapsedRealtime()
        // Stream only while the watch's Nock screen is open and asking (PATH_VIEWING) or answering frames. Frames sent
        // to a closed watch app kept it awake: 449 MB over Bluetooth, link awake 2.5 h of 5.5 h, 46% battery (2026-10-08).
        if (now - maxOf(lastAckAt, viewingAt) > WATCH_IDLE_MS) { sentAt.clear(); return }
        // Frames the watch never acked count as dropped and free their slot.
        sentAt.entries.removeIf { (_, at) -> (now - at > ACK_TIMEOUT_MS).also { if (it) stream.onDrop() } }
        if (sentAt.size >= MAX_IN_FLIGHT) return

        // A slow link (measured 25 KB/s vs a healthy 126-146 KB/s on 2026-10-07) is never changed for you:
        // after a few slow seconds, ask once whether to switch to Fast.
        stream.live()?.let { live ->
            if (live.delayMs > SLOW_MS && quality > 0) { if (slowSince == 0L) slowSince = now } else slowSince = 0L
            if (slowSince != 0L && now - slowSince > 5000 && !askedSlow) { askedSlow = true; runOnUiThread { askSlow(live) } }
        }
        val jpeg = encode(full, rotation, frameWidth)
        val n = seq.incrementAndGet()
        val zoomState = camera?.cameraInfo?.zoomState?.value
        // Header: seq, zoom, quality, zoom range — the phone is the one source of truth for all of it.
        // Quality byte: low 4 bits = quality, 0x10 = scoring is on (the watch's Start/Pause button follows it).
        val payload = ByteBuffer.allocate(17 + jpeg.size).putInt(n).putFloat(zoomState?.zoomRatio ?: 1f).put((quality or (if (shots.active) 0x10 else 0) or (if (xray) 0x20 else 0)).toByte())
            .putFloat(zoomState?.minZoomRatio ?: 1f).putFloat(zoomState?.maxZoomRatio ?: 1f).put(jpeg).array()
        sentAt[n] = now
        messageClient.sendMessage(target.id, PATH_FRAME, payload)
            .addOnFailureListener { sentAt.remove(n); stream.onDrop() }
    }

    /** Scale so the upright image is [width] wide, rotate upright, JPEG. */
    /** The X-ray marks (upright photo coords) to draw into the watch's live view too. */
    private class StreamMark(val outline: List<android.graphics.PointF>, val bulls: List<List<android.graphics.PointF>>, val known: Boolean, val label: String?)
    @Volatile private var streamMarks: List<StreamMark> = emptyList()
    private val streamLine by lazy { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF39FF14.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f } }
    private val streamDash by lazy { android.graphics.Paint(streamLine).apply { color = 0xCCF5F5F4.toInt(); pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 4f), 0f) } }
    private val streamText by lazy { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Smoke; textSize = 11f; isFakeBoldText = true; setShadowLayer(3f, 0f, 0f, Brand.Carbon) } }

    private fun encode(full: Bitmap, rotation: Int, width: Int, jpegQuality: Int = JPEG_QUALITY): ByteArray {
        var small = scaledUpright(full, rotation, width)
        val marks = streamMarks
        if (xray && marks.isNotEmpty()) {
            if (!small.isMutable || small === full) small = small.copy(Bitmap.Config.ARGB_8888, true).also { if (small !== full) small.recycle() } // never draw on the camera frame
            val k = small.width.toFloat() / (if (rotation % 180 == 0) full.width else full.height)
            val c = android.graphics.Canvas(small)
            fun path(ps: List<android.graphics.PointF>) = android.graphics.Path().apply { moveTo(ps[0].x * k, ps[0].y * k); ps.drop(1).forEach { lineTo(it.x * k, it.y * k) }; close() }
            for (m in marks) {
                c.drawPath(path(m.outline), if (m.known) streamLine else streamDash)
                m.bulls.filter { it.size >= 3 }.forEach { c.drawPath(path(it), streamLine) }
                m.label?.let { t -> c.drawText(t, m.outline.minOf { it.x } * k, (m.outline.minOf { it.y } * k - 4f).coerceAtLeast(12f), streamText) }
            }
        }
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        if (small !== full) small.recycle()
        return out.toByteArray()
    }

    private fun onShot(e: ShotDetector.Event) {
        // Watch-confirmed shot: tell the watch right away (its "saw it" buzz), then work out where it hit.
        // Unconfirmed changes (saved while the watch wasn't live) never buzz the watch.
        // No watch: still score it (phone-only shooting). Only a change with a NEW orange fletching becomes an
        // arrow (the hit finder needs one), so bumps and walk-bys drop out. Tagged "no_watch" in the result.
        if (e.confirmed) watch?.let { messageClient.sendMessage(it.id, PATH_ARROW, ByteBuffer.allocate(4).putInt(e.index).array()) }
        if (e.confirmed) runOnUiThread { addToRound(e.index, e.after) }
        hitExecutor.execute {
            var who = com.nock.data.Prefs(this).active // whoever is shooting now (or the shooter of the target it hit)
            // No watch to vouch for it and nowhere near a spot (off the 40 cm face): room clutter, not an arrow.
            // A remembered side: measure from its spots, mapped into this photo (re-found each shot, so a bump is fine).
            // Every known target in this photo (two blocks = both); the arrow is scored on the one it went into.
            val seenAll = runCatching { memory.findAll(e.afterBmp) }.getOrNull().orEmpty()
            val aim = seenAll.flatMap { it.spots }.takeIf { it.isNotEmpty() }
            val markedSeen = seenAll.filter { it.face.marked }
            // A marked target: the hit must land inside its outline, and it's measured in true inches on its face.
            val box = markedSeen.takeIf { it.isNotEmpty() }?.flatMap { it.outline }?.let { ps ->
                listOf(org.opencv.core.Point(ps.minOf { it.x }, ps.minOf { it.y }), org.opencv.core.Point(ps.maxOf { it.x }, ps.minOf { it.y }),
                    org.opencv.core.Point(ps.maxOf { it.x }, ps.maxOf { it.y }), org.opencv.core.Point(ps.minOf { it.x }, ps.maxOf { it.y }))
            }
            val ppiHint = markedSeen.firstOrNull()?.let { s -> kotlin.math.hypot(s.outline[1].x - s.outline[0].x, s.outline[1].y - s.outline[0].y) / s.face.widthIn }
            var inBull: Boolean? = null
            var face: TargetMemory.Face? = seenAll.firstOrNull()?.face ?: side
            val hit = runCatching { hitFinder.find(e.before, e.afterBmp, aim, box, ppiHint) }.getOrNull()?.let { h ->
                val entry = org.opencv.core.Point(h.entryX.toDouble(), h.entryY.toDouble())
                val on = markedSeen.firstOrNull { TargetMemory.inside(entry, it.outline) }
                    ?: markedSeen.minByOrNull { s -> kotlin.math.hypot(s.outline.sumOf { it.x } / 4 - entry.x, s.outline.sumOf { it.y } / 4 - entry.y) }
                if (on != null) face = on.face
                val m = on?.let { runCatching { memory.measure(it.face, e.afterBmp, entry) }.getOrNull() }
                if (m == null) h else {
                    inBull = m.inBull
                    h.copy(spotX = m.bullPx.x.toFloat(), spotY = m.bullPx.y.toFloat(), rightIn = m.rightIn.toFloat(), lowIn = m.lowIn.toFloat(), ppi = m.ppi.toFloat())
                }
            }?.takeIf { h ->
                (e.confirmed || box != null || h.distIn <= com.nock.ui.Target.FACE_IN).also { ok ->
                    if (!ok) android.util.Log.d("NockShots", "change #${e.index}: %.1f in from a spot, no watch: not an arrow".format(h.distIn))
                }
            }
            // The watch felt the shot but the finder lost the arrow: save it as "not found" so you can place it in
            // review (marker starts between the spots). It counts once placed.
            if (hit == null && e.confirmed) runCatching { hitFinder.face(e.afterBmp) }.getOrNull()?.let { (spots, ppi) ->
                val cx = spots.map { it.x }.average(); val cy = spots.map { it.y }.average()
                val sp = spots.minBy { kotlin.math.hypot(it.x - cx, it.y - cy) }
                val arr = org.json.JSONArray(spots.map { org.json.JSONArray(listOf(it.x, it.y)) })
                java.io.File(e.after.path.replace("_after.jpg", "_hit.json")).writeText(
                    org.json.JSONObject().put("found", false).put("entry", org.json.JSONArray(listOf(cx, cy)))
                        .put("spot", org.json.JSONArray(listOf(sp.x, sp.y))).put("spots", arr).put("ppi", ppi)
                        .put("right_in", (cx - sp.x) / ppi).put("low_in", (cy - sp.y) / ppi).toString()
                )
            }
            e.before.recycle(); e.afterBmp.recycle()
            // the watch's motion for this arrow (sent at release, ~1 s before the arrow is seen)
            val motion = pendingMotion?.takeIf { SystemClock.elapsedRealtime() - it.first < 8000 }?.second
            pendingMotion = null
            hit?.let { h ->
                val jf = java.io.File(e.after.path.replace("_after.jpg", "_hit.json"))
                jf.writeText(
                    "{\"entry\":[${h.entryX},${h.entryY}],\"spot\":[${h.spotX},${h.spotY}],\"right_in\":${h.rightIn},\"low_in\":${h.lowIn},\"ppi\":${h.ppi}}"
                )
            }
            if (hit != null && !e.confirmed) {
                val jf = java.io.File(e.after.path.replace("_after.jpg", "_hit.json"))
                jf.writeText(org.json.JSONObject(jf.readText()).put("no_watch", true).toString())
            }
            if (hit != null && motion != null) {
                val jf = java.io.File(e.after.path.replace("_after.jpg", "_hit.json"))
                jf.writeText(org.json.JSONObject(jf.readText()).put("motion", motion).toString())
            }
            java.io.File(e.after.path.replace("_after.jpg", "_hit.json")).takeIf { it.exists() }?.let { jf ->
                face?.let { f -> shooterFor[f.id]?.let { who = it } }
                val j = org.json.JSONObject(jf.readText()).put("who", who).put("round", roundId).apply { if (roundYd > 0) put("yd", roundYd) }
                face?.let { f -> if (aim != null) j.put("side", f.id) }
                inBull?.let { j.put("in_bull", it) }
                jf.writeText(j.toString())
                runOnUiThread { previewShot(e.after) } // the last arrow, in the corner
                sendShotToWatch(e.after)
            }
            val payload = ByteBuffer.allocate(13).putInt(e.index).put((if (hit != null) 1 else 0).toByte())
                .putFloat(hit?.rightIn ?: 0f).putFloat(hit?.lowIn ?: 0f).array()
            watch?.let { messageClient.sendMessage(it.id, PATH_HIT, payload) }
            runOnUiThread {
                // phone-only: it only becomes an arrow once the finder saw a new fletching
                if (!e.confirmed && hit != null) addToRound(e.index, e.after)
                round.lastOrNull { it.index == e.index && it.photo == e.after }?.let { r ->
                    if (hit != null) { r.right = hit.rightIn; r.low = hit.lowIn; r.state = 1 } else r.state = 2
                    if (hit != null) tally(face, com.nock.ui.Target.score(hit.distIn))
                    renderRound()
                    maybeEndRound()
                }
                if (e.confirmed || hit != null) showArrowBadge(e)
            }
        }
    }

    /**
     * Dev check: photo pairs pushed into files/selftest/ (NAME_before.jpg + NAME_after.jpg) are scored
     * on launch and logged under "NockSelfTest", to compare with tools/hits.py on the laptop.
     */
    private fun selfTest() {
        val dir = java.io.File(filesDir, "selftest")
        // Side memory check: side_*.jpg in files/selftest -> remember the first, recognize the rest (then forget it).
        dir.listFiles { f -> f.name.startsWith("side_") }?.sortedBy { it.name }?.takeIf { it.size >= 2 }?.let { sides ->
            hitExecutor.execute {
                val test = TargetMemory(java.io.File(dir, "faces_test"))
                val first = android.graphics.BitmapFactory.decodeFile(sides[0].path)
                val face = test.save(first, HitFinder().face(first)?.first ?: emptyList())
                android.util.Log.i("NockSelfTest", "remembered ${sides[0].name}: ${face.spots.size} spots")
                for (f in sides.drop(1)) {
                    val t0 = SystemClock.elapsedRealtime()
                    val seen = test.recognize(android.graphics.BitmapFactory.decodeFile(f.path))
                    android.util.Log.i("NockSelfTest", "${f.name}: " + (seen?.let { "${it.first.name}, spots " + it.second.joinToString { p -> "%.0f,%.0f".format(p.x, p.y) } } ?: "not a known side") + " in ${SystemClock.elapsedRealtime() - t0} ms")
                }
                test.delete(face)
            }
        }
        val afters = dir.listFiles { f -> f.name.endsWith("_after.jpg") }?.sortedBy { it.name } ?: return
        hitExecutor.execute {
            val finder = HitFinder()
            for (a in afters) {
                val b = java.io.File(a.path.replace("_after.jpg", "_before.jpg"))
                val t0 = SystemClock.elapsedRealtime()
                val hit = finder.find(android.graphics.BitmapFactory.decodeFile(b.path), android.graphics.BitmapFactory.decodeFile(a.path))
                val ms = SystemClock.elapsedRealtime() - t0
                android.util.Log.i("NockSelfTest", "${a.name}: " + (hit?.let { "%.1f in (%.1f right, %.1f low) entry %.0f,%.0f in %d ms".format(it.distIn, it.rightIn, it.lowIn, it.entryX, it.entryY, ms) } ?: "no hit ($ms ms)"))
            }
        }
    }

    /** Dev only (debug builds, `am start ... --ez demo true`): a fake round on screen, nothing saved. */
    private fun demo() {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent.getBooleanExtra("ask", false)) previewView.postDelayed({ ask("New target (5 spots). Remember it?", "Remember") {} }, 1500)
        if (!intent.getBooleanExtra("demo", false)) return
        val pts = listOf(0.2f to -0.1f, 0.9f to 0.6f, -1.6f to 0.9f, 0.1f to 0.05f, -0.5f to 1.2f)
        pts.forEachIndexed { i, (r, l) ->
            previewView.postDelayed({
                addToRound(i + 1, java.io.File(filesDir, "demo_$i"))
                round.last().apply { right = r; low = l; state = 1 }
                renderRound(); maybeEndRound()
            }, 1500L + i * 400L)
        }
    }

    private fun showArrowBadge(e: ShotDetector.Event) {
        zoomBadge.text = "Arrow ${e.index}"
        zoomBadge.fadeTo(true)
        zoomBadge.postDelayed({ zoomBadge.fadeTo(false) }, 1500)
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_ZOOM -> runOnUiThread { zoomBy(ByteBuffer.wrap(event.data).float) }
            PATH_ZOOM_TO -> runOnUiThread { setZoom(ByteBuffer.wrap(event.data).float) }
            PATH_RELEASE -> shots.onRelease()
            PATH_SCORING -> runOnUiThread { setScoring(event.data.firstOrNull()?.toInt() == 1) } // the watch's button
            PATH_XRAY -> runOnUiThread { xray = event.data.firstOrNull()?.toInt() == 1; renderXray() } // the watch's X-ray button
            PATH_ALIVE -> Unit // the watch heartbeat; detection no longer depends on it
            PATH_VIEWING -> viewingAt = SystemClock.elapsedRealtime()
            PATH_MOTION -> pendingMotion = SystemClock.elapsedRealtime() to motionJson(event.data)
            PATH_FIX -> hitExecutor.execute { fixFromWatch(event.data) }
            PATH_QUALITY -> runOnUiThread { setQuality(event.data[0].toInt().coerceIn(0, QUALITY_WIDTHS.size - 1)) }
        }
        if (event.path != PATH_ACK) return
        val n = ByteBuffer.wrap(event.data).int
        val sent = sentAt.remove(n) ?: return
        lastAckAt = SystemClock.elapsedRealtime()
        stream.onAck(lastAckAt - sent)
    }

    private val findWatch: Runnable = Runnable {
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            // Prefer a node on a direct Bluetooth/Wi-Fi link over one reached through the cloud.
            watch = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
        }
        previewView.postDelayed(findWatch, NODE_POLL_MS)
    }

    companion object {
        const val EXTRA_ADD_VIEW = "add_view" // from a target's page: take photos that teach it
        const val EXTRA_REVIEW = "review"
        const val EXTRA_CHECK = "check"   // only arrows you haven't fixed or confirmed
        const val EXTRA_PHOTO = "photo"   // start on this shot's after photo
        const val PATH_FRAME = "/nock/frame"
        const val PATH_ACK = "/nock/ack"
        const val PATH_ZOOM = "/nock/zoom"       // watch -> phone: multiply zoom by this float
        const val PATH_ZOOM_TO = "/nock/zoom-to" // watch -> phone: set zoom to this float
        const val PATH_QUALITY = "/nock/quality" // watch -> phone: quality index byte
        const val PATH_RELEASE = "/nock/release" // watch -> phone: a shot was released
        const val PATH_ALIVE = "/nock/alive"     // watch -> phone: session running (every 5 s)
        const val PATH_MOTION = "/nock/motion"   // watch -> phone: hold + aim trace for the shot
        const val PATH_ARROW = "/nock/arrow"     // phone -> watch: the new arrow was seen
        const val PHONE_REPOINTED_DPS = 25.0 // a real move of the phone (a touch or nudge stays well under this)
        const val BTN = 34 // top controls: round buttons and pills, dp
        val DISTANCES_YD = listOf(5, 6, 10, 15, 18, 20, 25, 30, 40, 50) // the Start card's distance choices
        const val GYRO = -2 // marks moved by the phone's turn (the picture couldn't be followed)
        const val STATIC = -1 // marks kept in place (tracking lost but the phone is still): not moved by any tracking
        const val REFRESH_MS = 2000L // targets on screen are re-aligned this often (cheap: ~0.2 s, no search)
        const val CAL_PER_WAY = 6 // guided gyro calibration: turns needed each way (24 in all; the fit wants 20+)
        const val EMPTY_RETRY_MS = 2000L // nothing on screen: the first look again after this (doubling, up to FULL_EVERY_MS)
        const val FULL_EVERY_MS = 10_000L // while re-aligning, a full search for new targets this often
        const val HOLD_MS = 8000L // a target blocked from view, phone still: kept this long after it was last confirmed
        const val PHONE_MOVED_DPS = 3.0 // phone gyro speed that means it's being handled (tripod: well under 1)
        const val PATH_XRAY = "/nock/xray"
        const val PATH_VIEWING = "/nock/viewing" // watch -> phone: its live view just opened (stream again) // watch -> phone: X-ray marks on (1) / off (0)
        const val PATH_SCORING = "/nock/scoring" // watch -> phone: start (1) / pause (0)
        const val PATH_END = "/nock/end"         // phone -> watch: a round is done (round no, points, arrows)
        const val PATH_HIT = "/nock/hit"         // phone -> watch: where it hit (index, found, right in, low in)
        const val PATH_SHOT = "/nock/shot"       // phone -> watch: the arrow's close-up (see sendShotToWatch)
        const val PATH_FIX = "/nock/fix"         // watch -> phone: you moved the arrow on the watch (stamp, x, y in the close-up)
        private const val ACK_TIMEOUT_MS = 1500L
        // Below this mean brightness (0-255) the frame is treated as black. Measured on the S23U 2026-10-13: lens face
        // down on a desk = 0.0 ("camera luma" log). A dim room has NOT been measured yet, so 8 is kept well under it.
        private const val DARK_LUMA = 8f
        private const val STILL_MS = 400L // still this long after moving/zooming: snap the marks onto the targets
        private const val WATCH_IDLE_MS = 3000L // no answer this long = the watch's live view is closed
        private const val MAX_IN_FLIGHT = 3
        private const val NODE_POLL_MS = 3000L
        private const val JPEG_QUALITY = 70
        private const val SLOW_MS = 400L // round trip that means the link is struggling (healthy: ~130 ms)
        private const val KEY_QUALITY = "quality"
        private val PRESETS = floatArrayOf(10f, 5f, 3f, 1f, 0.6f)
        private const val LOCK_AFTER_MS = 2000L
        private val QUALITY_NAMES = listOf("Fast", "Balanced", "Sharp")
        private val QUALITY_WIDTHS = intArrayOf(240, 300, 400)
    }
}

/** Rolling numbers over the last few seconds, so the readout tracks you as you walk back. */
private class StreamStats {
    class Live(val fps: Float, val delayMs: Long, val dropping: Boolean)

    private val acks = ArrayDeque<Pair<Long, Long>>() // (at, round trip ms)
    private val drops = ArrayDeque<Long>()

    @Synchronized fun onDrop() { drops.addLast(SystemClock.elapsedRealtime()) }
    @Synchronized fun onAck(rttMs: Long) { acks.addLast(SystemClock.elapsedRealtime() to rttMs) }
    @Synchronized fun reset() { acks.clear(); drops.clear() }

    /** Null when nothing has reached the watch lately. */
    @Synchronized fun live(): Live? {
        val now = SystemClock.elapsedRealtime()
        while (acks.isNotEmpty() && now - acks.first().first > WINDOW_MS) acks.removeFirst()
        while (drops.isNotEmpty() && now - drops.first() > WINDOW_MS) drops.removeFirst()
        if (acks.isEmpty()) return null
        return Live(
            fps = acks.size * 1000f / WINDOW_MS,
            delayMs = acks.map { it.second }.average().toLong(),
            dropping = drops.size >= 2,
        )
    }

    private companion object { const val WINDOW_MS = 3000L }
}
