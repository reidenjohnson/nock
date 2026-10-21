package com.nock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File

/**
 * The last arrow, small in the corner of the camera: a close-up of where it hit and the bullseye's centre (a dashed
 * line between them), with the distance and score. Pinch to zoom in on it, drag to look around; tap to open it full
 * screen, where you can drag the ring to fix it.
 */
@SuppressLint("ViewConstructor")
class ShotPreview(context: Context, private val onOpen: (File) -> Unit) : View(context) {
    private var crop: Bitmap? = null
    private var file: File? = null
    private val src = Rect()
    private var spotX = 0f; private var spotY = 0f; private var hitX = 0f; private var hitY = 0f
    private var found = true
    private var line1 = ""; private var line2 = ""
    private var zoom = 1f; private var panX = 0f; private var panY = 0f

    private val d = context.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Glass }
    private val img = Paint(Paint.FILTER_BITMAP_FLAG)
    private val neon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF39FF14.toInt(); strokeWidth = 1.6f * d; style = Paint.Style.STROKE }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCF5F5F4.toInt(); strokeWidth = 1.2f * d; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE600.toInt(); strokeWidth = 2f * d; style = Paint.Style.STROKE } // the app's guess ring, same yellow as review
    private val shade = Paint().apply { shader = null; color = 0x99000000.toInt() }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Smoke; textSize = 15f * context.resources.displayMetrics.scaledDensity; typeface = context.label(17f, bold = true).typeface }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Dim; textSize = 11f * context.resources.displayMetrics.scaledDensity; typeface = context.label(11f, bold = true).typeface }
    private val clip = Path()

    init { visibility = GONE }

    /** A new arrow: [spot] = bullseye centre, [hit] = where it went in (photo pixels), [ppi] = pixels per inch there. */
    fun show(after: File, sx: Float, sy: Float, hx: Float, hy: Float, ppi: Float, rightIn: Float, lowIn: Float, score: Int?, found: Boolean) {
        val r = Thread {
            runCatching {
                // the spot and the arrow, with half again around them (at least ±1.5 in)
                val half = maxOf(1.5f * ppi, maxOf(kotlin.math.abs(hx - sx), kotlin.math.abs(hy - sy)) * 0.75f + 0.6f * ppi)
                val cx = (sx + hx) / 2; val cy = (sy + hy) / 2
                val dec = BitmapRegionDecoder.newInstance(after.path)
                val rect = Rect((cx - half).toInt().coerceAtLeast(0), (cy - half).toInt().coerceAtLeast(0), (cx + half).toInt().coerceAtMost(dec.width), (cy + half).toInt().coerceAtMost(dec.height))
                val b = dec.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = if (rect.width() > 1200) 2 else 1 }); dec.recycle()
                post {
                    crop?.recycle(); crop = b; src.set(rect); file = after
                    spotX = sx; spotY = sy; hitX = hx; hitY = hy; this.found = found
                    val dist = kotlin.math.hypot(rightIn, lowIn)
                    line1 = if (found) "%.1f\"".format(dist) + (score?.let { " · $it" } ?: "") else "Not found"
                    line2 = if (found) "%.1f\" %s · %.1f\" %s".format(kotlin.math.abs(rightIn), if (rightIn >= 0) "right" else "left", kotlin.math.abs(lowIn), if (lowIn >= 0) "low" else "high") else "Tap to place it"
                    zoom = 1f; panX = 0f; panY = 0f
                    if (visibility != VISIBLE) { alpha = 0f; visibility = VISIBLE; animate().alpha(1f).setDuration(180) }
                    invalidate()
                }
            }
        }
        r.start()
    }

    fun hide() { visibility = GONE; crop?.recycle(); crop = null }
    /** The arrow shown now (to refresh it after a fix). */
    val shown: File? get() = file

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean { zoom = (zoom * det.scaleFactor).coerceIn(1f, 5f); clampPan(); invalidate(); return true }
    })
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean { file?.let(onOpen); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1f) return false
            panX -= dx; panY -= dy; clampPan(); invalidate(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { zoom = if (zoom > 1.5f) 1f else 2.5f; panX = 0f; panY = 0f; invalidate(); return true }
    })
    private fun clampPan() { val m = (zoom - 1) * width / 2; panX = panX.coerceIn(-m, m); panY = panY.coerceIn(-m, m) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean { scaler.onTouchEvent(e); if (!scaler.isInProgress) taps.onTouchEvent(e); return true }

    override fun onDraw(canvas: Canvas) {
        val b = crop ?: return
        val w = width.toFloat(); val h = height.toFloat(); val r = 18 * d
        clip.reset(); clip.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip)
        canvas.drawRect(0f, 0f, w, h, bg)
        // the crop, filling the card (zoom/pan about the centre)
        // centred in the part above the numbers' shade, so neither mark sits under the text
        val k = maxOf(w / src.width(), h / src.height()) * zoom
        val ox = w / 2 + panX - (src.width() / 2f) * k; val oy = (h - 40 * d) / 2 + panY - (src.height() / 2f) * k
        canvas.drawBitmap(b, null, RectF(ox, oy, ox + src.width() * k, oy + src.height() * k), img)
        fun x(px: Float) = ox + (px - src.left) * k
        fun y(py: Float) = oy + (py - src.top) * k
        // bullseye centre (cross), the line to the arrow, the arrow (ring)
        val c = 6 * d
        canvas.drawLine(x(spotX) - c, y(spotY), x(spotX) + c, y(spotY), neon); canvas.drawLine(x(spotX), y(spotY) - c, x(spotX), y(spotY) + c, neon)
        if (found) { canvas.drawLine(x(spotX), y(spotY), x(hitX), y(hitY), dash); canvas.drawCircle(x(hitX), y(hitY), 7 * d, ring) }
        // the numbers on a shade at the bottom
        canvas.drawRect(0f, h - 40 * d, w, h, shade)
        canvas.drawText(line1, 10 * d, h - 22 * d, big)
        canvas.drawText(line2, 10 * d, h - 8 * d, small)
        canvas.restore()
    }
}
