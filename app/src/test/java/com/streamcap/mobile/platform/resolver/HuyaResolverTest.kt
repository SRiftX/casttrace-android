package com.streamcap.mobile.platform.resolver

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class HuyaResolverTest {
    private val resolver = HuyaResolver(OkHttpClient())

    @Test fun replayStatusIsOffline() {
        assertFalse(resolver.isLiveStatus("REPLAY"))
        assertFalse(resolver.isLiveStatus("OFF"))
        assertTrue(resolver.isLiveStatus("ON"))
    }

    @Test fun originalQualityPreservesSignedUrlWithoutEmptyRatio() {
        val url = "https://example.com/live.m3u8?wsSecret=signed&ctype=tars_mp"
        assertEquals(url, resolver.withRatio(url, null))
        assertEquals(url, resolver.withRatio(url, ""))
    }

    @Test fun lowerQualityReplacesExistingRatioWithoutDuplicatingIt() {
        assertEquals(
            "https://example.com/live.m3u8?ratio=2000&wsSecret=signed",
            resolver.withRatio("https://example.com/live.m3u8?ratio=4000&wsSecret=signed", "2000"),
        )
    }

    @Test fun extractsNestedStreamJsonWithoutTruncatingAtInnerObject() {
        val html = "prefix stream: {\"data\":[{\"nested\":{\"name\":\"x\"}}]} suffix"
        assertEquals(
            "{\"data\":[{\"nested\":{\"name\":\"x\"}}]}",
            resolver.extractJsonObject(html, html.indexOf('{')),
        )
    }

    @Test fun usesLiveScreenshotAsRoomCoverAndSecuresIt() {
        assertEquals(
            "https://live-cover.example/frame.jpg",
            huyaCoverUrl(JSONObject("""{"screenshot":"http://live-cover.example/frame.jpg"}""")),
        )
        assertNull(huyaCoverUrl(JSONObject("""{"screenshot":""}""")))
    }
}
