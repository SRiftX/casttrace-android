package com.streamcap.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "streamcap-boot-recovery",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TaskRecoveryWorker>().build(),
        )
    }
}
