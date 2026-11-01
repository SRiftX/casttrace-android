package com.streamcap.mobile.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.ui.StreamCapApp
import com.streamcap.mobile.ui.theme.StreamCapTheme
import com.streamcap.mobile.ui.theme.ThemePalette
import com.streamcap.mobile.data.preferences.UserSettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamcap.mobile.service.RecordingService
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var repository: StreamCapRepository

    private val appPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    private var launchToken by mutableIntStateOf(0)
    private var launchIntent by mutableStateOf<Intent?>(null)
    var inPictureInPicture by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inPictureInPicture = isInPictureInPictureMode
        launchIntent = intent
        val storagePermissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missingPermissions = storagePermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            appPermissions.launch(missingPermissions.toTypedArray())
        }
        lifecycleScope.launch {
            repository.repairPausedRoomSettings()
            val settings = preferences.settings.first()
            if (settings.globalAutoRecordEnabled) {
                RecordingService.monitor(this@MainActivity)
            } else {
                RecordingService.recover(this@MainActivity)
                if (repository.hasEnabledReminders()) RecordingService.monitor(this@MainActivity)
            }
        }
        setContent {
            val settings by preferences.settings.collectAsStateWithLifecycle(UserSettings())
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

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val settings = preferences.settings.first()
            if (settings.globalAutoRecordEnabled || repository.hasEnabledReminders()) {
                RecordingService.monitor(this@MainActivity)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_REMINDERS = "openReminders"
    }
}
