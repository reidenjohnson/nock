package com.nock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.nio.ByteBuffer

/**
 * Runs the shooting session with the screen OFF: motion recording + release detection, the
 * buzzes, and this round's arrow list. The screen is only a window onto it, so the watch can
 * sleep normally (the screen was 87% of the battery drain when it was kept on).
 *
 * Foreground + partial wake lock keep the 100 Hz motion sensors flowing while the screen is off.
 * An Ongoing Activity makes raising your wrist come back to Nock instead of the watch face.
 * Runs only during a session: starts when Nock opens, stops on Back, and stops itself after
 * IDLE_STOP_MS with no phone stream and no shots (a forgotten session can't drain the watch).
 */
class ShotService : Service(), MessageClient.OnMessageReceivedListener {

    private val main = Handler(Looper.getMainLooper())
    private val messages by lazy { Wearable.getMessageClient(this) }
    private var wake: PowerManager.WakeLock? = null
    private val motion by lazy {
        MotionRecorder(this, onRelease = { wall, hold ->
            sendToPhone(PATH_RELEASE, ByteBuffer.allocate(8).putLong(wall).array())
            main.post { onReleased(hold) }
        }) { wall, hold, last, follow, trace ->
            // The shot's motion summary + aim trace, for the phone to keep with the arrow.
            // Layout: wall, hold, n, n x (x, y), then last + follow (older phones stop reading after the trace).
            val n = trace?.first?.size ?: 0
            val b = ByteBuffer.allocate(16 + n * 8 + 8).putLong(wall).putFloat(hold).putInt(n)
            trace?.let { (xs, ys) -> for (i in 0 until n) { b.putFloat(xs[i]); b.putFloat(ys[i]) } }
            b.putFloat(last).putFloat(follow)
            sendToPhone(PATH_MOTION, b.array())
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nock:session").apply { acquire(4 * 60 * 60 * 1000L) } // cap: 4 h
        messages.addListener(this)
        motion.start()
        running = true
        lastActivity = System.currentTimeMillis()
        main.postDelayed(idleCheck, IDLE_CHECK_MS)
        main.post(heartbeat)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        motion.stop()
        messages.removeListener(this)
        main.removeCallbacksAndMessages(null)
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Phone -> watch: arrow seen / where it hit ----

    /** "Watch session running" every 5 s, so the phone stays in watch mode while this screen sleeps. */
    private val heartbeat: Runnable = Runnable {
        sendToPhone(PATH_ALIVE, ByteArray(0))
        main.postDelayed(heartbeat, 5000)
    }

    private var lastActivity = 0L
    private val idleCheck: Runnable = Runnable {
        if (System.currentTimeMillis() - lastActivity > IDLE_STOP_MS) stopSelf()
        else main.postDelayed(idleCheck, IDLE_CHECK_MS)
    }

    override fun onMessageReceived(event: MessageEvent) {
        lastActivity = System.currentTimeMillis() // any phone traffic (frames, arrows) = session in use
        when (event.path) {
            PATH_ARROW -> { val i = ByteBuffer.wrap(event.data).int; main.post { onArrowSeen(i) } }
            PATH_BUZZ -> {
                getSharedPreferences("nock", MODE_PRIVATE).edit().putInt("buzz", event.data[0].toInt().coerceIn(0, 2)).apply()
                main.post { buzz(this, SEEN_BUZZ) } // feel the new strength
            }
            PATH_END -> {
                val b = ByteBuffer.wrap(event.data)
                val n = b.int; val pts = b.int; val arrows = b.int
                main.post { Session.end(n, pts, arrows) }
            }
            PATH_SHOT -> {
                // the arrow's close-up (see the phone's sendShotToWatch)
                val b = ByteBuffer.wrap(event.data)
                val stamp = b.long; val i = b.int; val found = b.get().toInt() == 1
                val r = b.float; val l = b.float; val sx = b.float; val sy = b.float; val hx = b.float; val hy = b.float; val ppi = b.float
                val bmp = android.graphics.BitmapFactory.decodeByteArray(event.data, b.position(), event.data.size - b.position()) ?: return
                main.post { Session.shot(ShotImage(stamp, i, found, r, l, sx, sy, hx, hy, ppi, bmp)) }
            }
            PATH_HIT -> {
                val b = ByteBuffer.wrap(event.data)
                val i = b.int; val found = b.get().toInt() == 1; val r = b.float; val l = b.float
                main.post { Session.hit(i, found, r, l) }
            }
        }
    }

    private var pendingHold = -1f

    private fun onReleased(hold: Float) {
        lastActivity = System.currentTimeMillis()
        pendingHold = hold
        main.removeCallbacks(noArrow)
        main.postDelayed(noArrow, NO_ARROW_MS)
    }

    private val noArrow = Runnable {
        if (!Session.scoring) { pendingHold = -1f; return@Runnable } // paused on the phone: a release isn't a miss
        buzz(this, FAIL_BUZZ)
        Session.add(Session.Row(0, System.currentTimeMillis(), pendingHold, seen = false))
        pendingHold = -1f
    }

    private fun onArrowSeen(index: Int) {
        main.removeCallbacks(noArrow)
        buzz(this, SEEN_BUZZ)
        Session.add(Session.Row(index, System.currentTimeMillis(), pendingHold, seen = true))
        pendingHold = -1f
    }

    private fun sendToPhone(path: String, data: ByteArray) {
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.let { messages.sendMessage(it.id, path, data) }
        }
    }

    // ---- Foreground + Ongoing Activity (wrist raise returns to Nock) ----

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Shooting session", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Nock")
            .setContentText("Watching for shots")
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setContentIntent(open)
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(android.R.drawable.ic_menu_mylocation)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate("Nock · watching for shots").build())
            .build()
            .apply(applicationContext)
        startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        @Volatile var running = false
        const val PATH_RELEASE = "/nock/release"
        const val PATH_ARROW = "/nock/arrow"
        const val PATH_HIT = "/nock/hit"
        const val PATH_SHOT = "/nock/shot" // phone -> watch: the arrow's close-up
        const val PATH_FIX = "/nock/fix"   // watch -> phone: you moved the arrow (stamp, x, y in the close-up)
        const val PATH_END = "/nock/end"
        const val PATH_MOTION = "/nock/motion"
        const val PATH_BUZZ = "/nock/buzz"
        const val PATH_ALIVE = "/nock/alive"
        val SEEN_BUZZ = longArrayOf(0, 380)                  // zzzz: phone saw the arrow
        val FAIL_BUZZ = longArrayOf(0, 90, 110, 90, 110, 90) // zz-zz-zz: shot, but no arrow seen
        val BUZZ_AMPS = intArrayOf(110, 180, 255)
        val BUZZ_NAMES = listOf("Light", "Medium", "Strong")
        private const val NO_ARROW_MS = 4000L
        private const val IDLE_STOP_MS = 10 * 60 * 1000L
        private const val IDLE_CHECK_MS = 60 * 1000L
        private const val CHANNEL = "session"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) { if (!running) context.startForegroundService(Intent(context, ShotService::class.java)) }
        fun stop(context: Context) = context.stopService(Intent(context, ShotService::class.java))

        fun buzz(context: Context, timings: LongArray) {
            val level = context.getSharedPreferences("nock", Context.MODE_PRIVATE).getInt("buzz", 2)
            val amps = IntArray(timings.size) { if (it % 2 == 1) BUZZ_AMPS[level] else 0 }
            context.getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createWaveform(timings, amps, -1))
        }
    }
}

/** This round's arrows, shared by the service (writes) and the screen (shows). Display only. */
object Session {
    class Row(val index: Int, val atMs: Long, val holdDps: Float, val seen: Boolean, var hit: String? = null, var ring: String? = null, var ringColor: Int = 0, var points: Int = 0)

    val rows = ArrayList<Row>()
    /** The screen listens while it's visible: (row that just changed or null for a reset). */
    var listener: ((Row?) -> Unit)? = null

    fun add(r: Row) { rows.add(r); listener?.invoke(r) }

    /** The last arrow's close-up; the screen shows it full screen (and on the next wrist raise if it was asleep). */
    var lastShot: ShotImage? = null; var lastShotAt = 0L; var shownStamp = 0L
    var shotListener: ((ShotImage) -> Unit)? = null
    fun shot(s: ShotImage) { lastShot = s; lastShotAt = System.currentTimeMillis(); shotListener?.invoke(s) }
    fun clear() { rows.clear(); listener?.invoke(null) }

    /** The phone's Start/Pause state (it rides on every frame). */
    @Volatile var scoring = false

    /** A round just finished on the phone: (round no, points, arrows). The screen shows it once. */
    var lastEnd: Triple<Int, Int, Int>? = null
    var endListener: ((Triple<Int, Int, Int>) -> Unit)? = null
    fun end(n: Int, pts: Int, arrows: Int) { Triple(n, pts, arrows).also { lastEnd = it; endListener?.invoke(it) } }

    fun hit(index: Int, found: Boolean, right: Float, low: Float) {
        val row = rows.lastOrNull { it.index == index } ?: return
        row.hit = if (!found) "hit ?" else {
            val d = kotlin.math.hypot(right, low)
            if (d < 0.3f) "%.1f\"".format(d) else "%.1f\" %s".format(d, arrowFor(right, low)) // dead centre: no direction
        }
        if (found) { val d = kotlin.math.hypot(right, low); row.ring = Ring.label(d); row.ringColor = Ring.color(Ring.score(d)); row.points = Ring.score(d) }
        listener?.invoke(null)
    }

    /** Which way the arrow went from the spot, as one glyph. Screen y grows down = low. */
    private fun arrowFor(right: Float, low: Float): String {
        val a = Math.toDegrees(kotlin.math.atan2(-low.toDouble(), right.toDouble())) // 0 = right, 90 = high
        val dirs = arrayOf("→", "↗", "↑", "↖", "←", "↙", "↓", "↘")
        return dirs[(((a + 360 + 22.5) % 360) / 45).toInt()]
    }
}
