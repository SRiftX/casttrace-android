package com.streamcap.mobile.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.streamcap.mobile.R
import com.streamcap.mobile.app.MainActivity
import com.streamcap.mobile.domain.model.LiveReminder

class LiveReminderNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    val canNotify: Boolean
        get() = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "主播开播提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "主播开播时发出声音、振动和横幅提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            },
        )
    }

    fun notifyOpened(reminder: LiveReminder) {
        val openIntent = PendingIntent.getActivity(
            context,
            reminder.id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_REMINDERS, true)
                data = android.net.Uri.parse("streamcap://reminder/${reminder.id}")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val message = reminder.roomTitle?.takeIf(String::isNotBlank) ?: "正在直播"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_streamcap_foreground)
            .setContentTitle("${reminder.anchorName} 开播了")
            .setContentText("${reminder.platform} · $message")
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        manager.notify("reminder:${reminder.id}", NOTIFICATION_ID, notification)
    }

    fun cancel(reminderId: Long) {
        manager.cancel("reminder:$reminderId", NOTIFICATION_ID)
    }

    companion object {
        private const val CHANNEL_ID = "live_reminders_v1"
        private const val NOTIFICATION_ID = 1
    }
}
