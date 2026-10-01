package com.streamcap.mobile

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.streamcap.mobile.app.MainActivity
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.data.database.LiveRoomEntity
import com.streamcap.mobile.domain.model.*
import com.streamcap.mobile.platform.resolver.PlatformResolver
import com.streamcap.mobile.platform.resolver.ResolverRegistry
import com.streamcap.mobile.service.RecordingService
import com.streamcap.mobile.service.RoomMonitorWorker
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

class ScreenOffRecordingAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    private fun cpuLockHeld(): Boolean = shell("dumpsys power").lineSequence().any {
        it.contains("PARTIAL_WAKE_LOCK") && it.contains("StreamCap:Recording")
    }

    @Test fun liveDetectionAndTransferContinueWithScreenOff(): Unit = runBlocking {
        val context = compose.activity
        val container = (context.application as StreamCapApplication).container
        val repository = container.repository
        assumeTrue("Requires idle QA emulator", repository.activeSessions().isEmpty() && repository.monitoredRooms().isEmpty())
        val previous = container.preferences.settings.first()
        context.stopService(android.content.Intent(context, RecordingService::class.java))
        delay(500)
        val live = AtomicBoolean(false)
        val sequence = AtomicInteger()
        val media = instrumentation.context.assets.open("preview.ts").use { it.readBytes() }
        val source = StreamSource("https://qa.invalid/screen-off.m3u8", StreamType.HLS, "原画")
        val fixture = object : PlatformResolver {
            override val platformId = "qaScreenOff"
            override fun supports(url: String) = url.startsWith("https://qa.invalid/")
            override suspend fun resolveRoom(url: String) = LiveRoomInfo(url, platformId, "QA息屏", null)
            override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) = if (live.get()) LiveStatus.LIVE else LiveStatus.OFFLINE
            override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) = if (live.get()) listOf(source) else emptyList()
        }
        val clientField = container.javaClass.getDeclaredField("httpClient").apply { isAccessible = true }
        val resolverField = ResolverRegistry::class.java.getDeclaredField("resolvers").apply { isAccessible = true }
        val originalClient = container.httpClient
        val originalResolvers = resolverField.get(container.resolverRegistry)
        @Suppress("UNCHECKED_CAST")
        resolverField.set(container.resolverRegistry, listOf(fixture) + originalResolvers as List<PlatformResolver>)
        clientField.set(container, originalClient.newBuilder().addInterceptor { chain ->
            if (chain.request().url.host != "qa.invalid") return@addInterceptor chain.proceed(chain.request())
            val bytes = if (chain.request().url.encodedPath.endsWith(".ts")) media else {
                val index = sequence.incrementAndGet()
                "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$index\n#EXTINF:2,\n/$index.ts\n".toByteArray()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
        }.build())
        val dao = container.database.liveRoomDao()
        val base = LiveRoomEntity(originalUrl = source.url, normalizedUrl = source.url, platform = fixture.platformId,
            anchorName = "QA息屏", roomTitle = null, outputFormat = "TS", segmentEnabled = false,
            scheduledMonitoringEnabled = true, checkIntervalSeconds = 15, notificationsEnabled = false)
        val roomId = dao.insert(base)
        val pausedId = dao.insert(base.copy(normalizedUrl = "https://qa.invalid/paused", taskStatus = TaskStatus.PAUSED))
        val manualId = dao.insert(base.copy(normalizedUrl = "https://qa.invalid/manual", scheduledMonitoringEnabled = false))
        try {
            container.preferences.setGlobalAutoRecordEnabled(true)
            container.preferences.setBackgroundMonitoringEnabled(true)
            container.preferences.setResumeAfterBoot(false)
            container.preferences.setAllowMobileData(true)
            RecordingService.monitor(context)
            withTimeout(30_000) { repository.logs.first { rows -> rows.any { it.eventType == "BACKGROUND_MONITOR_STARTED" } } }
            RoomMonitorWorker.schedule(context, container.preferences)
            assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("streamcap-room-monitor").get().any { !it.state.isFinished })
            shell("input keyevent 3")
            shell("input keyevent 223")
            val power = context.getSystemService(PowerManager::class.java)
            withTimeout(10_000) { while (power.isInteractive) delay(100) }
            assertTrue(cpuLockHeld())
            assertTrue(shell("dumpsys wifi").contains("StreamCap:Transfer"))
            live.set(true)
            val recording = withTimeout(45_000) { repository.recordings.first { rows ->
                rows.any { it.roomId == roomId && it.status == TaskStatus.RECORDING && it.totalBytes > 0 }
            }.first { it.roomId == roomId } }
            assertFalse(power.isInteractive)
            val initialBytes = recording.totalBytes
            // Disabling background checks must leave the ongoing transfer protected.
            container.preferences.setBackgroundMonitoringEnabled(false)
            RoomMonitorWorker.schedule(context, container.preferences)
            RecordingService.stopMonitoring(context)
            delay(8_000)
            val later = repository.session(recording.id)!!
            assertTrue("Download must grow while asleep: $initialBytes -> ${later.totalBytes}", later.totalBytes > initialBytes)
            assertFalse(power.isInteractive)
            assertTrue(cpuLockHeld())
            assertTrue(shell("dumpsys wifi").contains("StreamCap:Transfer"))
            assertFalse(repository.recordings.first().any { it.roomId == pausedId || it.roomId == manualId })
            assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("streamcap-room-monitor").get().all { it.state.isFinished })
            context.startService(RecordingService.pauseIntent(context, roomId))
            val saved = withTimeout(45_000) { repository.recordings.first { rows ->
                rows.any { it.id == recording.id && it.status in setOf(TaskStatus.PAUSED, TaskStatus.FAILED) }
            }.first { it.id == recording.id } }
            assertEquals(saved.errorMessage, TaskStatus.PAUSED, saved.status)
            assertTrue(saved.outputUris.isNotEmpty())
            val manager = context.getSystemService(ActivityManager::class.java)
            @Suppress("DEPRECATION")
            withTimeout(10_000) { while (manager.getRunningServices(100).any { it.service.className == RecordingService::class.java.name }) delay(100) }
            assertFalse(cpuLockHeld())
            assertFalse(shell("dumpsys wifi").lineSequence().any { it.contains("StreamCap:Transfer") && it.contains("WifiLock{") })
            android.util.Log.i("ScreenOffQA", "PASS screenOff=true autoStart=true bytes=$initialBytes->${later.totalBytes}; paused/manual excluded; recovery independent of boot; locks released at idle")
        } finally {
            context.startService(RecordingService.pauseIntent(context, roomId))
            RecordingService.stopMonitoring(context)
            delay(1_000)
            repository.recordings.first().filter { it.roomId in listOf(roomId, pausedId, manualId) }.forEach { saved ->
                container.recordingStorage.deleteRecordingFiles(saved.outputUris)
                repository.session(saved.id)?.let { repository.deleteSession(it) }
            }
            listOf(roomId, pausedId, manualId).forEach { repository.deleteRoom(it) }
            clientField.set(container, originalClient)
            resolverField.set(container.resolverRegistry, originalResolvers)
            container.preferences.setGlobalAutoRecordEnabled(previous.globalAutoRecordEnabled)
            container.preferences.setBackgroundMonitoringEnabled(previous.backgroundMonitoringEnabled)
            container.preferences.setResumeAfterBoot(previous.resumeAfterBoot)
            container.preferences.setAllowMobileData(previous.allowMobileData)
            RoomMonitorWorker.schedule(context, container.preferences)
            shell("input keyevent 224")
        }
    }
}
