package com.streamcap.mobile.ui.settings

import android.content.Context
import com.streamcap.mobile.BuildConfig

internal object AutoUpdateChecker {
    const val PREFERENCES_NAME = "app_updates"
    const val ENABLED_KEY = "automatic_updates_enabled"
    const val LAST_CHECK_KEY = "automatic_updates_last_check"
    private const val CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L

    fun isDue(enabled: Boolean, lastCheckAt: Long, now: Long): Boolean =
        enabled && (lastCheckAt <= 0L || now - lastCheckAt >= CHECK_INTERVAL_MILLIS)

    suspend fun checkIfDue(
        context: Context,
        updateManager: AppUpdateManager,
        force: Boolean = false,
    ): AutoUpdateCheckResult? {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(ENABLED_KEY, false)) return null
        val now = System.currentTimeMillis()
        val lastCheckAt = preferences.getLong(LAST_CHECK_KEY, 0L)
        if (!force && !isDue(true, lastCheckAt, now)) return null
        val update = updateManager.checkLatest(BuildConfig.VERSION_NAME)
        preferences.edit().putLong(LAST_CHECK_KEY, now).apply()
        if (update == null) return AutoUpdateCheckResult.UpToDate
        val apk = updateManager.download(update)
        return AutoUpdateCheckResult.UpdateReady(update.versionName, updateManager.launchInstaller(apk))
    }
}

internal sealed interface AutoUpdateCheckResult {
    data object UpToDate : AutoUpdateCheckResult
    data class UpdateReady(val versionName: String, val installPrompt: InstallPrompt) : AutoUpdateCheckResult
}
