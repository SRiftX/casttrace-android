package com.streamcap.mobile.platform

enum class AndroidSupportStatus {
    AVAILABLE,
    FIRST_BATCH,
    PLANNED,
    NEEDS_REVIEW,
    DISCONTINUED,
}

enum class PlatformEvidenceLevel(
    val label: String,
    val hasPassingRecordingSample: Boolean,
    val sortOrder: Int,
) {
    TEN_MINUTE_PASS("10 分钟专项通过", true, 0),
    SHORT_PASS("短测通过", true, 1),
    MIXED("有通过样本，另有疑点", true, 2),
    PREFLIGHT("预检通过，未完成录制", false, 3),
    INCOMPLETE("已保存，未达到目标时长", false, 4),
    BLOCKED("样本受限", false, 5),
    FAILED("本轮未通过", false, 6),
}

data class PlatformVerificationEvidence(
    val testedOn: String,
    val level: PlatformEvidenceLevel,
    val summary: String,
)

data class PlatformCatalogEntry(
    val key: String,
    val name: String,
    val domains: List<String>,
    val status: AndroidSupportStatus,
    val aliases: List<String> = emptyList(),
    val resolverId: String = key,
    val verification: PlatformVerificationEvidence? = null,
)

object PlatformCatalog {
    val entries = listOf(
        PlatformCatalogEntry("custom", "HLS / FLV 直链", listOf(".m3u8", ".flv"), AndroidSupportStatus.AVAILABLE, resolverId = "direct"),
        PlatformCatalogEntry("huya", "虎牙直播", listOf("huya.com", "hy.fan"), AndroidSupportStatus.AVAILABLE, verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.SHORT_PASS, "281014 成品 116 秒通过媒体检查，正常录制收到 2 条真实弹幕。")),
        PlatformCatalogEntry("douyin", "抖音直播", listOf("douyin.com", "v.douyin.com"), AndroidSupportStatus.AVAILABLE, aliases = listOf("抖音"), verification = PlatformVerificationEvidence("2026-10-28", PlatformEvidenceLevel.BLOCKED, "本轮公开房间两次未取得线路；需要新的开播房间复测。")),
        PlatformCatalogEntry("bilibili", "B站直播", listOf("live.bilibili.com", "b23.tv"), AndroidSupportStatus.AVAILABLE, aliases = listOf("B站", "哔哩"), verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.SHORT_PASS, "4350043 成品 123 秒通过解码与包间隔检查，收到 74 条真实弹幕。")),
        PlatformCatalogEntry("douyu", "斗鱼直播", listOf("douyu.com"), AndroidSupportStatus.AVAILABLE, verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.SHORT_PASS, "252140 成品 125 秒通过媒体检查；本轮弹幕连接失败。")),
        PlatformCatalogEntry("kuaishou", "快手直播", listOf("live.kuaishou.com"), AndroidSupportStatus.NEEDS_REVIEW, aliases = listOf("快手"), verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.BLOCKED, "公开房间触发请求频率限制，无法完成设备取流和弹幕验收。")),
        PlatformCatalogEntry("tiktok", "TikTok直播", listOf("tiktok.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("yy", "YY直播", listOf("yy.com"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.TEN_MINUTE_PASS, "636 秒成品专项通过；收到 16 条真实弹幕。20 分钟长稳尚未验证。")),
        PlatformCatalogEntry("rednote", "小红书直播", listOf("xiaohongshu.com", "xhslink.com", "app.xhs.cn"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.BLOCKED, "公开目录需要登录，未取得可验收的直播房间。")),
        PlatformCatalogEntry("bigo", "Bigo直播", listOf("bigo.tv"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-28", PlatformEvidenceLevel.BLOCKED, "当前公开房间要求登录；已接入 Cookie，需真实账号房间复测。")),
        PlatformCatalogEntry("blued", "Blued直播", listOf("blued.cn"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("netease", "网易CC直播", listOf("cc.163.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("pandatv", "PandaTV", listOf("pandalive.co.kr", "panda.tv"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("maoer", "猫耳FM直播", listOf("fm.missevan.com", "missevan.com"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.SHORT_PASS, "设备预检和 122 秒成品通过解码与时间轴检查；未完成十分钟测试。")),
        PlatformCatalogEntry("look", "Look直播", listOf("look.163.com"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.BLOCKED, "当前样本接口数据不可用，未取得可验收线路。")),
        PlatformCatalogEntry("flextv", "FlexTV", listOf("flextv.co.kr", "ttinglive.com"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-28", PlatformEvidenceLevel.FAILED, "保存出音视频但最大视频缺口超过 4 秒，成品审计未通过。")),
        PlatformCatalogEntry("popkontv", "PopkonTV", listOf("popkontv.com"), AndroidSupportStatus.PLANNED),
        PlatformCatalogEntry("twitcasting", "TwitCasting", listOf("twitcasting.tv"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-28", PlatformEvidenceLevel.MIXED, "新房间 88 秒成品及 43 条弹幕通过；旧房间样本有 6 秒媒体缺口。")),
        PlatformCatalogEntry("baidu", "百度直播", listOf("live.baidu.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("weibo", "微博直播", listOf("weibo.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("kugou", "酷狗直播", listOf("kugou.com"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.BLOCKED, "候选房间均无有效直播线路，需另找当前开播样本。")),
        PlatformCatalogEntry("twitch", "Twitch", listOf("twitch.tv"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-28", PlatformEvidenceLevel.MIXED, "eslcs 82 秒短测通过；另一个样本发现重复包疑点，仍需复核。")),
        PlatformCatalogEntry("liveme", "LiveMe", listOf("liveme.com"), AndroidSupportStatus.PLANNED),
        PlatformCatalogEntry("huajiao", "花椒直播", listOf("huajiao.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("showroom", "ShowRoom", listOf("showroom-live.com"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-28", PlatformEvidenceLevel.MIXED, "75721 成品 88 秒通过并保存 24 条弹幕；旧房间线路曾返回 403。")),
        PlatformCatalogEntry("acfun", "AcFun直播", listOf("live.acfun.cn"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.INCOMPLETE, "设备保存 494 秒后主播结束；未达到十分钟专项测试目标。")),
        PlatformCatalogEntry("inke", "映客直播", listOf("inke.cn"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.MIXED, "125 秒样本可解码；重复画面疑点尚未通过长测排除。")),
        PlatformCatalogEntry("yinbo", "音播直播", listOf("ybw1666.com"), AndroidSupportStatus.DISCONTINUED),
        PlatformCatalogEntry("changliao", "畅聊直播", listOf("tlclw.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("zhihu", "知乎直播", listOf("zhihu.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("chzzk", "CHZZK", listOf("chzzk.naver.com"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.TEN_MINUTE_PASS, "608 秒成品专项通过，60 秒收到 222 条真实弹幕；20 分钟长稳尚未验证。")),
        PlatformCatalogEntry("haixiu", "嗨秀直播", listOf("haixiutv.com"), AndroidSupportStatus.PLANNED),
        PlatformCatalogEntry("vvxq", "VV星球", listOf("vvxqiu.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("yiqilive", "17Live", listOf("17.live"), AndroidSupportStatus.NEEDS_REVIEW, aliases = listOf("17直播")),
        PlatformCatalogEntry("langlive", "浪Live", listOf("lang.live"), AndroidSupportStatus.NEEDS_REVIEW, aliases = listOf("Lang"), verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.PREFLIGHT, "1274044 设备 HLS 预检通过；持续录制尚未验收。")),
        PlatformCatalogEntry("piaopiao", "漂漂直播", listOf("weimipopo.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("sixroom", "六间房直播", listOf("6.cn"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-27", PlatformEvidenceLevel.TEN_MINUTE_PASS, "632 秒成品通过十分钟专项媒体审计。")),
        PlatformCatalogEntry("lehai", "乐嗨直播", listOf("lehaitv.com"), AndroidSupportStatus.PLANNED),
        PlatformCatalogEntry("shopee", "Shopee直播", listOf("live.shopee.sg", "live.shopee"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("youtube", "YouTube", listOf("youtube.com", "youtu.be"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("taobao", "淘宝直播", listOf("tb.cn", "tbzb.taobao.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("jd", "京东直播", listOf("lives.jd.com", "3.cn"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("faceit", "FACEIT", listOf("faceit.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("huamao", "花猫直播", listOf("catshow168.com", "h.catshow168.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("lianjie", "连接直播", listOf("lailianjie.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("migu", "咪咕直播", listOf("miguvideo.com"), AndroidSupportStatus.PLANNED),
        PlatformCatalogEntry("laixiu", "来秀直播", listOf("imkktv.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("picarto", "Picarto", listOf("picarto.tv"), AndroidSupportStatus.NEEDS_REVIEW, verification = PlatformVerificationEvidence("2026-10-28", PlatformEvidenceLevel.MIXED, "UFO200 84 秒短测通过；FattyNite 十分钟录制时长不足且有缺口。")),
        PlatformCatalogEntry("xindongrebo", "心动热播", listOf("xcqrkj.com"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("soop", "SOOP", listOf("sooplive.com", "sooplive.co.kr"), AndroidSupportStatus.NEEDS_REVIEW),
        PlatformCatalogEntry("qiandurebo", "千度热播", listOf("qiandurebo.com"), AndroidSupportStatus.DISCONTINUED),
        PlatformCatalogEntry("winktv", "WinkTV", listOf("winktv.co.kr"), AndroidSupportStatus.DISCONTINUED),
    )

    fun find(platform: String): PlatformCatalogEntry? {
        val value = platform.trim()
        if (value.isEmpty()) return null
        return entries.firstOrNull { entry ->
            (listOf(entry.key, entry.resolverId, entry.name) + entry.aliases).any { candidate ->
                candidate.equals(value, ignoreCase = true)
            }
        } ?: entries.firstOrNull { entry ->
            (listOf(entry.key, entry.resolverId, entry.name) + entry.aliases).any { candidate ->
                candidate.isNotBlank() && value.contains(candidate, ignoreCase = true)
            }
        }
    }

    fun canonicalPlatform(platform: String): String = find(platform)?.let { entry ->
        if (platform.trim().equals(entry.resolverId, ignoreCase = true) &&
            entries.none { it.key.equals(entry.resolverId, ignoreCase = true) }) {
            entry.resolverId
        } else entry.key
    } ?: platform.trim().lowercase()

    val resolverEntries: List<PlatformCatalogEntry>
        get() = entries.filter { it.status != AndroidSupportStatus.DISCONTINUED }

    val entriesWithEvidence: List<PlatformCatalogEntry>
        get() = entries.filter { it.verification != null }
            .sortedWith(compareBy({ it.verification?.level?.sortOrder ?: Int.MAX_VALUE }, { it.name }))

    val entriesWithPassingRecordingSamples: List<PlatformCatalogEntry>
        get() = entries.filter { it.verification?.level?.hasPassingRecordingSample == true }
}
