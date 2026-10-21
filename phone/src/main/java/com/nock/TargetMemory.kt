package com.nock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features.BFMatcher
import org.opencv.features.ORB
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.hypot

/**
 * Remembers each side of the block: a reference photo and the spots you aim at on it.
 * Recognizes a side again at any zoom or angle by ORB features + a homography (on saved photos: the same
 * side matched with 1,234-1,507 inlier points, a different side with 5-8). The saved spots are mapped
 * onto the live photo, so hits are measured from the spots you really aim at: stray blue things in the
 * room don't count, and a spot covered by arrows is still known.
 */
class TargetMemory(private val dir: File) {

    /**
     * A remembered target. [spots] = aim points (bullseye centres) in the reference photo. A marked target also has
     * its [outline] (4 corners, clockwise from top-left), real size, and bullseye shapes, which give true inches
     * anywhere on its face from any angle.
     */
    class Face(
        val id: String, val name: String, val spots: List<Point>, val photo: File,
        val outline: List<Point>? = null, val widthIn: Double = 0.0, val heightIn: Double = 0.0,
        val bulls: List<Pair<String, List<Point>>> = emptyList(),
    ) { val marked get() = outline != null && widthIn > 0 && heightIn > 0 }

    /** Where an arrow hit a marked target: inches from the nearest bullseye centre (+right, +low), inside it or not. */
    class Measured(val rightIn: Double, val lowIn: Double, val inBull: Boolean, val bullPx: Point, val ppi: Double)

    private val orb by lazy { ORB.create(ORB_N) }
    private val matcher by lazy { BFMatcher.create(Core.NORM_HAMMING, false) }
    private val cache = HashMap<String, Pair<MatOfKeyPoint, Mat>>()
    private val subs = HashMap<String, Mat>() // 1 in 8 of each image's features, for the quick first pass
    private val orbDir = File(dir.parentFile, dir.name + "_orb") // features of every reference image, worked out once

    init { dir.mkdirs() }

    fun faces(): List<Face> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
        runCatching {
            val j = JSONObject(f.readText())
            fun pts(a: JSONArray) = (0 until a.length()).map { a.getJSONArray(it).let { p -> Point(p.getDouble(0), p.getDouble(1)) } }
            Face(j.getString("id"), j.getString("name"), pts(j.getJSONArray("spots")), File(dir, j.getString("id") + ".jpg"),
                j.optJSONArray("outline")?.let { pts(it) }, j.optDouble("width_in", 0.0), j.optDouble("height_in", 0.0),
                j.optJSONArray("bulls")?.let { b -> (0 until b.length()).map { b.getJSONObject(it).let { o -> o.getString("type") to pts(o.getJSONArray("pts")) } } } ?: emptyList())
        }.getOrNull()
    }.sortedBy { it.id }

    /** Save the side in this (upright, full-res) photo with these aim spots. Named Side A, B, ... */
    fun save(photo: Bitmap, spots: List<Point>): Face {
        val id = "side_" + System.currentTimeMillis()
        val name = nextName()
        File(dir, "$id.jpg").outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        File(dir, "$id.json").writeText(JSONObject().put("id", id).put("name", name)
            .put("spots", JSONArray(spots.map { JSONArray(listOf(it.x, it.y)) })).toString())
        return Face(id, name, spots, File(dir, "$id.jpg"))
    }

    /** Save a target you marked (MarkSide): outline, real size, bullseyes. Replaces [replace] if given (re-marking). */
    fun saveMarked(photo: Bitmap, r: MarkSide.Result, replace: Face? = null): Face {
        fun arr(ps: List<android.graphics.PointF>) = JSONArray(ps.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) })
        // Editing (same reference photo): update the marks in place; the name and every learned view stay.
        if (replace != null) {
            val jf = File(dir, "${replace.id}.json"); val j = JSONObject(jf.readText())
            val centres = r.bulls.map { Shapes.centre(Shapes.polygon(it.type, it.pts, r.outline)) }
            jf.writeText(j.put("spots", arr(centres)).put("outline", arr(r.outline))
                .put("width_in", r.widthIn.toDouble()).put("height_in", r.heightIn.toDouble())
                .put("bulls", JSONArray(r.bulls.map { JSONObject().put("type", it.type).put("pts", arr(it.pts)) })).toString())
            cache.keys.removeAll { it.startsWith(replace.id) } // the matching area follows the new outline
            return faces().first { it.id == replace.id }
        }
        val id = "side_" + System.currentTimeMillis()
        val name = nextName()
        File(dir, "$id.jpg").outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val centres = r.bulls.map { Shapes.centre(Shapes.polygon(it.type, it.pts, r.outline)) }
        File(dir, "$id.json").writeText(JSONObject().put("id", id).put("name", name)
            .put("spots", arr(centres)).put("outline", arr(r.outline))
            .put("width_in", r.widthIn.toDouble()).put("height_in", r.heightIn.toDouble())
            .put("bulls", JSONArray(r.bulls.map { JSONObject().put("type", it.type).put("pts", arr(it.pts)) })).toString())
        return faces().first { it.id == id }
    }

    /**
     * Measure a hit on a marked target. [entry] is in [photo]; the photo is matched to the reference, the entry taken
     * back to the reference, then onto the flat face in inches via the outline + real size.
     */
    fun measure(face: Face, photo: Bitmap, entry: Point): Measured? {
        if (!face.marked) return null
        val h = Look(photo).use { placed(face, it) }?.first ?: return null // reference -> photo, full size, aligned
        val hInv = h.inv()
        fun toRef(p: Point): Point = mapPts(hInv, listOf(p))[0]
        fun toPhoto(p: Point): Point = mapPts(h, listOf(p))[0]
        val plane = Geometry.getPerspectiveTransform(MatOfPoint2f(*face.outline!!.toTypedArray()),
            MatOfPoint2f(Point(0.0, 0.0), Point(face.widthIn, 0.0), Point(face.widthIn, face.heightIn), Point(0.0, face.heightIn)))
        val e = mapPts(plane, listOf(toRef(entry)))[0]
        // nearest bullseye centre on the face; inside = inside its shape
        var best: Triple<Point, Boolean, Point>? = null; var bestD = Double.MAX_VALUE
        for ((type, pts) in face.bulls) {
            val poly = Shapes.polygon(type, pts.map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }, refOutline(face))
            val cRef = Shapes.centre(poly).let { Point(it.x.toDouble(), it.y.toDouble()) }
            val c = mapPts(plane, listOf(cRef))[0]
            val dist = hypot(e.x - c.x, e.y - c.y)
            if (dist < bestD) {
                bestD = dist
                val inside = Shapes.contains(type, pts.map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }, toRef(entry).let { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }, refOutline(face))
                best = Triple(c, inside, cRef)
            }
        }
        val (c, inside, cRef) = best ?: return null
        // local scale at the bullseye: photo pixels per inch on the face
        val planeInv = plane.inv()
        val oneIn = mapPts(planeInv, listOf(c, Point(c.x + 1, c.y))).map { toPhoto(it) }
        val ppi = hypot(oneIn[1].x - oneIn[0].x, oneIn[1].y - oneIn[0].y)
        return Measured(e.x - c.x, e.y - c.y, inside, toPhoto(cRef), ppi)
    }

    /** The outline mapped into a photo (for the target box), or null. */
    fun outlineIn(face: Face, photo: Bitmap): List<Point>? {
        val o = face.outline ?: return null
        val h = Look(photo).use { placed(face, it) }?.first ?: return null
        return mapPts(h, o)
    }

    /**
     * Reference photo -> this photo at full size, with a marked target's outline snapped onto its real edges in this
     * photo (the correction is applied to everything else on it: spots, bullseyes, measuring).
     */
    private fun placed(face: Face, look: Look): Pair<Mat, Int>? =
        // a miss keeps the last place (carried by tracking): it's where a partly-off-screen target still is
        (placedNow(face, look) ?: rescue(face, look))?.also { last[face.id] = Triple(look.photo.width, look.photo.height, it.first) }

    /** Where each target was last placed (photo size + reference -> photo): the first guess for the next look. */
    private val last = java.util.concurrent.ConcurrentHashMap<String, Triple<Int, Int, Mat>>()

    /**
     * The camera moved or zoomed since the last look, by [move] (that look's frame -> now, from the tracker): carry the
     * target's last place along, so the next look checks where it is now, not where it was (a 5-spot was missed by
     * the re-look after a zoom and vanished until a later look, 2026-10-08).
     */
    /**
     * Fast snap after the phone moves: the targets already on screen ([ids]), each fine-aligned from where tracking
     * carried it and kept only if it still looks like its marked photo. No feature matching, so it lands in a fraction
     * of a full look; the full look follows for anything new.
     */
    fun quick(photo: Bitmap, ids: Collection<String>): List<Seen> = Look(photo).use { look ->
        val faces = faces()
        ids.mapNotNull { id -> runCatching {
            val f = faces.firstOrNull { it.id == id } ?: return@runCatching null
            val (w, h, m) = last[id] ?: return@runCatching null
            if (w != photo.width || h != photo.height) return@runCatching null
            val pic = refPic(f) ?: return@runCatching null
            val fine = pic.align(look, m) ?: return@runCatching null
            if (pic.score(look, fine) < CONFIRM) return@runCatching null
            last[id] = Triple(w, h, fine)
            val area = targetArea(f) ?: return@runCatching null
            val outline = mapPts(fine, area)
            if (!sane(f, outline, photo.width, photo.height)) return@runCatching null
            Seen(f, outline, mapPts(fine, f.spots), f.bulls.map { (t, ps) -> t to mapPts(fine, ps) }, MIN_INLIERS)
        }.getOrNull() }
    }

    /** [faceId] where it was last placed in a photo this size (the phone hasn't moved since), with no checks. */
    fun atLast(faceId: String, photo: Bitmap): Seen? {
        val (w, h, m) = last[faceId] ?: return null
        if (w != photo.width || h != photo.height) return null
        val f = faces().firstOrNull { it.id == faceId } ?: return null
        val area = targetArea(f) ?: return null
        return Seen(f, mapPts(m, area), mapPts(m, f.spots), f.bulls.map { (t, ps) -> t to mapPts(m, ps) }, 0)
    }

    /** [faceId] at its last (carried) place if it's partly outside a [w] x [h] frame but at least a quarter inside. */
    fun partlyInView(faceId: String, photo: Bitmap): Seen? = Look(photo).use { look -> partlyInView(faceId, look) }

    private fun partlyInView(faceId: String, look: Look): Seen? {
        val w = look.photo.width; val h = look.photo.height
        val (lw, lh, m) = last[faceId] ?: return null
        if (lw != w || lh != h) return null
        val f = faces().firstOrNull { it.id == faceId } ?: return null
        val area = targetArea(f) ?: return null
        val q = mapPts(m, area)
        val outside = q.any { it.x < 0 || it.y < 0 || it.x > w || it.y > h }
        if (!outside) return null
        // how much of it is on screen: sample a grid over its outline
        val xs = (0..9).flatMap { i -> (0..9).map { j -> Point(q.minOf { it.x } + (q.maxOf { it.x } - q.minOf { it.x }) * (i + 0.5) / 10, q.minOf { it.y } + (q.maxOf { it.y } - q.minOf { it.y }) * (j + 0.5) / 10) } }
            .filter { inside(it, q) }
        if (xs.isEmpty() || xs.count { it.x in 0.0..w.toDouble() && it.y in 0.0..h.toDouble() } < 0.25 * xs.size) return null
        // the part on screen must still look like the target (tracking can drift after a big move: 2026-10-08 the
        // 5-spot was kept over the counter after he swung the phone away)
        val sc = look.let { lk -> refPic(f)?.scoreVisible(lk, m) } ?: return null
        android.util.Log.d("NockShots", "${f.name}: partly on screen, visible part matches %.2f".format(sc) + if (sc >= PARTIAL_CONFIRM) " - kept" else " - dropped")
        if (sc < PARTIAL_CONFIRM) return null
        return Seen(f, q, mapPts(m, f.spots), f.bulls.map { (t, ps) -> t to mapPts(m, ps) }, 0)
    }

    /** Reference photo -> photo for [faceId]'s last place (a copy), or null. */
    fun placeOf(faceId: String): Mat? = last[faceId]?.third?.clone()

    /** Put [faceId]'s last place at [h] (reference photo -> a [w] x [h] photo): where the gyro says it is now. */
    fun setLast(faceId: String, w: Int, hgt: Int, h: Mat) { last[faceId] = Triple(w, hgt, h) }

    fun carry(faceId: String, move: Mat) {
        val (w, h, m) = last[faceId] ?: return
        val out = Mat(); Core.gemm(move, m, 1.0, Mat(), 0.0, out)
        last[faceId] = Triple(w, h, out)
    }

    private fun placedNow(face: Face, look: Look): Pair<Mat, Int>? {
        val (half, n) = homography(face, look.kp, look.d) ?: return null
        val full = toFull(half)
        // Fine-align on the marked photo itself (a match through a learned view carries that view's own error, which
        // can push the lines off a small paper's edges): kept only if the target then looks at least as much like it.
        runCatching { refPic(face)?.let { pic ->
            val before = pic.score(look, full)
            val fine = pic.align(look, full)
            if (fine != null && pic.score(look, fine) >= before - 0.01) return fine to n
        } }
        val o = face.outline ?: return full to n
        val q = mapPts(full, o)
        val snapped = runCatching { look.snapper.snap(q) }.getOrNull() ?: return full to n
        // Only trust a small correction (a few % of the target); anything bigger means a wrong edge was picked.
        val size = hypot(q[1].x - q[0].x, q[1].y - q[0].y)
        if (q.indices.any { hypot(snapped[it].x - q[it].x, snapped[it].y - q[it].y) > 0.02 * size }) return full to n // small nudges only
        val fix = Geometry.getPerspectiveTransform(MatOfPoint2f(*q.toTypedArray()), MatOfPoint2f(*snapped.toTypedArray()))
        val fixed = Mat(); Core.gemm(fix, full, 1.0, Mat(), 0.0, fixed)
        return fixed to n
    }

    private fun toFull(half: Mat): Mat {
        val s = Mat.eye(3, 3, CvType.CV_64F).also { it.put(0, 0, SCALE); it.put(1, 1, SCALE) }
        val sInv = Mat.eye(3, 3, CvType.CV_64F).also { it.put(0, 0, 1 / SCALE); it.put(1, 1, 1 / SCALE) }
        val h64 = Mat(); half.convertTo(h64, CvType.CV_64F)
        val tmp = Mat(); Core.gemm(h64, s, 1.0, Mat(), 0.0, tmp)
        val full = Mat(); Core.gemm(sInv, tmp, 1.0, Mat(), 0.0, full)
        return full
    }

    /**
     * For targets the feature match can't hold on to (a printed ring face has few sharp corners, and a 3-spot has
     * three of each, so its matches look ambiguous): propose placements from a weak feature match (15+ points) and
     * from the gold centres in view paired with the target's own, fine-align each, and keep the best only if the
     * flattened target looks like the marked photo (picture match >= [CONFIRM]). On his saved photos (tools/recog.py):
     * his 3-spot found at 30-100% size, tilted and darker, outline within 0.15% of its width; other photos <= 0.49.
     */
    private fun rescue(face: Face, look: Look): Pair<Mat, Int>? = runCatching {
        val pic = refPic(face) ?: return null
        val props = ArrayList<Mat>()
        // where it was last time (a phone on a tripod barely moves), then a weak feature match, then the gold centres
        last[face.id]?.let { (w, h, m) -> if (w == look.photo.width && h == look.photo.height) props.add(m) }
        match(face.id, face.photo, targetArea(face), look.kp, look.d, 15, 15)?.let { props.add(toFull(it.first)) }
        props.addAll(pic.goldProposals(look))
        var best: Pair<Mat, Double>? = null
        // Every placement at all 4 turns (his 3-spot was once taken 30 deg turned, its bullseyes on the wrong rings), scored;
        // only the 2 best (0.3+) get the slow fine alignment.
        val pre = props.flatMap { pic.turns(it) }.map { it to pic.score(look, it) }.filter { it.second >= 0.3 }.sortedByDescending { it.second }
        for (h0 in pre.take(2).map { it.first }) {
            val h = pic.align(look, h0) ?: h0
            val sc = pic.score(look, h)
            if (best == null || sc > best.second) best = h to sc
        }
        val b = best ?: return null
        judged(face, b.second)
        android.util.Log.d("NockShots", "${face.name}: picture match %.2f".format(b.second) + if (b.second >= CONFIRM) " - found" else " - not it")
        if (b.second >= CONFIRM) b.first to MIN_INLIERS else null
    }.getOrNull()

    /** [face] placed on an outline found in view ([quad], any corner order): the best of its 4 turns, if it's the picture. */
    private fun byOutline(face: Face, look: Look, quad: List<Point>): Mat? = runCatching {
        val pic = refPic(face) ?: return null
        val ref = MatOfPoint2f(*pic.quad.toTypedArray())
        var best: Pair<Mat, Double>? = null
        for (k in 0 until 4) {
            val turned = (0 until 4).map { quad[(it + k) % 4] }
            val h0 = Geometry.getPerspectiveTransform(ref, MatOfPoint2f(*turned.toTypedArray()))
            if (pic.score(look, h0) < 0.3) continue
            val h = pic.align(look, h0) ?: h0
            val sc = pic.score(look, h)
            if (best == null || sc > best.second) best = h to sc
        }
        val b = best ?: return null
        judged(face, b.second)
        android.util.Log.d("NockShots", "${face.name}: on the outline found, picture match %.2f".format(b.second) + if (b.second >= CONFIRM) " - found" else " - not it")
        if (b.second < CONFIRM) return null
        last[face.id] = Triple(look.photo.width, look.photo.height, b.first) // next look starts here
        b.first
    }.getOrNull()

    /**
     * [face] placed by its round bullseyes: each set of 3 (or 2) of its bullseye centres paired with ring centres found in
     * view, kept when every one of its centres lands on a ring, then fine-aligned and confirmed by picture match.
     */
    private fun byRings(face: Face, look: Look, taken: List<List<Point>> = emptyList()): Mat? = runCatching {
        val pic = refPic(face) ?: return null
        val live = look.rings.filter { r -> taken.none { inside(r, it) } }.takeIf { it.size >= 2 } ?: return null
        val ref = face.bulls.filter { it.first == "circle" }.map { it.second[0] }
        val r0 = face.bulls.first { it.first == "circle" }.second.let { hypot(it[1].x - it[0].x, it[1].y - it[0].y) }
        fun aff(a: DoubleArray) = Mat(3, 3, CvType.CV_64F).also { it.put(0, 0, a[0], a[1], a[2], a[3], a[4], a[5], 0.0, 0.0, 1.0) }
        val hyps = ArrayList<Mat>()
        val n = live.size.coerceAtMost(8)
        if (ref.size >= 3) {
            // any 3 of its bullseyes (at a steep angle the ring finder can miss some) onto any 3 rings in view
            val triples = ArrayList<Triple<Int, Int, Int>>()
            for (a in ref.indices) for (b in a + 1 until ref.size) for (c in b + 1 until ref.size) triples.add(Triple(a, b, c))
            for ((ra, rb, rc) in triples.take(10)) {
                val src = MatOfPoint2f(ref[ra], ref[rb], ref[rc])
                for (i in 0 until n) for (j in 0 until n) for (k in 0 until n) {
                    if (i == j || j == k || i == k) continue
                    val a = Geometry.getAffineTransform(src, MatOfPoint2f(live[i], live[j], live[k]))
                    val v = DoubleArray(6); a.get(0, 0, v); hyps.add(aff(v))
                }
            }
        } else for (i in 0 until n) for (j in 0 until n) {
            if (i == j) continue
            val p = ref[0]; val q = ref[1]
            val dx = q.x - p.x; val dy = q.y - p.y; val ex = live[j].x - live[i].x; val ey = live[j].y - live[i].y
            val n2 = dx * dx + dy * dy; if (n2 == 0.0) continue
            val c = (dx * ex + dy * ey) / n2; val sn = (dx * ey - dy * ex) / n2
            hyps.add(aff(doubleArrayOf(c, -sn, live[i].x - (c * p.x - sn * p.y), sn, c, live[i].y - (sn * p.x + c * p.y))))
        }
        // Most of its bullseye centres land on a ring in view (within half a bullseye; at a steep angle some rings
        // aren't found), and the placement isn't wildly skewed (60 deg off face-on squashes it 2 : 1).
        val need = if (ref.size <= 2) ref.size else maxOf(3, kotlin.math.ceil(0.6 * ref.size).toInt())
        val fits = hyps.filter { h ->
            val sx = hypot(h.get(0, 0)[0], h.get(1, 0)[0]); val sy = hypot(h.get(0, 1)[0], h.get(1, 1)[0])
            if (maxOf(sx, sy) > 2.5 * minOf(sx, sy)) return@filter false
            val k = kotlin.math.sqrt(sx * sy)
            mapPts(h, ref).count { p -> live.any { hypot(it.x - p.x, it.y - p.y) < 0.5 * r0 * k } } >= need
        }
        var best: Pair<Mat, Double>? = null
        // Fine-align only placements that already look like it: on his kitchen frame (2026-10-08) the 5-spot's were
        // 0.91-0.96 before aligning, the 3-spot's (wrong paper) 0.40 at best; aligning is the slow part (~0.5 s each).
        // the best few placements, each at its 4 turns (a ring face lines up on its own rings turned too)
        val top = fits.map { it to pic.score(look, it) }.sortedByDescending { it.second }.take(3).map { it.first }
        val pre = top.flatMap { pic.turns(it) }.map { it to pic.score(look, it) }.filter { it.second >= 0.6 }.sortedByDescending { it.second }
        for (h0 in pre.take(2).map { it.first }) {
            val h = pic.align(look, h0) ?: h0
            val sc = pic.score(look, h)
            if (best == null || sc > best.second) best = h to sc
        }
        val b = best ?: return null
        judged(face, b.second)
        android.util.Log.d("NockShots", "${face.name}: by its rings, picture match %.2f".format(b.second) + if (b.second >= CONFIRM) " - found" else " - not it")
        if (b.second < CONFIRM) return null
        last[face.id] = Triple(look.photo.width, look.photo.height, b.first)
        b.first
    }.getOrNull()

    /** One photo being looked at: features, a gray copy and gold discs, each worked out once and shared by every target. */
    private inner class Look(val photo: Bitmap) : AutoCloseable {
        private val f by lazy { features(photo) }
        val kp get() = f.first
        val d get() = f.second
        private var g: Mat? = null
        val gray: Mat get() = g ?: Mat().also { out -> val rgba = Mat(); Utils.bitmapToMat(photo, rgba); Imgproc.cvtColor(rgba, out, Imgproc.COLOR_RGBA2GRAY); rgba.release(); g = out }
        val gold: List<DoubleArray> by lazy { goldDiscs(photo, null) }
        val snapper by lazy { EdgeSnap(photo) }
        /** Ring-face centres anywhere in view (any colour, face-on or at an angle), full-size coords. Found at half size. */
        val rings: List<Point> by lazy {
            runCatching {
                val half = Bitmap.createScaledBitmap(photo, photo.width / 2, photo.height / 2, true)
                TargetGuess.ringCentres(half).also { half.recycle() }.map { Point(2 * it.x, 2 * it.y) }
            }.onFailure { android.util.Log.d("NockShots", "ring finder failed: $it") }.getOrDefault(emptyList())
        }
        override fun close() { g?.release() }
    }

    /** A target's marked photo prepared for fine alignment and picture checks (made once per outline). */
    private inner class RefPic(val quad: List<Point>, gray: Mat, val discs: List<DoubleArray>) {
        val norm: Mat      // full photo -> NORM-size crop around the target
        val refN: Mat      // that crop (CV_32F)
        val mask: Mat      // the target inside it, edges trimmed
        val flatRef: Mat   // the target flattened for picture checks
        val flatRaw: Mat   // the same before normalising (for checks over part of it)
        val size: Size
        init {
            val x0 = quad.minOf { it.x }; val x1 = quad.maxOf { it.x }; val y0 = quad.minOf { it.y }; val y1 = quad.maxOf { it.y }
            val m = 0.1 * maxOf(x1 - x0, y1 - y0)
            val k = NORM / maxOf(x1 - x0 + 2 * m, y1 - y0 + 2 * m)
            norm = Mat(3, 3, CvType.CV_64F).also { it.put(0, 0, k, 0.0, -k * (x0 - m), 0.0, k, -k * (y0 - m), 0.0, 0.0, 1.0) }
            size = Size(Math.round((x1 - x0 + 2 * m) * k).toDouble(), Math.round((y1 - y0 + 2 * m) * k).toDouble())
            val r = Mat(); Imgproc.warpPerspective(gray, r, norm, size, Imgproc.INTER_AREA)
            refN = Mat(); r.convertTo(refN, CvType.CV_32F); r.release()
            mask = Mat.zeros(refN.size(), CvType.CV_8U)
            Imgproc.fillPoly(mask, listOf(org.opencv.core.MatOfPoint(*mapPts(norm, quad).toTypedArray())), org.opencv.core.Scalar(255.0))
            Imgproc.erode(mask, mask, Mat.ones(9, 9, CvType.CV_8U))
            flatRef = flat(gray, quad)
            flatRaw = Mat().also { out -> val t = TPL.toDouble(); val kk = (TPL * 0.06).toInt()
                val mm = Geometry.getPerspectiveTransform(MatOfPoint2f(*quad.toTypedArray()), MatOfPoint2f(Point(0.0, 0.0), Point(t, 0.0), Point(t, t), Point(0.0, t)))
                val f = Mat(); Imgproc.warpPerspective(gray, f, mm, Size(t, t), Imgproc.INTER_AREA)
                f.submat(kk, TPL - kk, kk, TPL - kk).convertTo(out, CvType.CV_32F); f.release()
                Imgproc.GaussianBlur(out, out, Size(0.0, 0.0), 1.5) }
        }

        /** [h] and the same placement with the target turned 90, 180 and 270 degrees on its own outline. */
        fun turns(h: Mat): List<Mat> = listOf(h) + (1..3).map { k ->
            val t = Geometry.getPerspectiveTransform(MatOfPoint2f(*quad.toTypedArray()), MatOfPoint2f(*(0 until 4).map { quad[(it + k) % 4] }.toTypedArray()))
            Mat().also { Core.gemm(h, t, 1.0, Mat(), 0.0, it) }
        }

        /** Like [score], over only the part of the target inside the photo (a target partly off screen). */
        fun scoreVisible(look: Look, h: Mat): Double {
            val t = TPL.toDouble(); val k = (TPL * 0.06).toInt()
            val q = mapPts(h, quad)
            val m = Geometry.getPerspectiveTransform(MatOfPoint2f(*q.toTypedArray()), MatOfPoint2f(Point(0.0, 0.0), Point(t, 0.0), Point(t, t), Point(0.0, t)))
            val f = Mat(); Imgproc.warpPerspective(look.gray, f, m, Size(t, t), Imgproc.INTER_AREA)
            val ones = Mat(look.gray.size(), CvType.CV_8U, org.opencv.core.Scalar(255.0))
            val valid = Mat(); Imgproc.warpPerspective(ones, valid, m, Size(t, t), Imgproc.INTER_NEAREST); ones.release()
            val live = Mat(); f.submat(k, TPL - k, k, TPL - k).convertTo(live, CvType.CV_32F); f.release()
            Imgproc.GaussianBlur(live, live, Size(0.0, 0.0), 1.5)
            val mask = Mat(); Imgproc.erode(valid.submat(k, TPL - k, k, TPL - k), mask, Mat.ones(5, 5, CvType.CV_8U)); valid.release()
            if (Core.countNonZero(mask) < 0.25 * mask.total()) return -1.0
            // compare inside the mask only: both sides zero-mean, unit-variance there
            fun norm(a: Mat): Mat { val mean = org.opencv.core.MatOfDouble(); val sd = org.opencv.core.MatOfDouble(); Core.meanStdDev(a, mean, sd, mask)
                val o = Mat(); Core.subtract(a, org.opencv.core.Scalar(mean.toArray()[0]), o); Core.multiply(o, org.opencv.core.Scalar(1.0 / (sd.toArray()[0] + 1e-6)), o); return o }
            val a = norm(flatRaw); val b = norm(live); live.release()
            val p = Mat(); Core.multiply(a, b, p)
            return Core.mean(p, mask).`val`[0].also { p.release(); a.release(); b.release(); mask.release() }
        }

        /** How much the target placed by [h] (reference -> photo, full size) looks like the marked photo: -1..1. */
        fun score(look: Look, h: Mat): Double = flat(look.gray, mapPts(h, quad)).let { f -> ncc(flatRef, f).also { f.release() } }

        /** ECC: align the live target (flattened through [h]) to the marked photo; works on smooth rings too. */
        fun align(look: Look, h: Mat): Mat? {
            val w = Mat(); Core.gemm(norm, h.inv(), 1.0, Mat(), 0.0, w) // photo -> crop
            val l = Mat(); Imgproc.warpPerspective(look.gray, l, w, size, Imgproc.INTER_AREA)
            val liveN = Mat(); l.convertTo(liveN, CvType.CV_32F); l.release()
            val warp = Mat.eye(3, 3, CvType.CV_32F)
            val ok = runCatching { org.opencv.video.Video.findTransformECC(refN, liveN, warp, org.opencv.video.Video.MOTION_HOMOGRAPHY,
                org.opencv.core.TermCriteria(org.opencv.core.TermCriteria.EPS + org.opencv.core.TermCriteria.COUNT, 60, 1e-5), mask, 5) }.isSuccess
            liveN.release()
            if (!ok) return null
            val w64 = Mat(); warp.convertTo(w64, CvType.CV_64F)
            // reference -> photo = inv(w) * warp * norm
            val t = Mat(); Core.gemm(w64, norm, 1.0, Mat(), 0.0, t)
            val out = Mat(); Core.gemm(w.inv(), t, 1.0, Mat(), 0.0, out)
            return out
        }

        /** Placements from pairing this target's gold centres with gold discs in view (best 3 by picture match). */
        fun goldProposals(look: Look): List<Mat> {
            val lb = look.gold
            if (discs.isEmpty() || lb.isEmpty()) return emptyList()
            val hyps = ArrayList<Mat>()
            fun aff(a: DoubleArray) = Mat(3, 3, CvType.CV_64F).also { it.put(0, 0, a[0], a[1], a[2], a[3], a[4], a[5], 0.0, 0.0, 1.0) }
            when {
                discs.size >= 3 -> {
                    val src = MatOfPoint2f(*discs.take(3).map { Point(it[0], it[1]) }.toTypedArray())
                    for (i in lb.indices) for (j in lb.indices) for (k in lb.indices) {
                        if (i == j || j == k || i == k) continue
                        val a = Geometry.getAffineTransform(src, MatOfPoint2f(Point(lb[i][0], lb[i][1]), Point(lb[j][0], lb[j][1]), Point(lb[k][0], lb[k][1])))
                        val v = DoubleArray(6); a.get(0, 0, v); hyps.add(aff(v))
                    }
                }
                discs.size == 2 -> for (i in lb.indices) for (j in lb.indices) {
                    if (i == j) continue
                    // similarity: the two centres onto two discs
                    val p = discs[0]; val q = discs[1]
                    val dx = q[0] - p[0]; val dy = q[1] - p[1]; val ex = lb[j][0] - lb[i][0]; val ey = lb[j][1] - lb[i][1]
                    val n2 = dx * dx + dy * dy; if (n2 == 0.0) continue
                    val c = (dx * ex + dy * ey) / n2; val sn = (dx * ey - dy * ex) / n2
                    hyps.add(aff(doubleArrayOf(c, -sn, lb[i][0] - (c * p[0] - sn * p[1]), sn, c, lb[i][1] - (sn * p[0] + c * p[1]))))
                }
                else -> { val x = discs[0][0]; val y = discs[0][1]; val r = discs[0][2]
                    for (b in lb) { val k = b[2] / r; hyps.add(aff(doubleArrayOf(k, 0.0, b[0] - k * x, 0.0, k, b[1] - k * y))) } }
            }
            val maxW = 4.0 * maxOf(look.photo.width, look.photo.height)
            return hyps.mapNotNull { h ->
                val q = mapPts(h, quad); val w = hypot(q[1].x - q[0].x, q[1].y - q[0].y)
                if (w < 40 || w > maxW) null else h to score(look, h)
            }.sortedByDescending { it.second }.take(3).map { it.first }
        }
    }

    private val refPics = HashMap<String, RefPic?>()
    private fun refPic(face: Face): RefPic? {
        val quad = targetArea(face) ?: return null
        val key = face.id + "/" + quad.joinToString { "%.1f,%.1f".format(it.x, it.y) }
        return synchronized(refPics) { refPics.getOrPut(key) {
            runCatching {
                val bmp = BitmapFactory.decodeFile(face.photo.path) ?: return@runCatching null
                val g = Mat(); val rgba = Mat(); Utils.bitmapToMat(bmp, rgba); Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY); rgba.release()
                RefPic(quad, g, goldDiscs(bmp, quad)).also { g.release(); bmp.recycle() }
            }.getOrNull()
        } }
    }

    /** The target flattened to a small square (edges trimmed, blurred, zero-mean unit-variance) for picture checks. */
    private fun flat(gray: Mat, quad: List<Point>): Mat {
        val t = TPL.toDouble()
        val m = Geometry.getPerspectiveTransform(MatOfPoint2f(*quad.toTypedArray()), MatOfPoint2f(Point(0.0, 0.0), Point(t, 0.0), Point(t, t), Point(0.0, t)))
        val f = Mat(); Imgproc.warpPerspective(gray, f, m, Size(t, t), Imgproc.INTER_AREA)
        val k = (TPL * 0.06).toInt()
        val c = Mat(); f.submat(k, TPL - k, k, TPL - k).convertTo(c, CvType.CV_32F); f.release()
        Imgproc.GaussianBlur(c, c, Size(0.0, 0.0), 1.5)
        val mean = org.opencv.core.MatOfDouble(); val sd = org.opencv.core.MatOfDouble(); Core.meanStdDev(c, mean, sd)
        Core.subtract(c, org.opencv.core.Scalar(mean.toArray()[0]), c); Core.multiply(c, org.opencv.core.Scalar(1.0 / (sd.toArray()[0] + 1e-6)), c)
        return c
    }

    private fun ncc(a: Mat, b: Mat): Double { val p = Mat(); Core.multiply(a, b, p); return Core.mean(p).`val`[0].also { p.release() } }

    /** Saturated gold discs (a ring face's 10/9 rings): (x, y, radius) in full-res photo coords, biggest first, at most 8. */
    private fun goldDiscs(photo: Bitmap, quad: List<Point>?): List<DoubleArray> {
        val rgba = Mat(); Utils.bitmapToMat(photo, rgba)
        val hsv = Mat(); Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB); Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV); rgba.release()
        val g = Mat(); Core.inRange(hsv, org.opencv.core.Scalar(18.0, 100.0, 60.0), org.opencv.core.Scalar(38.0, 255.0, 255.0), g); hsv.release()
        if (quad != null) { val m = Mat.zeros(g.size(), CvType.CV_8U); Imgproc.fillPoly(m, listOf(org.opencv.core.MatOfPoint(*quad.toTypedArray())), org.opencv.core.Scalar(255.0)); Core.bitwise_and(g, m, g); m.release() }
        Imgproc.morphologyEx(g, g, Imgproc.MORPH_CLOSE, Mat.ones(5, 5, CvType.CV_8U))
        val cs = ArrayList<org.opencv.core.MatOfPoint>(); Imgproc.findContours(g, cs, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE); g.release()
        return cs.mapNotNull { c ->
            val a = Geometry.contourArea(c); if (a < 40) return@mapNotNull null
            val centre = Point(); val r = FloatArray(1); Geometry.minEnclosingCircle(MatOfPoint2f(*c.toArray()), centre, r)
            if (a < 0.55 * Math.PI * r[0] * r[0]) null else doubleArrayOf(centre.x, centre.y, r[0].toDouble())
        }.sortedByDescending { it[2] }.take(8)
    }

    private fun mapPts(h: Mat, pts: List<Point>): List<Point> {
        val dst = MatOfPoint2f(); Core.perspectiveTransform(MatOfPoint2f(*pts.toTypedArray()), dst, h); return dst.toArray().toList()
    }

    /** Load every target's photos and features now (in the background), so the first look doesn't wait for them. */
    fun warm() {
        for (f in faces()) runCatching {
            refPic(f)
            val a = targetArea(f)
            synchronized(cache) { cache.getOrPut(f.id + "@" + a.hashCode()) { stored(f.photo, a) ?: return@runCatching } }
            for ((file, hMainToView) in views(f)) {
                val va = a?.let { mapPts(hMainToView, it.map { p -> Point(p.x * SCALE, p.y * SCALE) }).map { p -> Point(p.x / SCALE, p.y / SCALE) } }
                val key = f.id + "/" + file.name + "@" + va.hashCode()
                if (synchronized(cache) { key !in cache }) stored(file, va)?.let { v -> synchronized(cache) { cache.putIfAbsent(key, v) } }
            }
        }
    }

    /** Rename a target (keeps everything else). */
    fun rename(face: Face, name: String): Face {
        val f = File(dir, "${face.id}.json")
        f.writeText(JSONObject(f.readText()).put("name", name).toString())
        return faces().first { it.id == face.id }
    }

    /** Change the face's real size (the outline stays); bullseye sizes follow from it. */
    fun resize(face: Face, widthIn: Double, heightIn: Double): Face {
        val f = File(dir, "${face.id}.json")
        f.writeText(JSONObject(f.readText()).put("width_in", widthIn).put("height_in", heightIn).toString())
        return faces().first { it.id == face.id }
    }

    /** The target's true centre (the centre of the flat face, so it's right at an angle too) in the reference photo. */
    fun centrePx(face: Face): Point? {
        if (!face.marked) return null
        val plane = Geometry.getPerspectiveTransform(MatOfPoint2f(*face.outline!!.toTypedArray()),
            MatOfPoint2f(Point(0.0, 0.0), Point(face.widthIn, 0.0), Point(face.widthIn, face.heightIn), Point(0.0, face.heightIn)))
        return mapPts(plane.inv(), listOf(Point(face.widthIn / 2, face.heightIn / 2)))[0]
    }

    /** One bullseye's real size on the face (inches): bounding width x height, area, and centre from the top-left corner. */
    class BullSize(val type: String, val widthIn: Double, val heightIn: Double, val areaSqIn: Double, val fromLeftIn: Double, val fromTopIn: Double)

    /** Real sizes of a marked target's bullseyes, measured on the flat face (outline + your width/height). */
    fun sizes(face: Face): List<BullSize> {
        if (!face.marked) return emptyList()
        val plane = Geometry.getPerspectiveTransform(MatOfPoint2f(*face.outline!!.toTypedArray()),
            MatOfPoint2f(Point(0.0, 0.0), Point(face.widthIn, 0.0), Point(face.widthIn, face.heightIn), Point(0.0, face.heightIn)))
        return face.bulls.map { (type, pts) ->
            val poly = Shapes.polygon(type, pts.map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }, refOutline(face)).map { Point(it.x.toDouble(), it.y.toDouble()) }
            val p = mapPts(plane, poly)
            var a = 0.0
            for (i in p.indices) { val u = p[i]; val v = p[(i + 1) % p.size]; a += u.x * v.y - v.x * u.y }
            val c = Shapes.centre(p.map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) })
            BullSize(type, p.maxOf { it.x } - p.minOf { it.x }, p.maxOf { it.y } - p.minOf { it.y }, kotlin.math.abs(a) / 2, c.x.toDouble(), c.y.toDouble())
        }
    }

    /** The first unused "Target 1", "Target 2", ... (a deleted one's number gets reused, never doubled up). */
    private fun nextName(): String {
        val taken = faces().map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { "Target $it" }.first { it !in taken }
    }

    /** The marked outline in the target's own photo (for shapes that sit on the face). */
    private fun refOutline(face: Face) = face.outline?.map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }

    fun delete(face: Face) {
        views(face).forEach { it.first.delete() }
        File(dir, "${face.id}.json").delete(); face.photo.delete(); cache.keys.removeAll { it.startsWith(face.id) }
    }

    /** A known target found in a photo: its outline (or tape area), aim spots and bullseye shapes, in photo coords. */
    class Seen(val face: Face, val outline: List<Point>, val spots: List<Point>, val bulls: List<Pair<String, List<Point>>>, val inliers: Int)

    /**
     * Every known target in this photo (two blocks side by side = two entries). When two saved targets land on the same
     * spot (e.g. the same side marked twice) only the stronger match is kept.
     */
    fun findAll(photo: Bitmap, known: List<Seen> = emptyList(), only: Set<String>? = null, found: (List<Seen>) -> Unit = {}): List<Seen> {
        val t0 = android.os.SystemClock.elapsedRealtime()
        return Look(photo).use { look -> steps.clear(); steps.add("prep ${android.os.SystemClock.elapsedRealtime() - t0}"); findAll(look, known, only, found) }.also { s ->
            android.util.Log.d("NockShots", "look: ${s.joinToString { it.face.name }.ifEmpty { "none" }} in ${android.os.SystemClock.elapsedRealtime() - t0} ms (${steps.joinToString()})")
        }
    }

    private val steps = java.util.Collections.synchronizedList(ArrayList<String>()) // time per step of the last look, for the log
    /**
     * Targets the last look half-saw: a placement that looked somewhat like the marked photo (picture match between
     * [UNSURE] and [CONFIRM]) but not enough to show. The camera shows "can't make it out yet" for these.
     */
    @Volatile var unsure: Set<String> = emptySet(); private set
    private val unsureNow = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun judged(face: Face, sc: Double) { if (sc >= UNSURE && sc < CONFIRM) unsureNow.add(face.id) }

    /** [known] = targets already confirmed in this photo (snapped in place): kept as they are, not looked for again. */
    /**
     * [found] = called each time another target is found, with all found so far (so each shows as soon as it's found).
     * [only] = look for just these targets (Manual: the ones you've locked on); null = every saved target.
     */
    private fun findAll(look: Look, known: List<Seen>, only: Set<String>?, found: (List<Seen>) -> Unit): List<Seen> {
        val photo = look.photo
        unsureNow.clear()
        fun seen(f: Face, h: Mat, n: Int): Seen? {
            fun map(ps: List<Point>) = mapPts(h, ps)
            val area = targetArea(f) ?: return null
            val outline = map(area)
            if (!sane(f, outline, photo.width, photo.height)) { android.util.Log.d("NockShots", "${f.name}: $n points but the shape is wrong - ignored"); return null }
            return Seen(f, outline, map(f.spots), f.bulls.map { (t, ps) -> t to map(ps) }, n)
        }
        val faces = faces().filter { f -> known.none { it.face.id == f.id } && (only == null || f.id in only) }
        var t = android.os.SystemClock.elapsedRealtime()
        fun step(name: String) { val now = android.os.SystemClock.elapsedRealtime(); steps.add("$name ${now - t}"); t = now }
        look.kp; step("orb")
        val all = known.toMutableList()
        fun add(x: Seen?) { if (x != null && all.none { inside(centre(x.outline), it.outline) }) { all.add(x); found(all.toList()) } }
        for (f in faces) { add(placed(f, look)?.let { (h, n) -> seen(f, h, n) }); step(f.name) }
        // Saved targets still missing (a printed ring face has few sharp corners): if the outline finder sees a target
        // shape nobody claimed, try each one's outline on it (4 turns), fine-aligned and confirmed by picture match.
        val missing = faces.filter { f -> f.marked && all.none { it.face.id == f.id } }
        if (missing.isNotEmpty()) runCatching { TargetGuess.findQuad(photo, all.map { it.outline }) }.getOrNull()?.let { q ->
            val quad = q.map { Point(it.x.toDouble(), it.y.toDouble()) }
            for (f in missing) byOutline(f, look, quad)?.let { h -> add(seen(f, h, MIN_INLIERS)) }
        }
        step("outline")
        // Still missing and it has round bullseyes (a ring face, a 5-spot): line its bullseye centres up on ring centres in view.
        // Most bullseyes first (a 5-spot before a 3-spot), and rings inside a target already found are its own: the 3-spot
        // was tried on the 5-spot's rings and fine-aligned for nothing (1.3-1.7 s, 0.66-0.69 "not it", 2026-10-08).
        for (f in faces.filter { f -> f.marked && all.none { it.face.id == f.id } && f.bulls.count { it.first == "circle" } >= 2 }
            .sortedByDescending { f -> f.bulls.count { it.first == "circle" } })
            byRings(f, look, all.map { it.outline })?.let { h -> add(seen(f, h, MIN_INLIERS)) }.also { step("rings ${f.name}") }
        all.sortByDescending { it.inliers }
        val kept = ArrayList<Seen>()
        for (s in all) {
            val c = Point(s.outline.sumOf { it.x } / s.outline.size, s.outline.sumOf { it.y } / s.outline.size)
            if (kept.none { inside(c, it.outline) }) kept.add(s)
        }
        unsure = unsureNow.filter { id -> kept.none { it.face.id == id } }.toSet()
        return kept
    }

    private fun centre(ps: List<Point>) = Point(ps.sumOf { it.x } / ps.size, ps.sumOf { it.y } / ps.size)

    /** One image that taught a target: the main photo or a learned / added view. */
    class Image(val file: File, val main: Boolean, val inliers: Int, val added: Long, val byHand: Boolean)

    /** Every image behind a target, main photo first. */
    fun images(face: Face): List<Image> {
        val j = runCatching { JSONObject(File(dir, "${face.id}.json").readText()) }.getOrNull() ?: return emptyList()
        val vs = j.optJSONArray("views") ?: JSONArray()
        return listOf(Image(face.photo, true, 0, face.id.removePrefix("side_").toLongOrNull() ?: 0L, true)) +
            (0 until vs.length()).map { vs.getJSONObject(it) }.map { v ->
                Image(File(dir, v.getString("file")), false, v.optInt("inliers"), v.optLong("added", 0L), v.optBoolean("by_hand"))
            }.sortedByDescending { it.added }
    }

    /**
     * Just the target, cut out of one of its images and flattened face-on (the room around it gone), for thumbnails.
     * The cut is the outline you marked (or the tape area), carried into a view by its saved mapping. Null = the
     * image or its mapping is missing (show the plain photo then). [longSide] = output size in pixels.
     */
    fun cutout(face: Face, image: File, longSide: Int = 480): Bitmap? {
        val area = targetArea(face) ?: return null
        val quad = if (image == face.photo) area else {
            val j = runCatching { JSONObject(File(dir, "${face.id}.json").readText()) }.getOrNull() ?: return null
            val vs = j.optJSONArray("views") ?: return null
            val v = (0 until vs.length()).map { vs.getJSONObject(it) }.firstOrNull { it.getString("file") == image.name } ?: return null
            val hv = v.getJSONArray("h"); val h = Mat(3, 3, CvType.CV_64F); for (k in 0 until 9) h.put(k / 3, k % 3, hv.getDouble(k))
            mapPts(h, area.map { Point(it.x * SCALE, it.y * SCALE) }).map { Point(it.x / SCALE, it.y / SCALE) }
        }
        val sample = 4 // the cut is shown small: a quarter-size decode is plenty and fast
        val bmp = BitmapFactory.decodeFile(image.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // Real proportions: the measured size on a marked target, else the outline's own side lengths.
        val aspect = if (face.marked && face.widthIn > 0 && face.heightIn > 0) face.widthIn / face.heightIn
            else (hypot(quad[1].x - quad[0].x, quad[1].y - quad[0].y) + hypot(quad[2].x - quad[3].x, quad[2].y - quad[3].y)) /
                (hypot(quad[3].x - quad[0].x, quad[3].y - quad[0].y) + hypot(quad[2].x - quad[1].x, quad[2].y - quad[1].y))
        val w = if (aspect >= 1) longSide else (longSide * aspect).toInt().coerceAtLeast(1)
        val ht = if (aspect >= 1) (longSide / aspect).toInt().coerceAtLeast(1) else longSide
        val src = MatOfPoint2f(*quad.map { Point(it.x / sample, it.y / sample) }.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(w.toDouble(), 0.0), Point(w.toDouble(), ht.toDouble()), Point(0.0, ht.toDouble()))
        val m = Geometry.getPerspectiveTransform(src, dst)
        val rgba = Mat(); Utils.bitmapToMat(bmp, rgba); bmp.recycle()
        val out = Mat(); Imgproc.warpPerspective(rgba, out, m, Size(w.toDouble(), ht.toDouble()), Imgproc.INTER_AREA); rgba.release()
        return Bitmap.createBitmap(w, ht, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(out, it); out.release() }
    }

    /** Marked photo -> one of its images (full size): identity for the marked photo, the saved mapping for a view. */
    fun imageH(face: Face, image: File): Mat? {
        if (image == face.photo) return Mat.eye(3, 3, CvType.CV_64F)
        val j = runCatching { JSONObject(File(dir, "${face.id}.json").readText()) }.getOrNull() ?: return null
        val vs = j.optJSONArray("views") ?: return null
        val v = (0 until vs.length()).map { vs.getJSONObject(it) }.firstOrNull { it.getString("file") == image.name } ?: return null
        val hv = v.getJSONArray("h"); val half = Mat(3, 3, CvType.CV_64F); for (k in 0 until 9) half.put(k / 3, k % 3, hv.getDouble(k))
        return toFull(half)
    }

    fun mapPoints(h: Mat, ps: List<android.graphics.PointF>): List<android.graphics.PointF> =
        mapPts(h, ps.map { Point(it.x.toDouble(), it.y.toDouble()) }).map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }

    /** Remove one learned view (you said it's wrong). The main photo can't be removed this way. */
    fun deleteView(face: Face, file: File) {
        val jf = File(dir, "${face.id}.json"); val j = JSONObject(jf.readText())
        val vs = j.optJSONArray("views") ?: return
        val keep = JSONArray(); for (i in 0 until vs.length()) vs.getJSONObject(i).let { if (it.getString("file") != file.name) keep.put(it) }
        jf.writeText(j.put("views", keep).toString()); file.delete(); cache.keys.removeAll { it.contains("/" + file.name + "@") }
    }

    /**
     * Teach a target a view by hand: [quad] = where its outline (or tape area) sits in [photo], placed or confirmed by you.
     * Always kept (you vouched for it).
     */
    fun addView(face: Face, photo: Bitmap, quad: List<Point>): Face {
        val area = targetArea(face) ?: return face
        val src = MatOfPoint2f(*area.map { Point(it.x * SCALE, it.y * SCALE) }.toTypedArray())
        val dst = MatOfPoint2f(*quad.map { Point(it.x * SCALE, it.y * SCALE) }.toTypedArray())
        val h = Geometry.getPerspectiveTransform(src, dst)
        val now = System.currentTimeMillis()
        val name = "${face.id}_view_$now.jpg"
        File(dir, name).outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val jf = File(dir, "${face.id}.json"); val j = JSONObject(jf.readText())
        val vs = j.optJSONArray("views") ?: JSONArray()
        val hv = JSONArray(); for (k in 0 until 9) hv.put(h.get(k / 3, k % 3)[0])
        vs.put(JSONObject().put("file", name).put("h", hv).put("inliers", 0).put("added", now).put("used", now).put("checked", true).put("by_hand", true))
        jf.writeText(j.put("views", vs).toString())
        return faces().first { it.id == face.id }
    }

    /** Where this target sits in a photo (outline or tape area), if it's there. */
    fun areaIn(face: Face, photo: Bitmap): List<Point>? {
        val h = Look(photo).use { placed(face, it) }?.first ?: return null
        return targetArea(face)?.let { mapPts(h, it) }
    }

    /**
     * A found outline has to look like a real target: four-sided and convex, not tiny, not twisted, and roughly the
     * shape you measured (a 20 x 20 face can't show up as a long sliver). Rejects a shirt or a door that only shares a
     * few features with a target.
     */
    private fun sane(face: Face, q: List<Point>, photoW: Int, photoH: Int): Boolean {
        if (q.size != 4) return false
        if (!Geometry.isContourConvex(org.opencv.core.MatOfPoint(*q.map { Point(it.x, it.y) }.toTypedArray()))) return false
        var a = 0.0; for (i in q.indices) { val p = q[i]; val r = q[(i + 1) % 4]; a += p.x * r.y - r.x * p.y }
        if (kotlin.math.abs(a) / 2 < 0.002 * photoW * photoH) return false
        val top = hypot(q[1].x - q[0].x, q[1].y - q[0].y); val bottom = hypot(q[2].x - q[3].x, q[2].y - q[3].y)
        val left = hypot(q[3].x - q[0].x, q[3].y - q[0].y); val right = hypot(q[2].x - q[1].x, q[2].y - q[1].y)
        if (maxOf(top, bottom) > 4 * minOf(top, bottom) || maxOf(left, right) > 4 * minOf(left, right)) return false // twisted
        if (face.marked) {
            val seen = (top + bottom) / (left + right); val real = face.widthIn / face.heightIn
            if (seen / real > 3 || real / seen > 3) return false
        }
        return true
    }

    /** Which saved side this photo shows (best match), with its spots mapped into the photo; null = none. */
    fun recognize(photo: Bitmap): Pair<Face, List<Point>>? {
        val (kp, d) = features(photo)
        return faces().mapNotNull { f -> locate(f, kp, d)?.let { (spots, inliers) -> Triple(f, spots, inliers) } }
            .maxByOrNull { it.third }?.let { it.first to it.second }
    }

    /** The best-matching marked target in this photo, with its outline and bullseye shapes mapped into the photo. */
    fun recognizeMarked(photo: Bitmap): Triple<Face, List<android.graphics.PointF>, List<MarkSide.Shape>>? {
        val best = Look(photo).use { look -> faces().filter { it.marked }.mapNotNull { f -> placed(f, look)?.let { (h, n) -> Triple(f, h, n) } }.maxByOrNull { it.third } } ?: return null
        val (f, h, _) = best
        fun map(ps: List<Point>) = mapPts(h, ps).map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) }
        return Triple(f, map(f.outline!!), f.bulls.map { (type, pts) -> MarkSide.Shape(type, map(pts).toMutableList()) })
    }

    /** This side's spots mapped into the photo, or null if the photo doesn't show it. */
    fun locate(face: Face, photo: Bitmap): List<Point>? {
        val (kp, d) = features(photo)
        return locate(face, kp, d)?.first
    }

    private fun locate(face: Face, kp: MatOfKeyPoint, d: Mat): Pair<List<Point>, Int>? {
        val (h, inliers) = homography(face, kp, d) ?: return null
        return mapPts(h, face.spots.map { Point(it.x * SCALE, it.y * SCALE) }).map { Point(it.x / SCALE, it.y / SCALE) } to inliers
    }

    /** Reference (half size) -> photo (half size) homography and its inlier count, or null if it isn't this face. */
    /**
     * Main photo -> this photo (half size), from the best of the main photo and its extra views. An extra view's match is
     * composed with its stored main -> view mapping, so everything marked on the main photo still lands right.
     */
    private fun homography(face: Face, kp: MatOfKeyPoint, d: Mat): Pair<Mat, Int>? {
        // Only the target is learned from the reference photos (inside your outline, or around the tape spots),
        // so the room behind it can't pull the match.
        val area = targetArea(face)
        var best = match(face.id, face.photo, area, kp, d)
        lastWinner = null
        if (best == null || best.second < LEARN_BELOW) for ((file, hMainToView) in views(face)) {
            val viewArea = area?.let { a -> mapPts(hMainToView, a.map { Point(it.x * SCALE, it.y * SCALE) }).map { Point(it.x / SCALE, it.y / SCALE) } }
            val m = match(face.id + "/" + file.name, file, viewArea, kp, d) ?: continue
            if (best == null || m.second > best.second) { best = Mat().also { Core.gemm(m.first, hMainToView, 1.0, Mat(), 0.0, it) } to m.second; lastWinner = file.name; winners[face.id] = file.name }
            if (best.second >= LEARN_BELOW) break // strong enough: no need to try the rest
        }
        return best
    }
    private var lastWinner: String? = null // the extra view that gave the last match (null = the main photo)
    private val winners = java.util.concurrent.ConcurrentHashMap<String, String>() // per target: the view that last matched, tried first

    /** The target's area in its main photo: the outline you drew, or a box around the tape spots (plus half again). */
    private fun targetArea(face: Face): List<Point>? {
        face.outline?.let { return it }
        if (face.spots.size < 2) return null
        val x0 = face.spots.minOf { it.x }; val x1 = face.spots.maxOf { it.x }; val y0 = face.spots.minOf { it.y }; val y1 = face.spots.maxOf { it.y }
        val mx = (x1 - x0) * 0.5; val my = (y1 - y0) * 0.5
        return listOf(Point(x0 - mx, y0 - my), Point(x1 + mx, y0 - my), Point(x1 + mx, y1 + my), Point(x0 - mx, y1 + my))
    }

    /**
     * Check every learned view against every target's main photo. A view that is really another target (it matches that
     * one at least twice as well, 20+ points) moves to that target, re-aligned to its photo, so the learning is kept. A
     * view that matches no target at all (a person walked in front) is removed: it never showed a target.
     * Returns a line per change, for the log.
     */
    fun audit(): List<String> {
        val log = ArrayList<String>()
        val all = faces()
        for (owner in all) {
            val jf = File(dir, "${owner.id}.json"); val j = JSONObject(jf.readText())
            val views = j.optJSONArray("views") ?: continue
            val keep = JSONArray()
            for (i in 0 until views.length()) {
                val v = views.getJSONObject(i)
                if (v.optBoolean("checked")) { keep.put(v); continue }
                val file = File(dir, v.getString("file"))
                val bmp = BitmapFactory.decodeFile(file.path) ?: continue
                val hv = v.getJSONArray("h"); val hm = Mat(3, 3, CvType.CV_64F); for (k in 0 until 9) hm.put(k / 3, k % 3, hv.getDouble(k))
                val area = targetArea(owner)?.let { a -> mapPts(hm, a.map { Point(it.x * SCALE, it.y * SCALE) }).map { Point(it.x / SCALE, it.y / SCALE) } }
                val (kp, d) = features(bmp, area)
                val scores = all.associateWith { f -> match(f.id, f.photo, targetArea(f), kp, d, 15) }
                val mine = scores[owner]?.second ?: 0
                val best = scores.maxByOrNull { it.value?.second ?: 0 }
                val bestN = best?.value?.second ?: 0
                when {
                    best != null && best.key.id != owner.id && bestN >= 20 && bestN >= 2 * mine -> {
                        // really another target: move it there, aligned to that target's photo
                        val to = best.key; val h = best.value!!.first
                        val tf = File(dir, "${to.id}.json"); val tj = JSONObject(tf.readText())
                        val tv = tj.optJSONArray("views") ?: JSONArray()
                        val nh = JSONArray(); for (k in 0 until 9) nh.put(h.get(k / 3, k % 3)[0])
                        tv.put(JSONObject(v.toString()).put("h", nh).put("inliers", bestN).put("checked", true))
                        tf.writeText(tj.put("views", tv).toString())
                        log.add("${v.getString("file")}: ${owner.name} -> ${to.name} ($mine vs $bestN)")
                    }
                    bestN < 20 -> { file.delete(); log.add("${v.getString("file")}: no target in it, removed (best $bestN)") }
                    else -> keep.put(v.put("checked", true))
                }
                cache.keys.removeAll { it.contains("/" + file.name + "@") }
            }
            // re-read in case views were moved INTO this target while checking others
            val fresh = JSONObject(jf.readText()).optJSONArray("views") ?: JSONArray()
            val moved = (0 until fresh.length()).map { fresh.getJSONObject(it) }.filter { f -> (0 until views.length()).none { views.getJSONObject(it).getString("file") == f.getString("file") } }
            moved.forEach { keep.put(it) }
            jf.writeText(JSONObject(jf.readText()).put("views", keep).toString())
        }
        return log
    }

    /** Extra views of a face: (photo, main -> view homography at half size). */
    private fun views(face: Face): List<Pair<File, Mat>> {
        val j = runCatching { JSONObject(File(dir, "${face.id}.json").readText()) }.getOrNull() ?: return emptyList()
        val a = j.optJSONArray("views") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val v = a.getJSONObject(i); val f = File(dir, v.getString("file")); if (!f.exists()) return@mapNotNull null
            val h = v.getJSONArray("h"); val m = Mat(3, 3, CvType.CV_64F)
            for (k in 0 until 9) m.put(k / 3, k % 3, h.getDouble(k))
            Triple(f, m, v.optLong("used", v.optLong("added", 0L)))
        }.sortedByDescending { if (it.first.name == winners[face.id]) Long.MAX_VALUE else it.third }.map { it.first to it.second } // most recently useful first
    }

    /**
     * Learn a new view of a face automatically: when this photo matches it, but only weakly (new light, angle or wear),
     * keep it as an extra reference with how it lines up with the main photo. Views are never dropped.
     * Returns the match strength (inlier count) for logging.
     */
    fun learn(face: Face, photo: Bitmap): Int? {
        val (kp, d) = features(photo)
        val (h, n) = homography(face, kp, d) ?: return null
        val jf = File(dir, "${face.id}.json"); val j = JSONObject(jf.readText())
        val views = j.optJSONArray("views") ?: JSONArray()
        val now = System.currentTimeMillis()
        // A saved view carried this match: mark it used, so views that keep helping (the field, the counter) stay.
        lastWinner?.let { w -> for (i in 0 until views.length()) views.getJSONObject(i).takeIf { it.getString("file") == w }?.put("used", now) }
        // Strong match on the main photo or any saved view = nothing new to learn (a scene like one it already knows).
        if (n >= LEARN_BELOW) { jf.writeText(j.put("views", views).toString()); return n }
        // Only learn from a solid match that clearly belongs to this target and not a look-alike: a weak false match
        // saved as a view made Side A "see itself" on Sides B, C, D and a shirt (2026-10-08). Never forgets real views.
        if (n < LEARN_MIN) { jf.writeText(j.put("views", views).toString()); return n }
        val o = targetArea(face) ?: return n
        val area = mapPts(h, o.map { Point(it.x * SCALE, it.y * SCALE) }).map { Point(it.x / SCALE, it.y / SCALE) }
        val (kpA, dA) = features(photo, area)
        val rival = faces().filter { it.id != face.id }.maxOfOrNull { f -> match(f.id, f.photo, targetArea(f), kpA, dA, 15)?.second ?: 0 } ?: 0
        if (rival * 2 >= n) { android.util.Log.d("NockShots", "${face.name}: not learned, another target matches $rival vs $n"); return n }
        val name = "${face.id}_view_$now.jpg"
        File(dir, name).outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val hv = JSONArray(); for (k in 0 until 9) hv.put(h.get(k / 3, k % 3)[0])
        views.put(JSONObject().put("file", name).put("h", hv).put("inliers", n).put("added", now).put("used", now))
        jf.writeText(j.put("views", views).toString())
        return n
    }

    private fun match(key: String, file: File, area: List<Point>?, kp: MatOfKeyPoint, d: Mat, min: Int = MIN_INLIERS, minInliers: Int = MIN_INLIERS): Pair<Mat, Int>? {
        // keyed by the area too: an outline edited elsewhere never reuses the old features
        val (rk, rd) = synchronized(cache) { cache.getOrPut(key + "@" + area.hashCode()) { stored(file, area) ?: return null } }
        if (rd.empty() || d.empty()) return null
        // Quick first pass on 1 in 8 of the reference features: an image that can't be this target is skipped before the
        // full match. Measured 2026-10-08 (138 matches): real ones (60+ inliers) got 15+ here, wrong ones 0-11, 84% under 5.
        val sub = synchronized(cache) { subs.getOrPut(key + "@" + area.hashCode()) {
            val rows = (0 until rd.rows() step 8).toList()
            Mat(rows.size, rd.cols(), rd.type()).also { m -> rows.forEachIndexed { i, r -> rd.row(r).copyTo(m.row(i)) } }
        } }
        val kSub = ArrayList<MatOfDMatch>(); matcher.knnMatch(sub, d, kSub, 2)
        val subGood = kSub.count { m -> m.toArray().let { it.size == 2 && it[0].distance < RATIO * it[1].distance } }
        if (subGood < if (min >= MIN_INLIERS) PRE_MIN else 2) return null // a weak-match search (rescue, 15+) keeps nearly everything
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(rd, d, knn, 2)
        val good = knn.mapNotNull { m -> m.toArray().takeIf { it.size == 2 && it[0].distance < RATIO * it[1].distance }?.get(0) }
        if (good.size < min) return null
        val a = rk.toArray(); val b = kp.toArray()
        val mask = Mat()
        val h = Geometry.findHomography(MatOfPoint2f(*good.map { a[it.queryIdx].pt }.toTypedArray()),
            MatOfPoint2f(*good.map { b[it.trainIdx].pt }.toTypedArray()), Geometry.RANSAC, 4.0, mask)
        if (h.empty()) return null
        val inliers = Core.countNonZero(mask)
        if (inliers < minInliers) return null
        return h to inliers
    }

    /**
     * [features] of a reference image, kept on disk: a cold first look decoded every learned view (87 photos, 12 MP)
     * just to find its features again: 0.7-1.1 s per target not even in view (2026-10-08 timing). Keyed by the image
     * (path, size, time) and the area, so a changed outline or image is worked out fresh.
     */
    private fun stored(file: File, area: List<Point>?): Pair<MatOfKeyPoint, Mat>? {
        val id = java.security.MessageDigest.getInstance("SHA-1").digest(
            "${file.path}|${file.length()}|${file.lastModified()}|${area?.joinToString { "%.2f,%.2f".format(java.util.Locale.US, it.x, it.y) }}|$SCALE|$ORB_N".toByteArray()
        ).joinToString("") { "%02x".format(it) }
        val f = File(orbDir, "$id.bin")
        if (f.exists()) runCatching {
            java.io.DataInputStream(f.inputStream().buffered()).use { inp ->
                val kp = Array(inp.readInt()) { org.opencv.core.KeyPoint(inp.readFloat(), inp.readFloat(), inp.readFloat(), inp.readFloat(), inp.readFloat(), inp.readInt(), inp.readInt()) }
                val rows = inp.readInt(); val cols = inp.readInt()
                val d = if (rows == 0) Mat() else Mat(rows, cols, CvType.CV_8U).also { m -> ByteArray(rows * cols).also { b -> inp.readFully(b); m.put(0, 0, b) } }
                return MatOfKeyPoint(*kp) to d
            }
        }
        val bmp = BitmapFactory.decodeFile(file.path) ?: return null
        val out = features(bmp, area); bmp.recycle()
        runCatching {
            orbDir.mkdirs()
            val tmp = File(orbDir, "$id.${Thread.currentThread().id}.tmp")
            java.io.DataOutputStream(tmp.outputStream().buffered()).use { o ->
                val kp = out.first.toArray(); o.writeInt(kp.size)
                for (k in kp) { o.writeFloat(k.pt.x.toFloat()); o.writeFloat(k.pt.y.toFloat()); o.writeFloat(k.size); o.writeFloat(k.angle); o.writeFloat(k.response); o.writeInt(k.octave); o.writeInt(k.class_id) }
                val d = out.second; o.writeInt(d.rows()); o.writeInt(d.cols())
                if (d.rows() > 0) ByteArray(d.rows() * d.cols()).also { b -> d.get(0, 0, b); o.write(b) }
            }
            tmp.renameTo(f)
        }
        return out
    }

    /** ORB features at half size; [area] (full-res photo coords) = only look inside it (the target, not the room). */
    private fun features(photo: Bitmap, area: List<Point>? = null): Pair<MatOfKeyPoint, Mat> {
        val rgba = Mat(); Utils.bitmapToMat(photo, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY); rgba.release()
        val small = Mat(); Imgproc.resize(gray, small, Size(), SCALE, SCALE, Imgproc.INTER_AREA); gray.release()
        val mask = if (area == null || area.size < 3) Mat() else Mat.zeros(small.size(), CvType.CV_8U).also {
            Imgproc.fillPoly(it, listOf(org.opencv.core.MatOfPoint(*area.map { p -> Point(p.x * SCALE, p.y * SCALE) }.toTypedArray())), org.opencv.core.Scalar(255.0))
        }
        val kp = MatOfKeyPoint(); val d = Mat()
        orb.detectAndCompute(small, mask, kp, d)
        small.release()
        if (d.type() != CvType.CV_8U && !d.empty()) d.convertTo(d, CvType.CV_8U)
        return kp to d
    }

    companion object {
        /**
         * One per app run for the camera, so what it loaded (reference photos, features) survives the camera screen
         * being rebuilt (turning the phone between portrait and landscape rebuilds it; each look after that reloaded
         * every target first: 1.4-3.5 s vs about 1 s, 2026-10-08).
         */
        @Volatile private var shared: TargetMemory? = null
        fun shared(dir: File): TargetMemory = shared?.takeIf { it.dir == dir } ?: synchronized(this) {
            shared?.takeIf { it.dir == dir } ?: TargetMemory(dir).also { shared = it }
        }

        const val SCALE = 0.5
        const val ORB_N = 3000 // features per image
        const val PRE_MIN = 5 // quick first pass: fewer good matches than this on 1/8 of an image's features = skip it
        const val NORM = 480.0 // fine alignment compares the target at this many pixels on its long side
        const val TPL = 96     // picture checks compare the flattened target at this size
        // Picture match needed to accept a placement: real ones 0.97-1.00 in his 2026-10-08 evening session (all 40+), the
        // wrong ones he saw 0.72-0.82 (3-spot drawn ~30 deg turned at 0.77-0.79; 0.75 let them through). Other targets'
        // photos at most 0.49 (tools/recog.py).
        const val CONFIRM = 0.9
        // Between this and CONFIRM: it half-sees a target (shown as "can't make it out yet", never drawn).
        const val UNSURE = 0.6
        const val PARTIAL_CONFIRM = 0.6 // visible part of a partly-off-screen target: NOT yet tuned on measured scores
        const val RATIO = 0.75f
        const val MIN_INLIERS = 60 // same side: 1,200+; other side: under 10
        // Below this a real match is "weak" (about a third of the 1,234-1,507 seen for the same side on 2026-10-07):
        // worth keeping as an extra view. A starting value, to tune once real weak matches are logged.
        const val LEARN_BELOW = 400
        const val LEARN_MIN = 110 // wrong views were learned at 60-101 points; real ones at 123+

        /** Point in polygon (photo coords). */
        fun inside(p: Point, poly: List<Point>): Boolean {
            var c = false; var j = poly.size - 1
            for (i in poly.indices) {
                val a = poly[i]; val b = poly[j]
                if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
                j = i
            }
            return c
        }
    }
}
