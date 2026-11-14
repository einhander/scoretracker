package com.einhander.temposcore.ui

import com.einhander.temposcore.TrackToggleResult
import com.einhander.temposcore.toggleTrackSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSelectionDialogTest {
    @Test fun addingToEmptySelectionStoresTrack() {
        assertEquals(TrackToggleResult(listOf(3), false), toggleTrackSelection(emptyList(), 3))
    }

    @Test fun addingSecondTrackPreservesInsertionOrder() {
        assertEquals(TrackToggleResult(listOf(2, 0), false), toggleTrackSelection(listOf(2), 0))
    }

    @Test fun thirdTrackIsBlockedKeepingSelectionUntouched() {
        assertEquals(TrackToggleResult(listOf(2, 0), true), toggleTrackSelection(listOf(2, 0), 5))
    }

    @Test fun removingOneOfTwoAllowed() {
        assertEquals(TrackToggleResult(listOf(0), false), toggleTrackSelection(listOf(2, 0), 2))
    }

    @Test fun removingLastRemainingTrackIsBlocked() {
        assertEquals(TrackToggleResult(listOf(2), true), toggleTrackSelection(listOf(2), 2))
    }

    @Test fun addingAnyThirdTrackIsBlocked() {
        assertEquals(TrackToggleResult(listOf(2, 0), true), toggleTrackSelection(listOf(2, 0), 7))
    }

    @Test fun customLimitsStillEnforceMinAndCapacity() {
        assertEquals(TrackToggleResult(listOf(1, 2, 3), false), toggleTrackSelection(listOf(1, 2), 3, capacity = 3))
        assertEquals(TrackToggleResult(listOf(1, 2, 3), true), toggleTrackSelection(listOf(1, 2, 3), 5, capacity = 3))
        assertEquals(TrackToggleResult(listOf(1), true), toggleTrackSelection(listOf(1), 1, minimum = 2))
    }
}
