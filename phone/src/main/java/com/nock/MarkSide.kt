package com.nock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * "Mark this side": on a frozen photo of the target, in three steps.
 *  1. Outline: drag four corners onto the face's corners (the whole target).
 *  2. Bullseyes: Circle, Square or Draw (tap a few points; they're smoothed into a clean closed curve, so a
 *     heart takes 5-6 taps). Add as many as you aim at; drag anything to adjust; tap one to select it.
 *  3. Size: the face's real width and height from a tape measure. With the four corners that gives true
 *     inches anywhere on the face, even when the camera is at an angle.
 * Photo coordinates throughout (full-res, upright). A magnifier follows your finger while you drag; pinch to zoom
 * in and two fingers to pan, for exact corners on a target that's small in the frame.
 */
@SuppressLint("ViewConstructor")
class MarkSide(
    context: Context,
    private val photo: Bitmap,
    prefillSpots: List<PointF>,
    prefillRadius: Float,
    private val metric: Boolean,
    private val onSave: (Result) -> Unit,
    private val onCancel: () -> Unit,
    /** Editing a marked target: start from its outline, bullseyes and size. */
    private val existing: TargetMemory.Face? = null,
    /** New target: what Nock thinks it is (outline found + suggestions). */
    private val guess: TargetGuess.Result? = null,
    /** Only line up the outline (adding a view of a known target): one step, then save. [alignName] = which target. */
    private val alignName: String? = null,
) : FrameLayout(context) {

    /** type: "circle" (pts = centre, edge), "square" (pts = two corners), "draw" (pts = control points). */
    class Shape(val type: String, val pts: MutableList<PointF>)
    class Result(val outline: List<PointF>, val widthIn: Float, val heightIn: Float, val bulls: List<Shape>)

    private var step = 0
    private val outline = MutableList(4) { PointF() }
    private val bulls = ArrayList<Shape>()
    private var selected: Shape? = null
    private var tool = "circle"

    private val title = context.label(20f, bold = true).apply { gravity = Gravity.CENTER }
    private val hint = context.label(13f, color = Brand.Dim).apply { gravity = Gravity.CENTER; isSingleLine = false; maxLines = 2 }
    private val canvasView = Board(context)
    private val tools = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val toolPills = HashMap<String, TextView>()
    private val sizeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val widthField = numberField("Width")
    private val heightField = numberField("Height")
    private val back: TextView
    private val next: TextView

    init {
        setBackgroundColor(android.graphics.Color.BLACK) // the photo is the screen; nothing grey around it
        isClickable = true
        val dp = { v: Int -> context.dpi(v) }
        // Outline starts from the existing marks, else around the tape spots, else a centred box.
        val ex = existing?.takeIf { it.marked }
        if (ex != null) {
            ex.outline!!.forEachIndexed { i, p -> outline[i].set(p.x.toFloat(), p.y.toFloat()) }
            ex.bulls.forEach { (type, pts) -> bulls.add(Shape(type, pts.map { PointF(it.x.toFloat(), it.y.toFloat()) }.toMutableList())) }
            selected = bulls.lastOrNull()
        } else if (guess?.outline != null) {
            guess.outline.forEachIndexed { i, p -> outline[i].set(p.x, p.y) }
        } else if (prefillSpots.size >= 2) {
            val x0 = prefillSpots.minOf { it.x }; val x1 = prefillSpots.maxOf { it.x }; val y0 = prefillSpots.minOf { it.y }; val y1 = prefillSpots.maxOf { it.y }
            val mx = (x1 - x0) * 0.35f; val my = (y1 - y0) * 0.35f
            setQuad(x0 - mx, y0 - my, x1 + mx, y1 + my)
            prefillSpots.forEach { bulls.add(Shape("circle", mutableListOf(PointF(it.x, it.y), PointF(it.x + prefillRadius, it.y)))) }
        } else setQuad(photo.width * 0.36f, photo.height * 0.3f, photo.width * 0.64f, photo.height * 0.7f)

        for ((key, name) in listOf("circle" to "Circle", "square" to "Square", "draw" to "Draw")) {
            val pill = context.label(13f, bold = true).apply {
                text = name; gravity = Gravity.CENTER; setPadding(dp(16), dp(9), dp(16), dp(9))
                setOnClickListener { tool = key; addShape(); renderTools() }
            }
            toolPills[key] = pill
            tools.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        tools.addView(context.label(13f, bold = true, color = Brand.Brick).apply {
            text = "Remove"; gravity = Gravity.CENTER; setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { selected?.let { bulls.remove(it) }; selected = bulls.lastOrNull(); canvasView.invalidate(); renderTools() }
        })
        sizeRow.addView(widthField, LinearLayout.LayoutParams(dp(130), -2).apply { marginEnd = dp(10) })
        sizeRow.addView(heightField, LinearLayout.LayoutParams(dp(130), -2))

        back = pill("Cancel", false) { if (step == 0) onCancel() else go(step - 1) }
        next = pill("Next", true) {
            when {
                alignName != null -> onSave(Result(outline.map { PointF(it.x, it.y) }, 0f, 0f, emptyList()))
                step < 2 -> go(step + 1)
                else -> save()
            }
        }
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(back, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
            addView(next)
        }
        // Full-bleed photo; everything else floats over it on glass, like the camera screen.
        fun glass(r: Float) = android.graphics.drawable.GradientDrawable().apply { cornerRadius = context.dp(r); setColor(Brand.Glass) }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = glass(20f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            addView(title.apply { textSize = 16f })
            addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        }
        tools.background = glass(999f); tools.setPadding(dp(6), dp(6), dp(6), dp(6))
        val foot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(tools, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })
            addView(sizeRow, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })
            addView(nav)
        }
        addView(canvasView, LayoutParams(-1, -1))
        addView(head, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) })
        // What Nock thinks this is: the best guess with Use, other ideas as small chips. Nothing changes until you pick.
        guess?.suggestions?.takeIf { it.isNotEmpty() && existing == null && alignName == null }?.let { list ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                background = glass(20f); setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            fun use(sg: TargetGuess.Suggestion) {
                sg.outline?.forEachIndexed { i, p -> outline[i].set(p.x, p.y) }
                if (sg.bulls.isNotEmpty()) { bulls.clear(); sg.bulls.forEach { bulls.add(Shape(it.type, it.pts.map { p -> PointF(p.x, p.y) }.toMutableList())) }; selected = bulls.lastOrNull() }
                fun fmt(v: Double) = if (metric) "%.1f".format(v * 25.4) else "%.2f".format(v)
                if (sg.widthIn > 0) { widthField.setText(fmt(sg.widthIn)); heightField.setText(fmt(sg.heightIn)) } // paper: you measure it
                card.visibility = GONE
                canvasView.invalidate(); renderTools()
            }
            // One idea at a time: what it is, where the idea came from, Use or Not it (Not it shows the next idea, if any).
            val tag = context.label(11f, bold = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.08f }
            val name = context.label(15f, bold = true).apply { gravity = Gravity.CENTER; isSingleLine = false; maxLines = 3 }
            val detail = context.label(12f, color = Brand.Dim).apply { gravity = Gravity.CENTER; isSingleLine = false; maxLines = 3 }
            var at = 0
            fun showIdea() {
                val sg = list[at]; val saved = sg.face != null
                tag.text = if (saved) "SAVED TARGET" else "DETECTED"
                tag.setTextColor(if (saved) Brand.TealBright else Brand.Amber)
                name.text = sg.name
                detail.text = when {
                    saved -> "Recognised from before. Uses its outline, size and bullseyes."
                    sg.bulls.isNotEmpty() -> "New. A bullseye on each 10 ring. You'll measure the paper next."
                    sg.widthIn > 0 -> "New. Fills in the size; you mark the bullseyes."
                    else -> "New target."
                }
            }
            card.addView(tag); card.addView(name, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            card.addView(detail, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            card.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                addView(pill("Not it", false) { if (at < list.size - 1) { at++; showIdea() } else card.visibility = GONE })
                addView(pill("Use", true) { use(list[at]) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            showIdea()
            // Out of the target's way: a narrow card beside it in landscape, above the buttons in portrait.
            val wide = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
            card.minimumWidth = 0
            addView(card, if (wide) LayoutParams(dp(270), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16) }
                else LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(84) })
        }
        addView(foot, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })
        // Editing: the size you measured last time (set after the buttons exist; typing re-checks them).
        ex?.let { f ->
            fun fmt(v: Double) = if (metric) "%.1f".format(v * 25.4) else "%.2f".format(v)
            widthField.setText(fmt(f.widthIn)); heightField.setText(fmt(f.heightIn))
        }
        go(0)
    }

    private fun setQuad(x0: Float, y0: Float, x1: Float, y1: Float) {
        val cx0 = x0.coerceIn(0f, photo.width - 1f); val cx1 = x1.coerceIn(0f, photo.width - 1f)
        val cy0 = y0.coerceIn(0f, photo.height - 1f); val cy1 = y1.coerceIn(0f, photo.height - 1f)
        outline[0].set(cx0, cy0); outline[1].set(cx1, cy0); outline[2].set(cx1, cy1); outline[3].set(cx0, cy1)
    }

    private fun go(s: Int) {
        step = s
        title.text = listOf("Outline the target", "Mark your bullseyes", "Real size")[s]
        hint.text = listOf(
            "Drag the four corners onto the corners of the face.",
            "Pick a shape. Drag to move or resize. Draw: tap points around it, it smooths itself.",
            "Measure the outlined face with a tape measure (${if (metric) "mm" else "inches"}).",
        )[s]
        tools.visibility = if (s == 1) VISIBLE else GONE
        sizeRow.visibility = if (s == 2) VISIBLE else GONE
        back.text = if (s == 0) "Cancel" else "Back"
        next.text = if (s == 2) "Save target" else "Next"
        if (alignName != null) { title.text = "Line up $alignName"; hint.text = "Drag the four corners onto its corners. This photo teaches Nock another look of it."; next.text = "Save view" }
        if (s == 1 && bulls.isEmpty()) addShape()
        if (s != 2) hideKeyboard()
        renderTools(); canvasView.invalidate()
    }

    /** A new shape of the current tool, in the middle of the outline (Draw starts empty: tap to add points). */
    private fun addShape() {
        val cx = outline.map { it.x }.average().toFloat(); val cy = outline.map { it.y }.average().toFloat()
        val r = hypot(outline[1].x - outline[0].x, outline[1].y - outline[0].y) * 0.08f
        val s = when (tool) {
            "circle" -> Shape("circle", mutableListOf(PointF(cx, cy), PointF(cx + r, cy)))
            // four corners you can drag on their own, so a square can follow the target's tilt
            "square" -> Shape("square", Shapes.squareOnFace(PointF(cx, cy), r, outline))
            else -> Shape("draw", mutableListOf())
        }
        // An empty Draw shape that's still selected is reused rather than stacking up blanks.
        if (s.type == "draw" && selected?.type == "draw" && selected!!.pts.size < 3) { canvasView.invalidate(); return }
        bulls.add(s); selected = s; canvasView.invalidate()
    }

    private fun renderTools() {
        toolPills.forEach { (k, v) ->
            val on = selected?.type == k
            v.background = pillBackground(context, if (on) Brand.Smoke else 0x26F5F5F4)
            v.setTextColor(if (on) Brand.Carbon else Brand.Smoke)
        }
        val ok = when (step) { 0 -> true; 1 -> bulls.any { it.valid() }; else -> size() != null }
        next.alpha = if (ok) 1f else 0.4f; next.isEnabled = ok
    }

    private fun Shape.valid() = when (type) { "draw" -> pts.size >= 3; "square" -> pts.size == 2 || pts.size == 4; else -> pts.size == 2 }

    private fun size(): Pair<Float, Float>? {
        val w = widthField.text.toString().toFloatOrNull() ?: return null
        val h = heightField.text.toString().toFloatOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return if (metric) w / 25.4f to h / 25.4f else w to h
    }

    private fun save() {
        val (w, h) = size() ?: return
        hideKeyboard()
        onSave(Result(outline.map { PointF(it.x, it.y) }, w, h, bulls.filter { it.valid() }))
    }

    private fun hideKeyboard() = (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)

    private fun numberField(label: String) = EditText(context).apply {
        hint = label
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        // keep the screen visible while typing (landscape keyboards otherwise take it over)
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        setTextColor(Brand.Smoke); setHintTextColor(Brand.Dim)
        textSize = 17f; gravity = Gravity.CENTER
        background = pillBackground(context) // glass, readable over any photo
        setPadding(context.dpi(14), context.dpi(10), context.dpi(14), context.dpi(10))
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = renderTools()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    private fun pill(text: String, primary: Boolean, onTap: () -> Unit) = context.label(14f, bold = true, color = if (primary) Brand.Carbon else Brand.Smoke).apply {
        this.text = text; gravity = Gravity.CENTER
        background = pillBackground(context, if (primary) Brand.FernBright else 0x26F5F5F4)
        setPadding(context.dpi(22), context.dpi(11), context.dpi(22), context.dpi(11))
        setOnClickListener { onTap() }
    }

    /** The photo with the outline and bullseyes; all the dragging happens here. */
    private inner class Board(context: Context) : View(context) {
        private val toView = Matrix(); private val toPhoto = Matrix()
        private val fit = Matrix(); private val user = Matrix() // fit the photo, then your pinch/pan on top
        private var pinching = false
        private var cover = 1f // zoom at which the photo fills the whole screen
        private val pinch = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                val vals = FloatArray(9); user.getValues(vals)
                val f = (vals[Matrix.MSCALE_X] * d.scaleFactor).coerceIn(cover, 10f) / vals[Matrix.MSCALE_X]
                user.postScale(f, f, d.focusX, d.focusY); update(); return true
            }
        })
        private var panFrom: PointF? = null
        private fun update() {
            toView.set(fit); toView.postConcat(user)
            // Keep the photo covering the whole screen: never pan past its edges (no black strips).
            val r = RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat()); toView.mapRect(r)
            var dx = 0f; var dy = 0f
            if (r.width() >= width) { if (r.left > 0) dx = -r.left else if (r.right < width) dx = width - r.right } else dx = width / 2f - r.centerX()
            if (r.height() >= height) { if (r.top > 0) dy = -r.top else if (r.bottom < height) dy = height - r.bottom } else dy = height / 2f - r.centerY()
            if (dx != 0f || dy != 0f) { user.postTranslate(dx, dy); toView.set(fit); toView.postConcat(user) }
            toView.invert(toPhoto); invalidate()
        }
        private val neon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF39FF14.toInt(); style = Paint.Style.STROKE; strokeWidth = context.dp(1f) } // thin: the corner point stays visible
        private val neonFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3339FF14 }
        private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8839FF14.toInt(); style = Paint.Style.STROKE; strokeWidth = context.dp(1f); pathEffect = DashPathEffect(floatArrayOf(context.dp(6f), context.dp(5f)), 0f) }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3F5F5F4.toInt(); style = Paint.Style.STROKE; strokeWidth = context.dp(1f) } // see-through
        private val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brand.Smoke; strokeWidth = context.dp(0.75f); setShadowLayer(context.dp(1f), 0f, 0f, 0xCC000000.toInt()) }
        private val shadowRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt(); style = Paint.Style.STROKE; strokeWidth = context.dp(0.75f) }
        private val dim = Paint().apply { color = 0x66000000 }
        private val img = Paint(Paint.FILTER_BITMAP_FLAG)
        private var drag: PointF? = null           // the point being dragged (a corner, a shape point)
        private var moving: Shape? = null           // a whole shape being moved
        private var last = PointF()
        private var downAt = PointF(); private var moved = false
        private var panning = false                 // one finger on empty photo: move the page
        // Our own magnifier: a bigger window beside your finger, a gentle 2.5x of the screen, with the lines
        // redrawn thin at that zoom (the system magnifier just enlarged them).
        private var loupeAt: PointF? = null; private var loupeFingerX = 0f
        private val loupeFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCF5F5F4.toInt(); style = Paint.Style.STROKE; strokeWidth = context.dp(1f) }
        private val loupe = object {
            fun dismiss() { loupeAt = null; invalidate() }
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val k = minOf(w / photo.width.toFloat(), h / photo.height.toFloat())
            fit.setScale(k, k); fit.postTranslate((w - photo.width * k) / 2, (h - photo.height * k) / 2)
            // Open zoomed in on the outline, so the target fills the screen instead of a small photo in the middle.
            val bx0 = outline.minOf { it.x } * k; val bx1 = outline.maxOf { it.x } * k
            val by0 = outline.minOf { it.y } * k; val by1 = outline.maxOf { it.y } * k
            // ...and never smaller than filling the screen (no black bars, portrait or landscape).
            cover = maxOf(w / (photo.width * k), h / (photo.height * k))
            val z = minOf(w * 0.7f / (bx1 - bx0), h * 0.6f / (by1 - by0)).coerceIn(cover, 10f)
            val ox = (w - photo.width * k) / 2; val oy = (h - photo.height * k) / 2
            user.reset(); user.setScale(z, z)
            user.postTranslate(w / 2f - (ox + (bx0 + bx1) / 2) * z, h / 2f - (oy + (by0 + by1) / 2) * z)
            update()
        }

        private fun v(p: PointF): PointF { val a = floatArrayOf(p.x, p.y); toView.mapPoints(a); return PointF(a[0], a[1]) }
        private fun ph(x: Float, y: Float): PointF { val a = floatArrayOf(x, y); toPhoto.mapPoints(a); return PointF(a[0], a[1]) }

        override fun onDraw(canvas: Canvas) {
            canvas.save(); canvas.concat(toView); canvas.drawBitmap(photo, 0f, 0f, img); canvas.restore()
            val q = outline.map { v(it) }
            val outlinePath = Path().apply { moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            if (step == 0) {
                // dim everything outside the outline
                canvas.save(); canvas.clipOutPath(outlinePath); canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim); canvas.restore()
                canvas.drawPath(outlinePath, neon)
                q.forEach { drawHandle(canvas, it, corner = true) }
            } else canvas.drawPath(outlinePath, faint)
            if (step >= 1) for (s in bulls) {
                val path = shapePath(s) ?: continue
                canvas.drawPath(path, neonFill); canvas.drawPath(path, neon)
                // a circle grows from its centre: a fine cross there, so you can see where the centre sits as you size it
                if (s.type == "circle" && s.pts.isNotEmpty()) centreCross(canvas, v(s.pts[0]))
                if (step == 1 && s === selected) handlesOf(s).forEach { drawHandle(canvas, it) }
            }
            if (step == 1 && selected?.type == "draw") selected!!.pts.forEach { drawHandle(canvas, v(it), small = true) }
            loupeAt?.let { drawLoupe(canvas, it) }
        }

        /** The magnifier: 2.5x around [p] (view coords), beside the finger, lines thin, the point marked by a fine cross. */
        private fun drawLoupe(canvas: Canvas, p: PointF) {
            val size = context.dp(180f); val half = size / 2; val off = context.dp(140f)
            val cx = if (loupeFingerX > width / 2f) (loupeFingerX - off).coerceAtLeast(half + context.dp(8f)) else (loupeFingerX + off).coerceAtMost(width - half - context.dp(8f))
            val cy = p.y.coerceIn(half + context.dp(8f), height - half - context.dp(8f))
            val z = 2.5f
            val l = Matrix(toView); l.postTranslate(-p.x, -p.y); l.postScale(z, z); l.postTranslate(cx, cy)
            fun m(q: PointF): PointF { val a = floatArrayOf(q.x, q.y); l.mapPoints(a); return PointF(a[0], a[1]) }
            val box = RectF(cx - half, cy - half, cx + half, cy + half)
            val clip = Path().apply { addRoundRect(box, context.dp(20f), context.dp(20f), Path.Direction.CW) }
            canvas.save(); canvas.clipPath(clip)
            canvas.drawColor(Brand.Carbon)
            canvas.save(); canvas.concat(l); canvas.drawBitmap(photo, 0f, 0f, img); canvas.restore()
            if (step == 0) {
                val q = outline.map { m(it) }
                canvas.drawPath(Path().apply { moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) }; close() }, neon)
            } else for (sh in bulls) {
                val raw = if (sh.type == "draw") { if (sh.pts.size < 2) continue; Shapes.smooth(sh.pts) } else Shapes.polygon(sh.type, sh.pts, outline)
                val poly = raw.map { m(it) }
                canvas.drawPath(Path().apply { moveTo(poly[0].x, poly[0].y); poly.drop(1).forEach { lineTo(it.x, it.y) }; if (sh.type != "draw" || sh.pts.size >= 3) close() }, neon)
                if (sh.type == "circle" && sh.pts.isNotEmpty()) centreCross(canvas, m(sh.pts[0]))
            }
            val arm = context.dp(7f)
            canvas.drawLine(cx - arm, cy, cx + arm, cy, pin); canvas.drawLine(cx, cy - arm, cx, cy + arm, pin)
            canvas.restore()
            canvas.drawPath(clip, loupeFrame)
        }

        private fun centreCross(c: Canvas, p: PointF) {
            val arm = context.dp(5f)
            c.drawLine(p.x - arm, p.y, p.x + arm, p.y, neon); c.drawLine(p.x, p.y - arm, p.x, p.y + arm, neon)
        }

        /** A hollow ring: see-through, so exactly where the lines meet stays visible, even zoomed in. */
        // Small to see past; the finger reach (20 dp, corners 28) is separate from how big they look.
        private fun drawHandle(c: Canvas, p: PointF, small: Boolean = false, corner: Boolean = false) {
            val r = context.dp(if (corner) 6f else if (small) 3f else 4f)
            c.drawCircle(p.x, p.y, r + context.dp(1f), shadowRing) // thin dark edge: readable on light or dark
            c.drawCircle(p.x, p.y, r, handleRing)
            // a fine cross on the exact point, so you can put the corner right on it
            if (corner) { val arm = context.dp(3f); c.drawLine(p.x - arm, p.y, p.x + arm, p.y, pin); c.drawLine(p.x, p.y - arm, p.x, p.y + arm, pin) }
        }

        /** Handles (view coords) for a selected circle/square. */
        private fun handlesOf(s: Shape): List<PointF> = when (s.type) {
            "circle" -> listOf(v(s.pts[1]))
            "square" -> s.pts.map { v(it) }
            else -> emptyList()
        }

        private fun shapePath(s: Shape): Path? {
            val p = Path()
            when (s.type) {
                "circle" -> { val q = Shapes.polygon("circle", s.pts, outline).map { v(it) }; p.moveTo(q[0].x, q[0].y); q.drop(1).forEach { p.lineTo(it.x, it.y) }; p.close() }
                "square" -> { val q = Shapes.polygon("square", s.pts, outline).map { v(it) }; p.moveTo(q[0].x, q[0].y); q.drop(1).forEach { p.lineTo(it.x, it.y) }; p.close() }
                else -> {
                    if (s.pts.size < 2) return null
                    val pts = Shapes.smooth(s.pts).map { v(it) }
                    p.moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { p.lineTo(it.x, it.y) }; if (s.pts.size >= 3) p.close()
                }
            }
            return p
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val reach = context.dp(20f) // grab zone: a little tighter so you grab what you mean
            // Two fingers: zoom and pan the photo (never move a handle).
            pinch.onTouchEvent(e)
            if (e.pointerCount > 1 || pinching) {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) { pinching = true; drag = null; moving = null; panning = false; loupe.dismiss(); panFrom = mid(e) }
                if (e.actionMasked == MotionEvent.ACTION_MOVE && e.pointerCount > 1) {
                    val m = mid(e); panFrom?.let { user.postTranslate(m.x - it.x, m.y - it.y); update() }; panFrom = m
                }
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { pinching = false; panFrom = null }
                return true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = PointF(e.x, e.y); last = PointF(e.x, e.y); moved = false; drag = null; moving = null; panning = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (step == 0) drag = outline.minByOrNull { val p = v(it); hypot(p.x - e.x, p.y - e.y) }?.takeIf { val p = v(it); hypot(p.x - e.x, p.y - e.y) < reach * 1.4f }
                    if (step == 1) {
                        val sel = selected
                        // a handle of the selected shape, or a point of a Draw shape
                        val pts = when (sel?.type) { "circle" -> listOf(sel.pts[1]); "square" -> sel.pts; "draw" -> sel.pts; else -> emptyList() }
                        drag = pts.minByOrNull { val p = v(it); hypot(p.x - e.x, p.y - e.y) }?.takeIf { val p = v(it); hypot(p.x - e.x, p.y - e.y) < reach }
                        if (drag == null) moving = bulls.lastOrNull { contains(it, e.x, e.y) }
                    }
                    // Nothing under the finger: one finger moves the page. The magnifier only shows while placing.
                    panning = drag == null && moving == null
                    drag?.let { showLoupe(v(it).x, v(it).y, e.x) }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.x - downAt.x, e.y - downAt.y) > context.dp(6f)) moved = true
                    if (panning) {
                        if (moved) { user.postTranslate(e.x - last.x, e.y - last.y); update() }
                        last = PointF(e.x, e.y); return true
                    }
                    val a = ph(last.x, last.y); val b = ph(e.x, e.y); val dx = b.x - a.x; val dy = b.y - a.y
                    drag?.let { it.x = (it.x + dx).coerceIn(0f, photo.width - 1f); it.y = (it.y + dy).coerceIn(0f, photo.height - 1f) }
                    moving?.let { s -> s.pts.forEach { it.x += dx; it.y += dy }; selected = s }
                    last = PointF(e.x, e.y)
                    drag?.let { showLoupe(v(it).x, v(it).y, e.x) }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    loupe.dismiss()
                    if (step == 1 && !moved && e.actionMasked == MotionEvent.ACTION_UP) {
                        val hitShape = bulls.lastOrNull { contains(it, e.x, e.y) }
                        val sel = selected
                        when {
                            // Draw: a tap adds the next point
                            sel?.type == "draw" && drag == null && (hitShape == null || hitShape === sel) -> sel.pts.add(ph(e.x, e.y))
                            hitShape != null -> selected = hitShape
                        }
                    }
                    drag = null; moving = null; panning = false
                    invalidate(); renderTools()
                }
            }
            return true
        }

        /**
         * The magnifier sits beside the finger (left or right, whichever side has room), at the point's height, so the
         * finger never covers it. [fingerX] picks the side.
         */
        private fun showLoupe(x: Float, y: Float, fingerX: Float) { loupeAt = PointF(x, y); loupeFingerX = fingerX; invalidate() }

        private fun mid(e: MotionEvent) = PointF((e.getX(0) + e.getX(1)) / 2, (e.getY(0) + e.getY(1)) / 2)

        private fun contains(s: Shape, x: Float, y: Float): Boolean {
            val p = ph(x, y)
            return Shapes.contains(s.type, s.pts, p, outline)
        }
    }
}

/** Shape maths shared by marking (drawing) and scoring (is the hit inside?). Photo coordinates. */
object Shapes {
    /** A closed Catmull-Rom curve through the points, so a few taps make a smooth shape. */
    fun smooth(pts: List<PointF>, per: Int = 10): List<PointF> {
        if (pts.size < 3) return pts
        val out = ArrayList<PointF>()
        val n = pts.size
        for (i in 0 until n) {
            val p0 = pts[(i - 1 + n) % n]; val p1 = pts[i]; val p2 = pts[(i + 1) % n]; val p3 = pts[(i + 2) % n]
            for (k in 0 until per) {
                val t = k / per.toFloat(); val t2 = t * t; val t3 = t2 * t
                out.add(PointF(
                    0.5f * (2 * p1.x + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3),
                    0.5f * (2 * p1.y + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3),
                ))
            }
        }
        return out
    }

    /**
     * The flat face behind a photo outline: photo -> face (in the outline's own pixel size) and back. Used to keep
     * circles round ON the target, so on a tilted target they show as the right ellipse.
     */
    fun plane(outline: List<PointF>): Pair<android.graphics.Matrix, android.graphics.Matrix>? {
        if (outline.size != 4) return null
        fun d(a: PointF, b: PointF) = hypot(b.x - a.x, b.y - a.y)
        val w = (d(outline[0], outline[1]) + d(outline[3], outline[2])) / 2; val h = (d(outline[0], outline[3]) + d(outline[1], outline[2])) / 2
        if (w < 1 || h < 1) return null
        val src = outline.flatMap { listOf(it.x, it.y) }.toFloatArray()
        val toFace = android.graphics.Matrix(); if (!toFace.setPolyToPoly(src, 0, floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, 4)) return null
        val toPhoto = android.graphics.Matrix(); if (!toFace.invert(toPhoto)) return null
        return toFace to toPhoto
    }

    private fun map(m: android.graphics.Matrix, p: PointF): PointF { val a = floatArrayOf(p.x, p.y); m.mapPoints(a); return PointF(a[0], a[1]) }

    /** A square on the face around [c] (photo coords), [r] half-size in photo pixels: four corners in perspective. */
    fun squareOnFace(c: PointF, r: Float, outline: List<PointF>?): MutableList<PointF> {
        val pl = outline?.let { plane(it) } ?: return mutableListOf(PointF(c.x - r, c.y - r), PointF(c.x + r, c.y - r), PointF(c.x + r, c.y + r), PointF(c.x - r, c.y + r))
        val fc = map(pl.first, c)
        return listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (sx, sy) -> map(pl.second, PointF(fc.x + sx * r, fc.y + sy * r)) }.toMutableList()
    }

    /** The outline of a shape as a polygon. With the target's [outline], a circle is round on the face (an ellipse here). */
    fun polygon(type: String, pts: List<PointF>, outline: List<PointF>?): List<PointF> {
        if (type == "circle" && pts.size == 2) plane(outline ?: return polygon(type, pts))?.let { (toFace, toPhoto) ->
            val c = map(toFace, pts[0]); val e = map(toFace, pts[1]); val r = hypot(e.x - c.x, e.y - c.y)
            return (0 until 64).map { val a = it * Math.PI * 2 / 64; map(toPhoto, PointF(c.x + r * kotlin.math.cos(a).toFloat(), c.y + r * kotlin.math.sin(a).toFloat())) }
        }
        // A square saved by two opposite corners: square ON the face, so it turns and tilts with the target (built
        // level in the photo instead, a turned camera made it a thin strip, 2026-10-08).
        if (type == "square" && pts.size == 2) plane(outline ?: return polygon(type, pts))?.let { (toFace, toPhoto) ->
            val a = map(toFace, pts[0]); val b = map(toFace, pts[1])
            return listOf(PointF(a.x, a.y), PointF(b.x, a.y), PointF(b.x, b.y), PointF(a.x, b.y)).map { map(toPhoto, it) }
        }
        return polygon(type, pts)
    }

    fun contains(type: String, pts: List<PointF>, p: PointF, outline: List<PointF>?): Boolean =
        if ((type == "circle" || (type == "square" && pts.size == 2)) && outline != null) inPoly(polygon(type, pts, outline), p) else contains(type, pts, p)

    private fun inPoly(poly: List<PointF>, p: PointF): Boolean {
        var inside = false; var j = poly.size - 1
        for (i in poly.indices) { val a = poly[i]; val b = poly[j]; if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside; j = i }
        return inside
    }

    /** The outline of a shape as a polygon (circles and squares included). */
    fun polygon(type: String, pts: List<PointF>): List<PointF> = when (type) {
        "circle" -> { val c = pts[0]; val r = hypot(pts[1].x - c.x, pts[1].y - c.y); (0 until 48).map { val a = it * Math.PI * 2 / 48; PointF(c.x + r * kotlin.math.cos(a).toFloat(), c.y + r * kotlin.math.sin(a).toFloat()) } }
        "square" -> if (pts.size == 4) pts.toList() else { val a = pts[0]; val b = pts[1]; listOf(PointF(a.x, a.y), PointF(b.x, a.y), PointF(b.x, b.y), PointF(a.x, b.y)) }
        else -> smooth(pts)
    }

    fun contains(type: String, pts: List<PointF>, p: PointF): Boolean {
        if (type == "circle") return pts.size == 2 && hypot(p.x - pts[0].x, p.y - pts[0].y) <= hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y)
        val poly = polygon(type, pts); if (poly.size < 3) return false
        var inside = false; var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Area-weighted centre of a polygon (the aim point of an odd shape like a heart). */
    fun centre(poly: List<PointF>): PointF {
        var a = 0.0; var cx = 0.0; var cy = 0.0
        for (i in poly.indices) {
            val p = poly[i]; val q = poly[(i + 1) % poly.size]
            val c = p.x.toDouble() * q.y - q.x.toDouble() * p.y
            a += c; cx += (p.x + q.x) * c; cy += (p.y + q.y) * c
        }
        if (kotlin.math.abs(a) < 1e-6) return PointF(poly.map { it.x }.average().toFloat(), poly.map { it.y }.average().toFloat())
        return PointF((cx / (3 * a)).toFloat(), (cy / (3 * a)).toFloat())
    }
}
