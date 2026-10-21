package com.nock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Live marks on the camera: each target Nock sees, outlined where it really is (so you can check the corners at any
 * tilt), with its bullseyes and a name chip on top. A target it doesn't know gets a dashed outline and "New target?".
 * Points come in view coordinates (mapped from the photo by the caller). Hidden while the phone moves.
 */
@SuppressLint("ViewConstructor")
class TargetOverlay(context: Context, private val onKnown: (TargetMemory.Face) -> Unit, private val onUnknown: () -> Unit) : FrameLayout(context) {

    /** [label] = what floats over the target: its name, who's shooting it, and this round's arrows and points. */
    class Item(val face: TargetMemory.Face?, val outline: List<PointF>, val bulls: List<List<PointF>>, val label: String? = null, val faint: Boolean = false)

    private var lastItems: List<Item> = emptyList()
    /** Same targets, new words (after an arrow): only the labels change. */
    fun relabel(label: (Item) -> String?) {
        if (lastItems.isEmpty()) return
        show(lastItems.map { Item(it.face, it.outline, it.bulls, if (it.faint) null else label(it), it.faint) })
    }

    private var items: List<Item> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF39FF14.toInt(); style = Paint.Style.STROKE; strokeWidth = context.dp(1.25f) }
    private val dashed = Paint(line).apply { color = 0xCCF5F5F4.toInt(); pathEffect = DashPathEffect(floatArrayOf(context.dp(7f), context.dp(6f)), 0f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2639FF14 }
    // a target that isn't yours (Manual): thin, faint, dashed, so it reads as "there, not counted"
    private val faint = Paint(line).apply { color = 0xB3F5F5F4.toInt(); strokeWidth = context.dp(1.25f); pathEffect = DashPathEffect(floatArrayOf(context.dp(4f), context.dp(5f)), 0f) }

    /** The drawn target under a point on screen (the last one drawn wins), or null. */
    fun itemAt(x: Float, y: Float): Item? = items.lastOrNull { inside(x, y, it.outline) }
    private fun inside(x: Float, y: Float, poly: List<PointF>): Boolean {
        var c = false; var j = poly.size - 1
        for (i in poly.indices) { val a = poly[i]; val b = poly[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) c = !c; j = i }
        return c
    }

    init { setWillNotDraw(false) }

    fun show(list: List<Item>) {
        // Same targets and words, new place (the phone moved): just move the marks and chips, no rebuilding.
        if (list.size == items.size && childCount == list.size && visibility == VISIBLE &&
            list.indices.all { list[it].face?.id == items[it].face?.id && list[it].label == items[it].label && list[it].faint == items[it].faint }) {
            items = list; lastItems = list
            list.forEachIndexed { i, it -> if (!it.faint) place(getChildAt(i), it) }
            invalidate(); return
        }
        items = list; lastItems = list
        removeAllViews()
        for (it in list) {
            if (it.faint) { addView(android.view.View(context).apply { visibility = GONE }); continue } // not yours (Manual): no tag
            // a small tag pinned to the target's top-left corner (moves with it, stays out of the middle)
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = pillBackground(context, 0x47202321) // Carbon at 28%: the target shows through
                setPadding(context.dpi(8), context.dpi(3), context.dpi(8), context.dpi(3))
                addView(context.label(11f, bold = true).apply { text = it.label ?: it.face?.name ?: "New target?"; setShadowLayer(context.dp(3f), 0f, 0f, 0xCC000000.toInt()) })
                setOnClickListener { _ -> it.face?.let(onKnown) ?: onUnknown() }
            }
            addView(chip, LayoutParams(-2, -2))
            place(chip, it)
        }
        // Fade in only when appearing; an update while shown just moves the marks (no blink).
        if (visibility != VISIBLE || alpha < 1f) { alpha = 0f; animate().alpha(1f).setDuration(200) }
        visibility = VISIBLE
        invalidate()
    }

    /**
     * The tag is pinned to the target's top-left corner as you see it, tilted with that top edge (snapped to the target,
     * not the phone's horizon): just above the edge. When that's under the top controls it goes just below the bottom
     * edge instead (inside the top edge it covered the 5-spot's top-left bullseye); inside only when neither fits.
     */
    private fun place(chip: android.view.View, it: Item) {
        if (chip.measuredWidth == 0) chip.measure(0, 0)
        val h = chip.measuredHeight.toFloat(); val gap = context.dp(3f)
        // the target's edges that are on top and bottom as you look at it (a target hung sideways still gets its tag above it)
        val o = it.outline; val cy = o.sumOf { p -> p.y.toDouble() } / o.size
        val edges = o.indices.map { i -> Edge(o[i], o[(i + 1) % o.size]) }
        val top = edges.minByOrNull { (it.a.y + it.b.y) / 2 - cy } ?: Edge(o[0], o[1])
        val bottom = edges.maxByOrNull { (it.a.y + it.b.y) / 2 - cy } ?: top
        val above = top.a.y + top.ny * (gap + h) >= context.dp(TOP_BAR_DP)
        val below = !above && height > 0 && bottom.a.y - bottom.ny * (gap + h) <= height - context.dp(8f)
        val e = if (below) bottom else top
        chip.pivotX = 0f; chip.pivotY = if (above) h else 0f
        chip.rotation = e.ang
        val side = if (above) 1f else -1f
        chip.translationX = e.a.x + side * e.nx * gap; chip.translationY = e.a.y + side * e.ny * gap - chip.pivotY
    }

    /** One edge of a target, read left to right (never upside down), with its upward normal ([nx], [ny]). */
    private class Edge(p: PointF, q: PointF) {
        val a: PointF; val b: PointF; val ang: Float; val nx: Float; val ny: Float
        init {
            var a0 = p; var b0 = q
            var d = Math.toDegrees(kotlin.math.atan2((b0.y - a0.y).toDouble(), (b0.x - a0.x).toDouble())).toFloat()
            if (d > 90f || d < -90f) { val t = a0; a0 = b0; b0 = t; d += if (d > 0) -180f else 180f }
            val len = kotlin.math.hypot(b0.x - a0.x, b0.y - a0.y).coerceAtLeast(1f)
            a = a0; b = b0; ang = d; nx = (b0.y - a0.y) / len; ny = -(b0.x - a0.x) / len
        }
    }

    fun clear() { if (items.isEmpty()) return; items = emptyList(); removeAllViews(); if (edges.isEmpty() && ghosts.isEmpty()) visibility = GONE else invalidate() }

    /**
     * A remembered target coming back on screen, drawn where the gyro puts it (white, with its name) the moment any of it
     * is in view, until a look confirms it and it turns into the usual green marks. Before, nothing showed until the look
     * landed, so turning back felt like it had to find the target all over again (his words, 2026-10-09).
     */
    class Ghost(val label: String, val outline: List<PointF>)
    private var ghosts: List<Ghost> = emptyList()
    fun setGhosts(list: List<Ghost>) {
        if (list.isEmpty() && ghosts.isEmpty()) return
        ghosts = list; if (list.isNotEmpty()) visibility = VISIBLE; invalidate()
    }
    private val ghostLine = Paint(line).apply { color = 0xCCF5F5F4.toInt() }
    private fun drawGhosts(canvas: Canvas) {
        for (g in ghosts) {
            if (g.outline.size < 3) continue
            canvas.drawPath(path(g.outline), ghostLine)
            // its name just above its top edge (same pill as the edge tags), kept on screen and under the top controls
            val o = g.outline; val cy = o.sumOf { it.y.toDouble() } / o.size
            val e = o.indices.map { i -> Edge(o[i], o[(i + 1) % o.size]) }.minByOrNull { (it.a.y + it.b.y) / 2 - cy } ?: continue
            val tw = edgeText.measureText(g.label); val ph = context.dp(22f); val pw = tw + context.dp(18f)
            val left = e.a.x.coerceIn(context.dp(6f), width - pw - context.dp(6f))
            val tp = (e.a.y - context.dp(3f) - ph).coerceIn(context.dp(TOP_BAR_DP), height - ph - context.dp(6f))
            canvas.drawRoundRect(left, tp, left + pw, tp + ph, ph / 2, ph / 2, edgeBg)
            canvas.drawText(g.label, left + context.dp(9f), tp + ph / 2 - (edgeText.descent() + edgeText.ascent()) / 2, edgeText)
        }
    }

    /** A target off screen: its tag at the screen edge, toward where it is ([dx], [dy] = its direction, y down). */
    class EdgeMark(val label: String, val dx: Float, val dy: Float)
    private var edges: List<EdgeMark> = emptyList()
    fun setEdges(list: List<EdgeMark>) {
        if (list.isEmpty() && edges.isEmpty()) return
        edges = list; if (list.isNotEmpty()) visibility = VISIBLE; invalidate()
    }
    private val edgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3202321.toInt() }
    private val edgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F5F4.toInt(); textSize = 11f * context.resources.displayMetrics.scaledDensity
        typeface = context.label(11f, bold = true).typeface
    }
    private val edgeArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F5F4.toInt(); style = Paint.Style.FILL }
    private fun drawEdges(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val inX = context.dp(28f); val top = context.dp(TOP_BAR_DP); val bottom = context.dp(84f) // clear of the top bar and Start
        for (e in edges) {
            val len = kotlin.math.hypot(e.dx, e.dy); if (len < 1e-4f) continue
            val ux = e.dx / len; val uy = e.dy / len
            // from the centre toward the target, stopped at the inset edge of the screen
            val tx = if (ux > 0) (width - inX - cx) / ux else if (ux < 0) (inX - cx) / ux else Float.MAX_VALUE
            val ty = if (uy > 0) (height - bottom - cy) / uy else if (uy < 0) (top - cy) / uy else Float.MAX_VALUE
            val t = minOf(tx, ty); val x = cx + ux * t; val y = cy + uy * t
            val tw = edgeText.measureText(e.label); val ph = context.dp(22f); val pw = tw + context.dp(30f)
            val left = (x - pw / 2).coerceIn(context.dp(6f), width - pw - context.dp(6f)); val tp = (y - ph / 2).coerceIn(top - ph, height - bottom)
            canvas.drawRoundRect(left, tp, left + pw, tp + ph, ph / 2, ph / 2, edgeBg)
            // a small arrow pointing at it, then the name
            val ax = left + context.dp(12f); val ay = tp + ph / 2; val r = context.dp(4.5f)
            val ang = kotlin.math.atan2(uy, ux)
            val path = Path().apply {
                moveTo(ax + r * kotlin.math.cos(ang), ay + r * kotlin.math.sin(ang))
                lineTo(ax + r * kotlin.math.cos(ang + 2.5f), ay + r * kotlin.math.sin(ang + 2.5f))
                lineTo(ax + r * kotlin.math.cos(ang - 2.5f), ay + r * kotlin.math.sin(ang - 2.5f)); close()
            }
            canvas.drawPath(path, edgeArrow)
            canvas.drawText(e.label, left + context.dp(22f), ay - (edgeText.descent() + edgeText.ascent()) / 2, edgeText)
        }
    }

    override fun onDraw(canvas: Canvas) {
        drawEdges(canvas)
        drawGhosts(canvas)
        for (it in items) {
            if (it.faint) { canvas.drawPath(path(it.outline), faint); continue }
            canvas.drawPath(path(it.outline), if (it.face == null) dashed else line)
            for (b in it.bulls) if (b.size >= 3) path(b).let { p -> canvas.drawPath(p, fill); canvas.drawPath(p, line) }
        }
    }

    private fun path(ps: List<PointF>) = Path().apply { moveTo(ps[0].x, ps[0].y); ps.drop(1).forEach { lineTo(it.x, it.y) }; close() }

    private companion object { const val TOP_BAR_DP = 64f } // top controls: 20 dp margin + 34 dp buttons + a gap
}
