package com.streamcap.mobile

import android.app.NotificationManager
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.MainActivity
import com.streamcap.mobile.data.database.LiveRoomEntity
import com.streamcap.mobile.domain.model.*
import com.streamcap.mobile.notification.RecordingNotifications
import com.streamcap.mobile.platform.resolver.PlatformResolver
import com.streamcap.mobile.platform.resolver.ResolverRegistry
import com.streamcap.mobile.service.RecordingService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@dagger.hilt.android.testing.HiltAndroidTest
class RecordingLifecycleAcceptanceTest : HiltGraphTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun longMediaTimelineSavesOnceOnConfirmedLiveEnd(): Unit = runBlocking {
        val context = compose.activity
        val container = (context.application).testGraph
        val repository = container.repository
        assumeTrue("Run on an idle QA emulator", repository.activeSessions().isEmpty())
        val media = InstrumentationRegistry.getInstrumentation().context.assets.open("preview.ts").use { it.readBytes() }
        val offline = AtomicBoolean(false)
        val segments = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val isMedia = request.requestUrl?.encodedPath?.endsWith(".ts") == true
                if (isMedia && segments.incrementAndGet() >= 2) offline.set(true)
                val bytes = if (isMedia) media else "#EXTM3U\n#EXT-X-TARGETDURATION:1860\n#EXTINF:1860,\n/one.ts\n#EXT-X-ENDLIST\n".toByteArray()
                return MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes))
            }
        }
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val baseUrl = server.url("/").toString()
        val source = StreamSource(server.url("live.m3u8").toString(), StreamType.HLS, "原画")
        val fixtureResolver = object : PlatformResolver {
            override val platformId = "qaLifecycle"
            override fun supports(url: String) = url.startsWith(baseUrl)
            override suspend fun resolveRoom(url: String) = LiveRoomInfo(url, platformId, "QA下播测试", null)
            override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
                if (offline.get()) LiveStatus.OFFLINE else LiveStatus.LIVE
            override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) =
                if (offline.get()) emptyList() else listOf(source)
        }
        val registryField = ResolverRegistry::class.java.getDeclaredField("resolvers").apply { isAccessible = true }
        val originalResolvers = registryField.get(container.resolverRegistry)
        @Suppress("UNCHECKED_CAST")
        registryField.set(container.resolverRegistry, listOf(fixtureResolver) + originalResolvers as List<PlatformResolver>)
        val roomId = container.database.liveRoomDao().insert(LiveRoomEntity(
            originalUrl = source.url, normalizedUrl = source.url, platform = fixtureResolver.platformId,
            anchorName = "QA下播测试", roomTitle = null, segmentEnabled = false, outputFormat = "MP4",
            scheduledMonitoringEnabled = false, notificationsEnabled = true))
        try {
            RecordingService.start(context, roomId)
            val saved = withTimeout(60_000) { repository.recordings.first { rows ->
                rows.any { it.roomId == roomId && it.status in setOf(TaskStatus.COMPLETED, TaskStatus.FAILED) }
            }.first { it.roomId == roomId } }
            assertEquals(saved.errorMessage, TaskStatus.COMPLETED, saved.status)
            assertNull(saved.errorMessage)
            assertEquals(1, saved.outputUris.size)
            withTimeout(5_000) {
                while (repository.room(roomId)?.liveStatus != LiveStatus.OFFLINE) delay(50)
            }
            assertEquals(LiveStatus.OFFLINE, repository.room(roomId)!!.liveStatus)
            delay(4_000)
            val events = repository.logs.first().filter { it.roomId == roomId }
            assertTrue(events.any { it.eventType == "LIVE_ENDED" && it.level == "INFO" })
            assertFalse(events.any { it.level == "ERROR" || it.eventType == "RECORDING_RETRY" })
            assertEquals(1, repository.recordings.first().count { it.roomId == roomId })
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(context, Uri.parse(saved.outputUris.single()), null)
                assertTrue(extractor.trackCount > 0)
                extractor.selectTrack(0)
                extractor.seekTo(1_000_000, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                assertTrue(extractor.sampleTime >= 0)
            } finally { extractor.release() }
            android.util.Log.i("RecordingLifecycleQA", "PASS confirmed live end session=${saved.id} MP4 seekable; simulated HLS timeline=1860s; errors=0 retries=0")
            saved.outputUris.forEach {
                runCatching { context.contentResolver.delete(Uri.parse(it), null, null) }
            }
            repository.deleteSession(repository.session(saved.id)!!)
        } finally {
            registryField.set(container.resolverRegistry, originalResolvers)
            server.shutdown()
            repository.deleteRoom(roomId)
        }
    }

    @Test fun monitorNotificationReturnsAfterDismissalAndStopsWithMonitor(): Unit = runBlocking {
        val context = compose.activity
        val container = (context.application).testGraph
        assumeTrue("Requires an idle QA monitor", container.repository.activeSessions().isEmpty() && container.repository.monitoredRooms().isEmpty())
        val previous = container.preferences.settings.first()
        val manager = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Notification permission must be granted for this QA flow", manager.areNotificationsEnabled())
        val now = System.currentTimeMillis()
        val roomId = container.database.liveRoomDao().insert(LiveRoomEntity(
            originalUrl = "https://qa.invalid/future", normalizedUrl = "https://qa.invalid/future", platform = "qaFuture",
            anchorName = "QA通知测试", roomTitle = null, scheduledMonitoringEnabled = true,
            oneTimeStartAt = now + 86_400_000, oneTimeEndAt = now + 172_800_000))
        fun notification() = manager.activeNotifications.firstOrNull { it.id == RecordingNotifications.SERVICE_NOTIFICATION_ID }
        try {
            container.preferences.setGlobalAutoRecordEnabled(true)
            container.preferences.setBackgroundMonitoringEnabled(true)
            container.preferences.setResumeAfterBoot(false)
            RecordingService.monitor(context)
            withTimeout(30_000) { while (notification()?.notification?.extras?.getCharSequence("android.text")?.contains("监控") != true) delay(100) }
            InstrumentationRegistry.getArguments().getString("notificationHoldMillis")?.toLongOrNull()?.let { delay(it.coerceIn(0, 45_000)) }
            val deleteIntent = notification()!!.notification.deleteIntent
            manager.cancel(RecordingNotifications.SERVICE_NOTIFICATION_ID)
            deleteIntent.send()
            withTimeout(5_000) { while (notification() == null) delay(50) }
            assertTrue(notification()!!.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            RecordingService.stopMonitoring(context)
            withTimeout(5_000) { while (notification() != null) delay(50) }
            deleteIntent.send()
            delay(500)
            assertNull(notification())
            android.util.Log.i("RecordingLifecycleQA", "PASS monitor dismissal restored; explicit stop removes notification; stale dismissal cannot restart service")
        } finally {
            RecordingService.stopMonitoring(context)
            container.repository.deleteRoom(roomId)
            container.preferences.setGlobalAutoRecordEnabled(previous.globalAutoRecordEnabled)
            container.preferences.setBackgroundMonitoringEnabled(previous.backgroundMonitoringEnabled)
            container.preferences.setResumeAfterBoot(previous.resumeAfterBoot)
        }
    }
}
