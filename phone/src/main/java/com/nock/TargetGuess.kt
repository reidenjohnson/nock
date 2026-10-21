package com.nock

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

/**
 * When you start marking a new target, Nock suggests what it is:
 *  - one you've marked before (a saved side seen again): its outline, size and bullseyes, mapped onto this photo;
 *  - a standard ring face (gold/red/blue rings): World Archery sizes, with the 10 ring as the bullseye;
 *  - by shape: an index card (3x5 or 4x6), a letter sheet, or a square like the Block faces you've measured.
 * It also finds the target's outline (the biggest clean four-sided shape near the middle) so the corners start in place.
 * Tested on his deer side: the found corners landed within a few pixels of the ones he placed by hand.
 */
object TargetGuess {

    /** One suggestion. [outline]/[bulls] in photo coords when known; size in inches. */
    class Suggestion(val name: String, val widthIn: Double, val heightIn: Double,
                     val outline: List<PointF>? = null, val bulls: List<MarkSide.Shape> = emptyList(), val face: TargetMemory.Face? = null)

    class Result(val outline: List<PointF>?, val suggestions: List<Suggestion>)

    /** [outlineHint] = an outline already found (a new target picked on the camera). */
    fun guess(photo: Bitmap, memory: TargetMemory, outlineHint: List<PointF>? = null): Result {
        val out = ArrayList<Suggestion>()
        // 1. A target you've marked before.
        // Only if it's at THIS spot: another target elsewhere in the photo isn't this one.
        runCatching { memory.recognizeMarked(photo) }.getOrNull()?.let { (f, outline, bulls) ->
            val c = Point(outline.sumOf { it.x.toDouble() } / 4, outline.sumOf { it.y.toDouble() } / 4)
            val here = outlineHint == null || TargetMemory.inside(c, outlineHint.map { Point(it.x.toDouble(), it.y.toDouble()) })
            if (here) out.add(Suggestion(f.name, f.widthIn, f.heightIn, outline, bulls, f))
        }
        val found = outlineHint ?: out.firstOrNull()?.outline ?: runCatching { findQuad(photo) }.getOrNull()
        // Snap the outline onto the paper's / target's real edges, so the suggestion starts as close as it can.
        val quad = found?.let { q -> runCatching {
            EdgeSnap(photo).snap(q.map { Point(it.x.toDouble(), it.y.toDouble()) }).map { PointF(it.x.toFloat(), it.y.toFloat()) }
        }.getOrNull() ?: q }
        if (quad != null) {
            // Ring faces printed on it (a single face or a 3-spot): a circle on each gold centre, sized to its gold.
            val rings = runCatching { goldRings(photo, quad) }.getOrNull().orEmpty()
            if (rings.size > 1) out.add(0, Suggestion("Paper target with ${rings.size} ring faces", 0.0, 0.0, quad, rings))
            // No gold (a blue/white 5-spot, a black-and-white face): ring faces found by their circles, any colours.
            if (rings.isEmpty()) runCatching { anyRings(photo, quad) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { r ->
                out.add(0, Suggestion(if (r.size > 1) "Paper target with ${r.size} ring faces" else "Ring face", 0.0, 0.0, quad, r))
            }
            // 2. A standard ring face: centred 10 ring as the bullseye (ring width = face width / 20 on WA 10-zone faces).
            // One ring face: the standard indoor 40 cm (you can type another size on the last step).
            if (rings.size <= 1 && runCatching { isRingFace(photo, quad) }.getOrDefault(false)) {
                val inches = 40 / 2.54
                out.add(Suggestion("Ring face, 40 cm", inches, inches, quad, listOf(tenRing(quad, inches))))
            }
            // 3. By shape (as seen straight on; close enough to suggest, you confirm).
            val w = (hypot((quad[1].x - quad[0].x).toDouble(), (quad[1].y - quad[0].y).toDouble()) + hypot((quad[2].x - quad[3].x).toDouble(), (quad[2].y - quad[3].y).toDouble())) / 2
            val h = (hypot((quad[3].x - quad[0].x).toDouble(), (quad[3].y - quad[0].y).toDouble()) + hypot((quad[2].x - quad[1].x).toDouble(), (quad[2].y - quad[1].y).toDouble())) / 2
            val r = maxOf(w, h) / minOf(w, h); val wide = w >= h
            fun sized(name: String, a: Double, b: Double) = Suggestion(name, if (wide) maxOf(a, b) else minOf(a, b), if (wide) minOf(a, b) else maxOf(a, b), quad)
            when {
                r in 1.55..1.8 -> out.add(sized("Index card, 3 × 5 in", 5.0, 3.0))
                r in 1.4..1.55 -> out.add(sized("Index card, 4 × 6 in", 6.0, 4.0))
                r in 1.22..1.36 -> out.add(sized("Letter sheet, 8.5 × 11 in", 11.0, 8.5))
            }
        }
        return Result(quad, out.distinctBy { it.name })
    }

    private const val MIN_TARGETNESS = 0.8

    private fun areaOf(q: List<PointF>): Double {
        var a = 0.0; for (i in q.indices) { val p = q[i]; val r = q[(i + 1) % q.size]; a += p.x.toDouble() * r.y - r.x.toDouble() * p.y }; return kotlin.math.abs(a) / 2
    }

    private fun fmt(v: Double) = "%.2f".format(v)

    /** The biggest convex four-sided outline near the middle of the photo, or null. */
    fun findQuad(photo: Bitmap, exclude: List<List<org.opencv.core.Point>> = emptyList()): List<PointF>? {
        val rgba = Mat(); Utils.bitmapToMat(photo, rgba)
        val s = 960.0 / maxOf(photo.width, photo.height)
        val small = Mat(); Imgproc.resize(rgba, small, Size(), s, s, Imgproc.INTER_AREA); rgba.release()
        val g = Mat(); Imgproc.cvtColor(small, g, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(g, g, Size(5.0, 5.0), 0.0)
        val w = g.cols().toDouble(); val h = g.rows().toDouble()
        // What makes a target, not a cabinet door or a curtain: printed colour, busy edges, strong contrast inside.
        val hsv = Mat(); Imgproc.cvtColor(small, hsv, Imgproc.COLOR_RGBA2RGB); Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)
        val colour = Mat(); Core.inRange(hsv, Scalar(0.0, 110.0, 90.0), Scalar(180.0, 255.0, 255.0), colour); hsv.release()
        val edges = Mat(); Imgproc.Canny(g, edges, 60.0, 160.0)
        fun targetness(pts: Array<Point>): Double {
            val cx = pts.sumOf { it.x } / 4; val cy = pts.sumOf { it.y } / 4
            val inner = pts.map { Point(cx + (it.x - cx) * 0.9, cy + (it.y - cy) * 0.9) } // skip the border itself
            val m = Mat.zeros(g.size(), org.opencv.core.CvType.CV_8U)
            Imgproc.fillPoly(m, listOf(MatOfPoint(*inner.toTypedArray())), Scalar(255.0))
            val n = Core.countNonZero(m).toDouble().coerceAtLeast(1.0)
            val t = Mat()
            Core.bitwise_and(colour, m, t); val sat = Core.countNonZero(t) / n
            Core.bitwise_and(edges, m, t); val ed = Core.countNonZero(t) / n
            val mean = org.opencv.core.MatOfDouble(); val sd = org.opencv.core.MatOfDouble(); Core.meanStdDev(g, mean, sd, m)
            m.release(); t.release()
            return 3 * sat + 6 * ed + sd.toArray()[0] / 60 // his kitchen: targets 1.36-1.40, curtain 0.20, cabinet doors 0.18-0.42
        }
        val cands = ArrayList<Triple<Double, Double, List<PointF>>>() // score, targetness, outline
        for ((lo, hi) in listOf(30.0 to 90.0, 50.0 to 150.0, 80.0 to 200.0)) {
            val e = Mat(); Imgproc.Canny(g, e, lo, hi); Imgproc.dilate(e, e, Mat.ones(3, 3, org.opencv.core.CvType.CV_8U))
            val cs = ArrayList<MatOfPoint>(); Imgproc.findContours(e, cs, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            for (c in cs) {
                val a = Geometry.contourArea(c)
                if (a < 0.02 * w * h || a > 0.9 * w * h) continue
                val c2 = MatOfPoint2f(*c.toArray())
                val ap = MatOfPoint2f(); Geometry.approxPolyDP(c2, ap, 0.02 * Geometry.arcLength(c2, true), true)
                val pts = ap.toArray(); if (pts.size != 4) continue
                if (!Geometry.isContourConvex(MatOfPoint(*pts))) continue
                val cx = pts.sumOf { it.x } / 4; val cy = pts.sumOf { it.y } / 4
                if (exclude.any { TargetMemory.inside(Point(cx / s, cy / s), it) }) continue // a target we already know
                // a known target inside it = the panel/room around it, not a new target
                if (exclude.any { k -> TargetMemory.inside(Point(k.sumOf { it.x } / k.size * s, k.sumOf { it.y } / k.size * s), pts.toList()) }) continue
                // cut off by the frame edge (2+ corners on the border): not a whole target, can't be marked
                if (pts.count { it.x < 0.02 * w || it.x > 0.98 * w || it.y < 0.02 * h || it.y > 0.98 * h } >= 2) continue
                val tn = targetness(pts); if (tn < MIN_TARGETNESS) continue // plain: a door, a curtain, a wall
                val score = a * tn * (1 - 0.8 * hypot(cx - w / 2, cy - h / 2) / hypot(w / 2, h / 2))
                cands.add(Triple(score, tn, order(pts.map { PointF((it.x / s).toFloat(), (it.y / s).toFloat()) })))
            }
            e.release()
        }
        g.release(); small.release(); colour.release(); edges.release()
        // The best one; but if a smaller candidate inside it looks just as much like a target, that's the real target
        // (the paper, not the cabinet panel it's taped to).
        var best = cands.maxByOrNull { it.first } ?: return null
        while (true) {
            val inner = cands.filter { c -> c !== best && c.second >= 0.9 * best.second && areaOf(c.third) < 0.85 * areaOf(best.third) &&
                TargetMemory.inside(Point(c.third.sumOf { it.x.toDouble() } / 4, c.third.sumOf { it.y.toDouble() } / 4), best.third.map { Point(it.x.toDouble(), it.y.toDouble()) }) }
                .maxByOrNull { it.first } ?: break
            best = inner
        }
        return best.third
    }

    /** The best four-sided outline around [p] (a spot you held on the camera), searching a region around it. */
    fun findQuadNear(photo: Bitmap, p: PointF): List<PointF>? {
        val r = (maxOf(photo.width, photo.height) * 0.3f).toInt()
        val x0 = (p.x - r).toInt().coerceIn(0, photo.width - 1); val y0 = (p.y - r).toInt().coerceIn(0, photo.height - 1)
        val x1 = (p.x + r).toInt().coerceIn(x0 + 1, photo.width); val y1 = (p.y + r).toInt().coerceIn(y0 + 1, photo.height)
        val crop = Bitmap.createBitmap(photo, x0, y0, x1 - x0, y1 - y0)
        return findQuad(crop)?.map { PointF(it.x + x0, it.y + y0) }
    }

    /** Corners clockwise from top-left. */
    private fun order(p: List<PointF>): List<PointF> {
        val tl = p.minBy { it.x + it.y }; val br = p.maxBy { it.x + it.y }
        val tr = p.minBy { it.y - it.x }; val bl = p.maxBy { it.y - it.x }
        return listOf(tl, tr, br, bl)
    }

    /**
     * Each ring face's bullseye on a paper target: the gold (10 + 9) blobs inside the outline, as circles fitted to them.
     * Tested on: not yet (his 3-spot paper is the first).
     */
    fun goldRings(photo: Bitmap, quad: List<PointF>): List<MarkSide.Shape> {
        val rgba = Mat(); Utils.bitmapToMat(photo, rgba)
        val hsv = Mat(); Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB); Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV); rgba.release()
        val mask = Mat.zeros(hsv.size(), org.opencv.core.CvType.CV_8U)
        Imgproc.fillPoly(mask, listOf(MatOfPoint(*quad.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())), Scalar(255.0))
        val gold = Mat(); Core.inRange(hsv, Scalar(18.0, 110.0, 120.0), Scalar(38.0, 255.0, 255.0), gold); Core.bitwise_and(gold, mask, gold)
        Imgproc.morphologyEx(gold, gold, Imgproc.MORPH_CLOSE, Mat.ones(9, 9, org.opencv.core.CvType.CV_8U))
        val cs = ArrayList<MatOfPoint>(); Imgproc.findContours(gold, cs, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val total = Core.countNonZero(mask).toDouble()
        hsv.release(); mask.release(); gold.release()
        return cs.filter { Geometry.contourArea(it) > 0.002 * total }.mapNotNull { c ->
            val c2 = MatOfPoint2f(*c.toArray())
            val centre = Point(); val r = FloatArray(1); Geometry.minEnclosingCircle(c2, centre, r)
            // round enough to be a ring (area vs its enclosing circle), not a gold logo
            if (Geometry.contourArea(c) < 0.55 * Math.PI * r[0] * r[0]) return@mapNotNull null
            // The gold is the 10 and 9 rings; the bullseye is the 10 ring = the inner half of the gold (Vegas / WA 3-spot:
            // X and the yellow 10 both score 10; https://www.worldarchery.sport/news/202354 , nfaausa.com target-archery-indoor-rounds).
            val ten = r[0] / 2
            MarkSide.Shape("circle", mutableListOf(PointF(centre.x.toFloat(), centre.y.toFloat()), PointF((centre.x + ten).toFloat(), centre.y.toFloat())))
        }
    }

    /**
     * Ring faces of any colours: round outlines inside the target grouped by a shared centre (2+ rings = a face). The
     * bullseye = the centre disc: out from the centre, the first ring where the colour clearly changes, skipping the
     * thin lines around the X. On his NFAA-style blue 5-spot (2026-10-08): all 5 faces, centres on the X's, the white
     * centre picked on each (colour change 120-223 there, <= 40 at the X lines).
     */
    fun anyRings(photo: Bitmap, quad: List<PointF>): List<MarkSide.Shape> {
        val rgba = Mat(); Utils.bitmapToMat(photo, rgba)
        val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB); rgba.release()
        val g = Mat(); Imgproc.cvtColor(rgb, g, Imgproc.COLOR_RGB2GRAY); Imgproc.GaussianBlur(g, g, Size(5.0, 5.0), 0.0)
        val inside = Mat.zeros(g.size(), org.opencv.core.CvType.CV_8U)
        Imgproc.fillPoly(inside, listOf(MatOfPoint(*quad.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())), Scalar(255.0))
        val areaQ = areaOf(quad)
        val circles = ArrayList<DoubleArray>()
        for ((lo, hi) in listOf(30.0 to 90.0, 60.0 to 160.0)) {
            val e = Mat(); Imgproc.Canny(g, e, lo, hi); Core.bitwise_and(e, inside, e)
            Imgproc.dilate(e, e, Mat.ones(3, 3, org.opencv.core.CvType.CV_8U))
            val cs = ArrayList<MatOfPoint>(); Imgproc.findContours(e, cs, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE); e.release()
            for (c in cs) {
                if (c.total() < 20) continue // point count (OpenCV 5 contours are 1 x N: rows() is 1)
                val a = Geometry.contourArea(c); if (a < 0.0005 * areaQ) continue
                val c2 = MatOfPoint2f(*c.toArray())
                val centre = Point(); val r = FloatArray(1); Geometry.minEnclosingCircle(c2, centre, r)
                val per = Geometry.arcLength(c2, true)
                if (a < 0.7 * Math.PI * r[0] * r[0] || 4 * Math.PI * a / (per * per + 1e-6) < 0.7) continue
                circles.add(doubleArrayOf(centre.x, centre.y, r[0].toDouble()))
            }
        }
        g.release(); inside.release()
        // group by centre (biggest first), keep distinct radii
        val faces = ArrayList<Pair<Point, MutableList<Double>>>()
        for (c in circles.sortedByDescending { it[2] }) {
            val f = faces.firstOrNull { hypot(it.first.x - c[0], it.first.y - c[1]) < 0.15 * it.second[0] }
            if (f == null) faces.add(Point(c[0], c[1]) to mutableListOf(c[2]))
            else if (f.second.all { kotlin.math.abs(c[2] - it) > 0.06 * f.second[0] }) f.second.add(c[2])
        }
        fun band(c: Point, r0: Double, r1: Double): DoubleArray {
            val m = Mat.zeros(rgb.size(), org.opencv.core.CvType.CV_8U)
            Imgproc.circle(m, c, r1.toInt(), Scalar(255.0), -1); Imgproc.circle(m, c, r0.toInt(), Scalar(0.0), -1)
            return Core.mean(rgb, m).`val`.also { m.release() }
        }
        val out = faces.filter { it.second.size >= 2 }.mapNotNull { (c, rs) ->
            val big = rs.max()
            val bull = rs.sorted().filter { it >= 0.15 * big }.firstOrNull { r ->
                val a = band(c, 0.8 * r, 0.95 * r); val b = band(c, 1.05 * r, 1.2 * r)
                kotlin.math.sqrt((0 until 3).sumOf { (a[it] - b[it]) * (a[it] - b[it]) }) > 60
            } ?: return@mapNotNull null
            MarkSide.Shape("circle", mutableListOf(PointF(c.x.toFloat(), c.y.toFloat()), PointF((c.x + bull).toFloat(), c.y.toFloat())))
        }
        rgb.release()
        return out
    }

    /**
     * Centres of ring faces anywhere in [photo] (any colour, face-on or at an angle): 2+ concentric ovals. Rings seen at
     * an angle are ellipses, so each closed edge is fitted with an ellipse (a circle-only check found 0 of his 5-spot's
     * 5 faces at ~50 deg off face-on; ellipses: 5 of 5 up to ~50 deg, 3 at 60 deg - tools/rings.py, 2026-10-08).
     */
    fun ringCentres(photo: Bitmap): List<Point> {
        val rgba = Mat(); Utils.bitmapToMat(photo, rgba)
        val g = Mat(); Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY); rgba.release(); Imgproc.GaussianBlur(g, g, Size(5.0, 5.0), 0.0)
        val minArea = 0.0005 * photo.width * photo.height
        val ovals = ArrayList<DoubleArray>() // x, y, size
        for ((lo, hi) in listOf(30.0 to 90.0, 60.0 to 160.0)) {
            val e = Mat(); Imgproc.Canny(g, e, lo, hi); Imgproc.dilate(e, e, Mat.ones(3, 3, org.opencv.core.CvType.CV_8U))
            val cs = ArrayList<MatOfPoint>(); Imgproc.findContours(e, cs, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE); e.release()
            for (c in cs) {
                if (c.total() < 20) continue
                val a = Geometry.contourArea(c); if (a < minArea) continue
                val el = Geometry.fitEllipse(MatOfPoint2f(*c.toArray()))
                val big = maxOf(el.size.width, el.size.height) / 2; val small = minOf(el.size.width, el.size.height) / 2
                if (small < 0.35 * big) continue // flatter than ~70 deg off face-on
                val fill = a / (Math.PI * big * small + 1e-6)
                if (fill < 0.85 || fill > 1.15) continue // a smooth closed oval, not a blob or a corner
                ovals.add(doubleArrayOf(el.center.x, el.center.y, kotlin.math.sqrt(big * small)))
            }
        }
        g.release()
        val faces = ArrayList<Pair<Point, MutableList<Double>>>()
        for (o in ovals.sortedByDescending { it[2] }) {
            val f = faces.firstOrNull { hypot(it.first.x - o[0], it.first.y - o[1]) < 0.15 * it.second[0] }
            if (f == null) faces.add(Point(o[0], o[1]) to mutableListOf(o[2]))
            else if (f.second.all { kotlin.math.abs(o[2] - it) > 0.06 * f.second[0] }) f.second.add(o[2])
        }
        return faces.filter { it.second.size >= 2 }.map { it.first }
    }

    /** Gold, red and blue all present inside the outline (a standard ring face's colours). */
    private fun isRingFace(photo: Bitmap, quad: List<PointF>): Boolean {
        val rgba = Mat(); Utils.bitmapToMat(photo, rgba)
        val hsv = Mat(); Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB); Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV); rgba.release()
        val mask = Mat.zeros(hsv.size(), org.opencv.core.CvType.CV_8U)
        Imgproc.fillPoly(mask, listOf(MatOfPoint(*quad.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())), Scalar(255.0))
        val total = Core.countNonZero(mask).toDouble().coerceAtLeast(1.0)
        fun frac(lo: Scalar, hi: Scalar): Double { val m = Mat(); Core.inRange(hsv, lo, hi, m); Core.bitwise_and(m, mask, m); return Core.countNonZero(m) / total }
        val gold = frac(Scalar(20.0, 120.0, 120.0), Scalar(35.0, 255.0, 255.0))
        val red = frac(Scalar(0.0, 120.0, 100.0), Scalar(8.0, 255.0, 255.0)) + frac(Scalar(170.0, 120.0, 100.0), Scalar(180.0, 255.0, 255.0))
        val blue = frac(Scalar(90.0, 120.0, 80.0), Scalar(110.0, 255.0, 255.0))
        hsv.release(); mask.release()
        return gold > 0.01 && red > 0.02 && blue > 0.03
    }

    /** The 10 ring of a WA face filling [quad]: centred, radius = face / 20, mapped into the photo. */
    private fun tenRing(quad: List<PointF>, faceIn: Double): MarkSide.Shape {
        val plane = Geometry.getPerspectiveTransform(MatOfPoint2f(Point(0.0, 0.0), Point(faceIn, 0.0), Point(faceIn, faceIn), Point(0.0, faceIn)),
            MatOfPoint2f(*quad.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()))
        val dst = MatOfPoint2f(); Core.perspectiveTransform(MatOfPoint2f(Point(faceIn / 2, faceIn / 2), Point(faceIn / 2 + faceIn / 20, faceIn / 2)), dst, plane)
        val (c, e) = dst.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) }
        return MarkSide.Shape("circle", mutableListOf(c, e))
    }
}
