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

    @Test fun mp3DisablesEditingButRetainsSavedCorrection() {
        val controls = tempoAdjustmentControls(2, true)
        assertEquals(2, controls.value)
        assertEquals("+2", controls.signed)
        assertFalse(controls.decreaseEnabled)
        assertFalse(controls.increaseEnabled)
        assertEquals(2, nextTempoAdjustment(2, 1, true))
        assertEquals(2, nextTempoAdjustment(2, -1, true))
        assertTrue(tempoAdjustmentControls(2, false).increaseEnabled)
    }
}
