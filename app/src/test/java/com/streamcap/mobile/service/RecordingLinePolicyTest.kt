package com.streamcap.mobile.service

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingLinePolicyTest {
    private val hls = StreamSource("https://cdn.example/live.m3u8", StreamType.HLS, "原画")
    private val flv = StreamSource("https://cdn.example/live.flv", StreamType.FLV, "原画")

    @Test
    fun huyaAndDouyinPreferFlvForMp4WhenAvailable() {
        assertEquals(listOf(flv), RecordingLinePolicy.candidates(room("huya"), listOf(hls, flv), 0))
        assertEquals(listOf(flv), RecordingLinePolicy.candidates(room("douyin"), listOf(hls, flv), 0))
    }

    @Test
    fun repeatedNonHuyaFailuresSwitchToHls() {
        assertEquals(listOf(hls), RecordingLinePolicy.candidates(room("douyin", "FLV"), listOf(flv, hls), 3))
    }

    @Test
    fun missingRequestedFormatFallsBackToAvailableTypesAndRotates() {
        val sources = listOf(hls.copy(url = "https://a.example/live.m3u8"), hls.copy(url = "https://b.example/live.m3u8"))
        val candidates = RecordingLinePolicy.candidates(room("bilibili", "FLV"), sources, 0)
        assertEquals(sources, candidates)
        assertEquals(1, RecordingLinePolicy.initialIndex(1, candidates.size))
    }

    @Test
    fun selectedSourceDeterminesFallbackContainer() {
        assertEquals("MP4", RecordingLinePolicy.outputFormat("MP4", flv))
        assertEquals("FLV", RecordingLinePolicy.outputFormat("MKV", flv))
        assertEquals("TS", RecordingLinePolicy.outputFormat("MKV", hls))
    }

    private fun room(platform: String, outputFormat: String = "MP4") = LiveRoom(
        id = 1,
        originalUrl = "https://example.com/room",
        normalizedUrl = "https://example.com/room",
        platform = platform,
        anchorName = "anchor",
        roomTitle = null,
        enabled = true,
        autoRecord = true,
        quality = "原画",
        mediaType = "视频",
        outputFormat = outputFormat,
        flvDirectDownload = false,
        storageDirectoryUri = null,
        segmentEnabled = true,
        segmentSeconds = 1800,
        scheduledMonitoringEnabled = false,
        notificationsEnabled = true,
        checkIntervalSeconds = 60,
        liveStatus = LiveStatus.LIVE,
        taskStatus = TaskStatus.WAITING,
        lastCheckAt = null,
        createdAt = 0,
        updatedAt = 0,
    )
}
