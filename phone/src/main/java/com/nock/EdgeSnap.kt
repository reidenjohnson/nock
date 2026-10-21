package com.nock

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Snaps a found outline onto the target's real edges. Feature matching places the outline from the printed design
 * (the deer, letters, holes), so plain black edges far from it can hang a few pixels off. For each side, look a short
 * way either side of it (2.5% of its length) for the strongest edge across it, fit a line through those, and use the
 * corners where the lines meet. A side without a clear edge (a black face against the box's other black side) keeps
 * its matched position. Tested on his kitchen views: all four sides landed on the box edges; moves of 0-6 px.
 */
class EdgeSnap(photo: Bitmap) {
    private val w = photo.width; private val h = photo.height
    private val gx: FloatArray; private val gy: FloatArray

    init {
        val rgba = Mat(); Utils.bitmapToMat(photo, rgba)
        val g = Mat(); Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY); rgba.release()
        g.convertTo(g, CvType.CV_32F); Imgproc.GaussianBlur(g, g, Size(5.0, 5.0), 0.0)
        val mx = Mat(); val my = Mat()
        Imgproc.Sobel(g, mx, CvType.CV_32F, 1, 0, 3); Imgproc.Sobel(g, my, CvType.CV_32F, 0, 1, 3); g.release()
        gx = FloatArray(w * h).also { mx.get(0, 0, it) }; gy = FloatArray(w * h).also { my.get(0, 0, it) }
        mx.release(); my.release()
    }

    /** The outline (4 corners, photo coords) moved onto the real edges where they're clear. */
    fun snap(q: List<Point>): List<Point> {
        val cx = q.sumOf { it.x } / 4; val cy = q.sumOf { it.y } / 4
        val lines = (0 until 4).map { i ->
            val a = q[i]; val b = q[(i + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y); val ux = (b.x - a.x) / len; val uy = (b.y - a.y) / len
            var nx = -uy; var ny = ux
            if (nx * (cx - a.x) + ny * (cy - a.y) < 0) { nx = -nx; ny = -ny }
            val reach = maxOf(4, (0.025 * len).toInt())
            val pts = ArrayList<Point>()
            for (k in 0 until SAMPLES) {
                val t = 0.1 + 0.8 * k / (SAMPLES - 1)
                val px = a.x + ux * len * t; val py = a.y + uy * len * t
                // The edge NEAREST the matched side, not the strongest: a block's face edge and its top edge sit close
                // together, and taking the strongest pulled Side C up (2026-10-08).
                val mags = FloatArray(2 * reach + 1)
                for (o in -reach..reach) {
                    val x = (px + nx * o).roundToInt(); val y = (py + ny * o).roundToInt()
                    if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) continue
                    mags[o + reach] = abs(gx[y * w + x] * nx.toFloat() + gy[y * w + x] * ny.toFloat())
                }
                val strongest = mags.maxOrNull() ?: 0f
                if (strongest > MIN_EDGE) {
                    val o = (-reach..reach).filter { mags[it + reach] >= 0.6f * strongest }.minBy { abs(it) }
                    pts.add(Point(px + nx * o, py + ny * o))
                }
            }
            if (pts.size >= SAMPLES * 6 / 10) {
                val line = Mat(); Geometry.fitLine(MatOfPoint2f(*pts.toTypedArray()), line, Geometry.DIST_HUBER, 0.0, 0.01, 0.01)
                doubleArrayOf(line.get(2, 0)[0], line.get(3, 0)[0], line.get(0, 0)[0], line.get(1, 0)[0])
            } else doubleArrayOf(a.x, a.y, ux, uy) // no clear edge: keep the matched side
        }
        return (0 until 4).map { i -> cross(lines[(i + 3) % 4], lines[i]) ?: q[i] }
    }

    /** Where two lines (x, y, dx, dy) meet. */
    private fun cross(l1: DoubleArray, l2: DoubleArray): Point? {
        val det = l1[2] * (-l2[3]) - (-l2[2]) * l1[3]
        if (abs(det) < 1e-9) return null
        val rx = l2[0] - l1[0]; val ry = l2[1] - l1[1]
        val t = (rx * (-l2[3]) - (-l2[2]) * ry) / det
        return Point(l1[0] + l1[2] * t, l1[1] + l1[3] * t)
    }

    private companion object {
        const val SAMPLES = 40
        const val MIN_EDGE = 40f // gradient across the side (Sobel on 0-255 gray) for a "clear" edge
    }
}
