package com.streamcap.mobile.service

import android.content.Context

data class BackgroundMonitorSnapshot(
    val lastCheckAt: Long,
    val lastRunningAt: Long,
    val lastStoppedAt: Long,
    val blockedAt: Long,
    val blockedReason: String?,
) {
    fun isRunning(now: Long = System.currentTimeMillis()): Boolean =
        lastRunningAt > maxOf(blockedAt, lastStoppedAt) && now - lastRunningAt < 120_000L

    fun isBlocked(): Boolean = blockedAt > lastRunningAt
}

object BackgroundMonitorStatus {
    private const val NAME = "background-monitor-status"

    fun read(context: Context): BackgroundMonitorSnapshot {
        val values = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return BackgroundMonitorSnapshot(
            values.getLong("lastCheckAt", 0L), values.getLong("lastRunningAt", 0L),
            values.getLong("lastStoppedAt", 0L),
            values.getLong("blockedAt", 0L), values.getString("blockedReason", null),
        )
    }

    fun checked(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putLong("lastCheckAt", System.currentTimeMillis()).apply()
    }

    fun running(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putLong("lastRunningAt", System.currentTimeMillis()).apply()
    }

    fun stopped(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putLong("lastStoppedAt", System.currentTimeMillis()).apply()
    }

    fun blocked(context: Context, reason: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putLong("blockedAt", System.currentTimeMillis())
            .putString("blockedReason", reason.take(160)).apply()
        runCatching {
            context.getSystemService(android.app.NotificationManager::class.java).notify(
                92, com.streamcap.mobile.notification.RecordingNotifications(context).blockedNotification(),
            )
        }
    }
}
