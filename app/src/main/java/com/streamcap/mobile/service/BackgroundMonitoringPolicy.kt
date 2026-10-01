package com.streamcap.mobile.service

import com.streamcap.mobile.data.preferences.UserSettings

internal object BackgroundMonitoringPolicy {
    fun enabled(settings: UserSettings): Boolean =
        settings.globalAutoRecordEnabled && settings.backgroundMonitoringEnabled

    fun restoreAfterBoot(settings: UserSettings): Boolean = enabled(settings) && settings.resumeAfterBoot

    fun mayRestore(settings: UserSettings, scheduledBoot: Int, currentBoot: Int): Boolean =
        enabled(settings) && (settings.resumeAfterBoot || (currentBoot >= 0 && scheduledBoot == currentBoot))
}
