package com.streamcap.mobile

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.app.MainActivity
import com.streamcap.mobile.domain.model.RoomRecordingOptions
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.danmaku.DanmakuEventStore
import com.streamcap.mobile.danmaku.DanmakuSubtitleExporter
import com.streamcap.mobile.danmaku.DanmakuSubtitleFormat
import com.streamcap.mobile.service.RecordingService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

/** Uses the normal service and media recorder; retains evidence in the user's library. */
class RealDanmakuRecordingTest {
    @Test fun recordsVideoAndRealChatTogether() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit live room arguments", args.containsKey("platform") && args.containsKey("roomUrl"))
        val platform = requireNotNull(args.getString("platform"))
        val url = requireNotNull(args.getString("roomUrl"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StreamCapApplication
        val repo = app.container.repository
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val existing = repo.rooms.first().firstOrNull { it.normalizedUrl == url }
        val roomId = existing?.id ?: repo.addRoom(url, options = RoomRecordingOptions(outputFormat = "FLV", segmentEnabled = false))
        check(repo.activeSessionForRoom(roomId) == null) { "房间已有录制，跳过以保护现有任务" }
        val started = System.currentTimeMillis()
        val captureMs = (args.getString("captureSeconds")?.toLongOrNull() ?: 70L).coerceIn(45, 180) * 1000
        var sessionId: Long? = null
        try {
            RecordingService.start(app, roomId, started + captureMs)
            withTimeout(captureMs + 30_000) {
                while (true) {
                    val current = repo.recordings.first().firstOrNull { it.roomId == roomId && (it.startedAt ?: 0) >= started }
                    if (current != null) {
                        sessionId = current.id
                        if (current.totalBytes > 0 && current.danmakuPath?.let { File(it).length() > 0 } == true) break
                        if (current.status == TaskStatus.FAILED) error("录制失败: ${current.errorMessage}")
                        if (current.status in setOf(TaskStatus.COMPLETED, TaskStatus.PAUSED)) error("录制已结束但未同时保存视频和真实聊天")
                    }
                    delay(1_000)
                }
            }
            delay(35_000)
        } finally {
            app.startService(RecordingService.stopIntent(app, roomId))
        }
        val id = requireNotNull(sessionId)
        withTimeout(90_000) {
            repo.observeRecording(id).first {
                it != null && it.status !in setOf(TaskStatus.STARTING, TaskStatus.RECORDING, TaskStatus.RECONNECTING, TaskStatus.FINALIZING)
            }
        }
        val session = requireNotNull(repo.session(id))
        assertTrue(session.totalBytes > 0)
        val video = requireNotNull(session.outputUris.firstOrNull() ?: session.outputUri)
        app.contentResolver.openInputStream(Uri.parse(video)).use { assertTrue(requireNotNull(it).read() >= 0) }
        val file = File(requireNotNull(session.danmakuPath))
        val events = DanmakuEventStore.recover(file)
        assertTrue("没有真实聊天", events.isNotEmpty())
        assertTrue(events.all { it.platform == platform && !it.parserVersion.contains("simulat") && it.mediaTimeMs != null })
        assertEquals(events.size.toLong(), session.danmakuEventCount)
        assertEquals(0L, session.danmakuDroppedCount)
        val subtitle = File(file.parentFile, "qa-export.srt")
        DanmakuSubtitleExporter.export(events, subtitle, DanmakuSubtitleFormat.SRT)
        assertTrue(subtitle.length() > 0)
        println("REAL_RECORDING_SAVED platform=$platform session=$id videoBytes=${session.totalBytes} chat=${events.size} jsonlBytes=${file.length()} srtBytes=${subtitle.length()}")
    }
}
