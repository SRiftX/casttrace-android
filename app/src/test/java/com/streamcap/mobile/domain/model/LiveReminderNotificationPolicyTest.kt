package com.streamcap.mobile.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReminderNotificationPolicyTest {
    @Test
    fun notifiesOnceForEachLiveSession() {
        val firstLive = LiveReminderNotificationPolicy.decide(enabled = true, alreadyNotified = false, status = LiveStatus.LIVE)
        assertTrue(firstLive.shouldNotify)
        assertTrue(firstLive.notifiedForCurrentLive)

        val stillLive = LiveReminderNotificationPolicy.decide(enabled = true, alreadyNotified = firstLive.notifiedForCurrentLive, status = LiveStatus.LIVE)
        assertFalse(stillLive.shouldNotify)
        assertTrue(stillLive.notifiedForCurrentLive)

        val offline = LiveReminderNotificationPolicy.decide(enabled = true, alreadyNotified = stillLive.notifiedForCurrentLive, status = LiveStatus.OFFLINE)
        assertFalse(offline.notifiedForCurrentLive)
        val nextSession = LiveReminderNotificationPolicy.decide(enabled = true, alreadyNotified = offline.notifiedForCurrentLive, status = LiveStatus.LIVE)
        assertTrue(nextSession.shouldNotify)
    }

    @Test
    fun disabledReminderAndCheckErrorsDoNotNotifyOrLoseSessionState() {
        val disabled = LiveReminderNotificationPolicy.decide(enabled = false, alreadyNotified = false, status = LiveStatus.LIVE)
        assertFalse(disabled.shouldNotify)
        assertFalse(disabled.notifiedForCurrentLive)

        val noPermission = LiveReminderNotificationPolicy.decide(
            enabled = true,
            alreadyNotified = false,
            status = LiveStatus.LIVE,
            notificationsAllowed = false,
        )
        assertFalse(noPermission.shouldNotify)
        assertFalse(noPermission.notifiedForCurrentLive)

        val failedCheck = LiveReminderNotificationPolicy.decide(enabled = true, alreadyNotified = true, status = LiveStatus.ERROR)
        assertFalse(failedCheck.shouldNotify)
        assertTrue(failedCheck.notifiedForCurrentLive)
    }
}
