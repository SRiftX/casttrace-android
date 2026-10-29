package com.streamcap.mobile.domain.model

internal data class LiveReminderNotificationDecision(
    val shouldNotify: Boolean,
    val notifiedForCurrentLive: Boolean,
)

internal object LiveReminderNotificationPolicy {
    fun decide(
        enabled: Boolean,
        alreadyNotified: Boolean,
        status: LiveStatus,
        notificationsAllowed: Boolean = true,
    ): LiveReminderNotificationDecision {
        val shouldNotify = enabled && notificationsAllowed && status == LiveStatus.LIVE && !alreadyNotified
        val notified = when (status) {
            LiveStatus.LIVE -> alreadyNotified || shouldNotify
            LiveStatus.OFFLINE -> false
            else -> alreadyNotified
        }
        return LiveReminderNotificationDecision(shouldNotify, notified)
    }
}
