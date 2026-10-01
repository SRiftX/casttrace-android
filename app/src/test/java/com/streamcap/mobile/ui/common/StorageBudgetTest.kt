package com.streamcap.mobile.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageBudgetTest {
    @Test fun `no bitrate leaves estimate unknown`() {
        val budget = StorageBudget(10_000_000_000L, 0L, 0L)
        assertNull(budget.estimatedSecondsRemaining)
        assertEquals(StorageBudgetLevel.NORMAL, budget.level)
    }

    @Test fun `low free space is critical`() {
        val budget = StorageBudget(64L * 1024L * 1024L, 1_000_000L, 100L)
        assertEquals(StorageBudgetLevel.CRITICAL, budget.level)
    }

    @Test fun `estimate uses safety margin`() {
        val budget = StorageBudget(1_000_000_000L, 100_000_000L, 100L, safetyBytes = 100_000_000L)
        assertEquals(900L, budget.estimatedSecondsRemaining)
        assertTrue(budget.bytesPerSecond > 0)
    }
}
