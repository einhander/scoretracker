package com.einhander.temposcore.ui

import com.einhander.temposcore.collapsedPanelHeight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

class UpperPanelGeometryTest {
    @Test fun ordinaryPanelKeepsFortyEightDpControlTargetsAcrossDensities() {
        for (density in listOf(0.75f, 1f, 1.1f, 1.5f, 2f, 2.625f, 3f, 4f)) {
            val expanded = (124f * density).roundToInt()
            val collapsed = collapsedPanelHeight(expanded, density)
            assertEquals(ceil(48f * density).toInt(), collapsed)
            assertTrue(collapsed / density >= 48f)
            assertTrue(collapsed < expanded)
        }
    }

    @Test fun tallerMeasuredPanelUsesTenPercentWhenAboveReadableMinimum() {
        for (density in listOf(0.75f, 1f, 1.5f, 2f, 3f, 4f)) {
            val expanded = (600f * density).roundToInt()
            val collapsed = collapsedPanelHeight(expanded, density)
            assertTrue(abs(collapsed - expanded * 0.1f) <= 0.5f)
            assertTrue(collapsed / density >= 48f)
            assertTrue(collapsed < expanded)
        }
    }

    @Test fun pathologicalSmallPanelsNeverGrowBeyondTheirExpandedHeight() {
        for (density in listOf(0.75f, 1f, 2f, 4f)) {
            for (expanded in listOf(0, 1, 8, 16)) {
                assertEquals(expanded, collapsedPanelHeight(expanded, density))
            }
            assertEquals(0, collapsedPanelHeight(-1, density))
        }
    }

    @Test fun resizeAndRestartMeasurementsDoNotAccumulateCollapseOrDependOnPreviousHeight() {
        for (density in listOf(1f, 1.5f, 2.625f, 4f)) {
            val original = (124f * density).roundToInt()
            val resized = (640f * density).roundToInt()
            val before = collapsedPanelHeight(original, density)
            assertEquals((resized * 0.1f).roundToInt(), collapsedPanelHeight(resized, density))
            assertEquals(before, collapsedPanelHeight(original, density))
            assertEquals(ceil(48f * density).toInt(), before)
        }
    }
}
