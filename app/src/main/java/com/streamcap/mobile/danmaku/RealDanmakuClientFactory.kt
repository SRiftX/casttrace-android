package com.streamcap.mobile.danmaku

import okhttp3.OkHttpClient

data class DanmakuCapability(
    val enabled: Boolean,
    val events: Set<DanmakuEventType>,
    val identityQuality: String,
    val unavailableReason: String? = null,
)

class RealDanmakuClientFactory(private val httpClient: OkHttpClient, private val appContext: android.content.Context? = null) {
    fun capability(platform: String): DanmakuCapability = when {
        platform.equals("bilibili", true) || platform.contains("B站") || platform.contains("哔哩") ->
            DanmakuCapability(true, setOf(DanmakuEventType.CHAT, DanmakuEventType.GIFT,
                DanmakuEventType.SUPER_CHAT, DanmakuEventType.GUARD), "支持弹幕、SC、礼物和大航海；付费事件协议测试通过，真实接收待验证")
        platform.equals("douyin", true) || platform.contains("抖音") ||
            platform.equals("douyu", true) || platform.contains("斗鱼") ||
            platform.equals("huya", true) || platform.contains("虎牙") ||
            platform.equals("kuaishou", true) || platform.contains("快手") ||
            platform.equals("twitch", true) || platform.equals("chzzk", true) ->
            DanmakuCapability(true, setOf(DanmakuEventType.CHAT, DanmakuEventType.GIFT),
                "平台提供时保存用户、礼物与原始价值；付费事件协议测试通过，真实接收待验证")
        platform.equals("showroom", true) || platform.equals("twitcasting", true) ->
            DanmakuCapability(true, setOf(DanmakuEventType.CHAT), "平台提供时保存用户 ID 和昵称")
        platform.equals("yy", true) || platform.contains("YY", true) ->
            DanmakuCapability(appContext != null, setOf(DanmakuEventType.CHAT), "实验性网页采集，仅昵称；已通过短时真实接收验证")
        platform.equals("rednote", true) || platform.contains("小红书") ->
            DanmakuCapability(appContext != null, setOf(DanmakuEventType.CHAT), "实验性网页采集，需要有效分享链接及登录状态")
        else -> DanmakuCapability(false, emptySet(), "不可用", "此平台尚无已验收的弹幕客户端")
    }

    fun supports(platform: String): Boolean = capability(platform).enabled

    fun forPlatform(platform: String): DanmakuClient = when {
        platform.equals("douyin", true) || platform.contains("抖音") -> DouyinDanmakuClient(httpClient)
        platform.equals("douyu", true) || platform.contains("斗鱼") -> DouyuDanmakuClient(httpClient)
        platform.equals("bilibili", true) || platform.contains("B站") || platform.contains("哔哩") -> BilibiliDanmakuClient(httpClient)
        platform.equals("huya", true) || platform.contains("虎牙") -> HuyaDanmakuClient(httpClient)
        platform.equals("kuaishou", true) || platform.contains("快手") -> KuaishouDanmakuClient(httpClient)
        platform.equals("chzzk", true) -> ChzzkDanmakuClient(httpClient)
        platform.equals("twitch", true) -> TwitchDanmakuClient(httpClient)
        platform.equals("showroom", true) -> ShowroomDanmakuClient(httpClient)
        platform.equals("twitcasting", true) -> TwitCastingDanmakuClient(httpClient)
        (platform.equals("yy", true) || platform.contains("YY", true)) && appContext != null -> WebPageDanmakuClient(appContext, httpClient, "yy")
        (platform.equals("rednote", true) || platform.contains("小红书")) && appContext != null -> WebPageDanmakuClient(appContext, httpClient, "rednote")
        else -> UnsupportedDanmakuClient(platform)
    }
}

private class UnsupportedDanmakuClient(private val platform: String) : DanmakuClient {
    override suspend fun connect(context: DanmakuConnectContext) = kotlinx.coroutines.flow.flow<DanmakuFrame> {
        emit(DanmakuFrame.Status("unsupported-real-platform:$platform"))
    }

    override suspend fun close(reason: String) = Unit
}
