package com.nock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/**
 * Shot review: a pop-up over the dimmed camera, newest shot first. A close-up of the spot with
 * the green + (spot centre), the app's guess (yellow ring) and your fix (white ring).
 * Drag to place your fix; it saves itself the moment you let go. "Use app's guess" undoes a fix.
 * Every fix is an answer-key entry the finder can be scored and tuned against.
 * "Not an arrow" (a ghost, or a photo that can't show the real hit) leaves the shot out of the group,
 * the stats and the answer key ("ignored": true); tap it again to count it.
 */
@SuppressLint("ViewConstructor")
class ShotReview(
    context: Context,
    private val dir: File,
    private val onIgnored: (after: File, ignored: Boolean) -> Unit = { _, _ -> },
    private val onSaved: (index: Int, rightIn: Float, lowIn: Float) -> Unit,
) : FrameLayout(context) {

    /** Called when the review is closed (Done or Back). */
    var onClose: () -> Unit = {}
    fun close() { visibility = GONE; onClose() }

    private class Shot(val after: File, val json: File, val index: Int)

    private var shots: List<Shot> = emptyList()
    private var pos = 0
    private val title = context.label(20f, bold = true).apply { gravity = Gravity.CENTER }
    private val distance = context.label(34f, bold = true).apply { gravity = Gravity.CENTER }
    private val direction = context.label(14f, color = Brand.Dim).apply { gravity = Gravity.CENTER }
    private val source = context.label(12f, bold = true).apply { gravity = Gravity.CENTER }
    private val page = context.label(13f, bold = true, color = Brand.Dim).apply { gravity = Gravity.CENTER }
    private val revert: TextView
    private val confirm: TextView
    private val allShots: TextView
    private val ignore: TextView
    private var roundLabel: String? = null // set = showing one round, numbered newest #N .. #1
    private val metric get() = com.nock.data.Prefs(context).metric
    private fun len(inches: Float) = com.nock.data.Prefs.len(inches, metric)
    private val undo: TextView
    private var undoTo: Pair<Float, Float>? = null // where the ring was before the last drag (null = app's guess)
    private val prev: View
    private val next: View
    private val photo = Spot(context)
    private val emptyText = context.label(15f).apply { text = "No shots yet"; gravity = Gravity.CENTER }

    init {
        setBackgroundColor(0xFF202321.toInt()) // solid: the review gets your full attention
        isClickable = true
        val dp = { v: Int -> context.dpi(v) }

        fun round(text: String, onTap: () -> Unit) = context.label(22f, bold = true).apply {
            this.text = text; gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x26F5F5F4) }
            setOnClickListener { onTap() }
        }
        val close = context.label(14f, bold = true, color = Brand.Carbon).apply {
            text = "Done"; gravity = Gravity.CENTER
            background = pillBackground(context, Brand.FernBright)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { close() }
        }
        fun chevron(right: Boolean, onTap: () -> Unit) = ChevronIcon(context, right).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x26F5F5F4) }
            setOnClickListener { onTap() }
        }
        prev = chevron(false) { show(pos + 1) } // older
        next = chevron(true) { show(pos - 1) } // newer
        revert = context.label(13f, bold = true, color = Brand.Dim).apply {
            text = "Use app's guess"; gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { clearFix() }
        }

        confirm = context.label(13f, bold = true, color = Brand.Carbon).apply {
            text = "Looks right"; gravity = Gravity.CENTER
            background = pillBackground(context, Brand.FernBright)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            setOnClickListener { toggleConfirm() }
        }
        ignore = context.label(13f, bold = true, color = Brand.Dim).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { toggleIgnore() }
        }
        allShots = context.label(13f, bold = true).apply {
            text = "All shots"; gravity = Gravity.CENTER
            background = pillBackground(context, 0x26F5F5F4)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener { open() }
        }

        undo = context.label(13f, bold = true).apply {
            text = "Undo"; gravity = Gravity.CENTER
            background = pillBackground(context, 0x33F5F5F4)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            visibility = GONE
            setOnClickListener { undoLast() }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(title)
            addView(distance, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            addView(direction)
            addView(source, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prev, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(page, LinearLayout.LayoutParams(dp(90), -2))
            addView(next, LinearLayout.LayoutParams(dp(52), dp(52)))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(header)
            addView(photo, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, dp(18), 0, dp(10)) })
            addView(FrameLayout(context).apply {
                addView(revert, LayoutParams(-2, -2, Gravity.CENTER))
                addView(undo, LayoutParams(-2, -2, Gravity.CENTER))
                addView(confirm, LayoutParams(-2, -2, Gravity.CENTER))
            })
            addView(nav, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            addView(ignore, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }
        addView(column, LayoutParams(-1, -1).apply { setMargins(dp(16), dp(56), dp(16), dp(28)) })
        addView(close, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(16), dp(16), 0) })
        addView(allShots, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(18), 0, 0) })
        addView(emptyText, LayoutParams(-2, -2, Gravity.CENTER))
        visibility = GONE
    }

    /**
     * Open the review. [only] = just these photos (a round), labelled [label] and numbered newest #N .. #1;
     * [toCheck] = only arrows you haven't fixed or confirmed; [startAt] = begin on this photo.
     */
    fun open(only: List<File>? = null, label: String? = null, toCheck: Boolean = false, startAt: File? = null) {
        roundLabel = if (only != null) label else null
        val all = (only ?: (dir.listFiles { f -> f.name.endsWith("_after.jpg") && f.name.count { it == '_' } >= 3 } ?: emptyArray()).toList())
            .map { Shot(it, File(it.path.replace("_after.jpg", "_hit.json")), it.name.split('_')[1].toIntOrNull() ?: 0) }
            .filter { it.json.exists() && JSONObject(it.json.readText()).optString("who", com.nock.data.Prefs.FIRST) == com.nock.data.Prefs(context).active }
            .sortedByDescending { it.after.name.split('_')[2].toLongOrNull() ?: 0L } // newest first
        shots = if (toCheck) all.filter { val j = JSONObject(it.json.readText()); !j.has("fixed") && !j.optBoolean("confirmed") && !j.optBoolean("ignored") } else all
        allShots.visibility = if (only != null || toCheck) VISIBLE else GONE
        emptyText.text = if (toCheck) "All checked. Nice." else "No shots yet"
        emptyText.visibility = if (shots.isEmpty()) VISIBLE else GONE
        visibility = VISIBLE
        show(startAt?.let { f -> shots.indexOfFirst { it.after == f }.coerceAtLeast(0) } ?: 0)
    }

    /** "Looks right": the app's guess was correct. Saved as a confirmed answer (it teaches the finder too). */
    private fun toggleConfirm() {
        val s = shots.getOrNull(pos) ?: return
        val j = JSONObject(s.json.readText())
        if (j.optBoolean("confirmed")) j.remove("confirmed") else j.put("confirmed", true)
        s.json.writeText(j.toString())
        updateReading()
    }

    /** "Not an arrow": out of the group, the stats and the answer key. Tap again to count it. */
    private fun toggleIgnore() {
        val s = shots.getOrNull(pos) ?: return
        val j = JSONObject(s.json.readText())
        val now = !j.optBoolean("ignored")
        if (now) j.put("ignored", true) else j.remove("ignored")
        s.json.writeText(j.toString())
        onIgnored(s.after, now)
        updateReading()
    }

    private fun show(i: Int) {
        if (shots.isEmpty()) return
        undo.visibility = GONE
        pos = i.coerceIn(0, shots.size - 1)
        val s = shots[pos]
        val j = JSONObject(s.json.readText())
        val entry = j.getJSONArray("entry"); val spot = j.getJSONArray("spot")
        val fix = j.optJSONArray("fixed")
        photo.load(s.after, spot.getDouble(0).toFloat(), spot.getDouble(1).toFloat(),
            entry.getDouble(0).toFloat(), entry.getDouble(1).toFloat(),
            fix?.getDouble(0)?.toFloat(), fix?.getDouble(1)?.toFloat(), j.optDouble("ppi", 62.0).toFloat(),
            halfIn = if (!j.optBoolean("found", true)) 6f // lost arrow: show the whole face to find it
                else run { // the spot and the arrow both in the picture (an arrow over 2.4" out was drawn off it)
                    val px = fix ?: entry; val ppi = j.optDouble("ppi", 62.0)
                    maxOf(2.4, kotlin.math.hypot(px.getDouble(0) - spot.getDouble(0), px.getDouble(1) - spot.getDouble(1)) / ppi + 0.8).toFloat()
                }, onGuess = !j.optBoolean("found", true))
        title.text = roundLabel?.let { "$it  ·  Arrow ${shots.size - pos}" } ?: "Arrow ${s.index}"
        page.text = "${pos + 1} / ${shots.size}"
        prev.alpha = if (pos < shots.size - 1) 1f else 0.3f
        next.alpha = if (pos > 0) 1f else 0.3f
        updateReading()
    }

    private fun updateReading() {
        val (r, l) = photo.offsetIn()
        distance.text = len(hypot(r, l))
        direction.text = "%s %s  ·  %s %s".format(len(kotlin.math.abs(r)), if (r >= 0) "right" else "left", len(kotlin.math.abs(l)), if (l >= 0) "low" else "high")
        val fixed = photo.fixed
        val j = shots.getOrNull(pos)?.let { JSONObject(it.json.readText()) }
        val confirmed = j?.optBoolean("confirmed") == true
        val ignored = j?.optBoolean("ignored") == true
        photo.ignored = ignored
        val lost = j?.optBoolean("found", true) == false
        source.text = when { ignored -> "Not counted  ·  won't teach the app"; fixed -> "Your fix"; confirmed -> "You confirmed it"
            lost -> "Arrow not found  ·  drag the ring to where it hit"; else -> "App's guess  ·  drag to fix" }
        val tone = when { ignored -> Brand.Dim; fixed -> Brand.Smoke; confirmed -> Brand.FernBright; lost -> Brand.Amber; else -> YELLOW }
        source.setTextColor(tone)
        source.leadingDot(if (ignored || lost) null else tone) // the dot = the ring's colour on the photo
        distance.alpha = if (ignored || (lost && !fixed)) 0.35f else 1f; direction.alpha = distance.alpha
        ignore.text = if (ignored) "Count it again" else "Not an arrow"
        ignore.setTextColor(if (ignored) Brand.Smoke else Brand.Dim)
        revert.visibility = if (!ignored && fixed && undo.visibility != VISIBLE) VISIBLE else INVISIBLE
        confirm.visibility = if (!ignored && !fixed && !lost && undo.visibility != VISIBLE) VISIBLE else INVISIBLE
        confirm.text = if (confirmed) "Confirmed" else "Looks right"
        confirm.alpha = if (confirmed) 0.55f else 1f
    }

    /** An accidental drag: put the ring back where it was before (saved fix or the app's guess). */
    private fun undoLast() {
        val s = shots.getOrNull(pos) ?: return
        val back = undoTo
        undo.visibility = GONE
        if (back == null) { clearFix(); return }
        val j = JSONObject(s.json.readText())
        j.put("fixed", JSONArray(listOf(back.first.toDouble(), back.second.toDouble())))
        val r = (back.first - j.getJSONArray("spot").getDouble(0)) / j.optDouble("ppi", 62.0)
        val l = (back.second - j.getJSONArray("spot").getDouble(1)) / j.optDouble("ppi", 62.0)
        j.put("fixed_right_in", r); j.put("fixed_low_in", l)
        s.json.writeText(j.toString())
        onSaved(s.index, r.toFloat(), l.toFloat())
        show(pos)
    }

    private val hideUndo = Runnable { undo.visibility = GONE; updateReading() }

    /** Called when a drag ends: the fix saves itself. */
    private fun saveFix() {
        val s = shots.getOrNull(pos) ?: return
        val j = JSONObject(s.json.readText())
        // A hand-placed lost arrow is measured from whichever spot it landed nearest.
        j.optJSONArray("spots")?.let { sp ->
            val near = (0 until sp.length()).map { sp.getJSONArray(it) }
                .minBy { hypot(it.getDouble(0) - photo.markX, it.getDouble(1) - photo.markY) }
            j.put("spot", near); photo.moveSpot(near.getDouble(0).toFloat(), near.getDouble(1).toFloat())
        }
        val (r, l) = photo.offsetIn()
        j.put("fixed", JSONArray(listOf(photo.markX.toDouble(), photo.markY.toDouble())))
        j.put("fixed_right_in", r.toDouble()); j.put("fixed_low_in", l.toDouble())
        s.json.writeText(j.toString())
        onSaved(s.index, r, l)
        updateReading()
        undo.visibility = VISIBLE; revert.visibility = INVISIBLE
        undo.removeCallbacks(hideUndo); undo.postDelayed(hideUndo, 6000)
    }

    private fun clearFix() {
        val s = shots.getOrNull(pos) ?: return
        val j = JSONObject(s.json.readText())
        j.remove("fixed"); j.remove("fixed_right_in"); j.remove("fixed_low_in")
        s.json.writeText(j.toString())
        onSaved(s.index, j.getDouble("right_in").toFloat(), j.getDouble("low_in").toFloat())
        show(pos)
    }

    /** The close-up: spot-centred crop of the full-res photo with the markers; drag places your fix. */
    private inner class Spot(context: Context) : View(context) {
        private var crop: Bitmap? = null
        private val src = Rect()
        private var spotX = 0f; private var spotY = 0f; private var ppi = 62f
        private var guessX = 0f; private var guessY = 0f
        var markX = 0f; var markY = 0f   // the active point: your fix if there is one, else the app's guess
        var fixed = false
        var ignored = false
            set(v) { field = v; alpha = if (v) 0.35f else 1f }
        private val dst = RectF()
        private val clip = Path()
        private val neon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF39FF14.toInt(); strokeWidth = context.dp(2.5f); style = Paint.Style.STROKE }
        private val guessRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = YELLOW; strokeWidth = context.dp(3f); style = Paint.Style.STROKE }
        private val fixRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Smoke; strokeWidth = context.dp(1.75f); style = Paint.Style.STROKE; setShadowLayer(context.dp(3f), 0f, 0f, Brand.Carbon) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val img = Paint(Paint.FILTER_BITMAP_FLAG)
        private var lastX = 0f; private var lastY = 0f; private var moved = false
        private val loupe by lazy {
            android.widget.Magnifier.Builder(this)
                .setInitialZoom(3f)
                .setSize(context.dpi(180), context.dpi(132))
                .setCornerRadius(context.dp(18f))
                .setDefaultSourceToMagnifierOffset(0, -context.dpi(140))
                .build()
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // for the ring's soft shadow

        fun moveSpot(sx: Float, sy: Float) { spotX = sx; spotY = sy; invalidate() }

        fun load(after: File, sx: Float, sy: Float, gx: Float, gy: Float, fx: Float?, fy: Float?, ppi: Float, halfIn: Float = 2.4f, onGuess: Boolean = false) {
            spotX = sx; spotY = sy; guessX = gx; guessY = gy; this.ppi = ppi
            fixed = fx != null && fy != null
            markX = fx ?: gx; markY = fy ?: gy
            val half = (halfIn * ppi).toInt() // ±halfIn around the starting point
            val cx = if (onGuess) gx else sx; val cy = if (onGuess) gy else sy
            val dec = BitmapRegionDecoder.newInstance(after.path)
            src.set((cx - half).toInt().coerceAtLeast(0), (cy - half).toInt().coerceAtLeast(0),
                (cx + half).toInt().coerceAtMost(dec.width), (cy + half).toInt().coerceAtMost(dec.height))
            crop?.recycle()
            crop = dec.decodeRegion(src, BitmapFactory.Options())
            dec.recycle()
            invalidate()
        }

        fun offsetIn() = (markX - spotX) / ppi to (markY - spotY) / ppi

        private fun scale(): Float { val c = crop ?: return 1f; return minOf(width / c.width.toFloat(), height / c.height.toFloat()) }
        private fun toView(x: Float, y: Float): Pair<Float, Float> {
            val k = scale(); val c = crop!!
            val ox = (width - c.width * k) / 2; val oy = (height - c.height * k) / 2
            return ox + (x - src.left) * k to oy + (y - src.top) * k
        }

        override fun onDraw(canvas: Canvas) {
            val c = crop ?: return
            val k = scale()
            val ox = (width - c.width * k) / 2; val oy = (height - c.height * k) / 2
            dst.set(ox, oy, ox + c.width * k, oy + c.height * k)
            clip.reset(); clip.addRoundRect(dst, context.dp(20f), context.dp(20f), Path.Direction.CW)
            canvas.save(); canvas.clipPath(clip); canvas.drawBitmap(c, null, dst, img); canvas.restore()

            val (gx, gy) = toView(spotX, spotY); val arm = context.dp(14f)
            canvas.drawLine(gx - arm, gy, gx + arm, gy, neon); canvas.drawLine(gx, gy - arm, gx, gy + arm, neon)
            val r = context.dp(13f)
            val (ax, ay) = toView(guessX, guessY)
            if (fixed) {
                // the app's guess stays as a faint reference
                guessRing.alpha = 110; canvas.drawCircle(ax, ay, r * 0.7f, guessRing)
                val (fx, fy) = toView(markX, markY)
                canvas.drawCircle(fx, fy, r, fixRing)
                dot.color = Brand.Smoke; canvas.drawCircle(fx, fy, context.dp(1.6f), dot)
            } else {
                guessRing.alpha = 255; canvas.drawCircle(ax, ay, r, guessRing)
                dot.color = YELLOW; canvas.drawCircle(ax, ay, context.dp(1.6f), dot)
            }
        }

        /** Relative drag (the ring follows the finger's movement, so the finger never hides it). */
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (ignored) return false // a shot that doesn't count can't be fixed
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; moved = false
                    undoTo = if (fixed) markX to markY else null
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val (x, y) = toView(markX, markY); loupe.show(x, y)
                }
                MotionEvent.ACTION_MOVE -> {
                    val k = scale()
                    markX += (e.x - lastX) / k; markY += (e.y - lastY) / k
                    lastX = e.x; lastY = e.y
                    if (!moved) { moved = true; fixed = true }
                    invalidate(); updateReading()
                    val (x, y) = toView(markX, markY); loupe.show(x, y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    loupe.dismiss()
                    if (moved) saveFix() // auto-save the moment you let go
                }
            }
            return true
        }
    }

    private companion object { const val YELLOW = 0xFFFFE600.toInt() }
}
