package com.nock

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.geometry.Geometry
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features.BFMatcher
import org.opencv.features.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Finds where a new arrow went in, from the before/after photos, and how far that is from the
 * nearest blue painter's-tape spot. Port of tools/hits.py (proven on 20 real arrows: every hit
 * marked where the shaft meets the foam).
 *
 * 1. Register before -> after (the foam block nudges on impact).
 * 2. New orange = fletching. 3. From it, the direction with the longest continuous trail = shaft
 *    (directions unlike this session's arrows are skipped). 4. The trail's end = the hit.
 * 5. Scale: tape is 1.88" wide (factory edge); the printed white square is 6.75". -> offset from the nearest spot.
 */
class HitFinder {

    data class Hit(
        val entryX: Float, val entryY: Float,       // in the upright photo, full-res pixels
        val spotX: Float, val spotY: Float,
        val rightIn: Float, val lowIn: Float,       // offset from the spot, inches (+ = right / low)
        val ppi: Float,                             // pixels per inch in the photo
    ) { val distIn get() = hypot(rightIn, lowIn) }

    private val headings = ArrayList<Double>() // angles of clear shafts this session

    init { OpenCVLoader.initLocal() }

    /** [aim] = the remembered side's spots in this photo (TargetMemory); null = find the blue tape spots here. */
    fun find(beforeBmp: Bitmap, afterBmp: Bitmap, aim: List<Point>? = null, box: List<Point>? = null, ppiHint: Double? = null): Hit? {
        val after = toMat(afterBmp); val before = toMat(beforeBmp)
        try {
            val b = register(before, after) ?: return null
            val diff = diff(after, b)
            val hsv = Mat().also { Imgproc.cvtColor(after, it, Imgproc.COLOR_RGB2HSV) }
            val orange = Mat().also { Core.inRange(hsv, Scalar(3.0, 140.0, 140.0), Scalar(22.0, 255.0, 255.0), it) }

            val fl = fletching(orange, diff) ?: return null
            val (found, tapePx) = blueSpots(hsv)
            val spots = aim?.takeIf { it.isNotEmpty() } ?: found
            // A marked target's outline is the box a hit must end in; otherwise the box around the spots.
            val tBox = box?.takeIf { it.size >= 3 }?.let { b ->
                val m = 0.03 * after.cols()
                doubleArrayOf(b.minOf { it.x } - m, b.minOf { it.y } - m, b.maxOf { it.x } + m, b.maxOf { it.y } + m)
            } ?: targetBox(spots, after.cols())
            val (rough, reach0) = shaft(diff, fl, tBox) ?: return null
            if (reach0 > 0.08 * after.cols()) headings.add(atan2(rough.y - fl.y, rough.x - fl.x))
            var ux = (rough.x - fl.x) / reach0; var uy = (rough.y - fl.y) / reach0
            // The longest ray hugs one edge of the shaft and can run on into its shadow past the entry: fit the
            // shaft's centre line, walk it, and refit (a short first ray covers only part of the shaft).
            val dpx = ByteArray(diff.cols() * diff.rows()).also { diff.get(0, 0, it) }
            var o = fl; var reachC = reach0
            repeat(4) {
                val (o2, u2) = centreline(dpx, diff.cols(), diff.rows(), o, ux, uy, reachC)
                o = o2; ux = u2.x; uy = u2.y
                reachC = walk(dpx, diff.cols(), diff.rows(), o, ux, uy, 0.02 * diff.cols(), 0.35 * diff.cols()) ?: reachC
            }
            // Fine end: local re-alignment around the far shaft, then back off the shadow/blur overshoot.
            var reach = refineEnd(after, b, o, ux, uy, reachC)
            reach = maxOf(0.0, reach - ENTRY_BACKOFF_IN * (ppiHint ?: (tapePx / TAPE_IN)))
            val entry = Point(o.x + ux * reach, o.y + uy * reach)

            val spot = spots.minByOrNull { hypot(it.x - entry.x, it.y - entry.y) } ?: return null
            val ppi = ppiHint ?: ppi(hsv, tapePx).takeIf { it > 0 } ?: 1.0
            val s = 1.0 / SCALE
            return Hit(
                (entry.x * s).toFloat(), (entry.y * s).toFloat(), (spot.x * s).toFloat(), (spot.y * s).toFloat(),
                ((entry.x - spot.x) / ppi).toFloat(), ((entry.y - spot.y) / ppi).toFloat(), (ppi / s).toFloat(),
            )
        } finally { after.release(); before.release() }
    }

    /** The aim spots and scale in one photo, for placing an arrow by hand when [find] couldn't. Null = no tape seen. */
    fun face(afterBmp: Bitmap): Pair<List<Point>, Double>? {
        val after = toMat(afterBmp)
        try {
            val hsv = Mat().also { Imgproc.cvtColor(after, it, Imgproc.COLOR_RGB2HSV) }
            val (spots, tapePx) = blueSpots(hsv)
            if (spots.isEmpty() || tapePx <= 0) return null
            return spots to ppi(hsv, tapePx)
        } finally { after.release() }
    }

    /** Two rulers: the tape's factory edge and the printed white square. When they agree, use the square (bigger). */
    private fun ppi(hsv: Mat, tapePx: Double): Double {
        val ppi = tapePx / TAPE_IN
        val sq = whiteSquarePpi(hsv)
        return if (sq != null && kotlin.math.abs(sq / ppi - 1) < 0.15) sq else ppi
    }

    /** New session (new framing): forget the learned arrow heading. */
    fun reset() = headings.clear()

    // Full resolution, same settings as tools/hits.py (the version proven on real arrows).
    private fun toMat(bmp: Bitmap): Mat {
        val rgba = Mat(); Utils.bitmapToMat(bmp, rgba)
        val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB); rgba.release()
        val small = Mat(); Imgproc.resize(rgb, small, Size(), SCALE, SCALE, Imgproc.INTER_AREA); rgb.release()
        return small
    }

    private fun register(before: Mat, after: Mat): Mat? {
        val g1 = Mat(); val g2 = Mat()
        Imgproc.cvtColor(before, g1, Imgproc.COLOR_RGB2GRAY); Imgproc.cvtColor(after, g2, Imgproc.COLOR_RGB2GRAY)
        val orb = ORB.create(5000)
        val k1 = MatOfKeyPoint(); val k2 = MatOfKeyPoint(); val d1 = Mat(); val d2 = Mat()
        orb.detectAndCompute(g1, Mat(), k1, d1); orb.detectAndCompute(g2, Mat(), k2, d2)
        if (d1.empty() || d2.empty()) return null
        val matches = MatOfDMatch().also { BFMatcher.create(Core.NORM_HAMMING, true).match(d1, d2, it) }
        val best: List<DMatch> = matches.toList().sortedBy { it.distance }.take(1000)
        if (best.size < 20) return null
        val p1 = k1.toArray(); val p2 = k2.toArray()
        val src = MatOfPoint2f(*best.map { p1[it.queryIdx].pt }.toTypedArray())
        val dst = MatOfPoint2f(*best.map { p2[it.trainIdx].pt }.toTypedArray())
        val h = Geometry.findHomography(src, dst, Geometry.RANSAC, 2.0)
        if (h.empty()) return null
        return Mat().also { Imgproc.warpPerspective(before, it, h, after.size()) }
    }

    /** Per-pixel max-channel difference, as 8-bit. */
    private fun diff(a: Mat, b: Mat): Mat {
        val ba = Mat(); val bb = Mat()
        Imgproc.GaussianBlur(a, ba, Size(5.0, 5.0), 0.0); Imgproc.GaussianBlur(b, bb, Size(5.0, 5.0), 0.0)
        val d = Mat(); Core.absdiff(ba, bb, d)
        val ch = ArrayList<Mat>(); Core.split(d, ch)
        val m = Mat(); Core.max(ch[0], ch[1], m); Core.max(m, ch[2], m)
        return m
    }

    private fun fletching(orange: Mat, diff: Mat): Point? {
        val changed = Mat().also { Imgproc.threshold(diff, it, 40.0, 255.0, Imgproc.THRESH_BINARY) }
        val newOrange = Mat().also { Core.bitwise_and(orange, changed, it) }
        Imgproc.morphologyEx(newOrange, newOrange, Imgproc.MORPH_OPEN, Mat.ones(3, 3, CvType.CV_8U))
        val lab = Mat(); val st = Mat(); val cen = Mat()
        val n = Imgproc.connectedComponentsWithStats(newOrange, lab, st, cen)
        if (n < 2) return null
        val i = (1 until n).maxBy { st.get(it, Imgproc.CC_STAT_AREA)[0] }
        return Point(cen.get(i, 0)[0], cen.get(i, 1)[0])
    }

    /**
     * The shaft: from the fletching, try every direction in half-degree steps and keep the one with the
     * longest continuous run of changed pixels (gaps up to 30 px). Deterministic, unlike Hough line
     * detection, which is randomized and gave different answers run to run.
     */
    /**
     * The foam shifts a little on impact. Re-align 'before' to 'after' just around the far part of the shaft
     * (masking the shaft itself), redo the change map there, and walk again allowing longer gaps (dark shaft
     * over black foam). Local alignment keeps shifted-tape noise from extending the trail.
     * Took median error 0.78 -> 0.29 in on a tape-measured round (tools/score.py).
     */
    private fun refineEnd(after: Mat, b: Mat, fl: Point, ux: Double, uy: Double, last: Double): Double {
        val w = after.cols(); val h = after.rows()
        val pad = (0.06 * w).toInt()
        val p0x = fl.x + ux * 0.3 * last; val p0y = fl.y + uy * 0.3 * last
        val p1x = fl.x + ux * 1.8 * last; val p1y = fl.y + uy * 1.8 * last
        val x0 = maxOf(0, (minOf(p0x, p1x) - pad).toInt()); val x1 = minOf(w, (maxOf(p0x, p1x) + pad).toInt())
        val y0 = maxOf(0, (minOf(p0y, p1y) - pad).toInt()); val y1 = minOf(h, (maxOf(p0y, p1y) + pad).toInt())
        if (x1 - x0 < 40 || y1 - y0 < 40) return last
        val aRoi = after.submat(y0, y1, x0, x1); val bRoi = b.submat(y0, y1, x0, x1)
        val ga = Mat(); val gb = Mat()
        Imgproc.cvtColor(aRoi, ga, Imgproc.COLOR_RGB2GRAY); ga.convertTo(ga, CvType.CV_32F)
        Imgproc.cvtColor(bRoi, gb, Imgproc.COLOR_RGB2GRAY); gb.convertTo(gb, CvType.CV_32F)
        val mask = Mat(ga.size(), CvType.CV_8U, Scalar(255.0))
        Imgproc.line(mask, Point(fl.x - x0, fl.y - y0), Point(fl.x + ux * 2 * last - x0, fl.y + uy * 2 * last - y0), Scalar(0.0), 25)
        val m = Mat.eye(2, 3, CvType.CV_32F)
        try {
            org.opencv.video.Video.findTransformECC(ga, gb, m, org.opencv.video.Video.MOTION_EUCLIDEAN,
                org.opencv.core.TermCriteria(org.opencv.core.TermCriteria.EPS + org.opencv.core.TermCriteria.COUNT, 60, 1e-4), mask, 5)
        } catch (e: Exception) { return last }
        val bw = Mat()
        Imgproc.warpAffine(bRoi, bw, m, aRoi.size(), Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP)
        val loc = diff(aRoi, bw)
        val lw = loc.cols(); val lh = loc.rows()
        val px = ByteArray(lw * lh); loc.get(0, 0, px)
        fun on(x: Int, y: Int) = (px[y * lw + x].toInt() and 0xFF) > 18
        var k = 0.3 * last; var lastK = -1.0; var gap = 0.0
        while (k < 1.8 * last) {
            val ix = (fl.x + ux * k).toInt() - x0; val iy = (fl.y + uy * k).toInt() - y0
            if (ix < 2 || iy < 2 || ix >= lw - 2 || iy >= lh - 2) break
            if (on(ix, iy) || on(ix, iy - 1) || on(ix, iy + 1) || on(ix - 1, iy) || on(ix + 1, iy)) { lastK = k; gap = 0.0 }
            else { gap += 2; if (gap > REFINE_GAP) break }
            k += 2
        }
        return if (lastK >= 0) lastK else last
    }

    /** A hit lands on the target: the box around the tape spots, grown 25% per side (+5% of the width). */
    private fun targetBox(spots: List<Point>, w: Int): DoubleArray? {
        if (spots.size < 2) return null
        val x0 = spots.minOf { it.x }; val x1 = spots.maxOf { it.x }; val y0 = spots.minOf { it.y }; val y1 = spots.maxOf { it.y }
        val mx = 0.25 * (x1 - x0) + 0.05 * w; val my = 0.25 * (y1 - y0) + 0.05 * w
        return doubleArrayOf(x0 - mx, y0 - my, x1 + mx, y1 + my)
    }

    /**
     * The shaft's centre line: across the shaft at many points along its middle stretch, the centre of the
     * changed band; a robust line through those centres. Returns (point level with [o], unit direction).
     * Took median error 0.30 -> 0.04 in on 15 arrows you fixed (tools/score_fixes.py).
     */
    private fun centreline(px: ByteArray, w: Int, h: Int, o: Point, ux: Double, uy: Double, reach: Double): Pair<Point, Point> {
        val nx = -uy; val ny = ux
        val pts = ArrayList<Point>()
        var t = 0.2 * reach
        while (t < 0.8 * reach) {
            val cx = o.x + ux * t; val cy = o.y + uy * t
            var sw = 0.0; var sws = 0.0; var inside = true
            for (sIdx in -CENTRE_BAND..CENTRE_BAND) {
                val x = (cx + nx * sIdx).toInt(); val y = (cy + ny * sIdx).toInt()
                if (x < 0 || y < 0 || x >= w || y >= h) { inside = false; break }
                val v = ((px[y * w + x].toInt() and 0xFF) - 18).coerceAtLeast(0).toDouble()
                sw += v; sws += v * sIdx
            }
            if (inside && sw > 0) pts.add(Point(cx + nx * sws / sw, cy + ny * sws / sw))
            t += 3.0
        }
        if (pts.size < 5) return o to Point(ux, uy)
        val line = Mat(); Geometry.fitLine(MatOfPoint2f(*pts.toTypedArray()), line, Geometry.DIST_HUBER, 0.0, 0.01, 0.01)
        var vx = line.get(0, 0)[0]; var vy = line.get(1, 0)[0]
        if (vx * ux + vy * uy < 0) { vx = -vx; vy = -vy }
        val lx = line.get(2, 0)[0]; val ly = line.get(3, 0)[0]
        val k = (o.x - lx) * vx + (o.y - ly) * vy
        return Point(lx + vx * k, ly + vy * k) to Point(vx, vy)
    }

    /** Distance along o + u*t of the last changed pixel before a gap over 30 px, or null. */
    private fun walk(px: ByteArray, w: Int, h: Int, o: Point, ux: Double, uy: Double, start: Double, maxLen: Double): Double? {
        fun on(x: Int, y: Int) = (px[y * w + x].toInt() and 0xFF) > 18
        var t = start; var last: Double? = null; var gap = 0.0
        while (t < maxLen) {
            val x = (o.x + ux * t).toInt(); val y = (o.y + uy * t).toInt()
            if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) break
            if (on(x, y) || on(x, y - 1) || on(x, y + 1) || on(x - 1, y) || on(x + 1, y)) { last = t; gap = 0.0 }
            else { gap += 2; if (gap > 30) break }
            t += 2
        }
        return last
    }

    private fun shaft(diff: Mat, fl: Point, box: DoubleArray?): Pair<Point, Double>? {
        val w = diff.cols(); val h = diff.rows()
        val px = ByteArray(w * h); diff.get(0, 0, px)
        fun on(x: Int, y: Int) = (px[y * w + x].toInt() and 0xFF) > 18
        val heading = if (headings.size >= 2) headings.sorted()[headings.size / 2] else null
        val start = 0.02 * w // skip the vanes right around the fletching centre
        val maxLen = 0.35 * w
        var bestReach = -1.0; var bestAng = 0.0
        var deg = 0.0
        while (deg < 360.0) {
            val a = Math.toRadians(deg); deg += 0.5
            // From a fixed phone every arrow points about the same way; skip directions that don't.
            if (heading != null && cos(a - heading) < cos(Math.toRadians(20.0))) continue
            val ux = cos(a); val uy = kotlin.math.sin(a)
            var step = start; var last = -1.0; var gap = 0.0
            while (step < maxLen) {
                val x = (fl.x + ux * step).toInt(); val y = (fl.y + uy * step).toInt()
                if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) break
                if (on(x, y) || on(x, y - 1) || on(x, y + 1) || on(x - 1, y) || on(x + 1, y)) { last = step; gap = 0.0 }
                else { gap += 2; if (gap > 30) break }
                step += 2
            }
            if (last < 0) continue
            if (box != null) { // trail must end on the target, not out in the wall
                val ex = fl.x + ux * last; val ey = fl.y + uy * last
                if (ex < box[0] || ex > box[2] || ey < box[1] || ey > box[3]) continue
            }
            if (last > bestReach) { bestReach = last; bestAng = a }
        }
        if (bestReach < 0) return null
        return Point(fl.x + cos(bestAng) * bestReach, fl.y + kotlin.math.sin(bestAng) * bestReach) to bestReach
    }

    /**
     * Tape spots and tape width (px). Width = median short side of each piece's outline, with arrow
     * holes healed first. (Measuring the inside of the tape instead read ~1.7x too narrow.)
     */
    private fun blueSpots(hsv: Mat): Pair<List<Point>, Double> {
        val m = Mat().also { Core.inRange(hsv, Scalar(95.0, 120.0, 80.0), Scalar(125.0, 255.0, 255.0), it) }
        Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, Mat.ones(5, 5, CvType.CV_8U))
        Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, Mat.ones(15, 15, CvType.CV_8U))
        val contours = ArrayList<org.opencv.core.MatOfPoint>()
        Imgproc.findContours(m, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val spots = ArrayList<Point>(); val sides = ArrayList<Double>()
        for (c in contours) {
            if (Geometry.contourArea(c) < 800.0) continue // specks
            val r = Geometry.boundingRect(c)
            if (r.y < 0.08 * hsv.rows()) continue // the tape roll sitting on top of the block
            val box = Geometry.minAreaRect(MatOfPoint2f(*c.toArray()))
            sides.add(minOf(box.size.width, box.size.height))
            val mo = Geometry.moments(c)
            spots.add(Point(mo.m10 / mo.m00, mo.m01 / mo.m00))
        }
        return spots to (sides.sorted().getOrNull(sides.size / 2) ?: 0.0)
    }

    /** Px per inch from the Block face's printed white square (6.75 in), or null if not in view. */
    private fun whiteSquarePpi(hsv: Mat): Double? {
        val w = Mat().also { Core.inRange(hsv, Scalar(0.0, 0.0, 165.0), Scalar(180.0, 70.0, 255.0), it) }
        Imgproc.morphologyEx(w, w, Imgproc.MORPH_OPEN, Mat.ones(5, 5, CvType.CV_8U))
        Imgproc.morphologyEx(w, w, Imgproc.MORPH_CLOSE, Mat.ones(41, 41, CvType.CV_8U)) // bridge the gaps + holes
        val contours = ArrayList<org.opencv.core.MatOfPoint>()
        Imgproc.findContours(w, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val minArea = 0.01 * hsv.rows() * hsv.cols()
        val box = contours.filter { Geometry.contourArea(it) > minArea }
            .map { Geometry.minAreaRect(MatOfPoint2f(*it.toArray())) }
            .filter { it.size.width / it.size.height in 0.8..1.25 }
            .maxByOrNull { it.size.area() } ?: return null
        return (box.size.width + box.size.height) / 2 / WHITE_SQUARE_IN
    }

    private companion object {
        const val TAPE_IN = 1.88 // painter's tape width, the factory edge
        const val WHITE_SQUARE_IN = 6.75 // Block face's printed square (four bricks, 3/8 in gaps)
        const val SCALE = 1.0
        const val REFINE_GAP = 60
        const val CENTRE_BAND = 12 // px either side of the shaft searched for its centre
        // The trail runs a little past the real entry (shaft shadow + blur): +8..+22 px (avg 0.24 in) on
        // 4 tape-measured arrows. Back off a bit less than that.
        const val ENTRY_BACKOFF_IN = 0.2
    }

}
