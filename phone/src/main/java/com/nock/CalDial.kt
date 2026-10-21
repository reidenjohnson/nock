package com.nock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Gyro calibration progress: four arcs round a percentage, one for each way you turn the phone (left, right, up, down,
 * placed on the side you turn toward). Each fills as Nock collects turns that way; an arrow outside the ring points to
 * the way it needs next.
 */
class CalDial(context: Context) : View(context) {
    /** How full each way is, 0..1: left, right, up, down. */
    var fills = FloatArray(4)
        set(v) { field = v; invalidate() }
    /** The way to turn next (0 left, 1 right, 2 up, 3 down), or -1 for no arrow. */
    var next = -1
        set(v) { field = v; invalidate() }
    var percent = 0
        set(v) { field = v; invalidate() }

    private val d = context.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33F5F5F4; style = Paint.Style.STROKE; strokeWidth = 6 * d; strokeCap = Paint.Cap.ROUND }
    private val done = Paint(track).apply { color = Brand.FernBright }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Smoke; style = Paint.Style.FILL }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Brand.Smoke; textAlign = Paint.Align.CENTER; textSize = 26 * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.create(androidx.core.content.res.ResourcesCompat.getFont(context, R.font.bricolage_grotesque), 700, false)
    }

    // each way's arc: centred on its side of the ring (canvas angles: 0 = right, 90 = down)
    private val centres = floatArrayOf(180f, 0f, 270f, 90f)
    private val span = 70f

    override fun onDraw(canvas: Canvas) {
        val pad = 15 * d // room for the arrow outside the ring
        val r = RectF(pad, pad, width - pad, height - pad)
        for (i in 0 until 4) {
            val start = centres[i] - span / 2
            canvas.drawArc(r, start, span, false, track)
            val f = fills[i].coerceIn(0f, 1f)
            if (f > 0f) canvas.drawArc(r, start, span * f, false, done)
        }
        canvas.drawText("$percent%", width / 2f, height / 2f - (number.descent() + number.ascent()) / 2, number)
        if (next in 0..3) {
            // a small arrowhead just outside the ring, pointing the way to turn
            val a = Math.toRadians(centres[next].toDouble())
            val cx = width / 2f; val cy = height / 2f; val rr = r.width() / 2 + 6 * d; val s = 7 * d
            val tipX = cx + (rr + s) * Math.cos(a).toFloat(); val tipY = cy + (rr + s) * Math.sin(a).toFloat()
            val bx = cx + rr * Math.cos(a).toFloat(); val by = cy + rr * Math.sin(a).toFloat()
            val px = -Math.sin(a).toFloat() * s; val py = Math.cos(a).toFloat() * s
            canvas.drawPath(Path().apply { moveTo(tipX, tipY); lineTo(bx + px, by + py); lineTo(bx - px, by - py); close() }, arrow)
        }
    }
}
