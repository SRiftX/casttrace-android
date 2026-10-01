package com.streamcap.mobile

import com.streamcap.mobile.data.preferences.ConfigurationBackup
import com.streamcap.mobile.domain.model.TaskStatus
import org.junit.Assert.*
import org.junit.Test

class ConfigurationBackupTest {
    private val backup = """{"version":1,"format":"MP4","quality":"原画","interval":60,"rooms":[{"url":"https://www.huya.com/123","platform":"虎牙直播","name":"测试","format":"MP4","quality":"原画","segmentEnabled":true,"segmentSeconds":1800,"flv":true,"notifications":true,"interval":60}]}"""

    @Test fun importDefaultsToPausedWithoutDirectoryOrAutomaticRecording() {
        val room = ConfigurationBackup.parse(backup).rooms.single()
        assertEquals(TaskStatus.PAUSED, room.taskStatus)
        assertFalse(room.autoRecord)
        assertFalse(room.scheduledMonitoringEnabled)
        assertNull(room.storageDirectoryUri)
        assertEquals(1800, room.segmentSeconds)
    }

    @Test fun invalidVersionRangeAndCredentialUrlsAreRejectedBeforeImport() {
        listOf(backup.replace("\"version\":1", "\"version\":2"),
            backup.replace("1800", "-1"), backup.replace("https://www.huya.com/123", "https://user:secret@www.huya.com/123"),
            backup.replace("https://www.huya.com/123", "file:///private/secret"),
            backup.replace("https://www.huya.com/123", "https://www.huya.com/123?token=secret"), "{broken").forEach {
            assertTrue(runCatching { ConfigurationBackup.parse(it) }.isFailure)
        }
    }
}
