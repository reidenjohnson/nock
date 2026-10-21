package com.nock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A drawn plus (the "+" glyph renders thin and varies by font). */
@Composable
fun PlusIcon(color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) {
        val w = 2.2.dp.toPx(); val c = this.size.width / 2
        drawLine(color, Offset(c, w / 2), Offset(c, this.size.height - w / 2), w, cap = StrokeCap.Round)
        drawLine(color, Offset(w / 2, c), Offset(this.size.width - w / 2, c), w, cap = StrokeCap.Round)
    }
}

/** A drawn minus, the same weight as [PlusIcon]. */
@Composable
fun MinusIcon(color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) {
        val w = 2.2.dp.toPx(); val c = this.size.height / 2
        drawLine(color, Offset(w / 2, c), Offset(this.size.width - w / 2, c), w, cap = StrokeCap.Round)
    }
}
