package com.einhander.temposcore.ui

import com.einhander.temposcore.transportControlState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportControlStateTest {
    @Test fun unloadedOrUninitializedScoreDisablesBothActions() {
        for (loaded in listOf(false, true)) for (initialized in listOf(false, true)) {
            if (loaded && initialized) continue
            val controls = transportControlState(loaded, initialized, true, false, false)
            assertFalse(controls.listenEnabled)
            assertFalse(controls.resetEnabled)
            assertFalse(controls.stopping)
        }
    }

    @Test fun nativeStartOrPermissionPendingCannotBeReenabledByFrameRefresh() {
        for (running in listOf(false, true)) {
            val pending = transportControlState(true, true, running, false, true)
            assertFalse(pending.listenEnabled)
            assertTrue(pending.resetEnabled)
            assertEquals(running, pending.stopping)
        }
        val failedStart = transportControlState(true, true, false, false, false)
        assertTrue(failedStart.listenEnabled)
        assertFalse(failedStart.stopping)
    }

    @Test fun mp3PlayingOrPausedDisablesMicButKeepsScoreResetNotMp3Restart() {
        for (running in listOf(false, true)) {
            val controls = transportControlState(true, true, running, true, false)
            assertFalse(controls.listenEnabled)
            assertFalse(controls.stopping)
            assertTrue(controls.resetEnabled)
        }
        val stoppedMp3 = transportControlState(true, true, false, false, false)
        assertTrue(stoppedMp3.listenEnabled)
        assertTrue(stoppedMp3.resetEnabled)
    }

    @Test fun readyControlsSwitchListenStopLabelWithoutChangingResetAvailability() {
        val idle = transportControlState(true, true, false, false, false)
        val listening = transportControlState(true, true, true, false, false)
        assertTrue(idle.listenEnabled)
        assertTrue(listening.listenEnabled)
        assertFalse(idle.stopping)
        assertTrue(listening.stopping)
        assertEquals(idle.resetEnabled, listening.resetEnabled)
    }
}
