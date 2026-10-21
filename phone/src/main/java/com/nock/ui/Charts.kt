package com.nock.ui

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nock.data.Motion
import com.nock.data.Session
import com.nock.data.Shot
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Your group on the standard target (WA 40 cm face, see [Target]): every arrow of a session, centred on the
 * spot you aimed at. Ring borders only, in the face's colours; always the same scale, so sessions compare.
 * Each arrow is coloured by the ring it landed in; the group's centre is a hollow ring, so a steady pull
 * (always low-left, say) shows at a glance.
 */
@Composable
fun GroupPlot(shots: List<Shot>, modifier: Modifier, dotDp: Dp = 5.dp, zoom: Float = 1f, pan: Offset = Offset.Zero) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2) + pan
        val k = size.minDimension / 2 * 0.96f / Target.FACE_IN * zoom // px per inch (zoom redraws; lines stay fine)
        val line = (if (size.minDimension > 150.dp.toPx()) 1.5.dp else 1.dp).toPx()
        for (score in 1..10) drawCircle(Target.color(score), radius = Target.radiusIn(score) * k, center = c, style = Stroke(line))
        drawCircle(Target.Gold, radius = Target.X_IN * k, center = c, style = Stroke(line * 0.6f))
        val dr = dotDp.toPx()
        for (s in shots) {
            val p = Offset(c.x + s.rightIn * k, c.y + s.lowIn * k)
            drawCircle(Nock.Surface, radius = dr + 1.5.dp.toPx(), center = p) // keeps overlaps and ring lines readable
            drawCircle(Target.color(Target.score(s.distIn)), radius = dr, center = p)
        }
        if (shots.size >= 3) {
            val b = Offset(c.x + shots.map { it.rightIn }.average().toFloat() * k, c.y + shots.map { it.lowIn }.average().toFloat() * k)
            drawCircle(Nock.Text, radius = dr + 4.dp.toPx(), center = b, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/**
 * The group, interactive: pinch to zoom in on the rings (lines and dots stay crisp), drag to look around, double-tap to
 * reset, tap a dot to open that arrow.
 */
@Composable
fun GroupPlotInteractive(shots: List<Shot>, modifier: Modifier, onShot: (Shot) -> Unit) {
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    GroupPlot(shots, modifier.then(androidx.compose.ui.Modifier.clipToBounds())
        .pointerInput(shots) {
            detectTransformGestures { _, p, z, _ ->
                zoom = (zoom * z).coerceIn(1f, 12f)
                pan = if (zoom == 1f) Offset.Zero else pan + p
            }
        }
        .pointerInput(shots, zoom, pan) {
            detectTapGestures(
                onDoubleTap = { zoom = 1f; pan = Offset.Zero },
                onTap = { t ->
                    val c = Offset(size.width / 2f, size.height / 2f) + pan
                    val k = minOf(size.width, size.height) / 2f * 0.96f / Target.FACE_IN * zoom
                    shots.minByOrNull { s -> (Offset(c.x + s.rightIn * k, c.y + s.lowIn * k) - t).getDistance() }
                        ?.takeIf { s -> (Offset(c.x + s.rightIn * k, c.y + s.lowIn * k) - t).getDistance() < 24.dp.toPx() }
                        ?.let(onShot)
                },
            )
        }, dotDp = 5.dp, zoom = zoom, pan = pan)
}

/** How close, session by session: typical distance from the aim point. Lower is better. Tap a point for its value. */
@Composable
fun TrendChart(sessions: List<Session>, modifier: Modifier) {
    val metric = com.nock.data.Prefs(androidx.compose.ui.platform.LocalContext.current).metric
    val pts = sessions.reversed().takeLast(12) // oldest -> newest
    var picked by remember { mutableStateOf(pts.size - 1) }
    val label = rememberTextMeasurer()
    Box(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(150.dp).pointerInput(pts) {
                detectTapGestures { pos ->
                    if (pts.size < 2) return@detectTapGestures
                    val step = (size.width - 24.dp.toPx()) / (pts.size - 1)
                    picked = ((pos.x - 12.dp.toPx()) / step).toInt().coerceIn(0, pts.size - 1)
                }
            }
        ) {
            if (pts.isEmpty()) return@Canvas
            val top = 22.dp.toPx(); val bottom = size.height - 18.dp.toPx(); val left = 12.dp.toPx(); val right = size.width - 12.dp.toPx()
            val maxV = max(1f, ceil(pts.maxOf { it.medianIn } * 2f) / 2f)
            fun x(i: Int) = if (pts.size == 1) (left + right) / 2 else left + (right - left) * i / (pts.size - 1)
            fun y(v: Float) = bottom - (bottom - top) * (v / maxV)
            // baseline + one reference line, recessive
            drawLine(Nock.Line, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
            drawLine(Nock.Line, Offset(left, y(maxV)), Offset(right, y(maxV)), 1.dp.toPx())
            val ref = label.measure(if (metric) "%.1f mm".format(maxV * 25.4f) else "${"%.1f".format(maxV)}\"", Type.Label.copy(color = Nock.Faint))
            drawText(ref, topLeft = Offset(left, y(maxV) - ref.size.height - 2.dp.toPx()))
            val path = Path()
            pts.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(i), y(s.medianIn)) else path.lineTo(x(i), y(s.medianIn)) }
            drawPath(path, Nock.Teal, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            pts.forEachIndexed { i, s ->
                val on = i == picked
                drawCircle(Nock.Surface, radius = (if (on) 7f else 5f).dp.toPx(), center = Offset(x(i), y(s.medianIn)))
                drawCircle(Nock.Teal, radius = (if (on) 5f else 3.5f).dp.toPx(), center = Offset(x(i), y(s.medianIn)))
            }
            pts.getOrNull(picked)?.let { s ->
                val t = label.measure(com.nock.data.Prefs.len(s.medianIn, metric), Type.Label.copy(color = Nock.Text))
                val px = (x(picked) - t.size.width / 2).coerceIn(0f, size.width - t.size.width)
                drawText(t, topLeft = Offset(px, y(s.medianIn) - t.size.height - 9.dp.toPx()))
                val d = label.measure(java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(s.start), Type.Label.copy(color = Nock.Faint))
                drawText(d, topLeft = Offset((x(picked) - d.size.width / 2).coerceIn(0f, size.width - d.size.width), bottom + 3.dp.toPx()))
            }
        }
    }
}

/**
 * Your aim before the shot, Mantis-style: the path your bow arm drew in the seconds before release,
 * fading in from old to new, ending in the release dot. Rings show how big the wobble is (degrees).
 */
@Composable
fun AimTrace(motion: Motion?, modifier: Modifier) {
    val label = rememberTextMeasurer()
    Box(modifier, contentAlignment = Alignment.Center) {
        if (motion == null || motion.traceX.size < 2) {
            Text("Wear your watch while you shoot\nto see your aim here.", style = Type.Small.copy(color = Nock.Faint, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
            return@Box
        }
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val n = motion.traceX.size
            // centre the trace on where it ended (the release), like a scope view
            val ex = motion.traceX[n - 1]; val ey = motion.traceY[n - 1]
            val maxDeg = max(0.25f, (0 until n).maxOf { hypot(motion.traceX[it] - ex, motion.traceY[it] - ey) })
            val ringDeg = when { maxDeg <= 0.5f -> 0.25f; maxDeg <= 1f -> 0.5f; else -> 1f }
            val rings = ceil(maxDeg / ringDeg).toInt().coerceIn(1, 4)
            val r = size.minDimension / 2 * 0.9f
            val k = r / (rings * ringDeg)
            for (i in 1..rings) drawCircle(Nock.Line, radius = i * ringDeg * k, center = c, style = Stroke(1.dp.toPx()))
            val t = label.measure("${ringDeg}° per ring", Type.Label.copy(color = Nock.Faint))
            drawText(t, topLeft = Offset(size.width - t.size.width, size.height - t.size.height))
            for (i in 1 until n) {
                val a = Offset(c.x + (motion.traceX[i - 1] - ex) * k, c.y - (motion.traceY[i - 1] - ey) * k)
                val b = Offset(c.x + (motion.traceX[i] - ex) * k, c.y - (motion.traceY[i] - ey) * k)
                drawLine(Nock.Teal.copy(alpha = 0.15f + 0.85f * i / n), a, b, 2.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(Nock.Surface, radius = 8.dp.toPx(), center = c)
            drawCircle(Nock.Brick, radius = 6.dp.toPx(), center = c) // the release
        }
    }
}
