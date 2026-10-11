package com.streamcap.mobile.danmaku

import okhttp3.OkHttpClient

data class DanmakuCapability(
    val enabled: Boolean,
    val events: Set<DanmakuEventType>,
    val identityQuality: String,
    val unavailableReason: String? = null,
)

class RealDanmakuClientFactory(private val httpClient: OkHttpClient) {
    fun capability(platform: String): DanmakuCapability = when {
        platform.equals("douyin", true) || platform.contains("抖音") ||
            platform.equals("douyu", true) || platform.contains("斗鱼") ||
            platform.equals("bilibili", true) || platform.contains("B站") || platform.contains("哔哩") ->
            DanmakuCapability(true, setOf(DanmakuEventType.CHAT), "平台提供时保存用户 ID 和昵称")
        platform.equals("huya", true) || platform.contains("虎牙") ->
            DanmakuCapability(true, setOf(DanmakuEventType.CHAT), "平台提供时保存用户 ID 和昵称")
        else -> DanmakuCapability(false, emptySet(), "不可用", "此平台尚无已验收的弹幕客户端")
    }

    fun supports(platform: String): Boolean = capability(platform).enabled

    fun forPlatform(platform: String): DanmakuClient = when {
        platform.equals("douyin", true) || platform.contains("抖音") -> DouyinDanmakuClient(httpClient)
        platform.equals("douyu", true) || platform.contains("斗鱼") -> DouyuDanmakuClient(httpClient)
        platform.equals("bilibili", true) || platform.contains("B站") || platform.contains("哔哩") -> BilibiliDanmakuClient(httpClient)
        platform.equals("huya", true) || platform.contains("虎牙") -> HuyaDanmakuClient(httpClient)
        else -> UnsupportedDanmakuClient(platform)
    }
}

private class UnsupportedDanmakuClient(private val platform: String) : DanmakuClient {
    override suspend fun connect(context: DanmakuConnectContext) = kotlinx.coroutines.flow.flow<DanmakuFrame> {
        emit(DanmakuFrame.Status("unsupported-real-platform:$platform"))
    }

    override suspend fun close(reason: String) = Unit
}
