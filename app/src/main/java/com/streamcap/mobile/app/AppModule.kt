package com.streamcap.mobile.app

import android.content.Context
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import com.streamcap.mobile.data.database.MIGRATION_1_2
import com.streamcap.mobile.data.database.MIGRATION_2_3
import com.streamcap.mobile.data.database.StreamCapDatabase
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.data.repository.RecordingRepository
import com.streamcap.mobile.data.repository.RoomRepository
import com.streamcap.mobile.data.repository.RoomUrlResolver
import com.streamcap.mobile.data.repository.ReminderRepository
import com.streamcap.mobile.data.repository.LiveStatusChecker
import com.streamcap.mobile.data.repository.TaskLogRepository
import com.streamcap.mobile.data.security.CredentialStore
import com.streamcap.mobile.danmaku.RealDanmakuClientFactory
import com.streamcap.mobile.danmaku.RecordingShareExporter
import com.streamcap.mobile.danmaku.RecordingVideoExporter
import com.streamcap.mobile.network.RecordingNetworkPolicy
import com.streamcap.mobile.platform.resolver.PlatformResolverFactory
import com.streamcap.mobile.platform.resolver.ResolverRegistry
import com.streamcap.mobile.platform.resolver.ShareLinkDiscovery
import com.streamcap.mobile.storage.RecordingStorage
import com.streamcap.mobile.storage.RecordingExportOperations
import com.streamcap.mobile.storage.RecordingMediaStoreOperations
import com.streamcap.mobile.storage.RecordingFileOperations
import com.streamcap.mobile.storage.RecordingPublicationOperations
import com.streamcap.mobile.storage.RecordingSegmentDiscovery
import com.streamcap.mobile.storage.RecordingDeletionOperations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context) = AppPreferences(context)

    @Provides
    @Singleton
    fun networkPolicy(
        @ApplicationContext context: Context,
        preferences: AppPreferences,
    ) = RecordingNetworkPolicy(context, preferences)

    @Provides
    @Singleton
    fun httpClient(networkPolicy: RecordingNetworkPolicy): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            kotlinx.coroutines.runBlocking { networkPolicy.requireAllowed() }
            chain.proceed(chain.request())
        }
        .connectTimeout(20, TimeUnit.SECONDS)
        // Read timeout bounds inactivity, not the total lifetime of a live response.
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): StreamCapDatabase = Room.databaseBuilder(
        context,
        StreamCapDatabase::class.java,
        "streamcap.db",
    ).addMigrations(
        MIGRATION_1_2,
        MIGRATION_2_3,
        StreamCapDatabase.MIGRATION_3_4,
        StreamCapDatabase.MIGRATION_4_5,
        StreamCapDatabase.MIGRATION_5_6,
        StreamCapDatabase.MIGRATION_6_7,
        StreamCapDatabase.MIGRATION_7_8,
    ).build()

    @Provides
    @Singleton
    fun resolverRegistry(httpClient: OkHttpClient) =
        ResolverRegistry(PlatformResolverFactory.create(httpClient))

    @Provides
    @Singleton
    fun credentialStore(@ApplicationContext context: Context) = CredentialStore(context)

    @Provides
    @Singleton
    fun shareLinkDiscovery(httpClient: OkHttpClient, resolvers: ResolverRegistry) =
        ShareLinkDiscovery(httpClient, resolvers)

    @Provides
    @Singleton
    fun recordingRepository(database: StreamCapDatabase) = RecordingRepository(
        roomDao = database.liveRoomDao(),
        sessionDao = database.recordingSessionDao(),
    )

    @Provides
    @Singleton
    fun taskLogRepository(database: StreamCapDatabase) = TaskLogRepository(database.taskLogDao())

    @Provides
    @Singleton
    fun roomUrlResolver(
        shareLinks: ShareLinkDiscovery,
        resolvers: ResolverRegistry,
        credentialStore: CredentialStore,
    ) = RoomUrlResolver(shareLinks, resolvers, credentialStore)

    @Provides
    @Singleton
    fun roomRepository(
        database: StreamCapDatabase,
        urlResolver: RoomUrlResolver,
        taskLogRepository: TaskLogRepository,
    ) = RoomRepository(database.liveRoomDao(), urlResolver, taskLogRepository)

    @Provides
    @Singleton
    fun reminderRepository(
        database: StreamCapDatabase,
        urlResolver: RoomUrlResolver,
        resolvers: ResolverRegistry,
        credentialStore: CredentialStore,
    ) = ReminderRepository(database.liveReminderDao(), database.liveRoomDao(), urlResolver, resolvers, credentialStore)

    @Provides
    @Singleton
    fun liveStatusChecker(
        database: StreamCapDatabase,
        resolvers: ResolverRegistry,
        credentialStore: CredentialStore,
        taskLogRepository: TaskLogRepository,
    ) = LiveStatusChecker(database.liveRoomDao(), resolvers, credentialStore, taskLogRepository)

    @Provides
    @Singleton
    fun repository(
        database: StreamCapDatabase,
        resolvers: ResolverRegistry,
        shareLinks: ShareLinkDiscovery,
        credentialStore: CredentialStore,
        recordingsRepository: RecordingRepository,
        taskLogRepository: TaskLogRepository,
        urlResolver: RoomUrlResolver,
        roomRepository: RoomRepository,
        reminderRepository: ReminderRepository,
        liveStatusChecker: LiveStatusChecker,
    ) = StreamCapRepository(
        roomDao = database.liveRoomDao(),
        reminderDao = database.liveReminderDao(),
        sessionDao = database.recordingSessionDao(),
        logDao = database.taskLogDao(),
        resolvers = resolvers,
        shareLinks = shareLinks,
        credentialStore = credentialStore,
        taskLogRepository = taskLogRepository,
        urlResolver = urlResolver,
        roomRepository = roomRepository,
        reminderRepository = reminderRepository,
        recordingsRepository = recordingsRepository,
        liveStatusChecker = liveStatusChecker,
    )

    @Provides
    @Singleton
    fun recordingFileOperations(@ApplicationContext context: Context) = RecordingFileOperations(context)

    @Provides
    @Singleton
    fun recordingExportOperations(@ApplicationContext context: Context) = RecordingExportOperations(context)

    @Provides
    @Singleton
    fun recordingMediaStoreOperations(
        @ApplicationContext context: Context,
        exportOperations: RecordingExportOperations,
    ) = RecordingMediaStoreOperations(context, exportOperations)

    @Provides
    @Singleton
    fun recordingSegmentDiscovery(
        @ApplicationContext context: Context,
        exportOperations: RecordingExportOperations,
    ) = RecordingSegmentDiscovery(context, exportOperations)

    @Provides
    @Singleton
    fun recordingDeletionOperations(
        @ApplicationContext context: Context,
        segmentDiscovery: RecordingSegmentDiscovery,
    ) = RecordingDeletionOperations(context, segmentDiscovery)

    @Provides
    @Singleton
    fun recordingPublicationOperations(
        @ApplicationContext context: Context,
        deletionOperations: RecordingDeletionOperations,
    ) = RecordingPublicationOperations(context, deletionOperations)

    @Provides
    @Singleton
    fun recordingStorage(
        @ApplicationContext context: Context,
        fileOperations: RecordingFileOperations,
        exportOperations: RecordingExportOperations,
        mediaStoreOperations: RecordingMediaStoreOperations,
        segmentDiscovery: RecordingSegmentDiscovery,
        deletionOperations: RecordingDeletionOperations,
        publicationOperations: RecordingPublicationOperations,
    ) = RecordingStorage(
        context,
        fileOperations,
        exportOperations,
        segmentDiscovery,
        deletionOperations,
        publicationOperations,
        mediaStoreOperations,
    )

    @Provides
    @Singleton
    fun danmakuClients(
        httpClient: OkHttpClient,
        @ApplicationContext context: Context,
    ) = RealDanmakuClientFactory(httpClient, context)

    @Provides
    @Singleton
    fun videoExporter(
        @ApplicationContext context: Context,
        repository: StreamCapRepository,
        recordingStorage: RecordingStorage,
    ) = RecordingVideoExporter(context, repository, recordingStorage)

    @Provides
    @Singleton
    fun shareExporter(
        @ApplicationContext context: Context,
        repository: StreamCapRepository,
        recordingStorage: RecordingStorage,
    ) = RecordingShareExporter(context, repository, recordingStorage)
}
