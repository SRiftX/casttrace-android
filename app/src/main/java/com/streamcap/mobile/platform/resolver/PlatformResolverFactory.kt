package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.platform.PlatformCatalog
import okhttp3.OkHttpClient

/** Builds one resolver per catalog entry; generic web handling is the default adapter. */
object PlatformResolverFactory {
    fun create(httpClient: OkHttpClient): List<PlatformResolver> {
        val dedicated = listOf(
            HuyaResolver(httpClient),
            DouyinResolver(httpClient),
            BilibiliResolver(httpClient),
            DouyuResolver(httpClient),
            KuaishouResolver(httpClient),
            MaoerResolver(httpClient),
            ShowroomResolver(httpClient),
            ChzzkResolver(httpClient),
            PicartoResolver(httpClient),
            TwitCastingResolver(httpClient),
            LangLiveResolver(httpClient),
            YiqiLiveResolver(httpClient),
            KugouResolver(httpClient),
            InkeResolver(httpClient),
            NeteaseCcResolver(httpClient),
            BluedResolver(httpClient),
            BigoResolver(httpClient),
            PandaResolver(httpClient),
            TikTokResolver(httpClient),
            TwitchResolver(httpClient),
            YouTubeResolver(httpClient),
            TaobaoResolver(httpClient),
            JdResolver(httpClient),
            ShopeeResolver(httpClient),
            YyResolver(httpClient),
            SixRoomResolver(httpClient),
            BaiduLiveResolver(httpClient),
            LianjieResolver(httpClient),
            VvxqResolver(httpClient),
            WeiboResolver(httpClient),
            ChangliaoResolver(httpClient),
            FaceitResolver(httpClient),
            RednoteResolver(httpClient),
            FlexTvResolver(httpClient),
            ZhihuResolver(httpClient),
            PiaopiaoResolver(httpClient),
            HuamaoResolver(httpClient),
            SoopResolver(httpClient),
            HuajiaoResolver(httpClient),
            AcfunResolver(httpClient),
            LaixiuResolver(httpClient),
            XindongreboResolver(httpClient),
            LookResolver(httpClient),
            LiveMeResolver(),
            HaixiuResolver(),
            LehaiResolver(),
            MiguResolver(),
            PopkonResolver(),
            DirectStreamResolver(httpClient),
        )
        val byPlatformId = dedicated.associateBy(PlatformResolver::platformId)
        check(byPlatformId.size == dedicated.size) { "Duplicate dedicated platform resolver id" }

        return PlatformCatalog.resolverEntries
            .asSequence()
            .map { entry ->
                byPlatformId[entry.resolverId]
                    ?: GenericWebResolver(entry.key, entry.domains, httpClient)
            }
            .toList()
    }
}
