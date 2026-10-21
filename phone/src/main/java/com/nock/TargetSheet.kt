package com.nock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A target's menu: a pop-up over the dimmed camera. The name (tap to rename, saves itself), the reference photo with
 * your outline and bullseyes, the face size you measured and each bullseye's size estimated from it, then Edit marks
 * and hold-to-delete. Side by side in landscape, stacked in portrait.
 */
@SuppressLint("ViewConstructor")
class TargetSheet(
    context: Context,
    private val memory: TargetMemory,
    private var face: TargetMemory.Face,
    private val metric: Boolean,
    private val onChanged: (TargetMemory.Face?) -> Unit, // renamed (new face) or deleted (null)
    private val onEdit: (TargetMemory.Face) -> Unit,
    private val onNew: () -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    private val dp = { v: Int -> context.dpi(v) }
    private val name = EditText(context)
    private val sizes = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val widthField: EditText = sizeField(face.widthIn)
    private val heightField: EditText = sizeField(face.heightIn)
    private lateinit var photoView: View

    /** A typeable size in the user's units; a valid change saves itself and updates every number below. */
    private fun sizeField(inches: Double) = EditText(context).apply {
        setText(fmt(inches))
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setTextColor(Brand.Smoke); textSize = 17f; gravity = Gravity.CENTER
        typeface = context.label(17f, bold = true).typeface
        background = pillBackground(context, 0x26F5F5F4)
        setPadding(context.dpi(8), context.dpi(8), context.dpi(8), context.dpi(8))
        setOnEditorActionListener { v, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { finishTyping(); true } else false }
        setOnFocusChangeListener { v, has -> if (!has) (v as EditText).setText(fmt(if (v === widthField) face.widthIn else face.heightIn)) }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = resized()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    private fun fmt(inches: Double) = if (metric) "%.1f".format(inches * 25.4) else "%.2f".format(inches)

    private fun resized() {
        if (!::photoView.isInitialized) return
        val w = widthField.text.toString().toDoubleOrNull()?.let { if (metric) it / 25.4 else it } ?: return
        val h = heightField.text.toString().toDoubleOrNull()?.let { if (metric) it / 25.4 else it } ?: return
        if (w <= 0 || h <= 0 || (w == face.widthIn && h == face.heightIn)) return
        face = memory.resize(face, w, h); onChanged(face)
        renderSizes(); photoView.invalidate()
    }

    /** "17 × 17 in · 5 bullseyes": the whole target in one line. */
    private val summary = context.label(14f, color = Brand.Dim)
    private fun summaryText(): String {
        fun n(v: Double) = if (metric) "%.0f".format(v * 25.4) else if (v % 1.0 == 0.0) "%.0f".format(v) else "%.2f".format(v)
        val b = face.bulls.size
        return n(face.widthIn) + " × " + n(face.heightIn) + (if (metric) " mm" else " in") + if (b > 0) " · $b " + (if (b == 1) "bullseye" else "bullseyes") else ""
    }

    /** Each bullseye: its size and where its centre sits from the target's centre (same list as the Targets screen). */
    private fun renderSizes() {
        summary.text = summaryText()
        sizes.removeAllViews()
        MarkedImage.facts(memory, face, metric).forEach { f ->
            sizes.addView(row(f.label, f.value, f.where), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        if (face.bulls.isNotEmpty()) sizes.addView(context.label(12f, color = Brand.Dim).apply {
            text = MarkedImage.ESTIMATED
            isSingleLine = false
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    init {
        setBackgroundColor(0xB3000000.toInt())
        isClickable = true
        setOnClickListener { close() } // tap outside the card

        val landscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        val photo = MarkedPhoto(context).also { photoView = it }
        val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        name.apply {
            setText(face.name)
            textSize = 24f
            typeface = context.label(24f, bold = true).typeface
            setTextColor(Brand.Smoke)
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, action, ev -> if (action == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { finishTyping(); true } else false }
            setOnFocusChangeListener { _, has -> if (!has) saveName() }
        }
        details.addView(name)

        // One line under the name (the photo shows the rest); tap it for the sizes and each bullseye, in place.
        val more = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val chevron = ChevronIcon(context, right = true).apply { rotation = 90f }
        details.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
            addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)))
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener {
                val open = more.visibility != View.VISIBLE
                more.visibility = if (open) View.VISIBLE else View.GONE
                chevron.animate().rotation(if (open) -90f else 90f).setDuration(150)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        // Size: what you measured, typeable here; everything below follows it as you type.
        more.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(context.label(14f, color = Brand.Dim).apply { text = if (metric) "Size, mm" else "Size, inches" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(widthField, LinearLayout.LayoutParams(dp(78), -2))
            addView(context.label(16f, bold = true, color = Brand.Dim).apply { text = "×"; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(26), -2))
            addView(heightField, LinearLayout.LayoutParams(dp(78), -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        more.addView(sizes, LinearLayout.LayoutParams(-1, -2))
        details.addView(more, LinearLayout.LayoutParams(-1, -2))
        renderSizes()

        val edit = context.label(14f, bold = true).apply {
            text = "Edit marks"; gravity = Gravity.CENTER
            background = pillBackground(context, 0x26F5F5F4)
            setPadding(dp(20), dp(11), dp(20), dp(11))
            setOnClickListener { hideKeyboard(); onEdit(face) }
        }
        val delete = context.label(14f, bold = true, color = Brand.Brick).apply {
            text = "Hold to delete"; gravity = Gravity.CENTER
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setOnClickListener { text = "Hold to delete"; animate().translationX(dp(4).toFloat()).setDuration(60).withEndAction { animate().translationX(0f).setDuration(60) } }
            setOnLongClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                memory.delete(face); onChanged(null); close(); true
            }
        }
        // Done: saves what you typed, confirms it, and closes the menu.
        val done = context.label(14f, bold = true, color = Brand.Carbon).apply {
            text = "Done"; gravity = Gravity.CENTER
            background = pillBackground(context, Brand.FernBright)
            setPadding(dp(22), dp(11), dp(22), dp(11))
            setOnClickListener {
                saveName(); performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                text = "Saved"; postDelayed({ onClose() }, 450)
            }
        }
        details.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(edit)
            addView(delete, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        details.addView(done, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val body = LinearLayout(context).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (landscape) {
                addView(photo, LinearLayout.LayoutParams(0, -1, 1f))
                addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(details) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(22); gravity = Gravity.CENTER_VERTICAL })
            } else {
                addView(photo, LinearLayout.LayoutParams(-1, dp(260)))
                addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            }
        }
        val card = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = context.dp(28f); setColor(Brand.Carbon) }
            setPadding(dp(22), dp(22), dp(22), dp(22))
            isClickable = true // taps on the card don't close it
            addView(body)
        }
        val maxW = if (landscape) (resources.displayMetrics.widthPixels * 0.8f).toInt() else LayoutParams.MATCH_PARENT
        val maxH = if (landscape) (resources.displayMetrics.heightPixels * 0.84f).toInt() else LayoutParams.WRAP_CONTENT
        addView(card, LayoutParams(maxW, maxH, Gravity.CENTER).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
    }

    private fun row(label: String, value: String, sub: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            addView(context.label(14f, color = Brand.Dim).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(context.label(20f, bold = true).apply { text = value })
        })
        addView(context.label(12f, color = Brand.Dim).apply { text = sub; isSingleLine = false; gravity = Gravity.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }

    private fun saveName() {
        val n = name.text.toString().trim()
        if (n.isEmpty()) { name.setText(face.name); return }
        if (n != face.name) { face = memory.rename(face, n); onChanged(face) }
        hideKeyboard(); name.clearFocus()
    }

    /** The keyboard's Done key: save, close the keyboard, a tap of haptics to say it's kept. */
    private fun finishTyping() {
        saveName(); findFocus()?.clearFocus(); hideKeyboard()
        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
    }

    private fun hideKeyboard() = (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)

    private fun close() { saveName(); onClose() }

    /** The marked photo as MarkedImage draws it (outline, numbered bullseyes, centres), rendered off the main thread. */
    private inner class MarkedPhoto(context: Context) : View(context) {
        private var bmp: Bitmap? = null
        private val img = Paint(Paint.FILTER_BITMAP_FLAG)
        private val clip = Path()

        init {
            val f = face
            Thread {
                val r = runCatching { MarkedImage.render(context, memory, f, f.photo, metric, 1200) }.getOrNull()
                    ?: BitmapFactory.decodeFile(f.photo.path)
                post { bmp = r; invalidate() }
            }.start()
        }

        override fun onDraw(canvas: Canvas) {
            val b = bmp ?: return
            val k = minOf(width / b.width.toFloat(), height / b.height.toFloat())
            val dst = RectF((width - b.width * k) / 2, (height - b.height * k) / 2, (width + b.width * k) / 2, (height + b.height * k) / 2)
            clip.reset(); clip.addRoundRect(dst, context.dp(18f), context.dp(18f), Path.Direction.CW)
            canvas.save(); canvas.clipPath(clip); canvas.drawBitmap(b, null, dst, img); canvas.restore()
        }
    }
}
