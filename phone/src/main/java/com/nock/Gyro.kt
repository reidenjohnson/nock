package com.nock

import android.hardware.SensorManager
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import kotlin.math.abs
import kotlin.math.acos

/**
 * Where the phone points, from the game rotation vector (gyro + accelerometer, no compass: steady indoors). Kept with
 * timestamps so each camera frame gets the orientation at its own moment, not whenever it was processed.
 */
class Orientation {
    private val n = 64
    private val ts = LongArray(n)
    private val rs = Array(n) { FloatArray(9) }
    private var count = 0
    private var head = 0

    @Synchronized fun add(tNanos: Long, rotationVector: FloatArray) {
        SensorManager.getRotationMatrixFromVector(rs[head], rotationVector)
        ts[head] = tNanos; head = (head + 1) % n; if (count < n) count++
    }

    /** Device -> world rotation (row-major 3x3) nearest [tNanos], within 60 ms; else the latest. Null before any. */
    @Synchronized fun at(tNanos: Long): DoubleArray? {
        if (count == 0) return null
        var best = (head - 1 + n) % n; var bestDt = Long.MAX_VALUE
        for (k in 0 until count) { val i = (head - 1 - k + n) % n; val dt = abs(ts[i] - tNanos); if (dt < bestDt) { bestDt = dt; best = i } }
        if (bestDt > 60_000_000L) best = (head - 1 + n) % n
        return DoubleArray(9) { rs[best][it].toDouble() }
    }
}

/**
 * Pure-rotation camera geometry: a target 10-40 yd away barely moves in the picture when the phone shifts a few cm, so
 * turning the phone moves it by K * R * K^-1 (R = the camera's turn, K = its lens). [m] = which way the camera's axes
 * sit in the phone (4 choices, one per screen rotation; learned, never assumed); [fn] = focal length as a fraction of
 * the frame's long side at zoom 1.
 */
object GyroGeom {
    /** Camera axes (x right, y down, z forward) in device coordinates, as the columns of a row-major 3x3. */
    val CANDIDATES: List<DoubleArray> = (0 until 4).map { k ->
        // base: x = +X, y = -Y, z = -Z (back camera, upright portrait), then turned k x 90 deg about the optical axis
        val c = intArrayOf(1, 0, -1, 0)[k].toDouble(); val s = intArrayOf(0, 1, 0, -1)[k].toDouble()
        val x = doubleArrayOf(c, -s, 0.0); val y = doubleArrayOf(s, c, 0.0) // in base camera coords
        // base camera -> device: (1,0,0)->(1,0,0), (0,1,0)->(0,-1,0), (0,0,1)->(0,0,-1)
        fun dev(v: DoubleArray) = doubleArrayOf(v[0], -v[1], -v[2])
        val cx = dev(x); val cy = dev(y); val cz = doubleArrayOf(0.0, 0.0, -1.0)
        doubleArrayOf(cx[0], cy[0], cz[0], cx[1], cy[1], cz[1], cx[2], cy[2], cz[2])
    }

    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i -> val r = i / 3; val c = i % 3; a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c] }
    fun t(a: DoubleArray) = DoubleArray(9) { i -> a[(i % 3) * 3 + i / 3] }
    fun apply(a: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> a[r * 3] * v[0] + a[r * 3 + 1] * v[1] + a[r * 3 + 2] * v[2] }

    /** The camera's turn from frame [rk] to frame [rn]: a ray in the first camera -> the same ray in the second. */
    fun relative(rk: DoubleArray, rn: DoubleArray, m: DoubleArray) = mul(t(m), mul(t(rn), mul(rk, m)))

    /** Angle between two orientations, degrees. */
    fun angle(a: DoubleArray, b: DoubleArray): Double { val r = mul(t(a), b); return Math.toDegrees(acos(((r[0] + r[4] + r[8] - 1) / 2).coerceIn(-1.0, 1.0))) }

    /** Pixel -> pixel from a frame taken at ([rk], zoom [zk]) to one at ([rn], [zn]); frames [w] x [h], focal [fn]. */
    fun homography(rk: DoubleArray, zk: Float, rn: DoubleArray, zn: Float, m: DoubleArray, fn: Double, w: Int, h: Int): DoubleArray {
        val long = maxOf(w, h).toDouble(); val fk = fn * long * zk; val fz = fn * long * zn; val cx = w / 2.0; val cy = h / 2.0
        val kInv = doubleArrayOf(1 / fk, 0.0, -cx / fk, 0.0, 1 / fk, -cy / fk, 0.0, 0.0, 1.0)
        val kn = doubleArrayOf(fz, 0.0, cx, 0.0, fz, cy, 0.0, 0.0, 1.0)
        return mul(kn, mul(relative(rk, rn, m), kInv))
    }

    fun toMat(h: DoubleArray) = Mat(3, 3, CvType.CV_64F).also { it.put(0, 0, *h) }

    fun map(h: DoubleArray, p: Point): Point? {
        val z = h[6] * p.x + h[7] * p.y + h[8]; if (z <= 1e-9) return null
        return Point((h[0] * p.x + h[1] * p.y + h[2]) / z, (h[3] * p.x + h[4] * p.y + h[5]) / z)
    }

    /** Where a pixel of frame (rk, zk) points, in the camera of frame (rn, zn): its direction (x right, y down, z ahead). */
    fun direction(p: Point, rk: DoubleArray, zk: Float, rn: DoubleArray, m: DoubleArray, fn: Double, w: Int, h: Int): DoubleArray {
        val f = fn * maxOf(w, h) * zk
        return apply(relative(rk, rn, m), doubleArrayOf((p.x - w / 2.0) / f, (p.y - h / 2.0) / f, 1.0))
    }
}

/**
 * Learns how the gyro lines up with the picture, from the phone's own moves: while the camera follows the scene (the
 * tracker's measured move), each sample pairs that move with the gyro's turn. The axis choice and focal length that
 * predict the measured moves best win, and only clearly (well under 2.5% of the frame off, and the next axis choice at
 * least twice as far off). Nothing about the phone's axes is assumed.
 */
class GyroCal(private val fn0: Double) {
    private class Sample(val rk: DoubleArray, val zk: Float, val rn: DoubleArray, val zn: Float, val key: List<Point>, val now: List<Point>, val w: Int, val h: Int)
    private val samples = ArrayList<Sample>()

    @Synchronized fun add(rk: DoubleArray, zk: Float, rn: DoubleArray, zn: Float, tracked: Mat, w: Int, h: Int) {
        val key = listOf(0.5 to 0.5, 0.25 to 0.25, 0.75 to 0.25, 0.25 to 0.75, 0.75 to 0.75).map { (a, b) -> Point(a * w, b * h) }
        val now = MatOfPoint2f().also { org.opencv.core.Core.perspectiveTransform(MatOfPoint2f(*key.toTypedArray()), it, tracked) }.toArray().toList()
        samples.add(Sample(rk, zk, rn, zn, key, now, w, h)); if (samples.size > 80) samples.removeAt(0)
    }

    val size @Synchronized get() = samples.size

    /** (axis choice, focal) if the samples settle it clearly, with the fit's mean error as a fraction of the frame; else null. */
    @Synchronized fun solve(): Triple<Int, Double, Double>? {
        if (samples.size < 20) return null
        val perM = (0 until 4).map { mi ->
            val m = GyroGeom.CANDIDATES[mi]
            var best = Double.MAX_VALUE; var bestF = fn0
            var s = 0.6
            while (s <= 1.6) {
                val fn = fn0 * s
                var e = 0.0; var n = 0
                for (x in samples) {
                    val hh = GyroGeom.homography(x.rk, x.zk, x.rn, x.zn, m, fn, x.w, x.h)
                    for (i in x.key.indices) { val p = GyroGeom.map(hh, x.key[i]) ?: continue; e += Math.hypot(p.x - x.now[i].x, p.y - x.now[i].y) / maxOf(x.w, x.h); n++ }
                }
                val err = if (n == 0) Double.MAX_VALUE else e / n
                if (err < best) { best = err; bestF = fn }
                s += 0.01
            }
            Triple(mi, bestF, best)
        }.sortedBy { it.third }
        val (mi, fn, err) = perM[0]
        android.util.Log.d("NockGyro", "fit: " + perM.joinToString { "m${it.first} f=%.3f err=%.4f".format(it.second, it.third) } + " (${samples.size} samples)")
        return if (err < 0.025 && perM[1].third > 2 * err) Triple(mi, fn, err) else null
    }
}
