package com.streamcap.mobile.recording

import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.recording.engine.RecorderState
import com.streamcap.mobile.recording.engine.RecordingRequest
import com.streamcap.mobile.recording.flv.DirectHttpRecorder
import com.streamcap.mobile.recording.flv.FlvFileInspector
import com.streamcap.mobile.recording.hls.HlsRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RecordingEnginesTest {
    @Test fun confirmedFlvLiveEndCompletesOnceAndPreservesMedia() = runBlocking {
        val server = MockWebServer()
        val media = flvHeader() + flvTag(0, byteArrayOf(0x17, 1, 0, 0, 0, 1))
        server.enqueue(MockResponse().setBody(Buffer().write(media)))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = Files.createTempFile("flv-live-end", ".flv").toFile()
        var refreshes = 0
        try {
            val recorder = DirectHttpRecorder(OkHttpClient(), scope)
            val handle = recorder.start(RecordingRequest(server.url("/live").toString(), emptyMap(), StreamType.FLV, file,
                refreshSource = { refreshes++; throw com.streamcap.mobile.recording.engine.LiveStreamEndedException() }))
            val end = withTimeout(5_000) { recorder.observe(handle).first { it.state in setOf(RecorderState.COMPLETED, RecorderState.FAILED) } }
            assertEquals(RecorderState.COMPLETED, end.state)
            assertTrue(end.liveEnded)
            assertEquals(null, end.errorMessage)
            assertEquals(1, refreshes)
            assertEquals(1, server.requestCount)
            assertArrayEquals(media, file.readBytes())
        } finally { scope.cancel(); server.shutdown(); file.delete() }
    }

    @Test fun confirmedHlsLiveEndDoesNotRetryEndListAsAnError() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:1,\n/one.ts\n#EXT-X-ENDLIST\n"))
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:1,\n/one.ts\n#EXT-X-ENDLIST\n"))
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = Files.createTempFile("hls-live-end", ".ts").toFile()
        var refreshes = 0
        try {
            val recorder = HlsRecorder(OkHttpClient(), scope)
            val handle = recorder.start(RecordingRequest(server.url("/live").toString(), emptyMap(), StreamType.HLS, file,
                refreshSource = { refreshes++; throw com.streamcap.mobile.recording.engine.LiveStreamEndedException() }))
            val end = withTimeout(5_000) { recorder.observe(handle).first { it.state in setOf(RecorderState.COMPLETED, RecorderState.FAILED) } }
            assertEquals(RecorderState.COMPLETED, end.state)
            assertTrue(end.liveEnded)
            assertEquals(null, end.errorMessage)
            assertEquals(1, refreshes)
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        } finally { scope.cancel(); server.shutdown(); file.delete() }
    }
    @Test fun flvRetainsWrittenMediaAcrossMoreThanThreeEmptyReconnects() = runBlocking {
        val server = MockWebServer()
        val firstTag = flvTag(0, byteArrayOf(0x17, 1, 0, 0, 0, 1))
        val secondTag = flvTag(1000, byteArrayOf(0x27, 1, 0, 0, 0, 2))
        val thirdTag = flvTag(2000, byteArrayOf(0x27, 1, 0, 0, 0, 3))
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (attempts.incrementAndGet()) {
                1 -> MockResponse().setBody(Buffer().write(flvHeader() + firstTag))
                in 2..5 -> MockResponse().setResponseCode(503)
                6 -> MockResponse().setBody(Buffer().write(flvHeader() + secondTag))
                in 7..10 -> MockResponse().setResponseCode(503)
                else -> MockResponse().setBody(Buffer().write(flvHeader() + thirdTag))
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = Files.createTempFile("flv-outage", ".flv.part").toFile()
        try {
            val recorder = DirectHttpRecorder(OkHttpClient(), scope)
            val url = server.url("/stream").toString()
            val handle = recorder.start(RecordingRequest(url, emptyMap(), StreamType.FLV, file, 1800,
                refreshSource = { StreamSource(url, StreamType.FLV, "原画") }))
            val expected = flvHeader() + firstTag + secondTag + thirdTag
            withTimeout(25000) { recorder.observe(handle).first { it.bytesWritten >= expected.size } }
            recorder.stop(handle, com.streamcap.mobile.recording.engine.StopReason.PAUSE)
            assertTrue(attempts.get() >= 11)
            assertArrayEquals(expected, file.readBytes())
        } finally { scope.cancel(); server.shutdown(); file.delete() }
    }
    @Test fun flvReconnectSkipsReplayedGopAndDoesNotResetClockForCodecHeader() = runBlocking {
        val server = MockWebServer()
        val config = flvTag(0, byteArrayOf(0x17, 0))
        val first = flvTag(10000, byteArrayOf(0x17, 1, 10))
        val overlap = flvTag(11000, byteArrayOf(0x17, 1, 11))
        val next = flvTag(12000, byteArrayOf(0x17, 1, 12))
        server.enqueue(MockResponse().setBody(Buffer().write(flvHeader() + config + first + overlap)))
        server.enqueue(MockResponse().setBody(Buffer().write(flvHeader() + config + overlap + next)))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = Files.createTempFile("flv-overlap", ".flv.part").toFile()
        try {
            val expected = flvHeader() + config + first + overlap + flvTag(11000, byteArrayOf(0x17, 0)) + next
            val recorder = DirectHttpRecorder(OkHttpClient(), scope)
            val handle = recorder.start(RecordingRequest(server.url("/live").toString(), emptyMap(), StreamType.FLV, file, 1800))
            withTimeout(5000) { recorder.observe(handle).first { it.bytesWritten >= expected.size } }
            recorder.stop(handle, com.streamcap.mobile.recording.engine.StopReason.PAUSE)
            withTimeout(5000) { recorder.observe(handle).first { it.state == RecorderState.STOPPED } }
            assertArrayEquals(expected, file.readBytes())
        } finally { scope.cancel(); server.shutdown(); file.delete() }
    }

    @Test fun flvExpiredUrlRefreshKeepsOneFileAndExistingTags() = runBlocking {
        val server = MockWebServer()
        val firstTag = flvTag(1000, byteArrayOf(0x17, 1))
        val secondTag = flvTag(2000, byteArrayOf(0x17, 2))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/first" -> MockResponse().setBody(Buffer().write(flvHeader() + firstTag))
                "/expired" -> MockResponse().setResponseCode(403)
                else -> MockResponse().setBody(Buffer().write(flvHeader() + secondTag))
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = Files.createTempFile("flv-renew", ".flv.part").toFile()
        var refreshes = 0
        try {
            val recorder = DirectHttpRecorder(OkHttpClient(), scope)
            val expected = flvHeader() + firstTag + secondTag
            val handle = recorder.start(RecordingRequest(
                server.url("/first").toString(), emptyMap(), StreamType.FLV, file, 1800,
                refreshSource = {
                    StreamSource(server.url(if (++refreshes == 1) "/expired" else "/fresh").toString(), StreamType.FLV, "原画")
                },
            ))
            withTimeout(10000) { recorder.observe(handle).first { it.bytesWritten >= expected.size } }
            recorder.stop(handle, com.streamcap.mobile.recording.engine.StopReason.PAUSE)
            val stopped = withTimeout(5000) { recorder.observe(handle).first { it.state == RecorderState.STOPPED } }
            assertEquals(1, stopped.outputFiles.size)
            assertTrue(refreshes >= 2)
            assertArrayEquals(expected, file.readBytes())
        } finally { scope.cancel(); server.shutdown(); file.delete() }
    }

    @Test fun hlsPauseDuringHttpReadPreservesMediaAsStopped() = runBlocking {
        val server = MockWebServer()
        val readingSecond = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/live" -> MockResponse().setBody("#EXTM3U\n#EXTINF:1,\n/one.ts\n#EXTINF:1,\n/two.ts\n")
                "/one.ts" -> MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3)))
                else -> {
                    readingSecond.countDown()
                    MockResponse().setBody("delayed").setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = Files.createTempFile("hls-pause-read", ".ts").toFile()
        try {
            val recorder = HlsRecorder(OkHttpClient(), scope)
            val handle = recorder.start(RecordingRequest(server.url("/live").toString(), emptyMap(), StreamType.HLS, file))
            assertTrue(readingSecond.await(5, java.util.concurrent.TimeUnit.SECONDS))
            recorder.stop(handle, com.streamcap.mobile.recording.engine.StopReason.PAUSE)
            val terminal = withTimeout(5000) {
                recorder.observe(handle).first { it.state == RecorderState.STOPPED || it.state == RecorderState.FAILED }
            }
            assertEquals(RecorderState.STOPPED, terminal.state)
            assertEquals(3L, terminal.bytesWritten)
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        } finally { scope.cancel(); server.shutdown(); file.delete() }
    }

    @Test
    fun hlsMasterReturnedDuringPollingIsNotWrittenAsVideo() = runBlocking {
        val server = MockWebServer()
        var polls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/live" -> MockResponse().setBody(if (++polls == 1) {
                    "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\n/unused.ts\n"
                } else "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n/media\n")
                "/media" -> MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\n/a.ts\n#EXT-X-ENDLIST\n")
                "/a.ts" -> MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3)))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = Files.createTempFile("hls-master-switch", ".ts").toFile()
        try {
            val recorder = HlsRecorder(OkHttpClient(), scope)
            val handle = recorder.start(RecordingRequest(server.url("/live").toString(), emptyMap(), StreamType.HLS, file))
            val terminal = withTimeout(5000) { recorder.observe(handle).first { it.state == RecorderState.COMPLETED || it.state == RecorderState.FAILED } }
            assertEquals(RecorderState.COMPLETED, terminal.state)
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        } finally { scope.cancel(); server.shutdown(); file.delete() }
    }

    @Test
    fun hlsMasterPlaylistDownloadsAndSegmentsTsFiles() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/master.m3u8" -> MockResponse().setBody(
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n/media.m3u8\n",
                )
                "/media.m3u8" -> MockResponse().setBody(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:7\n" +
                        "#EXTINF:1.0,\n/one.ts\n#EXTINF:1.0,\n/two.ts\n#EXT-X-ENDLIST\n",
                )
                "/one.ts" -> MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3)))
                "/two.ts" -> MockResponse().setBody(Buffer().write(byteArrayOf(4, 5)))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = Files.createTempFile("streamcap-hls", ".ts.part").toFile()
        try {
            val recorder = HlsRecorder(OkHttpClient(), scope)
            val handle = recorder.start(
                RecordingRequest(server.url("/master.m3u8").toString(), emptyMap(), StreamType.HLS, first, 1),
            )
            val terminal = withTimeout(5_000) {
                recorder.observe(handle).first { it.state in setOf(RecorderState.COMPLETED, RecorderState.FAILED) }
            }
            assertEquals(RecorderState.COMPLETED, terminal.state)
            assertEquals(2, terminal.outputFiles.size)
            assertEquals(
                "${first.name.removeSuffix(".ts.part")}_002.ts.part",
                terminal.outputFiles[1].name,
            )
            assertArrayEquals(byteArrayOf(1, 2, 3), terminal.outputFiles[0].readBytes())
            assertArrayEquals(byteArrayOf(4, 5), terminal.outputFiles[1].readBytes())
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun hls403RefreshesSourceWithoutEndingRecording() = runBlocking {
        val server = MockWebServer()
        var segmentRequests = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/media.m3u8" -> MockResponse().setBody(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:${segmentRequests}\n" +
                        "#EXTINF:1.0,\n/segment.ts\n",
                )
                "/segment.ts" -> {
                    segmentRequests++
                    if (segmentRequests == 1) {
                        MockResponse().setBody(Buffer().write(byteArrayOf(1, 2)))
                    } else if (segmentRequests == 2) {
                        MockResponse().setResponseCode(403)
                    } else {
                        MockResponse().setBody(Buffer().write(byteArrayOf(3, 4)))
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val output = Files.createTempFile("streamcap-hls-refresh", ".ts.part").toFile()
        try {
            val recorder = HlsRecorder(OkHttpClient(), scope)
            val handle = recorder.start(
                RecordingRequest(
                    server.url("/media.m3u8").toString(),
                    emptyMap(),
                    StreamType.HLS,
                    output,
                    refreshSource = { StreamSource(server.url("/media.m3u8").toString(), StreamType.HLS, "原画") },
                ),
            )
            val progress = withTimeout(5_000) {
                recorder.observe(handle).first { it.bytesWritten >= 4L }
            }
            assertEquals(RecorderState.RECORDING, progress.state)
            recorder.stop(handle, com.streamcap.mobile.recording.engine.StopReason.USER)
            val terminal = recorder.observe(handle).first { it.state == RecorderState.STOPPED }
            assertEquals(RecorderState.STOPPED, terminal.state)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.readBytes())
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun hlsCdnRefreshDoesNotWriteSameSequenceTwice() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/first.m3u8" -> MockResponse().setBody(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:8\n" +
                        "#EXTINF:1.0,\n/cdn-a.ts\n#EXT-X-ENDLIST\n",
                )
                "/second.m3u8" -> MockResponse().setBody(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:8\n" +
                        "#EXTINF:1.0,\n/cdn-b.ts\n#EXTINF:1.0,\n/new.ts\n",
                )
                "/cdn-a.ts", "/cdn-b.ts" -> MockResponse().setBody(Buffer().write(byteArrayOf(1, 2)))
                "/new.ts" -> MockResponse().setBody(Buffer().write(byteArrayOf(3, 4)))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val output = Files.createTempFile("streamcap-hls-cdn", ".ts.part").toFile()
        try {
            val recorder = HlsRecorder(OkHttpClient(), scope)
            val handle = recorder.start(
                RecordingRequest(
                    server.url("/first.m3u8").toString(),
                    emptyMap(),
                    StreamType.HLS,
                    output,
                    refreshSource = { StreamSource(server.url("/second.m3u8").toString(), StreamType.HLS, "原画") },
                ),
            )
            withTimeout(5_000) { recorder.observe(handle).first { it.bytesWritten >= 4L } }
            recorder.stop(handle, com.streamcap.mobile.recording.engine.StopReason.USER)
            recorder.observe(handle).first { it.state == RecorderState.STOPPED }
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.readBytes())
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun flvSegmentationCutsOnlyAtTagBoundaryAndKeepsHeader() = runBlocking {
        // 直播源时间戳可能不是从 0 开始，首个媒体帧不能被误判为切片点。
        val audioConfig = flvTag(timestamp = 0, payload = byteArrayOf(0xaf.toByte(), 0, 0x12, 0x10), type = 8)
        val flv = flvHeader() + audioConfig + flvTag(timestamp = 300_000, payload = byteArrayOf(0x17, 0x01)) +
            flvTag(timestamp = 302_000, payload = byteArrayOf(0x17, 0x01))
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(flv)))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = Files.createTempFile("streamcap-flv", ".flv.part").toFile()
        try {
            val recorder = DirectHttpRecorder(OkHttpClient(), scope)
            val handle = recorder.start(
                RecordingRequest(server.url("/live.flv").toString(), emptyMap(), StreamType.FLV, first, 1),
            )
            withTimeout(5_000) { recorder.observe(handle).first { it.outputFiles.size >= 2 } }
            recorder.stop(handle, com.streamcap.mobile.recording.engine.StopReason.USER)
            val terminal = recorder.observe(handle).first { it.state == RecorderState.STOPPED }
            assertEquals(2, terminal.outputFiles.size)
            assertArrayEquals(audioConfig, terminal.outputFiles[1].readBytes().copyOfRange(13, 13 + audioConfig.size))
            terminal.outputFiles.forEach { file ->
                assertTrue(file.length() > 13)
                assertArrayEquals(byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte()), file.readBytes().copyOf(3))
            }
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun flvInspectorRejectsMetadataOnlyFile() {
        val metadataOnly = flvHeader() + flvTag(
            timestamp = 0,
            payload = byteArrayOf(1, 2, 3),
            type = 18,
        )
        val playable = flvHeader() + flvTag(timestamp = 0, payload = byteArrayOf(0x17, 0x01))

        assertTrue(!FlvFileInspector.hasMediaTags(metadataOnly.inputStream()))
        assertTrue(FlvFileInspector.hasMediaTags(playable.inputStream()))
    }

    @Test
    fun flvKeepsCompletedTagsWhenConnectionEndsInsideLastTag() = runBlocking {
        val complete = flvTag(timestamp = 0, payload = byteArrayOf(0x17, 0x00))
        val truncated = flvTag(timestamp = 1_000, payload = byteArrayOf(0x17, 0x01)).copyOf(8)
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(flvHeader() + complete + truncated)))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val output = Files.createTempFile("streamcap-flv-truncated", ".flv.part").toFile()
        try {
            val recorder = DirectHttpRecorder(OkHttpClient(), scope)
            val handle = recorder.start(
                RecordingRequest(server.url("/live.flv").toString(), emptyMap(), StreamType.FLV, output),
            )
            withTimeout(5_000) { recorder.observe(handle).first { it.bytesWritten > 0 } }
            recorder.stop(handle, com.streamcap.mobile.recording.engine.StopReason.USER)
            val terminal = recorder.observe(handle).first { it.state == RecorderState.STOPPED }
            assertArrayEquals(flvHeader() + complete, output.readBytes())
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun flvReconnectsAfterEofAndAppendsWithoutDuplicateHeader() = runBlocking {
        val firstTag = flvTag(timestamp = 0, payload = byteArrayOf(0x17, 0x00))
        val secondTag = flvTag(timestamp = 1_000, payload = byteArrayOf(0x17, 0x01))
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(flvHeader() + firstTag)))
        // 有些 CDN 重连时不重复发送 FLV Header，只返回后续 Tag。
        server.enqueue(MockResponse().setBody(Buffer().write(secondTag)))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val output = Files.createTempFile("streamcap-flv-reconnect", ".flv.part").toFile()
        try {
            val recorder = DirectHttpRecorder(OkHttpClient(), scope)
            val handle = recorder.start(
                RecordingRequest(server.url("/live.flv").toString(), emptyMap(), StreamType.FLV, output),
            )
            val expectedSize = (flvHeader().size + firstTag.size + secondTag.size).toLong()
            withTimeout(5_000) { recorder.observe(handle).first { it.bytesWritten >= expectedSize } }
            recorder.stop(handle, com.streamcap.mobile.recording.engine.StopReason.USER)
            recorder.observe(handle).first { it.state == RecorderState.STOPPED }

            assertEquals(2, server.requestCount)
            assertArrayEquals(flvHeader() + firstTag + secondTag, output.readBytes())
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun flvReconnectKeepsConfiguredSegmentation() = runBlocking {
        val firstTag = flvTag(timestamp = 0, payload = byteArrayOf(0x17, 0x01))
        val secondTag = flvTag(timestamp = 1_000, payload = byteArrayOf(0x17, 0x01))
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(flvHeader() + firstTag)))
        // The second response represents a reconnect after a network interruption. It
        // carries a fresh FLV header and a keyframe at the configured segment boundary.
        server.enqueue(MockResponse().setBody(Buffer().write(flvHeader() + secondTag)))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val output = Files.createTempFile("streamcap-flv-segment-reconnect", ".flv.part").toFile()
        try {
            val recorder = DirectHttpRecorder(OkHttpClient(), scope)
            val handle = recorder.start(
                RecordingRequest(server.url("/live.flv").toString(), emptyMap(), StreamType.FLV, output, 1),
            )
            val terminal = withTimeout(5_000) {
                recorder.observe(handle).first { it.outputFiles.size >= 2 }
            }
            recorder.stop(handle, com.streamcap.mobile.recording.engine.StopReason.USER)
            assertEquals(2, terminal.outputFiles.size)
            assertTrue(terminal.outputFiles.all { it.length() > flvHeader().size })
            terminal.outputFiles.forEach { file ->
                assertArrayEquals(flvHeader(), file.readBytes().copyOf(flvHeader().size))
            }
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    private fun flvHeader() = byteArrayOf(
        'F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, 5,
        0, 0, 0, 9,
        0, 0, 0, 0,
    )

    private fun flvTag(timestamp: Int, payload: ByteArray, type: Int = 9): ByteArray {
        val size = payload.size
        val header = byteArrayOf(
            type.toByte(),
            (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(),
            (timestamp ushr 16).toByte(), (timestamp ushr 8).toByte(), timestamp.toByte(), (timestamp ushr 24).toByte(),
            0, 0, 0,
        )
        val tagSize = 11 + size
        val previous = byteArrayOf(0, (tagSize ushr 16).toByte(), (tagSize ushr 8).toByte(), tagSize.toByte())
        return header + payload + previous
    }
}
