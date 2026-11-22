package com.streamcap.mobile.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUpdateCheckerTest {
    @Test
    fun `check is skipped while disabled or before one day has elapsed`() {
        val now = 10L * 24 * 60 * 60 * 1000

        assertFalse(AutoUpdateChecker.isDue(false, 0L, now))
        assertFalse(AutoUpdateChecker.isDue(true, now - 23 * 60 * 60 * 1000, now))
    }

    @Test
    fun `check is due on first run and after one day`() {
        val now = 10L * 24 * 60 * 60 * 1000

        assertTrue(AutoUpdateChecker.isDue(true, 0L, now))
        assertTrue(AutoUpdateChecker.isDue(true, now - 24 * 60 * 60 * 1000, now))
    }
}
