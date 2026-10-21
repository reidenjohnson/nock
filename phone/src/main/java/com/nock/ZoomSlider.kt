package com.nock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * Vertical zoom slider: a thin track with a pill-shaped handle that shows the zoom ("2.4×").
 * Top = most zoom. Camera-app spacing: 1× sits ~10% up, 3× ~35%, 10× at the top (even within each stretch).
 * Drag to zoom; it eases into 1× and 3× (gentle detents you can push through). Tap = presets.
 * Faded while idle so it stays out of the way of the picture.
 */
class ZoomSlider(context: Context, private val onChange: (Float) -> Unit) : View(context) {

    var min = 1f; private set
    var max = 10f; private set
    var value = 1f; private set
    /** Fires on finger down / up so the owner can keep the slider visible while it's held. */
    var onTouchState: (Boolean) -> Unit = {}
    /** A tap (no drag) on the slider. */
    var onTap: () -> Unit = {}

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var dragging = false
    private var inDetent = 0f

    init { alpha = IDLE_ALPHA }

    private fun px(dp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
    private val trackW = px(4f)
    private val thumbW = px(46f)
    private val thumbH = px(30f)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66F5F5F4 }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6F5F5F4.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202321.toInt()
        textSize = px(12f)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setRange(min: Float, max: Float) {
        if (min == this.min && max == this.max) return
        this.min = min; this.max = max.coerceAtLeast(min + 0.01f); invalidate()
    }

    fun setValue(v: Float) {
        val c = v.coerceIn(min, max)
        if (c == value) return
        value = c; invalidate()
    }

    private val top get() = paddingTop + thumbH / 2
    private val bottom get() = height - paddingBottom - thumbH / 2

    // Zoom <-> position (0 = bottom, 1 = top). Anchors are for the S23U's 0.6-10× range; a camera
    // with a different range uses the stretch of the table it covers, rescaled to fill the track.
    private fun rawT(v: Float): Float {
        val i = (1 until ANCHOR_ZOOM.size).firstOrNull { v <= ANCHOR_ZOOM[it] } ?: (ANCHOR_ZOOM.size - 1)
        val z0 = ANCHOR_ZOOM[i - 1]; val z1 = ANCHOR_ZOOM[i]
        val f = ((v - z0) / (z1 - z0)).coerceIn(0f, 1f)
        return ANCHOR_T[i - 1] + f * (ANCHOR_T[i] - ANCHOR_T[i - 1])
    }

    private fun rawZoom(t: Float): Float {
        val i = (1 until ANCHOR_T.size).firstOrNull { t <= ANCHOR_T[it] } ?: (ANCHOR_T.size - 1)
        val f = ((t - ANCHOR_T[i - 1]) / (ANCHOR_T[i] - ANCHOR_T[i - 1])).coerceIn(0f, 1f)
        return ANCHOR_ZOOM[i - 1] + f * (ANCHOR_ZOOM[i] - ANCHOR_ZOOM[i - 1])
    }

    private fun toT(v: Float): Float { val a = rawT(min); val b = rawT(max); return ((rawT(v) - a) / (b - a)).coerceIn(0f, 1f) }
    private fun fromT(t: Float): Float { val a = rawT(min); val b = rawT(max); return rawZoom(a + t * (b - a)).coerceIn(min, max) }

    private fun yFor(v: Float): Float = bottom - toT(v) * (bottom - top)

    private fun valueAt(y: Float): Float = fromT(((bottom - y) / (bottom - top)).coerceIn(0f, 1f))

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        rect.set(cx - trackW / 2, top, cx + trackW / 2, bottom)
        canvas.drawRoundRect(rect, trackW, trackW, track)
        val y = yFor(value)
        rect.set(cx - thumbW / 2, y - thumbH / 2, cx + thumbW / 2, y + thumbH / 2)
        canvas.drawRoundRect(rect, thumbH / 2, thumbH / 2, thumb)
        canvas.drawText(label(value), cx, y - (text.descent() + text.ascent()) / 2, text)
        // Right-edge swipes are Android's "back"; carve the handle (plus margin) out of that gesture.
        val pad = thumbH.toInt()
        systemGestureExclusionRects = listOf(android.graphics.Rect(0, (y - thumbH / 2).toInt() - pad, width, (y + thumbH / 2).toInt() + pad))
    }

    private fun label(v: Float) = if (v >= 9.95f) "%.0f×".format(v) else "%.1f×".format(v)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downY = e.y; dragging = false
                wake()
                onTouchState(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && kotlin.math.abs(e.y - downY) > touchSlop) dragging = true
                if (dragging) {
                    val v = snap(valueAt(e.y))
                    if (v != value) { value = v; invalidate(); onChange(v) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging && e.actionMasked == MotionEvent.ACTION_UP) onTap()
                onTouchState(false)
                sleepSoon()
            }
        }
        return true
    }

    /** Pull gently onto a detent when close; a firm drag carries on through. */
    private fun snap(v: Float): Float {
        val t = toT(v)
        for (d in DETENTS) {
            if (d < min || d > max) continue
            val td = toT(d)
            if (kotlin.math.abs(t - td) < DETENT_PULL) {
                if (inDetent != d) { inDetent = d; performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                return d
            }
        }
        inDetent = 0f
        return v
    }

    /** Full strength while in use; fades back after a moment. */
    fun wake() { removeCallbacks(fade); animate().alpha(1f).setDuration(120) }
    fun sleepSoon() { removeCallbacks(fade); postDelayed(fade, 1800) }
    private val fade = Runnable { animate().alpha(IDLE_ALPHA).setDuration(400) }

    private companion object {
        val DETENTS = floatArrayOf(1f, 3f)
        val ANCHOR_ZOOM = floatArrayOf(0.6f, 1f, 3f, 5f, 10f, 100f)
        val ANCHOR_T = floatArrayOf(0f, 0.10f, 0.35f, 0.52f, 1f, 1.5f)
        const val DETENT_PULL = 0.025f // of the track: small enough to zoom right next to a detent
        const val IDLE_ALPHA = 0.45f
    }
}
