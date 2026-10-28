package com.einhander.temposcore.score

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccidentalOwnershipTest {
    @Test fun denseEarlierNotesCannotPushLaterAccidentalsIntoTheirColumns() {
        for (zoom in listOf(0.1f, 0.25f, 1f, 4f)) {
            val viewport = BeatViewport(0.0, 100f, 50f * zoom, 0f, 500f)
            val ownerTick = 480L
            val ownerX = viewport.beatToX(1.0)
            for (tick in 460L until ownerTick) {
                // At minimum zoom these heads are less than one pixel apart.
                val otherX = viewport.beatToX(tick / 480.0)
                assertTrue(otherX <= ownerX)
                assertFalse(sharesAccidentalColumns(ownerTick, tick))
                assertFalse(sharesAccidentalColumns(tick, ownerTick))
            }
        }
    }

    @Test fun denseChordStillSharesColumnsButAdjacentChordsDoNot() {
        val chordTicks = listOf(0L, 1L, 480L, 481L, Long.MAX_VALUE)
        for (tick in chordTicks) {
            // All pitches and voices at the same MIDI onset retain collision packing.
            repeat(12) { assertTrue(sharesAccidentalColumns(tick, tick)) }
            for (other in chordTicks.filter { it != tick }) {
                assertFalse(sharesAccidentalColumns(tick, other))
            }
        }
    }

    @Test fun visuallyCoincidentOnsetsAreNotNecessarilyOneChord() {
        val viewport = BeatViewport(0.0, 100f, 5f, 0f, 500f)
        val first = viewport.beatToX(1.0)
        val second = viewport.beatToX(1.0 + 1.0 / Int.MAX_VALUE)
        assertTrue(first == second)
        assertFalse(sharesAccidentalColumns(0L, 1L))
    }
}
