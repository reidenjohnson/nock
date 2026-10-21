package com.nock

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.nio.ByteBuffer

/** Watch side: the phone's live view of the target, edge to edge. Acks each frame so the phone can pace itself. */
class MainActivity : Activity(), MessageClient.OnMessageReceivedListener {

    private lateinit var image: ImageView
    private lateinit var scrim: View
    private lateinit var pill: LinearLayout
    private lateinit var dotView: View
    private lateinit var pillText: TextView
    private lateinit var center: TextView
    private lateinit var zoomBadge: TextView
    private lateinit var sheet: FrameLayout
    private lateinit var qualityRows: List<TextView>
    private lateinit var clock: TextView
    private lateinit var slider: ZoomSlider
    private lateinit var presets: LinearLayout
    private var sliderHeld = false
    private var pendingZoomTo = 0f // slider value not yet sent to the phone (0 = nothing pending)
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    // ---- Arrow list: lives in Session (filled by ShotService even with the screen off) ----
    private val rows get() = Session.rows
    private val prefs by lazy { getSharedPreferences("nock", MODE_PRIVATE) }
    private var buzzLevel = 2 // 0 light, 1 medium, 2 strong
    private lateinit var counter: TextView
    private lateinit var mini: android.widget.ScrollView
    private lateinit var miniBody: LinearLayout
    private val listOpen get() = ::listPanel.isInitialized && listPanel.visibility == View.VISIBLE
    private lateinit var listPanel: LinearLayout
    private lateinit var listBody: LinearLayout
    private lateinit var toast: TextView
    private lateinit var playButton: FrameLayout
    private lateinit var xrayButton: FrameLayout
    private lateinit var xrayIcon: XrayIcon
    private var xrayOn = true

    /** X-ray marks on the live view, mirrored from the phone; tap sends the change there. */
    private fun renderXray() {
        xrayButton.visibility = if (lastFrameAt == 0L) View.GONE else View.VISIBLE
        xrayIcon.color = if (xrayOn) TEAL else 0x99F5F5F4.toInt()
        xrayButton.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GLASS) }
    }
    private lateinit var playIcon: PlayPauseIcon

    /** Start/Pause scoring, mirrored from the phone; tap sends the change there. */
    private fun renderPlay() {
        playButton.visibility = if (lastFrameAt == 0L) View.GONE else View.VISIBLE
        playIcon.playing = Session.scoring
        playIcon.color = if (Session.scoring) SMOKE else CARBON
        playButton.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (Session.scoring) GLASS else FERN_BRIGHT) }
    }
    private lateinit var listTitle: TextView
    private lateinit var scorePop: LinearLayout
    private lateinit var popRing: TextView
    private lateinit var popDetail: TextView
    private var poppedIndex = -1
    private var liveSince = 0L // when the stream last became healthy: the Live pill fades once it's steady

    /** The arrow just got scored: its ring number big, in the ring's colour, with how far and which way. */
    private fun popScore(r: Session.Row) {
        popRing.text = r.ring
        popRing.setTextColor(r.ringColor)
        popRing.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GLASS); setStroke(dpi(3), r.ringColor) }
        popDetail.text = r.hit ?: ""
        scorePop.alpha = 0f; scorePop.scaleX = 0.8f; scorePop.scaleY = 0.8f
        scorePop.visibility = View.VISIBLE
        scorePop.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        scorePop.removeCallbacks(hidePop)
        scorePop.postDelayed(hidePop, 2600)
    }
    /** Round done: its score in the pop, Fern ring (it's a total, not a ring). */
    private fun popEnd(e: Triple<Int, Int, Int>) {
        val (n, pts, arrows) = e
        scorePop.postDelayed({
            scorePop.removeCallbacks(hidePop)
            popRing.text = "$pts"
            popRing.setTextColor(SMOKE)
            popRing.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GLASS); setStroke(dpi(3), FERN_BRIGHT) }
            popDetail.text = "Round $n · of ${arrows * 10}"
            scorePop.alpha = 0f; scorePop.visibility = View.VISIBLE
            scorePop.animate().alpha(1f).setDuration(220).start()
            scorePop.postDelayed(hidePop, 4000)
        }, 2800) // after the last arrow's own score has had its moment
    }

    private val hidePop = Runnable { scorePop.animate().alpha(0f).setDuration(300).withEndAction { scorePop.visibility = View.GONE }.start() }

    private fun showToast(msg: String, dotColor: Int) {
        val sp = android.text.SpannableString("●  $msg")
        sp.setSpan(android.text.style.ForegroundColorSpan(dotColor), 0, 1, 0)
        toast.text = sp
        toast.visibility = View.VISIBLE
        toast.removeCallbacks(hideToast)
        toast.postDelayed(hideToast, 1800)
    }
    private val hideToast = Runnable { toast.visibility = View.GONE }

    private fun openList() {
        listPanel.visibility = View.VISIBLE
        // nothing else while the list is up: no zoom, no live pill, no slider
        slider.visibility = View.GONE; presets.visibility = View.GONE; zoomBadge.visibility = View.GONE
        pill.alpha = 0f
        renderList()
    }

    private fun closeList() {
        listPanel.visibility = View.GONE
        pill.alpha = 1f
        renderList()
    }

    /** Hold steadiness: green steady, amber so-so, red shaky (your shots so far: 1.9-2.8 deg/s). */
    private fun holdColor(h: Float) = when {
        h < 0f -> GLASS
        h < 3.5f -> FERN_BRIGHT
        h < 5.5f -> AMBER
        else -> BRICK
    }

    /** "#3 10 0.4\" ↘" with the ring number in the colour of the ring it landed in. */
    private fun ringText(prefix: String, r: Session.Row): CharSequence {
        val ring = r.ring ?: return prefix + (r.hit ?: "…")
        val s = android.text.SpannableStringBuilder(prefix)
        val start = s.length
        s.append(ring)
        s.setSpan(android.text.style.ForegroundColorSpan(r.ringColor), start, s.length, 0)
        s.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, s.length, 0)
        return s.append(" ").append((r.hit ?: "").replace("\" ", "\""))
    }

    private fun renderList() {
        val scored = rows.filter { it.ring != null }
        listTitle.text = if (scored.isEmpty()) "${rows.size} arrows" else "${scored.sumOf { it.points }} pts  ·  ${rows.size} arrows"
        counter.visibility = View.GONE // replaced by the always-visible side list
        mini.visibility = if (rows.isEmpty() || listOpen) View.GONE else View.VISIBLE
        miniBody.removeAllViews()
        for (r in rows.asReversed()) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(6), dpi(3), dpi(4), dpi(3))
                setOnClickListener { openList() }
            }
            line.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(holdColor(r.holdDps)) } },
                LinearLayout.LayoutParams(dpi(6), dpi(6)).apply { marginEnd = dpi(4) })
            line.addView(text(11f).apply {
                setTextColor(if (r.seen) SMOKE else BRICK)
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                text = if (!r.seen) "miss" else ringText("", r) // the side list is narrow: ring + distance + way, one line
                maxLines = 1
            })
            miniBody.addView(line)
        }
        listBody.removeAllViews()
        val fmt = if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        for (r in rows.asReversed()) { // newest on top
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(8), dpi(4), dpi(6), dpi(4))
            }
            line.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(holdColor(r.holdDps)) } },
                LinearLayout.LayoutParams(dpi(7), dpi(7)).apply { marginEnd = dpi(6) })
            line.addView(text(12f).apply {
                setTextColor(if (r.seen) SMOKE else BRICK)
                text = if (!r.seen) "missed" else ringText("#${r.index}  ", r)
            })
            line.addView(text(10f).apply {
                setTextColor(0x99F5F5F4.toInt())
                text = "  " + android.text.format.DateFormat.format(fmt, r.atMs)
            })
            listBody.addView(line)
        }
    }

    private fun buildList() {
        counter = text(15f).apply {
            setTextColor(SMOKE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(GLASS) }
            visibility = View.GONE
            setOnClickListener { openList() }
        }
        listBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(listBody) }
        // Clearing the list can't be undone: hold it (a tap just says so).
        val reset = text(12f).apply {
            text = "Hold to clear"
            setTextColor(0xB3F5F5F4.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(0x33F5F5F4) }
            setOnClickListener { showToast("Hold to clear the list", AMBER) }
            setOnLongClickListener { ShotService.buzz(this@MainActivity, ShotService.SEEN_BUZZ); Session.clear(); closeList(); true }
        }
        miniBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setOnClickListener { openList() } }
        mini = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(0x40202321) }
            addView(miniBody)
            visibility = View.GONE
        }
        listTitle = text(13f, display = true).apply { setTextColor(SMOKE); gravity = Gravity.CENTER }
        listPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18f); setColor(0x99202321.toInt()) } // lighter: the view shows through
            setPadding(dpi(4), dpi(8), dpi(4), dpi(8))
            addView(listTitle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(4) })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(reset, LinearLayout.LayoutParams(dpi(96), dpi(26)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dpi(4) })
            visibility = View.GONE
            setOnClickListener { closeList() } // tap empty space to tuck it away
        }
        popRing = text(26f, display = true).apply { gravity = Gravity.CENTER }
        popDetail = text(12f).apply { setTextColor(SMOKE); gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 0f, Color.BLACK) }
        scorePop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(popRing, LinearLayout.LayoutParams(dpi(58), dpi(58)))
            addView(popDetail, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(4) })
            visibility = View.GONE
        }
        xrayIcon = XrayIcon(this)
        xrayButton = FrameLayout(this).apply {
            addView(xrayIcon, FrameLayout.LayoutParams(dpi(16), dpi(16), Gravity.CENTER))
            visibility = View.GONE
            setOnClickListener { xrayOn = !xrayOn; renderXray(); send(PATH_XRAY, byteArrayOf(if (xrayOn) 1 else 0)) }
        }
        playIcon = PlayPauseIcon(this)
        playButton = FrameLayout(this).apply {
            addView(playIcon, FrameLayout.LayoutParams(dpi(18), dpi(18), Gravity.CENTER))
            visibility = View.GONE
            setOnClickListener {
                Session.scoring = !Session.scoring; renderPlay() // show it right away; the next frame confirms
                send(PATH_SCORING, byteArrayOf(if (Session.scoring) 1 else 0))
            }
        }
        toast = text(13f).apply {
            setTextColor(SMOKE)
            background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(GLASS) }
            setPadding(dpi(12), dpi(5), dpi(12), dpi(5))
            visibility = View.GONE
        }
    }

    private val frameTimes = ArrayDeque<Long>()
    private var lastFrameAt = 0L
    private var newestSeq = 0
    private var phoneNode: String? = null
    private var phoneZoom = 1f
    private var zoomRange = 1f..1f
    private var phoneQuality = 1
    private lateinit var root: FrameLayout
    private var pendingZoom = 1f // pinch factor not yet sent to the phone

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No keep-screen-on: the screen sleeps normally (it was 87% of the battery drain). Shot detection
        // and buzzes keep running in ShotService; raising your wrist brings this screen back.
        setTurnScreenOn(true) // opened from the phone while the watch screen was off
        ShotService.start(this)
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) // the "Nock is running" chip

        image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        scrim = View(this).apply { setBackgroundColor(0xB3202321.toInt()); visibility = View.GONE }

        dotView = View(this)
        pillText = text(13f).apply { setTextColor(SMOKE) }
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(GLASS) }
            setPadding(dpi(10), dpi(5), dpi(12), dpi(5))
            addView(dotView, LinearLayout.LayoutParams(dpi(7), dpi(7)).apply { marginEnd = dpi(6) })
            addView(pillText)
            visibility = View.GONE
        }
        center = text(17f).apply { setTextColor(SMOKE); gravity = Gravity.CENTER }
        zoomBadge = text(20f).apply {
            setTextColor(SMOKE)
            background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(GLASS) }
            setPadding(dpi(14), dpi(6), dpi(14), dpi(6))
            visibility = View.GONE
        }
        buildList()
        sheet = buildSettingsSheet()
        // Small clock up top, in the watch's own 12/24-hour setting.
        clock = text(13f).apply { setTextColor(SMOKE); setShadowLayer(6f, 0f, 0f, Color.BLACK) }
        // Zoom slider: hidden until you tap the screen.
        slider = ZoomSlider(this) { v ->
            phoneZoom = v
            if (pendingZoomTo == 0f) slider.postDelayed(flushZoomTo, 80)
            pendingZoomTo = v
        }.apply {
            visibility = View.GONE
            onTouchState = { held -> sliderHeld = held; if (!held) scheduleHideSlider() else removeCallbacks(hideSlider) }
            onTap = { if (presets.visibility == View.VISIBLE) presets.visibility = View.GONE else showPresets() }
        }
        presets = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        root = FrameLayout(this)
        setContentView(root.apply {
            setBackgroundColor(Color.BLACK)
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(8) })
            addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(30) })
            // Sized and inset so the handle stays inside the round screen at both ends of its travel.
            addView(slider, FrameLayout.LayoutParams(dpi(56), (resources.displayMetrics.heightPixels * 0.62f).toInt(), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dpi(22) })
            addView(presets, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dpi(80) })
            addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(zoomBadge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(24) })
            // Left side: arrow counter -> tap for this round's list. Sized to stay inside the round screen.
            addView(counter, FrameLayout.LayoutParams(dpi(34), dpi(34), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dpi(14) })
            val sw = resources.displayMetrics.widthPixels; val sh = resources.displayMetrics.heightPixels
            // Side list: scroll it right where it is; tap to open the full list. Inset to stay inside the circle.
            addView(mini, FrameLayout.LayoutParams((sw * 0.32f).toInt(), (sh * 0.40f).toInt(), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = (sw * 0.07f).toInt() })
            addView(listPanel, FrameLayout.LayoutParams((sw * 0.56f).toInt(), (sh * 0.62f).toInt(), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = (sw * 0.09f).toInt() })
            addView(toast, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(58) })
            addView(scorePop, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(58) })
            // Start/Pause: bottom centre, small, inside the round edge.
            // Start and X-ray side by side at the bottom, inside the round edge.
            addView(playButton, FrameLayout.LayoutParams(dpi(40), dpi(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(14); marginStart = dpi(48) })
            addView(xrayButton, FrameLayout.LayoutParams(dpi(40), dpi(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(14); marginEnd = dpi(48) })
            addView(sheet, FrameLayout.LayoutParams(-1, -1))
            addView(shotCard, FrameLayout.LayoutParams(-1, -1)) // the arrow you just shot, over everything
            // Tap: zoom slider pops up (and hides itself again). Hold: settings.
            setOnClickListener {
                if (listOpen) { closeList(); return@setOnClickListener } // tap outside the list = close it
                if (lastFrameAt == 0L || SystemClock.elapsedRealtime() - lastFrameAt > STALE_MS) { openOnPhone(); return@setOnClickListener }
                if (lastFrameAt != 0L) { slider.visibility = View.VISIBLE; slider.wake(); pill.alpha = 0f; scheduleHideSlider() }
            }
            setOnLongClickListener { if (!listOpen) openSheet(); true }
            // Bezel/crown events go to the focused view.
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        })
        render()
        demo()
    }

    /** Dev only (debug builds, `adb shell am start ... --ez demo true`): fake arrows to check the list and score pop. */
    private fun demo() {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0 || !intent.getBooleanExtra("demo", false)) return
        Session.clear()
        val now = System.currentTimeMillis()
        listOf(0.2f to -0.1f, 0.9f to 0.6f, -1.6f to 0.9f, 0.1f to 0.05f).forEachIndexed { i, (r, l) ->
            Session.add(Session.Row(i + 1, now - (4 - i) * 30_000L, 2f + i, seen = true))
            Session.hit(i + 1, true, r, l)
        }
        center.postDelayed({ Session.add(Session.Row(5, System.currentTimeMillis(), 3f, seen = true)) }, 800)
        center.postDelayed({ Session.hit(5, true, -0.5f, 1.2f) }, 2000)
        center.postDelayed({ Session.end(1, 46, 5) }, 2100)
    }

    /** Opens Nock on the phone. Uses the paired phone's node even before any frame has arrived. */
    private fun openOnPhone() {
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return@addOnSuccessListener
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                .setData(android.net.Uri.parse("nock://open"))
            // Fast path first; if no frames arrive shortly after, use the slower Play-services route.
            messageClient.sendMessage(node.id, OpenService.PATH_OPEN, ByteArray(0))
            center.postDelayed({
                if (lastFrameAt == 0L || SystemClock.elapsedRealtime() - lastFrameAt > STALE_MS)
                    androidx.wear.remote.interactions.RemoteActivityHelper(this).startRemoteActivity(intent, node.id)
            }, 3000) // backup only if the fast path didn't get the app streaming
            center.text = "Opening on phone…"
        }
    }

    // Opened the watch first? Open Nock on the phone too.
    private val autoOpenPhone = Runnable { if (lastFrameAt == 0L || SystemClock.elapsedRealtime() - lastFrameAt > STALE_MS) openOnPhone() }

    override fun onResume() {
        super.onResume()
        center.removeCallbacks(autoOpenPhone)
        center.post(autoOpenPhone) // right away: if Nock is already up on the phone, this is a no-op
        messageClient.addListener(this)
        sayViewing()
        Session.listener = { row ->
            runOnUiThread {
                if (row != null) {
                    if (row.seen) showToast("Arrow ${row.index}", FERN_BRIGHT) else showToast("No arrow seen", BRICK)
                } else rows.lastOrNull { it.ring != null }?.takeIf { it.index != poppedIndex }?.let { r ->
                    poppedIndex = r.index; toast.visibility = View.GONE; popScore(r) // scored: the toast gives way to the score
                }
                renderList()
            }
        }
        Session.endListener = { e -> runOnUiThread { popEnd(e) } }
        Session.shotListener = { s -> runOnUiThread { Session.shownStamp = s.stamp; shotCard.show(s) } }
        // raised your wrist after shooting: the arrow you haven't seen yet
        Session.lastShot?.takeIf { it.stamp != Session.shownStamp && System.currentTimeMillis() - Session.lastShotAt < 60_000 }?.let { Session.shownStamp = it.stamp; shotCard.show(it) }
        renderList()
        center.post(tick)
    }

    override fun onPause() {
        super.onPause()
        messageClient.removeListener(this)
        Session.listener = null
        Session.endListener = null
        center.removeCallbacks(tick)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_FRAME) return
        val data = event.data
        val header = ByteBuffer.wrap(data, 0, HEADER)
        val seq = header.int
        val zoom = header.float
        val qByte = header.get().toInt()
        val quality = qByte and 0x0F
        val scoringNow = qByte and 0x10 != 0
        val xrayNow = qByte and 0x20 != 0
        val minZoom = header.float
        val maxZoom = header.float
        val bitmap = BitmapFactory.decodeByteArray(data, HEADER, data.size - HEADER)
        runOnUiThread {
            phoneNode = event.sourceNodeId
            zoomRange = minZoom..maxZoom
            // While zooming, trust our own running value; otherwise follow the phone.
            val zooming = zoomBadge.visibility == View.VISIBLE || sliderHeld || pendingZoomTo != 0f
            if (!zooming) phoneZoom = zoom
            slider.setRange(minZoom, maxZoom)
            if (!zooming) slider.setValue(zoom)
            if (quality != phoneQuality) { phoneQuality = quality; markQuality() }
            if (scoringNow != Session.scoring || playButton.visibility != View.VISIBLE) { Session.scoring = scoringNow; renderPlay() }
            if (xrayNow != xrayOn || xrayButton.visibility != View.VISIBLE) { xrayOn = xrayNow; renderXray() }
            // Several frames travel at once; never let a late one replace a newer picture.
            // (A big jump backwards means the phone app restarted and its count began again.)
            if (bitmap != null && (seq > newestSeq || seq < newestSeq - 10)) { newestSeq = seq; image.setImageBitmap(bitmap) }
            val now = SystemClock.elapsedRealtime()
            if (lastFrameAt == 0L) pill.visibility = View.VISIBLE
            lastFrameAt = now
            frameTimes.addLast(now)
            render()
            // Ack after the frame is on screen, so the phone's round trip is what you actually see.
            messageClient.sendMessage(event.sourceNodeId, PATH_ACK, ByteBuffer.allocate(4).putInt(seq).array())
        }
    }

    /** Pinch the live view to zoom the phone's camera. */
    private val pinch by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                root.cancelLongPress() // two fingers down must never open settings
                zoom(detector.scaleFactor)
                return true
            }
        })
    }

    private val swipe by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (kotlin.math.abs(vx) > kotlin.math.abs(vy) * 1.5f && kotlin.math.abs(vx) > 600) { closeList(); return true }
                return false
            }
        })
    }

    // The arrow you just shot, full screen; a fix you make there goes back to the phone.
    private val shotCard by lazy {
        ShotCard(this, onFix = { s, x, y ->
            send(ShotService.PATH_FIX, ByteBuffer.allocate(16).putLong(s.stamp).putFloat(x).putFloat(y).array())
        }) { }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (shotCard.visibility == View.VISIBLE) return shotCard.dispatchTouchEvent(event) // the arrow card has the screen
        if (listOpen) { // only the list works now: scroll it, swipe it away, or tap outside it
            if (swipe.onTouchEvent(event)) return true
            return super.dispatchTouchEvent(event)
        }
        if (sheet.visibility != View.VISIBLE) pinch.onTouchEvent(event)
        if (pinch.isInProgress) return true
        return super.dispatchTouchEvent(event)
    }

    /** Also works with a rotating bezel or crown, on watches that have one. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL || !event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return super.onGenericMotionEvent(event)
        val clicks = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
        if (shotCard.editing) { if (clicks != 0f) shotCard.rotary(clicks); return true } // fixing an arrow: the bezel zooms it
        if (clicks != 0f) zoom(if (clicks > 0) ZOOM_STEP else 1f / ZOOM_STEP)
        return true
    }

    private fun zoom(factor: Float) {
        // Batch small pinch steps so the link isn't flooded; the phone gets the product every ~80 ms.
        if (pendingZoom == 1f) zoomBadge.postDelayed(flushZoom, 80)
        pendingZoom *= factor
        // Show where we're heading right away, clamped to what the phone's camera can do.
        phoneZoom = (phoneZoom * factor).coerceIn(zoomRange)
        zoomBadge.text = "%.1f×".format(phoneZoom)
        zoomBadge.visibility = View.VISIBLE
        zoomBadge.removeCallbacks(hideZoom)
        zoomBadge.postDelayed(hideZoom, 900)
    }

    private val flushZoom = Runnable {
        send(PATH_ZOOM, ByteBuffer.allocate(4).putFloat(pendingZoom).array())
        pendingZoom = 1f
    }

    private val hideZoom = Runnable { zoomBadge.visibility = View.GONE }

    private val flushZoomTo = Runnable {
        send(PATH_ZOOM_TO, ByteBuffer.allocate(4).putFloat(pendingZoomTo).array())
        pendingZoomTo = 0f
    }

    private val hideSlider = Runnable { if (!sliderHeld) { slider.visibility = View.GONE; presets.visibility = View.GONE; pill.alpha = 1f } }

    private fun showPresets() {
        presets.removeAllViews()
        for (z in PRESETS) {
            if (z < zoomRange.start - 0.01f || z > zoomRange.endInclusive + 0.01f) continue
            val on = kotlin.math.abs(z - phoneZoom) < 0.05f
            presets.addView(text(13f).apply {
                text = if (z < 1f) "%.1f×".format(z) else "%.0f×".format(z)
                gravity = Gravity.CENTER
                setTextColor(if (on) CARBON else SMOKE)
                background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(if (on) SMOKE else GLASS) }
                setOnClickListener {
                    send(PATH_ZOOM_TO, ByteBuffer.allocate(4).putFloat(z).array())
                    phoneZoom = z; slider.setValue(z)
                    presets.visibility = View.GONE
                    scheduleHideSlider()
                }
            }, LinearLayout.LayoutParams(dpi(46), dpi(28)).apply { bottomMargin = dpi(5) })
        }
        presets.visibility = View.VISIBLE
        scheduleHideSlider()
    }
    private fun scheduleHideSlider() { slider.removeCallbacks(hideSlider); slider.postDelayed(hideSlider, 3000) }

    // ---- Settings: a pop-up over the dimmed live view. ----

    private fun buildSettingsSheet(): FrameLayout {
        qualityRows = listOf("Fast", "Balanced", "Sharp").mapIndexed { i, name ->
            row(name) { send(PATH_QUALITY, byteArrayOf(i.toByte())); phoneQuality = i; markQuality(); closeSheet() }
        }
        val reset = row("Reset zoom") { send(PATH_ZOOM_TO, ByteBuffer.allocate(4).putFloat(1f).array()); closeSheet() }
        buzzLevel = prefs.getInt("buzz", 2)
        val buzzRow = row("Buzz: ${ShotService.BUZZ_NAMES[buzzLevel]}") {}
        buzzRow.setOnClickListener {
            buzzLevel = (buzzLevel + 1) % ShotService.BUZZ_AMPS.size
            prefs.edit().putInt("buzz", buzzLevel).apply()
            buzzRow.text = "Buzz: ${ShotService.BUZZ_NAMES[buzzLevel]}"
            ShotService.buzz(this, ShotService.SEEN_BUZZ) // feel it right away
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            qualityRows.forEach { addView(it, rowParams()) }
            addView(reset, rowParams().apply { topMargin = dpi(8) })
            addView(buzzRow, rowParams())
        }
        return FrameLayout(this).apply {
            setBackgroundColor(0xE6202321.toInt())
            addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener { closeSheet() } // tap outside the buttons to close
            visibility = View.GONE
        }.also { markQuality() }
    }

    private fun row(label: String, onTap: () -> Unit) = text(15f).apply {
        text = label
        gravity = Gravity.CENTER
        setOnClickListener { onTap() }
    }

    private fun rowParams() = LinearLayout.LayoutParams(dpi(124), dpi(34)).apply { bottomMargin = dpi(5) }

    private fun markQuality() {
        if (!::qualityRows.isInitialized) return
        qualityRows.forEachIndexed { i, row ->
            val on = i == phoneQuality
            row.background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(if (on) SMOKE else GLASS) }
            row.setTextColor(if (on) CARBON else SMOKE)
        }
    }

    private fun openSheet() { markQuality(); sheet.visibility = View.VISIBLE; pill.alpha = 0f }
    private fun closeSheet() { sheet.visibility = View.GONE; pill.alpha = 1f }

    override fun onBackPressed() {
        if (shotCard.visibility == View.VISIBLE) { shotCard.close(); return }
        if (sheet.visibility == View.VISIBLE) { closeSheet(); return }
        ShotService.stop(this) // leaving Nock with Back ends the session (and its battery use)
        super.onBackPressed()
    }

    private fun send(path: String, data: ByteArray) {
        val node = phoneNode ?: return
        messageClient.sendMessage(node, path, data)
    }

    private val tick: Runnable = Runnable {
        render()
        // No frames coming while this screen is open: ask the phone for them (it never streams unasked).
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt > 2000 && now - viewingSaidAt > 2000) sayViewing()
        center.postDelayed(tick, 500)
    }

    /**
     * The phone streams only while this screen is open and asking (frames sent to a closed watch app kept the watch
     * awake: 449 MB over Bluetooth, link awake 2.5 h of 5.5 h, 46% battery, 2026-10-08). Closed = nothing is sent.
     */
    private var viewingSaidAt = 0L
    private fun sayViewing() {
        viewingSaidAt = SystemClock.elapsedRealtime()
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            (phoneNode ?: (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id)?.let { messageClient.sendMessage(it, PATH_VIEWING, ByteArray(0)) }
        }
    }

    private fun render() {
        clock.text = android.text.format.DateFormat.format(if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm", System.currentTimeMillis())
        val now = SystemClock.elapsedRealtime()
        while (frameTimes.isNotEmpty() && now - frameTimes.first() > WINDOW_MS) frameTimes.removeFirst()
        val lost = lastFrameAt != 0L && now - lastFrameAt > STALE_MS
        when {
            lastFrameAt == 0L -> {
                center.text = "Tap to open\non your phone"
                scrim.visibility = View.GONE
            }
            lost -> {
                liveSince = 0L; pill.visibility = View.VISIBLE
                center.text = "Signal lost"
                scrim.visibility = View.VISIBLE // last frame stays, dimmed, so you still see the group
                setPill(BRICK, "%.0fs ago".format((now - lastFrameAt) / 1000f))
            }
            else -> {
                center.text = ""
                scrim.visibility = View.GONE
                // Over the frames actually seen: right after opening the window isn't full yet (read as "weak").
                val span = if (frameTimes.size < 2) WINDOW_MS else maxOf(500L, minOf(WINDOW_MS, now - frameTimes.first()))
                val fps = if (frameTimes.size < 2) 10f else (frameTimes.size - 1) * 1000f / span
                if (fps < 6f) { liveSince = 0L; setPill(AMBER, "Weak signal") } // choppy: say so, and keep saying it
                else {
                    if (liveSince == 0L) liveSince = now
                    setPill(FERN_BRIGHT, "Live")
                }
                // Steady for a few seconds: the pill gets out of the way. It comes back if the signal drops.
                pill.visibility = if (liveSince != 0L && now - liveSince > LIVE_FADE_MS) View.INVISIBLE else View.VISIBLE
            }
        }
    }

    private fun setPill(color: Int, label: String) {
        dotView.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        pillText.text = label
    }

    // Brand type, same as the phone: Bricolage Grotesque for display numbers, Hanken Grotesk for the rest.
    private val bricolage by lazy { Typeface.create(resources.getFont(R.font.bricolage_grotesque), 700, false) }
    private val hanken by lazy { Typeface.create(resources.getFont(R.font.hanken_grotesk), 600, false) }

    private fun text(sp: Float, display: Boolean = false) = TextView(this).apply {
        textSize = sp
        typeface = if (display || sp >= 17f) bricolage else hanken
        includeFontPadding = false
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Int) = dp(v.toFloat()).toInt()

    private companion object {
        const val PATH_FRAME = "/nock/frame"
        const val PATH_ACK = "/nock/ack"
        const val PATH_ZOOM = "/nock/zoom"
        const val PATH_ZOOM_TO = "/nock/zoom-to"
        const val PATH_QUALITY = "/nock/quality"
        const val PATH_SCORING = "/nock/scoring"
        const val PATH_XRAY = "/nock/xray"
        const val PATH_VIEWING = "/nock/viewing" // watch -> phone: the live view just opened
        const val TEAL = 0xFF57A6BA.toInt()
        const val AMBER = 0xFFDE8521.toInt()
        const val HEADER = 17 // seq int, zoom float, quality byte, min zoom float, max zoom float
        const val ZOOM_STEP = 1.15f
        val PRESETS = floatArrayOf(10f, 5f, 3f, 1f, 0.6f)
        const val WINDOW_MS = 3000L
        const val STALE_MS = 2000L
        const val LIVE_FADE_MS = 3000L
        // Brand palette (same as the phone): Carbon base, Fern = good, Brick = bad.
        const val SMOKE = 0xFFF5F5F4.toInt()
        const val CARBON = 0xFF202321.toInt()
        const val GLASS = 0xB3202321.toInt()
        const val FERN_BRIGHT = 0xFF7FA05C.toInt()
        const val BRICK = 0xFFB23A2E.toInt()
    }
}
