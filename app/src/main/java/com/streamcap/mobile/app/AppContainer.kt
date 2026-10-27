package com.streamcap.mobile.app

import android.content.Context
import androidx.room.Room
import com.streamcap.mobile.data.database.StreamCapDatabase
import com.streamcap.mobile.data.database.MIGRATION_1_2
import com.streamcap.mobile.data.database.MIGRATION_2_3
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.platform.resolver.DirectStreamResolver
import com.streamcap.mobile.platform.resolver.BilibiliResolver
import com.streamcap.mobile.platform.resolver.DouyinResolver
import com.streamcap.mobile.platform.resolver.DouyuResolver
import com.streamcap.mobile.platform.resolver.HuyaResolver
import com.streamcap.mobile.platform.resolver.KuaishouResolver
import com.streamcap.mobile.platform.resolver.MaoerResolver
import com.streamcap.mobile.platform.resolver.ShowroomResolver
import com.streamcap.mobile.platform.resolver.ChzzkResolver
import com.streamcap.mobile.platform.resolver.PicartoResolver
import com.streamcap.mobile.platform.resolver.TwitCastingResolver
import com.streamcap.mobile.platform.resolver.GenericWebResolver
import com.streamcap.mobile.platform.resolver.LangLiveResolver
import com.streamcap.mobile.platform.resolver.YiqiLiveResolver
import com.streamcap.mobile.platform.resolver.KugouResolver
import com.streamcap.mobile.platform.resolver.InkeResolver
import com.streamcap.mobile.platform.resolver.NeteaseCcResolver
import com.streamcap.mobile.platform.resolver.BluedResolver
import com.streamcap.mobile.platform.resolver.BigoResolver
import com.streamcap.mobile.platform.resolver.PandaResolver
import com.streamcap.mobile.platform.resolver.TikTokResolver
import com.streamcap.mobile.platform.resolver.TwitchResolver
import com.streamcap.mobile.platform.resolver.YouTubeResolver
import com.streamcap.mobile.platform.resolver.TaobaoResolver
import com.streamcap.mobile.platform.resolver.JdResolver
import com.streamcap.mobile.platform.resolver.ShopeeResolver
import com.streamcap.mobile.platform.resolver.YyResolver
import com.streamcap.mobile.platform.resolver.SixRoomResolver
import com.streamcap.mobile.platform.resolver.BaiduLiveResolver
import com.streamcap.mobile.platform.resolver.LianjieResolver
import com.streamcap.mobile.platform.resolver.VvxqResolver
import com.streamcap.mobile.platform.resolver.WeiboResolver
import com.streamcap.mobile.platform.resolver.ChangliaoResolver
import com.streamcap.mobile.platform.resolver.FaceitResolver
import com.streamcap.mobile.platform.resolver.RednoteResolver
import com.streamcap.mobile.platform.resolver.FlexTvResolver
import com.streamcap.mobile.platform.resolver.ZhihuResolver
import com.streamcap.mobile.platform.resolver.PiaopiaoResolver
import com.streamcap.mobile.platform.resolver.HuajiaoResolver
import com.streamcap.mobile.platform.resolver.AcfunResolver
import com.streamcap.mobile.platform.resolver.LaixiuResolver
import com.streamcap.mobile.platform.resolver.XindongreboResolver
import com.streamcap.mobile.platform.resolver.LookResolver
import com.streamcap.mobile.platform.resolver.LiveMeResolver
import com.streamcap.mobile.platform.resolver.HaixiuResolver
import com.streamcap.mobile.platform.resolver.LehaiResolver
import com.streamcap.mobile.platform.resolver.MiguResolver
import com.streamcap.mobile.platform.resolver.PopkonResolver
import com.streamcap.mobile.platform.PlatformCatalog
import com.streamcap.mobile.platform.resolver.ResolverRegistry
import com.streamcap.mobile.platform.resolver.ShareLinkDiscovery
import com.streamcap.mobile.storage.RecordingStorage
import com.streamcap.mobile.data.security.CredentialStore
import com.streamcap.mobile.danmaku.RealDanmakuClientFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val preferences = AppPreferences(context)
    val networkPolicy = com.streamcap.mobile.network.RecordingNetworkPolicy(context, preferences)
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            kotlinx.coroutines.runBlocking { networkPolicy.requireAllowed() }
            chain.proceed(chain.request())
        }
        .connectTimeout(20, TimeUnit.SECONDS)
        // Read timeout bounds inactivity, not the total lifetime of a live response.
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val database: StreamCapDatabase = Room.databaseBuilder(
        context,
        StreamCapDatabase::class.java,
        "streamcap.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, StreamCapDatabase.MIGRATION_3_4, StreamCapDatabase.MIGRATION_4_5, StreamCapDatabase.MIGRATION_5_6).build()

    val resolverRegistry = ResolverRegistry(
        listOf(
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
            com.streamcap.mobile.platform.resolver.HuamaoResolver(httpClient),
            com.streamcap.mobile.platform.resolver.SoopResolver(httpClient),
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
            *PlatformCatalog.entries.filter { entry -> entry.status != com.streamcap.mobile.platform.AndroidSupportStatus.DISCONTINUED && entry.key !in setOf(
                "custom", "huya", "douyin", "bilibili", "douyu", "kuaishou", "maoer",
                "showroom", "chzzk", "picarto", "twitcasting", "langlive", "yiqilive", "kugou", "inke", "netease", "blued", "bigo", "pandatv",
                "tiktok", "twitch", "youtube", "taobao", "jd", "shopee",
                "yy", "sixroom", "baidu",
                "lianjie", "vvxq",
                "weibo", "changliao", "faceit",
                "rednote", "flextv", "zhihu", "piaopiao", "huamao", "soop",
                "huajiao", "acfun",
                "laixiu", "xindongrebo", "look",
                "liveme", "haixiu", "lehai", "migu", "popkontv",
            ) }.map { GenericWebResolver(it.key, it.domains, httpClient) }.toTypedArray(),
            DirectStreamResolver(httpClient),
        ),
    )

    val credentialStore = CredentialStore(context)
    val danmakuClients = RealDanmakuClientFactory(httpClient, context)

    val repository = StreamCapRepository(
        roomDao = database.liveRoomDao(),
        sessionDao = database.recordingSessionDao(),
        logDao = database.taskLogDao(),
        resolvers = resolverRegistry,
        shareLinks = ShareLinkDiscovery(httpClient, resolverRegistry),
        credentialStore = credentialStore,
    )

    val recordingStorage = RecordingStorage(context)
    val videoExporter = com.streamcap.mobile.danmaku.RecordingVideoExporter(context, repository)
    val shareExporter = com.streamcap.mobile.danmaku.RecordingShareExporter(context, repository)
}
