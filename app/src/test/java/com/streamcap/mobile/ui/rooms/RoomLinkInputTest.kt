package com.streamcap.mobile.ui.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLinkInputTest {
    @Test fun extractsSharedUrlWithoutTrailingPunctuation() {
        assertEquals("https://www.huya.com/123", RoomLinkInput.extract("主播开播啦 https://www.huya.com/123。"))
        assertNull(RoomLinkInput.extract("没有链接"))
    }

    @Test fun extractsTheThreeReportedShares() {
        assertEquals("https://b23.tv/nyVZ9cs", RoomLinkInput.extract("【青春制作人们 请pick我！-bilibili直播】 https://b23.tv/nyVZ9cs"))
        assertEquals("https://v.douyin.com/RWTjSDkAJRs/", RoomLinkInput.extract("9- #在抖音，记录美好生活#【戟创官方旗舰店】正在直播，来和我一起支持Ta吧。复制下方链接，打开【抖音】，直接观看直播！ https://v.douyin.com/RWTjSDkAJRs/ 8@1.com :2pm"))
        assertEquals("https://hy.fan/CH965Gr", RoomLinkInput.extract("https://hy.fan/CH965Gr"))
        assertEquals("https://b23.tv/nyVZ9cs", RoomLinkInput.extract("[https://b23.tv/nyVZ9cs](https://b23.tv/nyVZ9cs)\\"))
    }

    @Test fun preservesLinesAndRejectsOverLimit() {
        assertEquals(2, RoomLinkInput.lines("https://a\n\nhttps://b").size)
        assertTrue(RoomLinkInput.exceedsLimit((1..201).joinToString("\n")))
    }
}
