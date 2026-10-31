package com.streamcap.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.repository.StreamCapRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var repository: StreamCapRepository

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = preferences.settings.first()
                if (BackgroundMonitoringPolicy.restoreAfterBoot(settings) || repository.hasEnabledReminders()) {
                    RoomMonitorWorker.schedule(context, settings.allowMobileData)
                } else {
                    // A periodic request survives reboot. Cancel it here so a disabled
                    // boot-resume setting cannot silently restart monitoring.
                    RoomMonitorWorker.cancelSchedule(context)
                }
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "streamcap-boot-recovery",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<TaskRecoveryWorker>().build(),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
