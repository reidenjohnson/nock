package com.nock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Camera level, drawn to the Samsung camera's on his S23 (measured from its screen, 2026-10-08), without its circle:
 *  - side to side: two fixed ticks (18 dp, inner ends 29 dp from centre) and a 95 dp line between their outer ends that
 *    turns with the phone; see-through white while off, solid Fern when level (Samsung: yellow) with a haptic tick;
 *  - forward/back: a fixed ladder of dashes (8 / 15 / 24 dp long at 6 / 11 / 17 dp from centre) and a solid Fern dash
 *    (Samsung: green) that slides along it, 1 dp per degree, stopping at the ladder's ends.
 * Checked against Samsung's on the same pose: 16 deg tilt drawn 15.8 deg there; 16.8 deg lean = its dash at the bottom.
 * Shown with the camera's controls when you tap ([shown]); never while the phone lies flat.
 */
class HorizonLevel(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.2f * d; strokeCap = Paint.Cap.ROUND; color = 0x99F5F5F4.toInt() }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.8f * d; strokeCap = Paint.Cap.ROUND; color = Brand.FernBright }
    private var gx = 0f; private var gy = 0f; private var gz = 0f
    private var tilt = 0f            // degrees the phone is turned off level, screen's own up
    private var pitch = 0f           // degrees the camera leans down (+) or up (-) from straight ahead
    private var level = false
    private var levelSince = 0L
    /** On with the camera's controls (tap the screen), off when they hide. */
    var shown = false

    init { alpha = 0f }

    /** Gravity in the phone's axes (m/s^2) and the screen's rotation (Surface.ROTATION_*). */
    fun onGravity(x: Float, y: Float, z: Float, rotation: Int) {
        // smoothed, so the line glides instead of shaking
        gx += 0.35f * (x - gx); gy += 0.35f * (y - gy); gz += 0.35f * (z - gz)
        val g = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.1f)
        var a = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat() - rotation * 90f
        while (a > 180f) a -= 360f; while (a < -180f) a += 360f
        tilt = a
        pitch = Math.toDegrees(atan2(gz.toDouble(), sqrt((gx * gx + gy * gy).toDouble()))).toFloat()
        val flat = abs(gz) > 0.8f * g // pointing at the floor or the sky: side-to-side means nothing
        val now = android.os.SystemClock.elapsedRealtime()
        val wasLevel = level
        level = !flat && abs(tilt) < if (level) 0.8f else LEVEL_DEG // a little give so it doesn't flicker at the edge
        if (level && !wasLevel && shown) { levelSince = now; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        val target = if (shown && !flat) 1f else 0f
        alpha += (target - alpha) * 0.3f
        visibility = if (alpha < 0.02f && target == 0f) INVISIBLE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        // forward/back ladder (fixed) and the dash that slides along it
        for ((off, len) in listOf(5.7f to 8f, 11.4f to 15f, 17f to 24f)) for (sgn in listOf(-1f, 1f))
            canvas.drawLine(cx - len / 2 * d, cy + sgn * off * d, cx + len / 2 * d, cy + sgn * off * d, thin)
        val y = cy + pitch.coerceIn(-17f, 17f) * d
        canvas.drawLine(cx - 13.5f * d, y, cx + 13.5f * d, y, bold)
        // side to side: fixed ticks, and the line that lies along the real horizon
        if (level) canvas.drawLine(cx - 47 * d, cy, cx + 47 * d, cy, bold)
        else {
            canvas.drawLine(cx - 47 * d, cy, cx - 29 * d, cy, thin); canvas.drawLine(cx + 29 * d, cy, cx + 47 * d, cy, thin)
            canvas.save(); canvas.rotate(tilt, cx, cy); canvas.drawLine(cx - 47.5f * d, cy, cx + 47.5f * d, cy, thin); canvas.restore()
        }
    }

    companion object {
        const val LEVEL_DEG = 0.5f
    }
}
