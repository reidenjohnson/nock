package com.nock

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.math.abs

/**
 * Watches the camera for "the target changed, then went still again" — an arrow landing.
 *
 * Every frame is shrunk to a small gray thumbnail. While consecutive thumbnails barely differ the
 * scene is still; once it has been still for STILL_MS we compare it with the last still scene.
 * Enough changed pixels = something new on the target, and both full frames are saved as a pair
 * (before/after) so the arrow can be found by differencing them.
 *
 * Every settled change is saved and scored; none is ever dropped. [Event.confirmed] says the watch felt a
 * release just before it (the arrow scene settles ~1.0-1.2 s after release). An unconfirmed change only
 * becomes an arrow if the hit finder sees a new fletching, so bumps and walk-bys drop out there.
 *
 * "Moving" counts pixels that jumped, not the mean change: at high zoom in a dim room sensor noise alone
 * pushed the mean over the old 2.0 limit, so the scene never settled and whole ends were missed, while a
 * clean arrow landing (no block jolt) can stay under it.
 */
class ShotDetector(private val dir: File, private val onEvent: (Event) -> Unit) {

    /** [before]/[after] are upright full-res copies; the receiver owns (and recycles) them. */
    class Event(val index: Int, val changedFraction: Float, val after: File, val before: Bitmap, val afterBmp: Bitmap, val confirmed: Boolean)

    private var prevThumb: IntArray? = null
    private var stillSince = 0L
    private var settled = false
    private var refThumb: IntArray? = null
    private var refFrame: Bitmap? = null
    private var count = 0
    private var rotation = 0
    @Volatile private var releasedAt = 0L
    @Volatile private var resetPending = false
    /** Scoring on/off (the Start button). Off: changes are never saved; on: starts fresh from the current view. */
    @Volatile var active = false
        set(v) { if (v && !field) resetPending = true; field = v }
    /** Last time the phone itself moved (its gyro): changes while it's being handled aren't arrows. */
    @Volatile var phoneMovedAt = 0L
    private var lastLog = 0L

    /** The watch saw a shot. */
    fun onRelease() { releasedAt = SystemClock.elapsedRealtime() }

    /** The view changed on purpose (zoom): start over from the next settled scene; never score across it. */
    fun resetReference() { resetPending = true }

    init { dir.mkdirs() }

    /** Call with every analysis frame as the sensor delivers it. Copies what it keeps. */
    fun onFrame(frame: Bitmap, rotation: Int) {
        this.rotation = rotation
        val thumb = grayThumb(frame)
        val now = SystemClock.elapsedRealtime()
        val prev = prevThumb
        prevThumb = thumb
        if (prev == null) return
        if (!active) return
        if (resetPending) { resetPending = false; refThumb = null; stillSince = 0; settled = false }

        val jumped = thumb.indices.count { abs(thumb[it] - prev[it]) > PIXEL_CHANGE }
        if (now - lastLog > 5000) {
            lastLog = now
            Log.d(TAG, "jumped=$jumped mean=%.2f settled=$settled".format(meanAbsDiff(prev, thumb)))
        }
        val moving = jumped > MOVE_PIXELS
        if (moving) { stillSince = 0; settled = false; return }
        if (stillSince == 0L) stillSince = now
        if (settled || now - stillSince < STILL_MS) return
        settled = true

        val ref = refThumb
        if (ref == null) { keepAsReference(frame, thumb); return }
        val changed = thumb.indices.count { abs(thumb[it] - ref[it]) > PIXEL_CHANGE }
        if (changed < MIN_CHANGED_PIXELS) return
        if (now - phoneMovedAt < HANDLED_MS) { // the phone was picked up or bumped: just the new view
            Log.d(TAG, "phone moved: new reference")
            keepAsReference(frame, thumb); return
        }
        if (changed > thumb.size * SCENE_CHANGE) { // a different view, not an arrow
            Log.d(TAG, "scene change ($changed px): new reference")
            keepAsReference(frame, thumb); return
        }
        val confirmed = now - releasedAt <= RELEASE_WINDOW_MS // the watch felt a release just before this
        releasedAt = 0

        count++
        val fraction = changed.toFloat() / thumb.size
        // Wall-clock ms in the name lines each shot up with the watch's motion log.
        val base = "shot_%03d_%d".format(count, System.currentTimeMillis())
        val beforeUp = upright(refFrame!!)
        val afterUp = upright(frame)
        save(beforeUp, File(dir, "${base}_before.jpg"))
        val after = File(dir, "${base}_after.jpg")
        save(afterUp, after)
        keepAsReference(frame, thumb)
        Log.d(TAG, "change #$count: $changed px, confirmed=$confirmed")
        onEvent(Event(count, fraction, after, beforeUp, afterUp, confirmed))
    }

    private fun keepAsReference(frame: Bitmap, thumb: IntArray) {
        refFrame?.recycle()
        refFrame = frame.copy(Bitmap.Config.ARGB_8888, false)
        refThumb = thumb
    }

    /** Always a new bitmap (never the input), upright. */
    private fun upright(bitmap: Bitmap): Bitmap {
        val up = scaledUpright(bitmap, rotation, if (rotation % 180 == 0) bitmap.width else bitmap.height)
        return if (up === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else up
    }

    private fun save(upright: Bitmap, file: File) {
        dir.mkdirs()
        file.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    private fun grayThumb(frame: Bitmap): IntArray {
        val h = THUMB_W * frame.height / frame.width
        val small = Bitmap.createScaledBitmap(frame, THUMB_W, h, true)
        val px = IntArray(THUMB_W * h)
        small.getPixels(px, 0, THUMB_W, 0, 0, THUMB_W, h)
        if (small !== frame) small.recycle()
        for (i in px.indices) {
            val c = px[i]
            px[i] = ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        return px
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray): Float {
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toFloat() / a.size
    }

    private companion object {
        const val THUMB_W = 320
        const val TAG = "NockShots"
        const val MOVE_PIXELS = 40      // pixels that jumped between frames for the scene to count as moving
        const val HANDLED_MS = 3000L    // after the phone moves, wait this long before changes count again
        const val SCENE_CHANGE = 0.3f   // over this fraction changed = zoom / camera moved, not an arrow
        const val STILL_MS = 700L       // how long it must stay still to count as settled
        const val PIXEL_CHANGE = 30     // gray levels for a pixel to count as changed vs. the last still scene
        const val RELEASE_WINDOW_MS = 4000L // release -> settled scene was 1.0-1.2 s in testing
        const val MIN_CHANGED_PIXELS = 120 // of 320×180 ≈ 0.2%: an arrow shaft a few yards out clears this
    }
}

/** Rotate [src] upright and scale it so the result is [width] wide, in one pass. */
fun scaledUpright(src: Bitmap, rotation: Int, width: Int): Bitmap {
    val uprightW = if (rotation % 180 == 0) src.width else src.height
    if (rotation == 0 && width == uprightW) return src
    val scale = width.toFloat() / uprightW
    val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()); postScale(scale, scale) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, scale != 1f)
}
