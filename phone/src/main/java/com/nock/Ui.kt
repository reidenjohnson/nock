package com.nock

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Brand palette (same as Kairos): neutral Carbon base, Fern = good, Amber = needs you, Brick = bad. */
object Brand {
    const val Carbon = 0xFF202321.toInt()
    const val Smoke = 0xFFF5F5F4.toInt()
    const val Fern = 0xFF566E3F.toInt()
    const val FernBright = 0xFF7FA05C.toInt() // Fern lifted for a status dot on black
    const val Amber = 0xFFDE8521.toInt()
    const val Brick = 0xFFB23A2E.toInt()
    const val TealBright = 0xFF57A6BA.toInt() // Teal lifted for dark: targets and vision
    const val Glass = 0xB3202321.toInt()       // Carbon @ 70% over the camera feed
    const val Dim = 0x99F5F5F4.toInt()         // Smoke @ 60% for secondary text
}

fun Context.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.dpi(v: Int) = dp(v.toFloat()).toInt()

fun pillBackground(ctx: Context, color: Int = Brand.Glass) = GradientDrawable().apply {
    cornerRadius = ctx.dp(999f)
    setColor(color)
}

fun dot(ctx: Context, color: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    setSize(ctx.dpi(8), ctx.dpi(8))
}

/** Brand type: Bricolage Grotesque for big/bold, Hanken Grotesk for text (same as the Compose screens). */
fun Context.label(sizeSp: Float, bold: Boolean = false, color: Int = Brand.Smoke) = TextView(this).apply {
    setTextColor(color)
    textSize = sizeSp
    typeface = if (bold && sizeSp >= 17f) Typeface.create(androidx.core.content.res.ResourcesCompat.getFont(context, com.nock.R.font.bricolage_grotesque), 700, false)
        else Typeface.create(androidx.core.content.res.ResourcesCompat.getFont(context, com.nock.R.font.hanken_grotesk), if (bold) 600 else 500, false)
    letterSpacing = if (bold) -0.01f else 0f
    gravity = Gravity.CENTER_VERTICAL
    includeFontPadding = false
}

fun View.fadeTo(visible: Boolean) {
    animate().cancel()
    if (visible) { if (visibility != View.VISIBLE) { alpha = 0f; visibility = View.VISIBLE }; animate().alpha(1f).setDuration(150) }
    else animate().alpha(0f).setDuration(250).withEndAction { visibility = View.GONE }
}

/** A back chevron, drawn (the "‹" glyph came out thin and faint). */
class ChevronIcon(context: Context, private val right: Boolean = false) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Brand.Smoke; style = android.graphics.Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2.2f
        strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val path = android.graphics.Path()
    override fun onDraw(canvas: android.graphics.Canvas) {
        val c = width / 2f; val m = height / 2f; val s = minOf(width, height) * 0.14f
        val d = if (right) -1f else 1f
        path.reset(); path.moveTo(c + d * s * 0.6f, m - s * 1.2f); path.lineTo(c - d * s * 0.6f, m); path.lineTo(c + d * s * 0.6f, m + s * 1.2f)
        canvas.drawPath(path, paint)
    }
}

/** The sleek arrow (same as Home's Shoot card): a thin line fading in from nothing to a clean chevron tip. */
class SleekArrow(context: Context) : View(context) {
    var color = Brand.Smoke
        set(v) { field = v; invalidate() }
    private val d = context.resources.displayMetrics.density
    private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.75f * d; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val y = height / 2f; val end = width - line.strokeWidth
        line.shader = android.graphics.LinearGradient(0f, y, end, y, color and 0x00FFFFFF, color, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawLine(line.strokeWidth, y, end, y, line)
        line.shader = null; line.color = color
        val k = 4.5f * d
        canvas.drawLine(end, y, end - k, y - k, line); canvas.drawLine(end, y, end - k, y + k, line)
    }
}

/** Play (triangle) or pause (two bars), drawn so it's crisp at any size. */
class PlayPauseIcon(context: Context) : View(context) {
    var playing = false
        set(v) { field = v; invalidate() }
    var color = Brand.Smoke
        set(v) { field = v; paint.color = v; invalidate() }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Smoke }
    private val path = android.graphics.Path()
    override fun onDraw(canvas: android.graphics.Canvas) {
        val s = minOf(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f
        if (playing) {
            val w = s * 0.16f; val h = s * 0.62f; val gap = s * 0.14f; val r = w * 0.35f
            canvas.drawRoundRect(cx - gap / 2 - w, cy - h / 2, cx - gap / 2, cy + h / 2, r, r, paint)
            canvas.drawRoundRect(cx + gap / 2, cy - h / 2, cx + gap / 2 + w, cy + h / 2, r, r, paint)
        } else {
            // a sleek arrowhead pointing right: sharp tip, swept back, notched tail
            path.reset()
            path.moveTo(cx + s * 0.34f, cy)
            path.lineTo(cx - s * 0.26f, cy - s * 0.30f)
            path.lineTo(cx - s * 0.10f, cy)
            path.lineTo(cx - s * 0.26f, cy + s * 0.30f)
            path.close()
            canvas.drawPath(path, paint)
        }
    }
}

/** X-ray: a viewfinder (four corner brackets) with a dot, for the live target marks button. */
class XrayIcon(context: Context) : View(context) {
    var color = Brand.Smoke
        set(v) { field = v; paint.color = v; dotPaint.color = v; invalidate() }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Brand.Smoke; style = android.graphics.Paint.Style.STROKE; strokeWidth = context.resources.displayMetrics.density * 1.6f
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Smoke }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val i = paint.strokeWidth; val l = w * 0.3f
        for ((x, y, dx, dy) in listOf(listOf(i, i, 1f, 1f), listOf(w - i, i, -1f, 1f), listOf(w - i, h - i, -1f, -1f), listOf(i, h - i, 1f, -1f))) {
            canvas.drawLine(x, y, x + dx * l, y, paint); canvas.drawLine(x, y, x, y + dy * l, paint)
        }
        canvas.drawCircle(w / 2, h / 2, w * 0.11f, dotPaint)
    }
}

/** A small target glyph (two rings and a centre dot), for the Mark target chip. */
class TargetIcon(context: Context, tint: Int = Brand.TealBright) : View(context) {
    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; style = android.graphics.Paint.Style.STROKE; strokeWidth = context.resources.displayMetrics.density * 1.6f
    }
    private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    var color = tint
        set(v) { field = v; ring.color = v; dot.color = v; invalidate() }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val c = width / 2f; val m = height / 2f; val r = minOf(width, height) / 2f - ring.strokeWidth
        canvas.drawCircle(c, m, r, ring); canvas.drawCircle(c, m, r * 0.55f, ring); canvas.drawCircle(c, m, r * 0.16f, dot)
    }
}

/** Tiny watch glyph for the status chip: rounded case + two straps. */
class WatchIcon(context: Context) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Smoke }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRoundRect(w * 0.25f, 0f, w * 0.75f, h * 0.24f, w * 0.1f, w * 0.1f, paint)
        canvas.drawRoundRect(w * 0.25f, h * 0.76f, w * 0.75f, h, w * 0.1f, w * 0.1f, paint)
        canvas.drawRoundRect(0f, h * 0.18f, w, h * 0.82f, w * 0.32f, w * 0.32f, paint)
    }
}

/** A drawn check mark (the "✓" glyph renders thin and varies by font). */
class CheckIcon(context: Context, tint: Int = Brand.FernBright) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; style = android.graphics.Paint.Style.STROKE; strokeWidth = context.resources.displayMetrics.density * 2.2f
        strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val path = android.graphics.Path()
    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = minOf(w, h); val x = (w - s) / 2; val y = (h - s) / 2
        path.reset(); path.moveTo(x + s * 0.18f, y + s * 0.52f); path.lineTo(x + s * 0.42f, y + s * 0.74f); path.lineTo(x + s * 0.84f, y + s * 0.28f)
        canvas.drawPath(path, paint)
    }
}

/** Which way an arrow missed, drawn at its exact angle (a dot when it's on the aim). [right]/[low] in inches. */
class MissArrow(context: Context) : View(context) {
    private var right = 0f; private var low = 0f
    var color = Brand.Smoke
        set(v) { field = v; paint.color = v; invalidate() }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Brand.Smoke; style = android.graphics.Paint.Style.STROKE; strokeWidth = context.resources.displayMetrics.density * 1.8f
        strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    fun set(right: Float, low: Float) { this.right = right; this.low = low; invalidate() }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val c = width / 2f; val m = height / 2f; val r = minOf(width, height) * 0.4f
        if (kotlin.math.hypot(right, low) < 0.15f) {
            paint.style = android.graphics.Paint.Style.FILL; canvas.drawCircle(c, m, r * 0.32f, paint); paint.style = android.graphics.Paint.Style.STROKE; return
        }
        canvas.save(); canvas.rotate(Math.toDegrees(kotlin.math.atan2(low.toDouble(), right.toDouble())).toFloat(), c, m)
        canvas.drawLine(c - r, m, c + r, m, paint)
        canvas.drawLine(c + r, m, c + r * 0.35f, m - r * 0.6f, paint); canvas.drawLine(c + r, m, c + r * 0.35f, m + r * 0.6f, paint)
        canvas.restore()
    }
}

/** A small filled dot drawn beside a label's text (instead of the "●" glyph), in [color]. */
fun TextView.leadingDot(color: Int?) {
    if (color == null) { setCompoundDrawables(null, null, null, null); return }
    val d = context.resources.displayMetrics.density
    val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize((7 * d).toInt(), (7 * d).toInt()) }
    dot.setBounds(0, 0, (7 * d).toInt(), (7 * d).toInt())
    setCompoundDrawables(dot, null, null, null); compoundDrawablePadding = (7 * d).toInt()
}

/** The HUD button's icon: the camera level in miniature (two ticks and the line between them). */
class LevelIcon(context: Context) : View(context) {
    var color = Brand.Smoke
        set(v) { field = v; paint.color = v; invalidate() }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Brand.Smoke; strokeWidth = context.resources.displayMetrics.density * 1.8f; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val m = height / 2f; val i = paint.strokeWidth
        canvas.drawLine(i, m, w * 0.2f, m, paint); canvas.drawLine(w * 0.8f, m, w - i, m, paint)
        canvas.drawLine(w * 0.3f, m, w * 0.7f, m, paint)
        canvas.drawLine(w * 0.4f, m + w * 0.22f, w * 0.6f, m + w * 0.22f, paint)
    }
}

/** Calibrate-gyro menu icon: an arrow turning round a dot (turn the phone around). */
class GyroIcon(context: Context) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Brand.Smoke; strokeWidth = context.resources.displayMetrics.density * 1.8f; strokeCap = android.graphics.Paint.Cap.ROUND
        style = android.graphics.Paint.Style.STROKE
    }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Smoke }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val i = paint.strokeWidth
        val r = android.graphics.RectF(i, i, w - i, h - i)
        canvas.drawArc(r, -40f, 290f, false, paint)
        // arrowhead at the arc's end (-40 + 290 = 250 degrees)
        val a = Math.toRadians(250.0); val cx = w / 2; val cy = h / 2; val rr = r.width() / 2; val s = w * 0.2f
        val ex = cx + rr * Math.cos(a).toFloat(); val ey = cy + rr * Math.sin(a).toFloat()
        val tx = -Math.sin(a).toFloat(); val ty = Math.cos(a).toFloat() // along the turn
        canvas.drawLine(ex, ey, ex - tx * s + (ex - cx) / rr * s * 0.7f, ey - ty * s + (ey - cy) / rr * s * 0.7f, paint)
        canvas.drawLine(ex, ey, ex - tx * s - (ex - cx) / rr * s * 0.7f, ey - ty * s - (ey - cy) / rr * s * 0.7f, paint)
        canvas.drawCircle(cx, cy, w * 0.1f, fill)
    }
}

/** The camera's menu button: three short lines. */
class MenuIcon(context: Context) : View(context) {
    var color = Brand.Smoke
        set(v) { field = v; paint.color = v; invalidate() }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Brand.Smoke; strokeWidth = context.resources.displayMetrics.density * 1.8f; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (k in 0..2) { val y = h * (0.25f + 0.25f * k); canvas.drawLine(w * 0.12f, y, w * 0.88f, y, paint) }
    }
}

/**
 * The camera's target button, one compact glass pill: the target glyph and how many targets it sees. While [spinning],
 * a short white flash runs around the pill's edge, like a loader.
 */
class TargetPill(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Glass }
    private val glyph = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Smoke; style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val glyphDot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Smoke }
    private val flash = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.8f * d; strokeCap = android.graphics.Paint.Cap.ROUND; color = Brand.Smoke
    }
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Brand.Smoke; textSize = 13f * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.create(androidx.core.content.res.ResourcesCompat.getFont(context, com.nock.R.font.hanken_grotesk), 700, false)
    }
    var count = 0; set(v) { field = v; requestLayout(); invalidate() }
    var tint = Brand.Smoke; set(v) { field = v; glyph.color = v; glyphDot.color = v; invalidate() }
    var textColor = Brand.Smoke; set(v) { field = v; text.color = v; invalidate() }
    private var phase = 0f
    private val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100; repeatCount = android.animation.ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    var spinning = false
        set(v) { if (v == field) return; field = v; if (v) anim.start() else { anim.cancel(); invalidate() } }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); anim.cancel() }

    // layout: [pad][glyph 14][gap 6][count][pad]
    private val pad get() = 11f * d
    private val gs get() = 14f * d
    private val gap get() = 6f * d
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = pad * 2 + gs + gap + text.measureText(count.toString())
        setMeasuredDimension(w.toInt() + 1, resolveSize((34 * d).toInt(), hSpec))
    }
    private val outline = android.graphics.Path(); private val seg = android.graphics.Path(); private val pm = android.graphics.PathMeasure()
    private val rect = android.graphics.RectF()
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val i = flash.strokeWidth / 2; rect.set(i, i, w - i, h - i)
        outline.reset(); outline.addRoundRect(rect, h / 2f, h / 2f, android.graphics.Path.Direction.CW); pm.setPath(outline, true)
    }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val h = height.toFloat(); val m = h / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), h, m, m, bg)
        var x = pad
        val gr = gs / 2 - glyph.strokeWidth / 2
        canvas.drawCircle(x + gs / 2, m, gr, glyph); canvas.drawCircle(x + gs / 2, m, gr * 0.5f, glyph); canvas.drawCircle(x + gs / 2, m, gr * 0.16f, glyphDot)
        x += gs + gap
        canvas.drawText(count.toString(), x, m - (text.descent() + text.ascent()) / 2, text)
        if (spinning) {
            // the flash: a quarter of the edge, its tail fading, running clockwise
            val len = pm.length; val head = phase * len; val tail = len * 0.25f; val n = 10
            for (k in 0 until n) {
                val a = head - tail * (k + 1) / n; val b = head - tail * k / n
                seg.reset()
                if (a >= 0) pm.getSegment(a, b, seg, true)
                else { pm.getSegment(len + a, len, seg, true); if (b > 0) pm.getSegment(0f, b, seg, true) }
                flash.alpha = (255 * (1f - k.toFloat() / n)).toInt()
                canvas.drawPath(seg, flash)
            }
        }
    }
}

/** A drawn plus (the "+" glyph renders thin and varies by font). */
class PlusIcon(context: Context, tint: Int = Brand.TealBright) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; strokeWidth = context.resources.displayMetrics.density * 2f; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val i = paint.strokeWidth
        canvas.drawLine(w / 2, i, w / 2, h - i, paint); canvas.drawLine(i, h / 2, w - i, h / 2, paint)
    }
}
