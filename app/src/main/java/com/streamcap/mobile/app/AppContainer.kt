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
import com.streamcap.mobile.platform.resolver.ResolverRegistry
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
            DirectStreamResolver(httpClient),
        ),
    )

    val credentialStore = CredentialStore(context)
    val danmakuClients = RealDanmakuClientFactory(httpClient)

    val repository = StreamCapRepository(
        roomDao = database.liveRoomDao(),
        sessionDao = database.recordingSessionDao(),
        logDao = database.taskLogDao(),
        resolvers = resolverRegistry,
        credentialStore = credentialStore,
    )

    val recordingStorage = RecordingStorage(context)
    val videoExporter = com.streamcap.mobile.danmaku.RecordingVideoExporter(context, repository)
    val shareExporter = com.streamcap.mobile.danmaku.RecordingShareExporter(context, repository)
}
