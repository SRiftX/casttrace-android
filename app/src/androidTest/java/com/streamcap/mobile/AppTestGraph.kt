package com.streamcap.mobile

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.streamcap.mobile.data.database.StreamCapDatabase
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.data.security.CredentialStore
import com.streamcap.mobile.danmaku.RealDanmakuClientFactory
import com.streamcap.mobile.danmaku.RecordingShareExporter
import com.streamcap.mobile.danmaku.RecordingVideoExporter
import com.streamcap.mobile.network.RecordingNetworkPolicy
import com.streamcap.mobile.platform.resolver.ResolverRegistry
import com.streamcap.mobile.storage.RecordingStorage
import okhttp3.OkHttpClient

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppTestGraph {
    val preferences: AppPreferences
    val networkPolicy: RecordingNetworkPolicy
    val httpClient: OkHttpClient
    val database: StreamCapDatabase
    val resolverRegistry: ResolverRegistry
    val credentialStore: CredentialStore
    val danmakuClients: RealDanmakuClientFactory
    val repository: StreamCapRepository
    val recordingStorage: RecordingStorage
    val videoExporter: RecordingVideoExporter
    val shareExporter: RecordingShareExporter
    val workerFactory: HiltWorkerFactory
}

val Context.testGraph: AppTestGraph
    get() = EntryPointAccessors.fromApplication(applicationContext, AppTestGraph::class.java)
