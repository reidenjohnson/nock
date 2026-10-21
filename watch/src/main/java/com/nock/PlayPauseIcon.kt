package com.nock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Play (triangle) or pause (two bars), drawn. Same shape as the phone's. */
class PlayPauseIcon(context: Context) : View(context) {
    var playing = false
        set(v) { field = v; invalidate() }
    var color = 0xFFF5F5F4.toInt()
        set(v) { field = v; paint.color = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F5F4.toInt() }
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f
        if (playing) {
            val w = s * 0.18f; val h = s * 0.7f; val gap = s * 0.16f; val r = w * 0.35f
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
