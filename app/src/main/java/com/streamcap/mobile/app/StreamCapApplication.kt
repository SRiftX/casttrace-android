package com.streamcap.mobile.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class StreamCapApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Synchronous and restricted to our preview files: an asynchronous startup
        // cleanup could delete a newly opened player's snapshot.
        com.streamcap.mobile.storage.PreviewCache.cleanAbandoned(cacheDir)
    }
}
