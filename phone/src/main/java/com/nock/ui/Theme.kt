package com.nock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nock.R

/**
 * Nock's look: dark carbon ground (it lives next to a camera), Reiden's brand palette lifted for
 * dark (same values as Kairos' dark theme), Bricolage Grotesque for display, Hanken Grotesk for text.
 * Colour by meaning: Fern = good/primary, Teal = trends/links, Amber = needs you, Brick = off/miss only.
 */
object Nock {
    val Bg = Color(0xFF19181A)
    val Surface = Color(0xFF232224)
    val Surface2 = Color(0xFF2C2B2E)
    val Line = Color(0x1AF1F0F2)
    val Text = Color(0xFFECEBEC)
    val Dim = Color(0xFFA8A7A9)
    val Faint = Color(0xFF767579)
    val Fern = Color(0xFF8CB06B)
    val FernDeep = Color(0xFF4E6A37)
    val FernDeeper = Color(0xFF3A5029)
    val Teal = Color(0xFF57A6BA)
    val Amber = Color(0xFFE89A45)
    val Brick = Color(0xFFE1614E)
    val Neon = Color(0xFF39FF14) // target marks, like a red-dot optic's glow
}

private val Bricolage = FontFamily(
    Font(R.font.bricolage_grotesque, FontWeight.Medium),
    Font(R.font.bricolage_grotesque, FontWeight.Bold),
    Font(R.font.bricolage_grotesque, FontWeight.ExtraBold),
)
private val Hanken = FontFamily(
    Font(R.font.hanken_grotesk, FontWeight.Normal),
    Font(R.font.hanken_grotesk, FontWeight.Medium),
    Font(R.font.hanken_grotesk, FontWeight.SemiBold),
    Font(R.font.hanken_grotesk, FontWeight.Bold),
)

object Type {
    val Hero = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 56.sp, lineHeight = 56.sp, letterSpacing = (-1.5).sp)
    val Title = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp)
    val Heading = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp)
    val Number = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 26.sp)
    val Body = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
    val Label = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp)
    val Small = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp)
}

@Composable
fun NockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Nock.Bg, surface = Nock.Surface, primary = Nock.Fern,
            onBackground = Nock.Text, onSurface = Nock.Text, onPrimary = Nock.Bg,
        ),
        content = content,
    )
}
