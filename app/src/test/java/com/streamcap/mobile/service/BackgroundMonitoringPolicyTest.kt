package com.streamcap.mobile.service

import com.streamcap.mobile.data.preferences.UserSettings
import org.junit.Assert.*
import org.junit.Test

class BackgroundMonitoringPolicyTest {
    @Test fun processRecoveryDoesNotRequireBootOptIn() {
        val settings = UserSettings(resumeAfterBoot = false)
        assertTrue(BackgroundMonitoringPolicy.enabled(settings))
        assertFalse(BackgroundMonitoringPolicy.restoreAfterBoot(settings))
        assertTrue(BackgroundMonitoringPolicy.restoreAfterBoot(settings.copy(resumeAfterBoot = true)))
    }

    @Test fun disabledGlobalOrBackgroundSwitchPreventsBothRecoveryPaths() {
        listOf(UserSettings(globalAutoRecordEnabled = false, resumeAfterBoot = true),
            UserSettings(backgroundMonitoringEnabled = false, resumeAfterBoot = true)).forEach {
            assertFalse(BackgroundMonitoringPolicy.enabled(it))
            assertFalse(BackgroundMonitoringPolicy.restoreAfterBoot(it))
        }
    }

    @Test fun recoveryRespectsBootOptInEvenBeforeBootReceiverRuns() {
        val settings = UserSettings(resumeAfterBoot = false)
        assertTrue(BackgroundMonitoringPolicy.mayRestore(settings, 4, 4))
        assertFalse(BackgroundMonitoringPolicy.mayRestore(settings, 4, 5))
        assertFalse(BackgroundMonitoringPolicy.mayRestore(settings, -1, -1))
        assertTrue(BackgroundMonitoringPolicy.mayRestore(settings.copy(resumeAfterBoot = true), 4, 5))
    }
}
