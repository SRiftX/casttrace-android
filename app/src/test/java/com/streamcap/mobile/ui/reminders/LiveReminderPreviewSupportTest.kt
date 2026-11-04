package com.streamcap.mobile.ui.reminders

import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.hls.HlsSourceSelection
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveReminderPreviewSupportTest {
    @Test fun selectedHlsVariantReplacesTheRouteThatWasActuallyProbed() {
        val flv = StreamSource("https://flv.example/live.flv", StreamType.FLV, "原画")
        val firstHls = StreamSource("https://a.example/master.m3u8", StreamType.HLS, "原画")
        val secondHls = StreamSource("https://b.example/master.m3u8", StreamType.HLS, "原画")
        val selectedVariant = secondHls.copy(url = "https://b.example/720p.m3u8", quality = "720p")

        val (sources, selectedIndex) = applyPreferredHlsSelection(
            listOf(flv, firstHls, secondHls),
            listOf(firstHls, secondHls),
            HlsSourceSelection(sourceIndex = 1, source = selectedVariant),
        )

        assertEquals(2, selectedIndex)
        assertEquals(selectedVariant, sources[selectedIndex])
        assertEquals(firstHls, sources[1])
    }

    @Test fun qualityMenuOffersResolverSupportedLevelsInsteadOfCdnRows() {
        val routes = listOf(
            StreamSource("https://a.example/live.flv", StreamType.FLV, "原画"),
            StreamSource("https://b.example/live.flv", StreamType.FLV, "原画"),
        )

        assertEquals(listOf("原画", "蓝光", "超清", "高清", "标清", "流畅"), previewQualityOptions("B站直播", routes))
        assertEquals(listOf("原画", "蓝光", "超清", "高清", "标清", "流畅"), previewQualityOptions("斗鱼直播", routes))
        assertEquals(
            listOf("原画", "蓝光", "超清", "高清", "标清", "流畅"),
            previewQualityOptions("douyin", emptyList(), selectedQuality = "高清"),
        )
        assertEquals(
            listOf("原画", "蓝光", "超清", "高清", "标清", "流畅"),
            previewQualityOptions("maoer", emptyList()),
        )
    }

    @Test fun failedRoutesAdvanceOnceAndStopAfterEveryRouteWasTried() {
        val routes = listOf(
            StreamSource("https://flv-a.example/live.flv", StreamType.FLV, "原画"),
            StreamSource("https://hls-a.example/live.m3u8", StreamType.HLS, "原画"),
            StreamSource("https://flv-b.example/live.flv", StreamType.FLV, "原画"),
            StreamSource("https://hls-b.example/live.m3u8", StreamType.HLS, "原画"),
        )

        assertEquals(2, nextPreviewSourceIndex(routes, setOf(1), 1))
        assertEquals(3, nextPreviewSourceIndex(routes, setOf(1, 2), 2))
        assertEquals(0, nextPreviewSourceIndex(routes, setOf(1, 2, 3), 3))
        assertNull(nextPreviewSourceIndex(routes, routes.indices.toSet(), 3))
        assertNull(nextPreviewSourceIndex(emptyList(), emptySet(), 0))
    }

    @Test fun playbackStatusWaitsForARealVideoFrame() {
        val route = StreamSource("https://hls.example/live.m3u8", StreamType.HLS, "原画")

        assertEquals("正在连接", previewPlaybackStatus(false, route, null, Player.STATE_IDLE, false, false))
        assertEquals("正在缓冲", previewPlaybackStatus(false, route, null, Player.STATE_BUFFERING, false, false))
        assertEquals("线路已结束", previewPlaybackStatus(false, route, null, Player.STATE_ENDED, true, false))
        assertEquals("正在播放", previewPlaybackStatus(false, route, null, Player.STATE_READY, true, true))
        assertEquals("线路异常", previewPlaybackStatus(false, route, "HTTP 403", Player.STATE_BUFFERING, false, false))
    }

    @Test fun onlyAPlayingHuyaFlvReconnectsAfterEof() {
        assertEquals(true, shouldReconnectHuyaFlvAfterEof("虎牙直播", StreamType.FLV, Player.STATE_ENDED, true))
        assertEquals(true, shouldReconnectHuyaFlvAfterEof("huya", StreamType.FLV, Player.STATE_ENDED, true))
        assertEquals(false, shouldReconnectHuyaFlvAfterEof("douyu", StreamType.FLV, Player.STATE_ENDED, true))
        assertEquals(false, shouldReconnectHuyaFlvAfterEof("huya", StreamType.HLS, Player.STATE_ENDED, true))
        assertEquals(false, shouldReconnectHuyaFlvAfterEof("huya", StreamType.FLV, Player.STATE_ENDED, false))
        assertEquals(false, shouldReconnectHuyaFlvAfterEof("huya", StreamType.FLV, Player.STATE_BUFFERING, true))
    }

    @Test fun stalledPreviewFallsBackOnlyWhenPlaybackIsExpected() {
        assertEquals(true, previewPlaybackNeedsFallback(Player.STATE_ENDED, true, false, true))
        assertEquals(true, previewPlaybackNeedsFallback(Player.STATE_READY, true, false, true))
        assertEquals(false, previewPlaybackNeedsFallback(Player.STATE_READY, true, false, false))
        assertEquals(false, previewPlaybackNeedsFallback(Player.STATE_BUFFERING, true, false, true))
        assertEquals(false, previewPlaybackNeedsFallback(Player.STATE_READY, false, false, true))
    }

    @Test fun hlsPreflightFallbackKeepsAnAttemptableRouteWhenAllCandidatesAreHls() {
        val hls = listOf(
            StreamSource("https://a.example/live.m3u8", StreamType.HLS, "原画"),
            StreamSource("https://b.example/live.m3u8", StreamType.HLS, "原画"),
        )
        val mixed = hls + StreamSource("https://c.example/live.flv", StreamType.FLV, "原画")

        assertEquals(0, fallbackPreviewSourceIndex(hls))
        assertEquals(2, fallbackPreviewSourceIndex(mixed))
        assertEquals(0, fallbackPreviewSourceIndex(emptyList()))
    }

    @Test fun qualitySelectionConstrainsAdaptiveTracks() {
        assertEquals(1920 to 1080, previewMaxVideoSize("蓝光"))
        assertEquals(1280 to 720, previewMaxVideoSize("超清"))
        assertEquals(854 to 480, previewMaxVideoSize("高清"))
        assertEquals(Int.MAX_VALUE to Int.MAX_VALUE, previewMaxVideoSize("原画"))
    }

    @Test fun actualVideoResolutionAccountsForPortraitRotationAndUnknownSize() {
        assertEquals("1920 × 1080", previewResolutionLabel(1920, 1080))
        assertEquals("1080 × 1920", previewResolutionLabel(1920, 1080, rotationDegrees = 90))
        assertNull(previewResolutionLabel(0, 0))
    }

    @Test fun zoomCanShrinkAndRemainsWithinPlayerBounds() {
        assertEquals(0.5f, clampPreviewZoom(0.1f))
        assertEquals(0.75f, clampPreviewZoom(0.75f))
        assertEquals(3f, clampPreviewZoom(4f))
    }

    @Test fun routeMenuIdentifiesTheCdnHost() {
        assertEquals(
            "线路 2 · HLS · cdn.example",
            previewRouteLabel(StreamSource("https://www.cdn.example/live.m3u8", StreamType.HLS, "原画"), 1),
        )
    }
}
