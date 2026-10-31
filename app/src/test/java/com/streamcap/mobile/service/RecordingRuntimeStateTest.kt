package com.streamcap.mobile.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRuntimeStateTest {
    @Test
    fun newerMonitorGenerationMakesOlderCleanupStale() {
        val state = RecordingRuntimeState()
        val first = state.beginMonitorGeneration()
        val second = state.beginMonitorGeneration()

        assertFalse(state.isMonitorGenerationCurrent(first))
        assertTrue(state.isMonitorGenerationCurrent(second))

        state.invalidateMonitorGeneration()

        assertFalse(state.isMonitorGenerationCurrent(second))
    }
}
