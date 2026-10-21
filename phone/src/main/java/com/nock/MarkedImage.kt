package com.nock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import kotlin.math.hypot

/**
 * One of a target's images (its marked photo or a view it learned) with what Nock knows drawn on it: the outline, each
 * bullseye (numbered when there are several, matching the list beside it), the target's centre and a dashed line to each
 * bullseye's centre. [sizes] also writes the width, height and each bullseye's size on the picture. Marks are carried
 * into a view by that view's saved mapping, so what's drawn is exactly what Nock uses there. The camera's target menu
 * and the Targets screen both draw with this, so they always match.
 */
object MarkedImage {

    fun render(context: Context, memory: TargetMemory, face: TargetMemory.Face, image: File, metric: Boolean, longSide: Int = 1600, sizes: Boolean = false): Bitmap? {
        val outline = face.outline ?: return null
        val h = memory.imageH(face, image) ?: return null
        fun map(ps: List<PointF>) = memory.mapPoints(h, ps)
        val src = BitmapFactory.decodeFile(image.path) ?: return null
        val q = map(outline.map { PointF(it.x.toFloat(), it.y.toFloat()) })
        // the target plus a little room (more when sizes are written outside the edges)
        val w0 = q.maxOf { it.x } - q.minOf { it.x }; val h0 = q.maxOf { it.y } - q.minOf { it.y }
        val pad = (if (sizes) 0.22f else 0.08f) * maxOf(w0, h0)
        val crop = RectF((q.minOf { it.x } - pad).coerceAtLeast(0f), (q.minOf { it.y } - pad).coerceAtLeast(0f),
            (q.maxOf { it.x } + pad).coerceAtMost(src.width.toFloat()), (q.maxOf { it.y } + pad).coerceAtMost(src.height.toFloat()))
        val k = longSide / maxOf(crop.width(), crop.height())
        val out = Bitmap.createBitmap((crop.width() * k).toInt().coerceAtLeast(1), (crop.height() * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, android.graphics.Rect(crop.left.toInt(), crop.top.toInt(), crop.right.toInt(), crop.bottom.toInt()),
            RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        fun v(p: PointF) = PointF((p.x - crop.left) * k, (p.y - crop.top) * k)

        val unit = longSide / 400f // 4 px at 1600
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NEON; style = Paint.Style.STROKE; strokeWidth = 0.9f * unit }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3339FF14 }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NEON }
        val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Smoke; strokeWidth = 1.1f * unit; setShadowLayer(unit, 0f, 0f, Brand.Carbon) }
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCF5F5F4.toInt(); strokeWidth = 0.6f * unit; pathEffect = DashPathEffect(floatArrayOf(2.5f * unit, 2.5f * unit), 0f) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Brand.Smoke; textSize = 7.5f * unit
            typeface = Typeface.create(androidx.core.content.res.ResourcesCompat.getFont(context, R.font.hanken_grotesk), 600, false)
        }
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9202321.toInt() }

        val qv = q.map { v(it) }
        c.drawPath(path(qv), line)
        val tc = memory.centrePx(face)?.let { v(map(listOf(PointF(it.x.toFloat(), it.y.toFloat())))[0]) }
        val ref = outline.map { PointF(it.x.toFloat(), it.y.toFloat()) }
        val measured = if (sizes) runCatching { memory.sizes(face) }.getOrDefault(emptyList()) else emptyList()
        val labels = ArrayList<Pair<String, PointF>>()
        face.bulls.forEachIndexed { i, (type, pts) ->
            // built on the marked photo (circles round on the face), then carried into this image
            val raw = Shapes.polygon(type, pts.map { PointF(it.x.toFloat(), it.y.toFloat()) }, ref)
            if (raw.size < 3) return@forEachIndexed
            val poly = map(raw).map { v(it) }
            c.drawPath(path(poly), fill); c.drawPath(path(poly), line)
            val bc = v(map(listOf(Shapes.centre(raw)))[0])
            if (tc != null && hypot(bc.x - tc.x, bc.y - tc.y) > 3 * unit) c.drawLine(tc.x, tc.y, bc.x, bc.y, dash)
            c.drawCircle(bc.x, bc.y, 1.6f * unit, dot)
            measured.getOrNull(i)?.let { s ->
                val lbl = if (type == "circle") "⌀ " + len(s.widthIn, metric) else len(s.widthIn, metric) + " × " + len(s.heightIn, metric)
                labels.add(lbl to PointF(bc.x, poly.maxOf { it.y } + 7 * unit))
            }
            if (face.bulls.size > 1) labels.add("${i + 1}" to PointF(bc.x, poly.minOf { it.y } - 7 * unit))
        }
        tc?.let { p -> val a = 4.5f * unit; c.drawLine(p.x - a, p.y, p.x + a, p.y, cross); c.drawLine(p.x, p.y - a, p.x, p.y + a, cross) }

        // Width on the top edge, height on the left edge: just outside the outline, centred on the edge.
        val mid = PointF(qv.map { it.x }.average().toFloat(), qv.map { it.y }.average().toFloat())
        fun outside(a: PointF, b: PointF): PointF {
            val m = PointF((a.x + b.x) / 2, (a.y + b.y) / 2)
            val d = hypot(m.x - mid.x, m.y - mid.y).coerceAtLeast(1f)
            return PointF(m.x + (m.x - mid.x) / d * 9 * unit, m.y + (m.y - mid.y) / d * 9 * unit)
        }
        if (sizes && face.marked) {
            labels.add(len(face.widthIn, metric) to outside(qv[0], qv[1]))
            labels.add(len(face.heightIn, metric) to outside(qv[0], qv[3]))
        }
        for ((s, at) in labels) {
            val tw = text.measureText(s); val fm = text.fontMetrics; val th = fm.descent - fm.ascent
            val r = RectF(at.x - tw / 2 - 3 * unit, at.y - th / 2 - 1.5f * unit, at.x + tw / 2 + 3 * unit, at.y + th / 2 + 1.5f * unit)
            // kept inside the picture
            val dx = when { r.left < 0 -> -r.left; r.right > out.width -> out.width - r.right; else -> 0f }
            val dy = when { r.top < 0 -> -r.top; r.bottom > out.height -> out.height - r.bottom; else -> 0f }
            r.offset(dx, dy)
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, pill)
            c.drawText(s, r.centerX() - tw / 2, r.centerY() - (fm.ascent + fm.descent) / 2, text)
        }
        return out
    }

    /** One line of the bullseye list: name, size, and where its centre sits from the target's centre. */
    class Fact(val label: String, val value: String, val where: String)

    /** The bullseye list shown beside the picture (same numbers as the tags on it). */
    fun facts(memory: TargetMemory, face: TargetMemory.Face, metric: Boolean): List<Fact> =
        runCatching { memory.sizes(face) }.getOrDefault(emptyList()).mapIndexed { i, b ->
            val dx = b.fromLeftIn - face.widthIn / 2; val dy = b.fromTopIn - face.heightIn / 2
            val where = if (hypot(dx, dy) < 0.1) "Centred"
                else listOfNotNull(
                    if (kotlin.math.abs(dx) >= 0.05) "${len(kotlin.math.abs(dx), metric)} ${if (dx >= 0) "right" else "left"}" else null,
                    if (kotlin.math.abs(dy) >= 0.05) "${len(kotlin.math.abs(dy), metric)} ${if (dy >= 0) "low" else "high"}" else null,
                ).joinToString(" · ") + " of centre"
            val value = if (b.type == "circle") "⌀ " + len(b.widthIn, metric) else len(b.widthIn, metric) + " × " + len(b.heightIn, metric)
            Fact(if (face.bulls.size == 1) "Bullseye" else "Bullseye ${i + 1}", value, where)
        }

    const val ESTIMATED = "Bullseye sizes are estimated from your outline and the target size."

    private fun len(inches: Double, metric: Boolean) = com.nock.data.Prefs.len(inches.toFloat(), metric)
    private fun path(ps: List<PointF>) = Path().apply { moveTo(ps[0].x, ps[0].y); ps.drop(1).forEach { lineTo(it.x, it.y) }; close() }
    private const val NEON = 0xFF39FF14.toInt()
}
