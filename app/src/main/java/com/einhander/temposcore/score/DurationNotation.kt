package com.einhander.temposcore.score

import kotlin.math.abs
import kotlin.math.ln

enum class DurationDisplay { Symbols, Ribbons }

data class DurationGlyph(val denominator: Int, val dots: Int, val quarterBeats: Double, val approximate: Boolean = false) {
    val hollow: Boolean get() = denominator <= 2
    val hasStem: Boolean get() = denominator != 1
    val flags: Int get() = when (denominator) { 8 -> 1; 16 -> 2; 32 -> 3; 64 -> 4; else -> 0 }
    val stemLengthUnits: Float get() = if (!hasStem) 0f else if (flags == 4) 3.8f else 3.5f

    fun aboveHeadUnits(step: Int): Float = if (hasStem && step < 0) stemLengthUnits + 0.2f else 0.95f
    fun belowHeadUnits(step: Int): Float = if (hasStem && step >= 0) stemLengthUnits + 0.2f else 0.95f
}

private val durationGlyphs = (0..2).flatMap { dots ->
    listOf(1, 2, 4, 8, 16, 32, 64).map { denominator ->
        val multiplier = when (dots) { 1 -> 1.5; 2 -> 1.75; else -> 1.0 }
        DurationGlyph(denominator, dots, 4.0 / denominator * multiplier)
    }
}

/** Relative distance avoids favoring tiny values. Ties prefer fewer dots, then longer base notes. */
fun durationGlyph(quarterBeats: Double): DurationGlyph {
    if (!quarterBeats.isFinite()) return DurationGlyph(4, 0, 1.0, true)
    if (quarterBeats <= 0.0) return DurationGlyph(64, 0, 0.0625, true)
    var closest = durationGlyphs.first()
    var distance = Double.POSITIVE_INFINITY
    for (candidate in durationGlyphs) {
        val error = abs(ln(quarterBeats) - ln(candidate.quarterBeats))
        if (error < distance - 1e-12) { closest = candidate; distance = error }
    }
    return closest.copy(approximate = abs(quarterBeats - closest.quarterBeats) > maxOf(1e-9, closest.quarterBeats * 1e-6))
}

/** Dark, white-background-safe hues; pitch names/staff positions remain the non-color cues. */
private val pitchClassColors = intArrayOf(
    0xFF9B2D30.toInt(), 0xFF9A3C14.toInt(), 0xFF835500.toInt(), 0xFF5F6500.toInt(),
    0xFF2E6B34.toInt(), 0xFF006B57.toInt(), 0xFF006B78.toInt(), 0xFF245E9B.toInt(),
    0xFF51489B.toInt(), 0xFF783D91.toInt(), 0xFF923764.toInt(), 0xFF8E3F48.toInt(),
)

fun pitchClassColor(pitch: Int, past: Boolean = false): Int {
    val color = pitchClassColors[Math.floorMod(pitch, 12)]
    if (!past) return color
    // A small white blend de-emphasizes history without changing hue or losing contrast.
    fun faded(channel: Int) = (channel * 9 + 255) / 10
    return (255 shl 24) or (faded((color shr 16) and 255) shl 16) or
        (faded((color shr 8) and 255) shl 8) or faded(color and 255)
}
