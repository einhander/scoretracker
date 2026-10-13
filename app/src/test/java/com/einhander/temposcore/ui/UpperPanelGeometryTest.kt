package com.einhander.temposcore.ui

import com.einhander.temposcore.collapsedPanelHeight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class UpperPanelGeometryTest {
    @Test fun collapseUsesMeasuredHeightAndLeavesOnlyTenPercentPlusPixelRounding() {
        for (expanded in listOf(124, 248, 310, 372, 496, 620)) {
            val collapsed = collapsedPanelHeight(expanded)
            assertTrue(abs(collapsed - expanded * 0.1f) <= 0.5f)
            assertTrue(collapsed < expanded)
        }
        assertEquals(12, collapsedPanelHeight(124))
        assertEquals(25, collapsedPanelHeight(248))
        assertEquals(1, collapsedPanelHeight(0))
    }
}
