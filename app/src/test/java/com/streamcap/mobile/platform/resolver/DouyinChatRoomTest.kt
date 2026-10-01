package com.streamcap.mobile.platform.resolver

import org.junit.Assert.assertEquals
import org.junit.Test

class DouyinChatRoomTest {
    @Test fun takesCurrentLiveInstanceFromStructuredRoomData() {
        val html = """<script type="application/json">{"roomStore":{"roomInfo":{"room":{"id_str":"7599999999999999999","status":2,"title":"Live","owner":{"nickname":"Anchor"}}}}}</script>"""
        val result = DouyinPageParser.parse(html)
        assertEquals("7599999999999999999", result.roomId)
        assertEquals(2, result.roomStatus)
    }
}
