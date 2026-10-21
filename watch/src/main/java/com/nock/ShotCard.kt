package com.nock

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** One arrow from the phone: its close-up and where things are on it (close-up pixels). */
class ShotImage(val stamp: Long, val index: Int, val found: Boolean, val rightIn: Float, val lowIn: Float,
                val spotX: Float, val spotY: Float, val hitX: Float, val hitY: Float, val pxPerIn: Float, val bmp: Bitmap)

/**
 * The arrow you just shot, full screen: the close-up with the bullseye's centre (green cross), the arrow (ring) and the
 * line between, its score big in the ring's colour and how far off. Tap to put it away.
 * Press and hold to fix it: the buzz builds as a ring fills round the edge, then pops, and you're editing: drag to
 * move the ring (it follows your finger's movement, so your finger never covers it), turn the bezel or double-tap to
 * zoom, Done to save (it goes back to the phone and counts like a fix in review).
 */
@SuppressLint("ViewConstructor")
class ShotCard(context: Context, private val onFix: (ShotImage, Float, Float) -> Unit, private val onClose: () -> Unit) : View(context) {
    private var shot: ShotImage? = null
    private var markX = 0f; private var markY = 0f
    var editing = false; private set
    private var charge = 0f // 0..1 while you hold
    private var zoom = 1f
    private var saved = false

    private val d = context.resources.displayMetrics.density
    private val sp = context.resources.displayMetrics.scaledDensity
    private val img = Paint(Paint.FILTER_BITMAP_FLAG)
    private val neon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF39FF14.toInt(); strokeWidth = 2f * d; style = Paint.Style.STROKE }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCF5F5F4.toInt(); strokeWidth = 1.5f * d; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(5 * d, 5 * d), 0f) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.5f * d; style = Paint.Style.STROKE }
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 34f * sp; typeface = Typeface.create(context.resources.getFont(R.font.bricolage_grotesque), 700, false) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F5F4.toInt(); textAlign = Paint.Align.CENTER; textSize = 13f * sp; typeface = Typeface.create(context.resources.getFont(R.font.hanken_grotesk), 600, false) }
    private val chargeArc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F5F4.toInt(); strokeWidth = 4f * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val doneBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7FA05C.toInt() }
    private val doneText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202321.toInt(); textAlign = Paint.Align.CENTER; textSize = 14f * sp; typeface = Typeface.create(context.resources.getFont(R.font.hanken_grotesk), 700, false) }
    private val doneRect = RectF()

    init { visibility = GONE }

    fun show(s: ShotImage) {
        if (editing) return // don't swap the arrow out from under your finger
        shot?.bmp?.takeIf { it !== s.bmp }?.recycle()
        shot = s; markX = s.hitX; markY = s.hitY; zoom = 1f; charge = 0f; saved = false
        visibility = VISIBLE; alpha = 0f; animate().alpha(1f).setDuration(200)
        removeCallbacks(autoHide); postDelayed(autoHide, SHOW_MS)
        invalidate()
    }

    private val autoHide = Runnable { if (!editing) close() }
    fun close() { editing = false; charge = 0f; chargeAnim.cancel(); vibrator.cancel(); animate().alpha(0f).setDuration(200).withEndAction { visibility = GONE; onClose() } }

    /** The bezel turned: zoom while editing. */
    fun rotary(delta: Float) { if (!editing) return; zoom = (zoom * if (delta > 0) 1.15f else 1 / 1.15f).coerceIn(1f, 5f); invalidate() }

    // ---- hold to edit: the buzz builds, then pops ----
    private val vibrator get() = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val chargeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CHARGE_MS
        addUpdateListener { charge = it.animatedValue as Float; invalidate() }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                if (charge < 1f) return
                // the pop
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                editing = true; charge = 0f; removeCallbacks(autoHide); invalidate()
            }
        })
    }
    private fun startCharge() {
        removeCallbacks(autoHide)
        // a rising buzz: steps getting stronger over the hold
        val n = 7; val step = CHARGE_MS / n
        val timings = LongArray(n) { step }; val amps = IntArray(n) { (30 + 225 * (it + 1) / n).coerceAtMost(255) }
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
        chargeAnim.start()
    }
    private fun stopCharge() {
        if (!chargeAnim.isRunning) return
        chargeAnim.cancel(); charge = 0f; vibrator.cancel(); invalidate(); postDelayed(autoHide, SHOW_MS)
    }

    private var lastX = 0f; private var lastY = 0f
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (editing && doneRect.contains(e.x, e.y)) { save(); return true }
            if (!editing) close()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { if (editing) { zoom = if (zoom > 1.5f) 1f else 2.5f; invalidate() }; return true }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = shot ?: return false
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; if (!editing) startCharge() }
            MotionEvent.ACTION_MOVE -> {
                if (!editing) { if (hypot(e.x - lastX, e.y - lastY) > 12 * d) stopCharge() }
                else if (!doneRect.contains(e.x, e.y)) {
                    // relative drag, in close-up pixels at the current zoom
                    val k = scale(s) ; markX = (markX + (e.x - lastX) / k).coerceIn(0f, s.bmp.width.toFloat()); markY = (markY + (e.y - lastY) / k).coerceIn(0f, s.bmp.height.toFloat())
                    lastX = e.x; lastY = e.y; invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!editing) stopCharge()
        }
        return true
    }

    private fun save() {
        val s = shot ?: return
        onFix(s, markX, markY)
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        editing = false; saved = true; invalidate()
        postDelayed({ close() }, 900)
    }

    private fun scale(s: ShotImage) = maxOf(width / s.bmp.width.toFloat(), height / s.bmp.height.toFloat()) * zoom

    override fun onDraw(canvas: Canvas) {
        val s = shot ?: return
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(0xFF000000.toInt())
        // the close-up fills the round screen; zoomed about the arrow while editing. What matters (spot and arrow, or the
        // ring you're moving) sits in the clear part above the score band (it was half under it, behind the score), and the
        // picture always covers the screen edge to edge.
        val k = scale(s)
        val fx = if (editing) markX else (s.spotX + s.hitX) / 2; val fy = if (editing) markY else (s.spotY + s.hitY) / 2
        val ox = (w / 2 - fx * k).coerceIn(w - s.bmp.width * k, 0f); val oy = (BAND_TOP / 2 * h - fy * k).coerceIn(h - s.bmp.height * k, 0f)
        canvas.drawBitmap(s.bmp, null, RectF(ox, oy, ox + s.bmp.width * k, oy + s.bmp.height * k), img)
        fun x(v: Float) = ox + v * k
        fun y(v: Float) = oy + v * k
        val c = 8 * d
        canvas.drawLine(x(s.spotX) - c, y(s.spotY), x(s.spotX) + c, y(s.spotY), neon); canvas.drawLine(x(s.spotX), y(s.spotY) - c, x(s.spotX), y(s.spotY) + c, neon)
        val right = (markX - s.spotX) / s.pxPerIn; val low = (markY - s.spotY) / s.pxPerIn
        val dist = hypot(right, low)
        val sc = Ring.score(dist)
        ring.color = if (editing) 0xFFF5F5F4.toInt() else Ring.color(sc)
        if (s.found || editing || saved) { canvas.drawLine(x(s.spotX), y(s.spotY), x(markX), y(markY), dash); canvas.drawCircle(x(markX), y(markY), 10 * d, ring) }
        // the score and distance, low on the round face
        canvas.drawRect(0f, h * BAND_TOP, w, h, shade)
        if (editing) {
            small.color = 0xFFF5F5F4.toInt()
            // distance, hint, then Done below them (Done covered the hint), all inside the round face
            canvas.drawText("%.1f\"  ·  %s".format(dist, Ring.label(dist)), w / 2, h * 0.73f, small)
            canvas.drawText("Drag to place · bezel zooms", w / 2, h * 0.73f + 15 * sp, small.apply { color = 0x99F5F5F4.toInt() })
            val bw = 84 * d; val bh = 30 * d
            doneRect.set(w / 2 - bw / 2, h * 0.87f - bh / 2, w / 2 + bw / 2, h * 0.87f + bh / 2)
            canvas.drawRoundRect(doneRect, bh / 2, bh / 2, doneBg)
            canvas.drawText("Done", w / 2, doneRect.centerY() - (doneText.descent() + doneText.ascent()) / 2, doneText)
        } else {
            big.color = if (s.found || saved) Ring.color(sc) else 0xFFF5F5F4.toInt()
            canvas.drawText(if (s.found || saved) Ring.label(dist) else "?", w / 2, h * 0.82f, big)
            small.color = 0xFFF5F5F4.toInt()
            canvas.drawText(if (saved) "Saved" else if (s.found) "%.1f\" off  ·  hold to fix".format(dist) else "Not found  ·  hold to place it", w / 2, h * 0.82f + 18 * sp, small)
            doneRect.setEmpty()
        }
        // holding: the ring round the edge fills
        if (charge > 0f) { val i = chargeArc.strokeWidth; canvas.drawArc(i, i, w - i, h - i, -90f, 360f * charge, false, chargeArc) }
    }

    private companion object { const val CHARGE_MS = 700L; const val SHOW_MS = 6000L; const val BAND_TOP = 0.68f } // score band: bottom 32%
}
