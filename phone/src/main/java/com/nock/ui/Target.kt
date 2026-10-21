package com.nock.ui

import androidx.compose.ui.graphics.Color

/**
 * The one target every group is drawn on: the World Archery 40 cm face (the standard indoor 18 m target).
 * Ten scoring rings, each 2 cm wide, plus the inner X ring (2 cm across). Colours by pairs, centre out:
 * gold 10-9, red 8-7, light blue 6-5, black 4-3, white 2-1 (WA's PMS 107 / 032 / 306).
 * Scored by where the arrow's centre is (no line-cutter rule yet).
 */
object Target {
    const val CM = 1f / 2.54f                 // inches
    const val RING_IN = 2f * CM               // each ring's width
    const val X_IN = 1f * CM                  // X ring radius
    const val FACE_IN = 10 * RING_IN          // outer radius: 20 cm

    val Gold = Color(0xFFFFE552)
    val Red = Color(0xFFEF3340)
    val Blue = Color(0xFF00B3E3)
    // Black and white zones on a dark screen: black lifted just enough to see, white as the app's text white.
    val Black = Color(0xFF5E5D62)
    val White = Color(0xFFECEBEC)

    /** Outer radius of ring [score] (10 = the gold centre, 1 = the outer white), inches. */
    fun radiusIn(score: Int) = (11 - score) * RING_IN

    /** 10..1, or 0 off the face. */
    fun score(distIn: Float): Int = if (distIn > FACE_IN) 0 else (10 - (distIn / RING_IN).toInt()).coerceIn(1, 10)

    fun isX(distIn: Float) = distIn <= X_IN

    fun color(score: Int): Color = when (score) {
        10, 9 -> Gold; 8, 7 -> Red; 6, 5 -> Blue; 4, 3 -> Black; 2, 1 -> White
        else -> Nock.Faint
    }

    fun label(distIn: Float): String = when (val s = score(distIn)) { 0 -> "Miss"; 10 -> if (isX(distIn)) "X" else "10"; else -> "$s" }
}
