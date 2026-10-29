package com.streamcap.mobile.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.streamcap.mobile.ui.StreamCapApp
import com.streamcap.mobile.ui.theme.StreamCapTheme
import com.streamcap.mobile.ui.theme.ThemePalette
import com.streamcap.mobile.data.preferences.UserSettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamcap.mobile.service.RecordingService
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val appPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    private var launchToken by mutableIntStateOf(0)
    private var launchIntent by mutableStateOf<Intent?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchIntent = intent
        val storagePermissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        appPermissions.launch(storagePermissions)
        lifecycleScope.launch {
            val app = (application as StreamCapApplication).container
            val settings = app.preferences.settings.first()
            if (settings.globalAutoRecordEnabled) {
                RecordingService.monitor(this@MainActivity)
            } else {
                RecordingService.recover(this@MainActivity)
                if (app.repository.hasEnabledReminders()) RecordingService.monitor(this@MainActivity)
            }
        }
        setContent {
            val settings by (application as StreamCapApplication).container.preferences.settings
                .collectAsStateWithLifecycle(UserSettings())
            StreamCapTheme(palette = ThemePalette.fromId(settings.themePalette)) {
                StreamCapApp(
                    launchToken = launchToken,
                    sharedUrl = launchIntent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT),
                    notificationRoomId = launchIntent?.getLongExtra("roomId", -1)?.takeIf { it > 0 },
                    openReminders = launchIntent?.getBooleanExtra(EXTRA_OPEN_REMINDERS, false) == true,
                )
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchToken++
        launchIntent = intent
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val app = application as StreamCapApplication
            val settings = app.container.preferences.settings.first()
            if (settings.globalAutoRecordEnabled || app.container.repository.hasEnabledReminders()) {
                RecordingService.monitor(this@MainActivity)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_REMINDERS = "openReminders"
    }
}
