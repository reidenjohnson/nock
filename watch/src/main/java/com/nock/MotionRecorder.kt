package com.nock

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Records the wrist's motion (gyroscope + accelerometer, 100 Hz — this watch's max) to a CSV for
 * the whole time the app is open. Rows carry wall-clock ms so they line up with the phone's photos.
 *
 * Raw data first: what a yank, twitch or dropped bow arm looks like gets worked out from real shots,
 * not guessed thresholds.
 */
class MotionRecorder(
    private val context: Context,
    /** Right at the jolt (the phone pairs it with the arrow). */
    private val onRelease: (wallMs: Long, holdDps: Float) -> Unit,
    /** FOLLOW_MS later: the whole shot. lastDps = arm movement just before release, followDps = just after. */
    private val onShot: (wallMs: Long, holdDps: Float, lastDps: Float, followDps: Float, trace: Pair<FloatArray, FloatArray>?) -> Unit,
) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    private var out: BufferedWriter? = null
    // Sensor timestamps are elapsed-realtime nanos; this offset turns them into wall-clock ms.
    private var wallOffsetMs = 0L
    private var lastFlushMs = 0L

    fun start() {
        if (thread != null) return
        val dir = File(context.filesDir, "motion").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        out = File(dir, "motion_$name.csv").bufferedWriter().apply { write("wall_ms,sensor,x,y,z\n") }
        wallOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()

        val t = HandlerThread("motion").apply { start() }
        thread = t
        val handler = Handler(t.looper)
        listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ACCELEROMETER).forEach { type ->
            sensors.getDefaultSensor(type)?.let { sensors.registerListener(this, it, PERIOD_US, handler) }
        }
    }

    fun stop() {
        sensors.unregisterListener(this)
        val t = thread ?: return
        thread = null
        Handler(t.looper).post {
            out?.close()
            out = null
            t.quitSafely()
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val tag = if (e.sensor.type == Sensor.TYPE_GYROSCOPE) 'g' else 'a'
        val wall = wallOffsetMs + e.timestamp / 1_000_000
        out?.write("$wall,$tag,${e.values[0]},${e.values[1]},${e.values[2]}\n")
        // Flush every second so a killed app loses at most a second of motion.
        if (wall - lastFlushMs > 1000) { out?.flush(); lastFlushMs = wall }
        if (tag == 'g') onGyro(wall, e.values) else onAccel(wall, e.values)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    // ---- Release detection ----
    // A shot = a sharp jolt (> RELEASE_G) right after at least HOLD_MS of steady aim (gyro RMS < HOLD_DPS).
    // Checked against both basement sessions: 10 of 10 shots found, and none of the 8 other big jolts
    // (handling the bow, walking, pulling arrows) passed, because none came out of a steady hold.

    private val gyroTimes = LongArray(GYRO_RING)
    private val gyroSq = FloatArray(GYRO_RING) // squared magnitude, (deg/s)^2
    private var gyroHead = 0
    private var lastReleaseMs = 0L

    // Full vectors for the aim trace (3.5 s of each sensor).
    private val gT = LongArray(TRACE_RING); private val gX = FloatArray(TRACE_RING); private val gY = FloatArray(TRACE_RING); private val gZ = FloatArray(TRACE_RING)
    private var gH = 0
    private val aT = LongArray(TRACE_RING); private val aX = FloatArray(TRACE_RING); private val aY = FloatArray(TRACE_RING); private val aZ = FloatArray(TRACE_RING)
    private var aH = 0

    /**
     * The bow arm's aim path over the last TRACE_MS before release, in degrees (x = right, y = up).
     * Gravity (mean accel over the window) gives "up"; the forearm is the watch's x axis (toward the
     * hand on a wrist-worn watch) made horizontal. Left/right = turning about "up", up/down = tilting
     * about the horizontal axis across the forearm. Integrated gyro, so it's the shape of the wobble,
     * not an absolute sight picture.
     */
    private fun aimTrace(release: Long): Pair<FloatArray, FloatArray>? {
        val from = release - TRACE_MS; val to = release - 60
        var ux = 0.0; var uy = 0.0; var uz = 0.0; var na = 0
        for (i in 0 until TRACE_RING) if (aT[i] in from..to) { ux += aX[i]; uy += aY[i]; uz += aZ[i]; na++ }
        if (na < 50) return null
        val un = kotlin.math.sqrt(ux * ux + uy * uy + uz * uz); ux /= un; uy /= un; uz /= un // up
        // forearm: watch x axis with its "up" part removed
        var fx = 1.0 - ux * ux; var fy = -ux * uy; var fz = -ux * uz
        val fn = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz); if (fn < 0.3) return null
        fx /= fn; fy /= fn; fz /= fn
        // across = up x forearm (points left when the forearm points at the target)
        val cx = uy * fz - uz * fy; val cy = uz * fx - ux * fz; val cz = ux * fy - uy * fx
        val idx = (0 until TRACE_RING).filter { gT[it] in from..to }.sortedBy { gT[it] }
        if (idx.size < 50) return null
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        var yaw = 0.0; var pitch = 0.0; var prev = gT[idx[0]]
        for (i in idx) {
            val dt = (gT[i] - prev) / 1000.0; prev = gT[i]
            yaw += (gX[i] * ux + gY[i] * uy + gZ[i] * uz) * dt
            pitch += (gX[i] * cx + gY[i] * cy + gZ[i] * cz) * dt
            xs.add(Math.toDegrees(-yaw).toFloat()); ys.add(Math.toDegrees(-pitch).toFloat())
        }
        val step = maxOf(1, xs.size / TRACE_POINTS)
        val ox = FloatArray((xs.size + step - 1) / step) { xs[minOf(it * step, xs.size - 1)] }
        val oy = FloatArray(ox.size) { ys[minOf(it * step, ys.size - 1)] }
        return ox to oy
    }

    // A release waiting for its follow-through window to fill.
    private var pendingWall = 0L; private var pendingHold = 0f; private var pendingLast = 0f
    private var pendingTrace: Pair<FloatArray, FloatArray>? = null

    /** RMS gyro speed (deg/s) between two wall times. */
    private fun gyroRms(from: Long, to: Long): Float {
        var sum = 0.0; var n = 0
        for (i in 0 until TRACE_RING) if (gT[i] in from..to) {
            val d = Math.toDegrees(sqrt(gX[i] * gX[i] + gY[i] * gY[i] + gZ[i] * gZ[i]).toDouble()); sum += d * d; n++
        }
        return if (n == 0) 0f else sqrt(sum / n).toFloat()
    }

    private fun onGyro(wall: Long, v: FloatArray) {
        gT[gH] = wall; gX[gH] = v[0]; gY[gH] = v[1]; gZ[gH] = v[2]; gH = (gH + 1) % TRACE_RING
        if (pendingWall != 0L && wall - pendingWall >= FOLLOW_MS + 50) {
            val w = pendingWall; pendingWall = 0L
            onShot(w, pendingHold, pendingLast, gyroRms(w + 100, w + FOLLOW_MS), pendingTrace)
        }
        val dps = Math.toDegrees(sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
        gyroTimes[gyroHead] = wall
        gyroSq[gyroHead] = dps * dps
        gyroHead = (gyroHead + 1) % GYRO_RING
    }

    private fun onAccel(wall: Long, v: FloatArray) {
        aT[aH] = wall; aX[aH] = v[0]; aY[aH] = v[1]; aZ[aH] = v[2]; aH = (aH + 1) % TRACE_RING
        val g = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) / SensorManager.GRAVITY_EARTH
        if (g < RELEASE_G || wall - lastReleaseMs < MIN_GAP_MS) return
        // Steadiness over the hold window, ending a moment before the jolt.
        var sum = 0f
        var n = 0
        for (i in 0 until GYRO_RING) {
            if (gyroTimes[i] in (wall - HOLD_MS - 100)..(wall - 100)) { sum += gyroSq[i]; n++ }
        }
        if (n < HOLD_MS / 12) return // not enough samples yet (app just opened)
        val hold = sqrt(sum / n)
        if (hold > HOLD_DPS) return
        lastReleaseMs = wall
        onRelease(wall, hold)
        // The last instant before the jolt (a punch or a yank shows here); follow-through is measured once it's happened.
        pendingWall = wall; pendingHold = hold; pendingLast = gyroRms(wall - 250, wall - 60)
        pendingTrace = runCatching { aimTrace(wall) }.getOrNull()
    }

    private companion object {
        const val PERIOD_US = 10_000 // 100 Hz
        const val GYRO_RING = 200    // 2 s of gyro
        const val RELEASE_G = 2.5f   // shots measured 3.1-6.0 g; margin below the softest
        const val HOLD_MS = 1000L
        const val HOLD_DPS = 8f      // shots measured 1.9-2.7 deg/s; generous margin for a shakier day
        const val MIN_GAP_MS = 3000L
        const val TRACE_RING = 360   // 3.6 s at 100 Hz
        const val TRACE_MS = 2500L   // the aim path shown: last 2.5 s before release
        const val TRACE_POINTS = 80
        const val FOLLOW_MS = 600L   // follow-through window after release
    }
}
