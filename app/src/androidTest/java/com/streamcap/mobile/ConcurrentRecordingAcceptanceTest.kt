package com.streamcap.mobile

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.domain.model.RoomRecordingOptions
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.service.RecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device-only batch test: one real room per platform, recorded in parallel. */
class ConcurrentRecordingAcceptanceTest {
    @Test fun distinctPlatformsRecordTenMinutes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext as StreamCapApplication).container.repository
        val urls = InstrumentationRegistry.getArguments().getString("platformUrls")
            .orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
        require(urls.size in 2..10) { "platformUrls 需要传入 2 至 10 个平台 URL" }
        val outputFormat = InstrumentationRegistry.getArguments().getString("batchOutputFormat") ?: "MP4"
        val recordSeconds = InstrumentationRegistry.getArguments().getString("batchRecordSeconds")?.toLongOrNull()
            ?.also { require(it in 60..1200) } ?: 630L
        require(outputFormat in setOf("MP4", "FLV", "TS"))
        val rooms = urls.map { url ->
            val roomId = repository.rooms.first().firstOrNull { it.normalizedUrl == url }?.id
                ?: repository.addRoom(url, options = RoomRecordingOptions(
                    outputFormat = outputFormat, quality = "流畅", segmentEnabled = false))
            val sources = repository.streams(roomId)
            repository.updateRecordingOptions(roomId, RoomRecordingOptions(outputFormat = outputFormat, quality = "流畅", segmentEnabled = false))
            assertTrue("No live source for $url", sources.isNotEmpty())
            android.util.Log.i("ConcurrentAcceptance", "PREFLIGHT room=$roomId platform=${repository.room(roomId)?.platform} types=${sources.map { it.type }.distinct()}")
            roomId to requireNotNull(repository.room(roomId)).platform
        }
        require(rooms.map { it.second.lowercase() }.distinct().size == rooms.size) {
            "platformUrls 中存在重复平台；每个平台只能传一个真实房间"
        }
        val roomIds = rooms.map { it.first }
        val baseline = repository.recordings.first().maxOfOrNull { it.id } ?: 0
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(2000)
        val started = SystemClock.elapsedRealtime()
        try {
            roomIds.forEach { id -> withContext(Dispatchers.Main) { RecordingService.start(context, id) } }
            delay(15000)
            while (SystemClock.elapsedRealtime() - started < recordSeconds * 1000) {
                val sessions = repository.recordings.first().filter { it.id > baseline && it.roomId in roomIds }
                android.util.Log.i("ConcurrentAcceptance", "PROGRESS elapsed=${(SystemClock.elapsedRealtime()-started)/1000} sessions=${sessions.map { "${it.roomId}:${it.id}:${it.status}:${it.totalBytes}:${it.durationSeconds}" }}")
                delay(15000)
            }
        } finally {
            roomIds.forEach { id -> withContext(Dispatchers.Main) { context.startService(RecordingService.pauseIntent(context, id)) } }
        }
        delay(90000)
        val sessions = repository.recordings.first().filter { it.id > baseline && it.roomId in roomIds }
        sessions.forEach {
            android.util.Log.i("ConcurrentAcceptance", "RESULT room=${it.roomId} session=${it.id} status=${it.status} duration=${it.durationSeconds} bytes=${it.totalBytes} danmaku=${it.danmakuEventCount} outputs=${it.outputUris.size} error=${it.errorMessage}")
        }
        assertTrue("Only ${sessions.size}/${roomIds.size} platform rooms produced sessions", sessions.map { it.roomId }.distinct().size == roomIds.size)
        val minimumDuration = if (recordSeconds >= 630) recordSeconds - 30 else recordSeconds * 8 / 10
        assertTrue("Not all platform rooms produced media close to ${recordSeconds}s wall time", sessions.all {
            it.durationSeconds in minimumDuration..(recordSeconds + 90) && it.totalBytes > 0 && it.outputUris.isNotEmpty() &&
                it.status in setOf(TaskStatus.PAUSED, TaskStatus.COMPLETED)
        })
        sessions.flatMap { it.outputUris }.forEach { uri ->
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.use { assertTrue(it.read() >= 0) }
        }
    }
}
