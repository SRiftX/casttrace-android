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
        get() = canNotify(strongReminderEnabled = true)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                STRONG_CHANNEL_ID,
                "主播开播强提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "主播开播时通过声音、振动和横幅进行强提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 700)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NORMAL_CHANNEL_ID,
                "主播开播通知",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "主播开播时显示静音通知"
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    fun canNotify(strongReminderEnabled: Boolean): Boolean {
        val channelId = if (strongReminderEnabled) STRONG_CHANNEL_ID else NORMAL_CHANNEL_ID
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun notifyOpened(reminder: LiveReminder, strongReminderEnabled: Boolean = true) {
        val channelId = if (strongReminderEnabled) STRONG_CHANNEL_ID else NORMAL_CHANNEL_ID
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
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_streamcap_foreground)
            .setContentTitle("${reminder.anchorName} 开播了")
            .setContentText("${reminder.platform} · $message")
            .setContentIntent(openIntent)
            .setCategory(if (strongReminderEnabled) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_EVENT)
            .setPriority(if (strongReminderEnabled) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(if (strongReminderEnabled) Notification.DEFAULT_ALL else 0)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        manager.notify("reminder:${reminder.id}", NOTIFICATION_ID, notification)
    }

    fun cancel(reminderId: Long) {
        manager.cancel("reminder:$reminderId", NOTIFICATION_ID)
    }

    companion object {
        private const val STRONG_CHANNEL_ID = "live_reminders_strong_v2"
        private const val NORMAL_CHANNEL_ID = "live_reminders_normal_v2"
        private const val NOTIFICATION_ID = 1
    }
}
