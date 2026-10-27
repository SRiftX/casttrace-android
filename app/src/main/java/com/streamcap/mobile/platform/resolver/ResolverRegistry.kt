package com.streamcap.mobile.platform.resolver

class ResolverRegistry(
    private val resolvers: List<PlatformResolver>,
) {
    val platformIds: Set<String> get() = resolvers.mapTo(linkedSetOf()) { it.platformId }
    fun supports(url: String): Boolean = resolvers.any { it.supports(url) }
    fun resolverFor(url: String): PlatformResolver = resolvers.firstOrNull { it.supports(url) }
        ?: throw UnsupportedPlatformException(url)

    fun resolverForPlatform(platform: String): PlatformResolver = resolvers.firstOrNull {
        platform.startsWith(it.platformId, ignoreCase = true) || it.matchesDisplayName(platform)
    } ?: throw UnsupportedPlatformException(platform)

    private fun PlatformResolver.matchesDisplayName(displayName: String): Boolean = when (platformId) {
        "huya" -> displayName.contains("虎牙")
        "douyin" -> displayName.contains("抖音")
        "bilibili" -> displayName.contains("B站", true) || displayName.contains("哔哩")
        "douyu" -> displayName.contains("斗鱼")
        "kuaishou" -> displayName.contains("快手")
        "direct" -> displayName.startsWith("直链")
        "maoer" -> displayName.contains("猫耳")
        "showroom" -> displayName.contains("ShowRoom", true)
        "chzzk" -> displayName.contains("CHZZK", true)
        "picarto" -> displayName.contains("Picarto", true)
        "twitcasting" -> displayName.contains("TwitCasting", true)
        "langlive" -> displayName.contains("Lang", true) || displayName.contains("浪Live")
        "yiqilive" -> displayName.contains("17Live", true) || displayName.contains("17直播")
        "kugou" -> displayName.contains("酷狗")
        "inke" -> displayName.contains("映客")
        "netease" -> displayName.contains("网易CC", true)
        "blued" -> displayName.contains("Blued", true)
        "bigo" -> displayName.contains("Bigo", true)
        "pandatv" -> displayName.contains("Panda", true)
        "tiktok" -> displayName.contains("TikTok", true)
        "twitch" -> displayName.contains("Twitch", true)
        "youtube" -> displayName.contains("YouTube", true)
        "taobao" -> displayName.contains("淘宝")
        "jd" -> displayName.contains("京东")
        "shopee" -> displayName.contains("Shopee", true)
        "yy" -> displayName.contains("YY", true)
        "sixroom" -> displayName.contains("六间房")
        "baidu" -> displayName.contains("百度")
        "lianjie" -> displayName.contains("连接直播")
        "vvxq" -> displayName.contains("VV")
        "weibo" -> displayName.contains("微博")
        "changliao" -> displayName.contains("畅聊")
        "faceit" -> displayName.contains("FACEIT", true)
        "rednote" -> displayName.contains("小红书")
        "flextv" -> displayName.contains("FlexTV", true)
        "zhihu" -> displayName.contains("知乎")
        "piaopiao" -> displayName.contains("漂漂")
        "huajiao" -> displayName.contains("花椒")
        "acfun" -> displayName.contains("AcFun", true)
        "laixiu" -> displayName.contains("来秀")
        "xindongrebo" -> displayName.contains("心动热播")
        "look" -> displayName.contains("Look", true)
        "liveme" -> displayName.contains("LiveMe", true)
        "haixiu" -> displayName.contains("嗨秀")
        "lehai" -> displayName.contains("乐嗨")
        "migu" -> displayName.contains("咪咕")
        "popkontv" -> displayName.contains("Popkon", true)
        else -> false
    }
}
