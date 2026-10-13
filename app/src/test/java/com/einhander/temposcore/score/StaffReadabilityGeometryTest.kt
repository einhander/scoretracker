package com.einhander.temposcore.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffReadabilityGeometryTest {
    @Test fun onlyRibbonGeometryEnlargesHeadsThicknessAndAccidentals() {
        val symbols = staffNoteGeometry(DurationDisplay.Symbols)
        val ribbons = staffNoteGeometry(DurationDisplay.Ribbons)
        assertEquals(0.52f, symbols.headXUnits, 0f)
        assertEquals(0.33f, symbols.headYUnits, 0f)
        assertEquals(1f, symbols.scale, 0f)
        assertEquals(symbols.headXUnits * 1.3f, ribbons.headXUnits, 1e-6f)
        assertEquals(symbols.headYUnits * 1.3f, ribbons.headYUnits, 1e-6f)
        for (lane in listOf(0, 1, 2, 5, 12)) {
            assertEquals(symbols.ribbonHalfThicknessUnits(lane) * 1.3f, ribbons.ribbonHalfThicknessUnits(lane), 1e-6f)
        }
        assertSame(symbols, staffNoteGeometry(DurationDisplay.Symbols))
        assertSame(ribbons, staffNoteGeometry(DurationDisplay.Ribbons))
    }

    @Test fun fullSelectionExtentsContainLargerHeadsOutlinesSignsAndRibbonStems() {
        val geometry = staffNoteGeometry(DurationDisplay.Ribbons)
        val steps = listOf(-24, -8, 0, 8, 24)
        val full = steps.fold(StaffExtent()) { extent, step ->
            extent.including(step, true, 0.2f, geometry.ribbonAboveHeadUnits, geometry.ribbonBelowHeadUnits(true))
        }
        for (step in steps) {
            assertTrue(full.top >= step / 2f - 0.2f + geometry.ribbonAboveHeadUnits)
            assertTrue(full.bottom >= -step / 2f + 0.2f + geometry.ribbonBelowHeadUnits(true))
        }
        assertTrue(geometry.ribbonAboveHeadUnits >= 1.7f)
        assertTrue(geometry.ribbonBelowHeadUnits(false) > geometry.headYUnits + 0.2f)
        val largestSignHalfHeightWithPadding = 1.25f * geometry.scale * 0.58f
        assertTrue(geometry.ribbonBelowHeadUnits(true) >= largestSignHalfHeightWithPadding)
    }

    @Test fun fontSizedLabelRowsAdaptToHeightAndNeverReserveAnUnboundedRowCount() {
        for (density in listOf(0.75f, 1f, 2f, 3f, 4f)) {
            assertEquals(3, staffLabelRows(220f * density, 21f * density, density))
            assertEquals(2, staffLabelRows(200f * density, 21f * density, density))
            assertEquals(2, staffLabelRows(220f * density, 42f * density, density))
            assertEquals(1, staffLabelRows(100f * density, 42f * density, density))
            assertEquals(1, staffLabelRows(20f * density, 42f * density, density))
        }
    }

    @Test fun largerHeadsDoNotChangeMidiAnchorsNoteOffOrViewportZoom() {
        val viewport = BeatViewport(8.0, 170f, 50f, 0f, 500f)
        val interval = NoteInterval(7.0, 10.0)
        val onset = viewport.beatToX(interval.startBeat)
        val release = viewport.beatToX(interval.endBeat)
        val ranges = DurationDisplay.entries.map { mode ->
            val geometry = staffNoteGeometry(mode)
            val headRadius = 20f * geometry.headXUnits
            assertTrue(onset + headRadius < release)
            assertEquals(270f, release, 0f)
            assertEquals(120f, onset, 0f)
            assertEquals(NoteInterval(7.0, 10.0), interval)
            viewport.visibleBeatRange()
        }
        assertEquals(ranges[0], ranges[1])
        assertEquals(50f, viewport.pixelsPerQuarterBeat, 0f)
    }
}
