package com.einhander.temposcore.ui

import com.einhander.temposcore.nextTempoAdjustment
import com.einhander.temposcore.tempoAdjustmentControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoAdjustmentControlsTest {
    @Test fun savedValuesAreBounded() {
        assertEquals(-30, tempoAdjustmentControls(Int.MIN_VALUE, false).value)
        assertEquals(30, tempoAdjustmentControls(Int.MAX_VALUE, false).value)
        assertEquals(0, tempoAdjustmentControls(0, false).value)
    }

    @Test fun labelsKeepCorrectionSeparateAndSigned() {
        assertEquals("+2", tempoAdjustmentControls(2, false).signed)
        assertEquals("-2", tempoAdjustmentControls(-2, false).signed)
        assertEquals("0", tempoAdjustmentControls(0, false).signed)
    }

    @Test fun eachClickChangesOneBpmWithinBounds() {
        assertEquals(1, nextTempoAdjustment(0, 1, false))
        assertEquals(-1, nextTempoAdjustment(0, -1, false))
        assertEquals(0, nextTempoAdjustment(1, -1, false))
        assertEquals(30, nextTempoAdjustment(30, 1, false))
        assertEquals(-30, nextTempoAdjustment(-30, -1, false))
    }

    @Test fun onlyOutwardButtonIsDisabledAtLimit() {
        assertFalse(tempoAdjustmentControls(-30, false).decreaseEnabled)
        assertTrue(tempoAdjustmentControls(-30, false).increaseEnabled)
        assertTrue(tempoAdjustmentControls(30, false).decreaseEnabled)
        assertFalse(tempoAdjustmentControls(30, false).increaseEnabled)
        assertTrue(tempoAdjustmentControls(0, false).decreaseEnabled)
        assertTrue(tempoAdjustmentControls(0, false).increaseEnabled)
    }

    @Test fun mp3PlayingAndPausedAllowEditingSavedMicCorrection() {
        // MainActivity maps both Playing and Paused to testActive=true.
        for (state in listOf("Playing", "Paused")) {
            var saved = 2
            val controls = tempoAdjustmentControls(saved, true)
            assertEquals(state, "+2", controls.signed)
            assertTrue(state, controls.decreaseEnabled)
            assertTrue(state, controls.increaseEnabled)
            saved = nextTempoAdjustment(saved, 1, true)
            assertEquals(state, 3, saved)
            assertEquals(state, "+3", tempoAdjustmentControls(saved, true).signed)
            saved = nextTempoAdjustment(saved, -1, true)
            assertEquals(state, 2, saved)
            // Leaving MP3 does not discard the edited value; microphone gets the same correction.
            assertEquals(tempoAdjustmentControls(saved, true), tempoAdjustmentControls(saved, false))
        }
    }

    @Test fun mp3EditingKeepsBoundsAndAllowsInwardClicks() {
        assertFalse(tempoAdjustmentControls(-30, true).decreaseEnabled)
        assertTrue(tempoAdjustmentControls(-30, true).increaseEnabled)
        assertTrue(tempoAdjustmentControls(30, true).decreaseEnabled)
        assertFalse(tempoAdjustmentControls(30, true).increaseEnabled)
        assertEquals(-30, nextTempoAdjustment(-30, -1, true))
        assertEquals(-29, nextTempoAdjustment(-30, 1, true))
        assertEquals(30, nextTempoAdjustment(30, 1, true))
        assertEquals(29, nextTempoAdjustment(30, -1, true))
    }
}
