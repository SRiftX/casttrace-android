package com.streamcap.mobile.ui.reminders

import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.hls.HlsSourceSelection
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
        assertEquals(1, nextPreviewSourceIndex(3, setOf(0), 0))
        assertEquals(1, nextPreviewSourceIndex(3, setOf(0, 2), 2))
        assertNull(nextPreviewSourceIndex(3, setOf(0, 1, 2), 2))
        assertNull(nextPreviewSourceIndex(0, emptySet(), 0))
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
