package com.streamcap.mobile.ui.common

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import com.streamcap.mobile.domain.model.TaskStatus

val activeRecordingStatuses = setOf(TaskStatus.STARTING, TaskStatus.RECORDING, TaskStatus.RECONNECTING, TaskStatus.FINALIZING)

/** Ask in context, once. Denial does not trap users in a permission loop. */
@Composable
fun rememberRecordingAction(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("ux", 0) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var explain by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke(); pending = null
    }
    if (explain) AlertDialog(
        onDismissRequest = { explain = false; pending = null },
        title = { Text("在后台查看录制进度") },
        text = { Text("通知会显示主播、录制进度和暂停并保存操作。拒绝后仍可在直播间查看状态，也可稍后在设置中开启。") },
        confirmButton = { TextButton(onClick = {
            explain = false
            preferences.edit().putBoolean("notificationExplained", true).apply()
            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else { pending?.invoke(); pending = null }
        }) { Text("继续") } },
        dismissButton = { TextButton(onClick = {
            explain = false
            preferences.edit().putBoolean("notificationExplained", true).apply()
            pending?.invoke(); pending = null
        }) { Text("暂不开启，开始录制") } },
    )
    return { action ->
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            !preferences.getBoolean("notificationExplained", false)) {
            pending = action; explain = true
        } else action()
    }
}
