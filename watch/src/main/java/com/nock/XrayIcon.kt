package com.nock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** X-ray: a viewfinder (four corner brackets) with a dot. Same as the phone's. */
class XrayIcon(context: Context) : View(context) {
    var color = 0xFFF5F5F4.toInt()
        set(v) { field = v; paint.color = v; dot.color = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.resources.displayMetrics.density * 1.6f; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val i = paint.strokeWidth; val l = w * 0.3f
        for ((x, y, dx, dy) in listOf(listOf(i, i, 1f, 1f), listOf(w - i, i, -1f, 1f), listOf(w - i, h - i, -1f, -1f), listOf(i, h - i, 1f, -1f))) {
            canvas.drawLine(x, y, x + dx * l, y, paint); canvas.drawLine(x, y, x, y + dy * l, paint)
        }
        canvas.drawCircle(w / 2, h / 2, w * 0.11f, dot)
    }
}
