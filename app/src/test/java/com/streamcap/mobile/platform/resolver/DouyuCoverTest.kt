package com.streamcap.mobile.platform.resolver

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DouyuCoverTest {
    @Test
    fun extractsAndSecuresRoomCoverUrl() {
        val cover = douyuCoverUrl(JSONObject("""{"room_src":"http://img.example/live.jpg"}"""))

        assertEquals("https://img.example/live.jpg", cover)
    }

    @Test
    fun acceptsProtocolRelativeCoverUrl() {
        val cover = douyuCoverUrl(JSONObject("""{"roomPic":"//img.example/live.jpg"}"""))

        assertEquals("https://img.example/live.jpg", cover)
    }

    @Test
    fun ignoresMissingCoverUrl() {
        assertNull(douyuCoverUrl(JSONObject("{}")))
    }
}
