package com.streamcap.mobile.app

import android.app.Application

class StreamCapApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Synchronous and restricted to our preview files: an asynchronous startup
        // cleanup could delete a newly opened player's snapshot.
        com.streamcap.mobile.storage.PreviewCache.cleanAbandoned(cacheDir)
    }
}
