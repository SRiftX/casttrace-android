package com.streamcap.mobile.platform.resolver

import org.junit.Assert.assertEquals
import org.junit.Test
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType

class ResolverSupportTest {
    @Test fun selectsQualityByLabelAndPreservesSameTierCdnAlternatives() {
        val sources = listOf(
            StreamSource("https://a/low.flv", StreamType.FLV, "标清"),
            StreamSource("https://a/high.flv", StreamType.FLV, "原画"),
            StreamSource("https://b/high.flv", StreamType.FLV, "原画"),
            StreamSource("https://a/hd.flv", StreamType.FLV, "高清"),
        )
        assertEquals(listOf(sources[1], sources[2]), sources.selectQuality("原画"))
        assertEquals(listOf(sources[3]), sources.selectQuality("高清"))
        assertEquals(listOf(sources[3]), sources.selectQuality("超清"))
    }

    @Test fun unknownQualityUsesDistinctBitratesAndDoesNotPreferMissingBitrate() {
        val sources = listOf(
            StreamSource("https://a/unknown.flv", StreamType.FLV, "未知"),
            StreamSource("https://a/high.flv", StreamType.FLV, "未知", bitrate = 2000),
            StreamSource("https://b/high.flv", StreamType.FLV, "未知", bitrate = 2000),
            StreamSource("https://a/low.flv", StreamType.FLV, "未知", bitrate = 1000),
        )
        assertEquals(listOf(sources[1], sources[2]), sources.selectQuality("原画"))
        assertEquals(listOf(sources[3]), sources.selectQuality("蓝光"))
    }
    @Test
    fun upgradesRemoteCleartextStreamToTls() {
        assertEquals(
            "https://cdn.example.com/live.flv?token=abc",
            secureStreamUrl("http://cdn.example.com/live.flv?token=abc"),
        )
    }

    @Test
    fun keepsLocalFixtureServerCleartext() {
        assertEquals(
            "http://localhost:8080/live.m3u8",
            secureStreamUrl("http://localhost:8080/live.m3u8"),
        )
    }
}
