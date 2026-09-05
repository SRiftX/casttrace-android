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
        title = "StreamCap 正在运行",
        message = message,
        roomId = null,
        ongoing = true,
    )

    fun recordingNotification(
        roomId: Long,
        roomName: String,
        message: String,
        ongoing: Boolean,
    ): Notification = build(roomName, message, roomId, ongoing)

    fun notifyRoom(roomId: Long, notification: Notification) {
        manager.notify(notificationId(roomId), notification)
    }

    fun cancelRoom(roomId: Long) {
        manager.cancel(notificationId(roomId))
    }

    private fun build(
        title: String,
        message: String,
        roomId: Long?,
        ongoing: Boolean,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            roomId?.toInt() ?: 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_streamcap_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (roomId != null && ongoing) {
            val stopIntent = PendingIntent.getService(
                context,
                roomId.toInt(),
                RecordingService.stopIntent(context, roomId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "停止录制", stopIntent)
        }
        return builder.build()
    }

    private fun notificationId(roomId: Long): Int = 1_000 + (roomId % 10_000).toInt()
}
