package com.streamcap.mobile.platform.resolver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.TaskStatus
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StripchatGirlsLiveSmokeTest {
    @Test
    fun resolvesLiveProfileAndFetchesHlsPlaylist() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass stripchatGirlsLive=true to run the public room smoke test",
            arguments.getString("stripchatGirlsLive")?.toBooleanStrictOrNull() == true,
        )
        val url = "https://stripchatgirls.com/BARBIEsexyCOUPLE"
        val http = OkHttpClient()
        val resolver = StripchatGirlsResolver(http)
        val info = resolver.resolveRoom(url)
        val room = LiveRoom(
            id = 1, originalUrl = url, normalizedUrl = url, platform = info.platform,
            anchorName = info.anchorName, roomTitle = info.roomTitle, enabled = true, autoRecord = false,
            quality = "原画", mediaType = "视频", outputFormat = "MP4", flvDirectDownload = false,
            storageDirectoryUri = null, segmentEnabled = true, segmentSeconds = 1800,
            scheduledMonitoringEnabled = false, notificationsEnabled = true,
            checkIntervalSeconds = 60, liveStatus = LiveStatus.LIVE, taskStatus = TaskStatus.WAITING,
            lastCheckAt = null, createdAt = 0, updatedAt = 0,
        )
        assumeTrue("The public sample room is currently offline", resolver.checkLive(room) == LiveStatus.LIVE)
        val streams = resolver.getStreams(room)
        assertEquals(3, streams.size)

        val playable = streams.firstNotNullOfOrNull { stream ->
            http.newCall(Request.Builder().url(stream.url).headers(okhttp3.Headers.headersOf(
                "User-Agent", stream.headers["User-Agent"].orEmpty(),
                "Referer", stream.headers["Referer"].orEmpty(),
            )).build()).execute().use { response ->
                response.takeIf { it.isSuccessful && it.body?.string()?.startsWith("#EXTM3U") == true }
            }
        }
        assertNotNull("No CDN returned a valid HLS playlist", playable)
        assertTrue(info.anchorName.isNotBlank())
    }
}
