package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.notification.RecordingNotifications
import org.junit.Assert.*
import org.junit.Test

class RecordingNotificationTest {
    @Test fun serviceNotificationIsOngoingAndCannotBeClearedWithOrdinaryNotifications() {
        val notifications = RecordingNotifications(InstrumentationRegistry.getInstrumentation().targetContext)
        val notification = notifications.serviceNotification("正在检测直播状态")
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and android.app.Notification.FLAG_NO_CLEAR != 0)
        assertEquals(0, notification.flags and android.app.Notification.FLAG_AUTO_CANCEL)
        assertNotNull(notification.deleteIntent)
    }
    @Test fun roomIdentitySurvivesLongIdsWithIdenticalIntegerRequestCodes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notifications = RecordingNotifications(context)
        val first = notifications.recordingNotification(1L, "A", "已录制 00:01", true)
        val second = notifications.recordingNotification(4_294_967_297L, "B", "已录制 00:01", true)
        assertNotEquals(first.contentIntent, second.contentIntent)
        assertNotEquals(first.actions.single().actionIntent, second.actions.single().actionIntent)
        assertEquals("暂停并保存", first.actions.single().title.toString())
    }

    @Test fun finalizingAndCompletedNotificationsDoNotOfferAnotherPause() {
        val notifications = RecordingNotifications(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(notifications.recordingNotification(7L, "A", "正在保存录制文件", true).actions.isNullOrEmpty())
        assertTrue(notifications.recordingNotification(7L, "A", "已保存", false).actions.isNullOrEmpty())
    }
}
