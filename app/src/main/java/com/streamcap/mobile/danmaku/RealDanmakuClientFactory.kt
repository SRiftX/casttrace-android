package com.streamcap.mobile.danmaku

import okhttp3.OkHttpClient

class RealDanmakuClientFactory(private val httpClient: OkHttpClient) {
    fun supports(platform: String): Boolean =
        platform.equals("douyin", true) || platform.contains("抖音") ||
            platform.equals("douyu", true) || platform.contains("斗鱼")
            || platform.equals("bilibili", true) || platform.contains("B站") || platform.contains("哔哩")

    fun forPlatform(platform: String): DanmakuClient = when {
        platform.equals("douyin", true) || platform.contains("抖音") -> DouyinDanmakuClient(httpClient)
        platform.equals("douyu", true) || platform.contains("斗鱼") -> DouyuDanmakuClient(httpClient)
        platform.equals("bilibili", true) || platform.contains("B站") || platform.contains("哔哩") -> BilibiliDanmakuClient(httpClient)
        else -> UnsupportedDanmakuClient(platform)
    }
}

private class UnsupportedDanmakuClient(private val platform: String) : DanmakuClient {
    override suspend fun connect(context: DanmakuConnectContext) = kotlinx.coroutines.flow.flow<DanmakuFrame> {
        emit(DanmakuFrame.Status("unsupported-real-platform:$platform"))
    }

    override suspend fun close(reason: String) = Unit
}
