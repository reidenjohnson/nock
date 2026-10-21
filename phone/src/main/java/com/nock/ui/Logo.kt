package com.nock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The Nock mark: a target ring with the arrow's nock (the V notch) sitting dead centre. */
@Composable
fun LogoMark(size: Dp = 28.dp) {
    Canvas(Modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        drawCircle(Nock.Fern, radius = r * 0.92f, center = c, style = Stroke(r * 0.14f))
        val v = Path().apply {
            moveTo(c.x - r * 0.42f, c.y - r * 0.30f)
            lineTo(c.x, c.y + r * 0.22f)
            lineTo(c.x + r * 0.42f, c.y - r * 0.30f)
        }
        drawPath(v, Nock.Text, style = Stroke(r * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Nock.Fern, radius = r * 0.12f, center = Offset(c.x, c.y + r * 0.52f))
    }
}
