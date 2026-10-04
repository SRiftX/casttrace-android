package com.streamcap.mobile.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.streamcap.mobile.R
import com.streamcap.mobile.app.MainActivity
import com.streamcap.mobile.service.RecordingService

class RecordingNotifications(private val context: Context) {
    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val SERVICE_NOTIFICATION_ID = 91
        const val ACTION_SERVICE_NOTIFICATION_DISMISSED = "com.streamcap.mobile.action.SERVICE_NOTIFICATION_DISMISSED"
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECORDING,
                "直播录制",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示监控、录制和定时停止状态"
                setShowBadge(false)
            },
        )
    }

    fun serviceNotification(message: String): Notification = build(
        title = "StreamCap 正在运行", message = message, roomId = null, ongoing = true,
    ).apply {
        flags = flags or Notification.FLAG_NO_CLEAR
        deleteIntent = PendingIntent.getBroadcast(context, SERVICE_NOTIFICATION_ID,
            Intent(ACTION_SERVICE_NOTIFICATION_DISMISSED).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun recordingNotification(
        roomId: Long,
        roomName: String,
        message: String,
        ongoing: Boolean,
        recoverySessionId: Long? = null,
    ): Notification = build(roomName, message, roomId, ongoing, recoverySessionId)

    fun blockedNotification(): Notification = build(
        "后台启动受限", "点击打开应用恢复检测；并检查应用启动管理", null, false,
    )

    fun notifyRoom(roomId: Long, notification: Notification) {
        manager.notify("room:$roomId", ROOM_NOTIFICATION_ID, notification)
    }

    fun cancelRoom(roomId: Long) {
        manager.cancel("room:$roomId", ROOM_NOTIFICATION_ID)
    }

    private fun build(
        title: String,
        message: String,
        roomId: Long?,
        ongoing: Boolean,
        recoverySessionId: Long? = null,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            roomId?.toInt() ?: 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                roomId?.let { putExtra("roomId", it); data = android.net.Uri.parse("streamcap://room/$it") }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_streamcap_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (roomId != null && ongoing && !message.contains("正在保存")) {
            val stopIntent = PendingIntent.getService(
                context,
                roomId.toInt(),
                RecordingService.pauseIntent(context, roomId).setData(android.net.Uri.parse("streamcap://room/$roomId/stop")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "暂停并保存", stopIntent)
        }
        if (roomId != null && recoverySessionId != null && (message.contains("失败") || message.contains("异常"))) {
            val recoverIntent = PendingIntent.getService(
                context,
                (roomId * 31 + recoverySessionId).toInt(),
                Intent(context, RecordingService::class.java).setAction("com.streamcap.mobile.action.RECOVER")
                    .putExtra("recoverySessionId", recoverySessionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "重试保存", recoverIntent)
        }
        return builder.build()
    }

    private val ROOM_NOTIFICATION_ID = 1_000
}
