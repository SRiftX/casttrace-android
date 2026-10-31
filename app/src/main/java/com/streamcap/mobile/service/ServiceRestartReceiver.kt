package com.streamcap.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
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
class ServiceRestartReceiver : BroadcastReceiver() {
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var repository: StreamCapRepository

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_RESTART) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext
                val expected = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(GENERATION, 0L)
                if (intent.getLongExtra(EXTRA_GENERATION, -1L) != expected) return@launch
                val settings = preferences.settings.first()
                val remindersEnabled = repository.hasEnabledReminders()
                if (BackgroundMonitoringPolicy.enabled(settings) || remindersEnabled) RecordingService.monitor(app)
                else RecordingService.recover(app)
            } catch (error: Exception) {
                android.util.Log.w("ServiceRestartReceiver", "Unable to restart recording recovery", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESTART = "com.streamcap.mobile.action.RESTART_SERVICE"
        const val EXTRA_GENERATION = "generation"
        private const val PREFS = "service_recovery"
        private const val GENERATION = "generation"
    }
}
