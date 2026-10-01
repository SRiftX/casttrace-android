package com.streamcap.mobile

import android.app.NotificationManager
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.MainActivity
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.data.database.LiveRoomEntity
import com.streamcap.mobile.domain.model.*
import com.streamcap.mobile.notification.RecordingNotifications
import com.streamcap.mobile.platform.resolver.PlatformResolver
import com.streamcap.mobile.platform.resolver.ResolverRegistry
import com.streamcap.mobile.service.RecordingService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RecordingLifecycleAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun longMediaTimelineSavesOnceOnConfirmedLiveEnd(): Unit = runBlocking {
        val context = compose.activity
        val container = (context.application as StreamCapApplication).container
        val repository = container.repository
        assumeTrue("Run on an idle QA emulator", repository.activeSessions().isEmpty())
        val media = InstrumentationRegistry.getInstrumentation().context.assets.open("preview.ts").use { it.readBytes() }
        val offline = AtomicBoolean(false)
        val segments = AtomicInteger()
        val source = StreamSource("https://qa.invalid/live.m3u8", StreamType.HLS, "原画")
        val fixtureResolver = object : PlatformResolver {
            override val platformId = "qaLifecycle"
            override fun supports(url: String) = url.startsWith("https://qa.invalid/")
            override suspend fun resolveRoom(url: String) = LiveRoomInfo(url, platformId, "QA下播测试", null)
            override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
                if (offline.get()) LiveStatus.OFFLINE else LiveStatus.LIVE
            override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) =
                if (offline.get()) emptyList() else listOf(source)
        }
        val clientField = container.javaClass.getDeclaredField("httpClient").apply { isAccessible = true }
        val registryField = ResolverRegistry::class.java.getDeclaredField("resolvers").apply { isAccessible = true }
        val originalClient = container.httpClient
        val originalResolvers = registryField.get(container.resolverRegistry)
        @Suppress("UNCHECKED_CAST")
        registryField.set(container.resolverRegistry, listOf(fixtureResolver) + originalResolvers as List<PlatformResolver>)
        clientField.set(container, originalClient.newBuilder().addInterceptor { chain ->
            if (chain.request().url.host != "qa.invalid") return@addInterceptor chain.proceed(chain.request())
            val isMedia = chain.request().url.encodedPath.endsWith(".ts")
            if (isMedia && segments.incrementAndGet() >= 2) offline.set(true)
            val bytes = if (isMedia) media else "#EXTM3U\n#EXT-X-TARGETDURATION:1860\n#EXTINF:1860,\n/one.ts\n#EXT-X-ENDLIST\n".toByteArray()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
        }.build())
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
            saved.outputUris.forEach { context.contentResolver.delete(Uri.parse(it), null, null) }
            repository.deleteSession(repository.session(saved.id)!!)
        } finally {
            clientField.set(container, originalClient)
            registryField.set(container.resolverRegistry, originalResolvers)
            repository.deleteRoom(roomId)
        }
    }

    @Test fun monitorNotificationReturnsAfterDismissalAndStopsWithMonitor(): Unit = runBlocking {
        val context = compose.activity
        val container = (context.application as StreamCapApplication).container
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
