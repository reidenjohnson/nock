package com.nock

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features.Features
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

/**
 * Keeps the target marks on the targets while the phone moves between looks (a look takes about a second, and
 * until it lands the old marks sat where the target used to be, 2026-10-08). From the frame a look was taken on
 * (the key frame), ~400 corners of the scene are followed frame to frame with optical flow (pyramidal Lucas-Kanade).
 * Each target is moved by a homography fitted only to the corners ON it ([hFor]): a target face is flat, so that fit
 * is exact, while one fit to the whole room was pulled off by parallax when the phone moved (2026-10-08). The whole
 * scene's fit is the fallback for a target with too few corners, and the lost check: too few points moving together
 * = lost (hide the marks and look again).
 * Runs on the camera's analysis thread only. Coordinates: upright full-size frame pixels.
 */
class MarkTracker {
    private var prev: Mat? = null
    private var prevPts: List<Point> = emptyList()
    private var keyPts: List<Point> = emptyList() // the same corners, where they were in the key frame
    private var s = 1.0                            // small / full
    private var hSmall: Mat? = null                // key -> now, small frame
    /** Which look this tracking belongs to (marks from any other look don't move with it). */
    var key = 0; private set
    val active get() = prev != null

    /** The camera frame (sensor-oriented) shrunk to [SMALL] on its long side, gray, turned upright. */
    fun small(full: Bitmap, rotation: Int): Mat {
        val rgba = Mat(); Utils.bitmapToMat(full, rgba)
        s = SMALL / maxOf(full.width, full.height).toDouble()
        val sm = Mat(); Imgproc.resize(rgba, sm, Size(), s, s, Imgproc.INTER_AREA); rgba.release()
        val g = Mat(); Imgproc.cvtColor(sm, g, Imgproc.COLOR_RGBA2GRAY); sm.release()
        when (rotation) {
            90 -> Core.rotate(g, g, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(g, g, Core.ROTATE_180)
            270 -> Core.rotate(g, g, Core.ROTATE_90_COUNTERCLOCKWISE)
        }
        return g
    }

    /** A look is being taken on this frame: it becomes the key frame. Returns its key. */
    fun start(g: Mat): Int {
        reset()
        prev = g
        prevPts = corners(g, MAX_PTS)
        keyPts = prevPts
        hSmall = Mat.eye(3, 3, CvType.CV_64F)
        return ++key
    }

    fun reset() { prev?.release(); prev = null; hSmall = null; prevPts = emptyList(); keyPts = emptyList() }

    /**
     * Follow the corners into this frame. [zoomBy] = how much the camera zoomed since the last frame (1 = not): the
     * corners' expected places (scaled about the frame centre) start the search, so a zoom doesn't throw the marks off.
     * False = lost (call [reset] and look again).
     */
    fun update(g: Mat, zoomBy: Double = 1.0): Boolean {
        val p = prev ?: return false.also { g.release() }
        if (prevPts.size < MIN_PTS) { lose(g); return false }
        val status = MatOfByte(); val err = MatOfFloat()
        val zoomed = kotlin.math.abs(zoomBy - 1) > 0.002
        val cx = g.cols() / 2.0; val cy = g.rows() / 2.0
        val next = if (zoomed) MatOfPoint2f(*prevPts.map { Point(cx + (it.x - cx) * zoomBy, cy + (it.y - cy) * zoomBy) }.toTypedArray()) else MatOfPoint2f()
        Video.calcOpticalFlowPyrLK(p, g, MatOfPoint2f(*prevPts.toTypedArray()), next, status, err, Size(21.0, 21.0), 3,
            org.opencv.core.TermCriteria(org.opencv.core.TermCriteria.COUNT + org.opencv.core.TermCriteria.EPS, 30, 0.01),
            if (zoomed) Video.OPTFLOW_USE_INITIAL_FLOW else 0, 1e-4)
        val st = status.toArray(); val nx = next.toArray()
        val k = ArrayList<Point>(); val n = ArrayList<Point>()
        for (i in st.indices) if (st[i].toInt() == 1) { k.add(keyPts[i]); n.add(nx[i]) }
        if (n.size < MIN_PTS) { lose(g); return false }
        val mask = Mat()
        // loose: near and far parts of the room move a little differently; this only checks the view is still followed
        val h = Geometry.findHomography(MatOfPoint2f(*k.toTypedArray()), MatOfPoint2f(*n.toTypedArray()), Geometry.RANSAC, 4.0, mask)
        if (h.empty()) { lose(g); return false }
        val agree = Core.countNonZero(mask)
        if (agree < MIN_PTS || agree < 0.3 * n.size) { lose(g); return false }
        // every followed point is kept: one that's off for the room (parallax) can be right for its own target
        keyPts = k; prevPts = n; hSmall = h
        // Corners leave the frame as the phone turns: top up with new ones, placed back into the key frame.
        if (prevPts.size < MAX_PTS / 2) runCatching {
            val inv = h.inv()
            val fresh = corners(g, MAX_PTS - prevPts.size)
            val back = MatOfPoint2f(); Core.perspectiveTransform(MatOfPoint2f(*fresh.toTypedArray()), back, inv)
            prevPts = prevPts + fresh; keyPts = keyPts + back.toArray()
        }
        p.release(); prev = g
        return true
    }

    /** Key frame -> now, in upright full-size pixels (null = not tracking). */
    fun h(): Mat? = hSmall?.let { toFull(it) }

    /**
     * Key frame -> now for a target whose outline in the key frame is [outline] (full-size pixels): fitted to the
     * corners on it, else the whole scene's.
     */
    fun hFor(outline: List<Point>): Mat? {
        val global = h() ?: return null
        val poly = MatOfPoint2f(*outline.map { Point(it.x * s, it.y * s) }.toTypedArray())
        val idx = keyPts.indices.filter { Geometry.pointPolygonTest(poly, keyPts[it], false) >= 0 }
        if (idx.size < MIN_ON_TARGET) return global
        val mask = Mat()
        val hs = Geometry.findHomography(MatOfPoint2f(*idx.map { keyPts[it] }.toTypedArray()), MatOfPoint2f(*idx.map { prevPts[it] }.toTypedArray()), Geometry.RANSAC, 2.0, mask)
        if (hs.empty() || Core.countNonZero(mask) < MIN_ON_TARGET) return global
        return toFull(hs)
    }

    private fun toFull(hs: Mat): Mat {
        val sm = Mat.eye(3, 3, CvType.CV_64F).also { it.put(0, 0, s); it.put(1, 1, s) }
        val si = Mat.eye(3, 3, CvType.CV_64F).also { it.put(0, 0, 1 / s); it.put(1, 1, 1 / s) }
        val t = Mat(); Core.gemm(hs, sm, 1.0, Mat(), 0.0, t)
        val out = Mat(); Core.gemm(si, t, 1.0, Mat(), 0.0, out)
        return out
    }

    private fun lose(g: Mat) { g.release(); reset() }

    private fun corners(g: Mat, max: Int): List<Point> {
        val c = MatOfPoint(); Features.goodFeaturesToTrack(g, c, max, 0.01, 5.0, Mat(), 3, false)
        return c.toArray().map { Point(it.x, it.y) }
    }

    companion object {
        const val SMALL = 480.0 // tracking size (long side): ~1/4 of the camera frame, plenty for following the scene
        const val MAX_PTS = 400
        const val MIN_ON_TARGET = 10 // corners on a target needed to move it by its own fit
        const val MIN_PTS = 20
    }
}
