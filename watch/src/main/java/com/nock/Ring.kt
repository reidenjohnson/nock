package com.nock

/**
 * The standard target the phone draws every group on (World Archery 40 cm indoor face): ten 2 cm rings,
 * X ring 1 cm radius; gold 10-9, red 8-7, light blue 6-5, black 4-3, white 2-1. Same as the phone's ui/Target.kt.
 */
object Ring {
    private const val RING_IN = 2f / 2.54f
    private const val X_IN = 1f / 2.54f

    fun score(distIn: Float): Int = if (distIn > 10 * RING_IN) 0 else (10 - (distIn / RING_IN).toInt()).coerceIn(1, 10)

    fun label(distIn: Float): String = when (val s = score(distIn)) { 0 -> "M"; 10 -> if (distIn <= X_IN) "X" else "10"; else -> "$s" }

    fun color(score: Int): Int = when (score) {
        10, 9 -> 0xFFFFE552.toInt(); 8, 7 -> 0xFFEF3340.toInt(); 6, 5 -> 0xFF00B3E3.toInt()
        4, 3 -> 0xFF8E8D92.toInt(); 2, 1 -> 0xFFECEBEC.toInt() // black lifted to read on the watch's black
        else -> 0x99F5F5F4.toInt()
    }
}
