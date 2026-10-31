package com.streamcap.mobile

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.CustomTestApplication

open class BaseTestApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(EntryPointAccessors.fromApplication(this, AppTestGraph::class.java).workerFactory)
            .build()
}

@CustomTestApplication(BaseTestApplication::class)
interface HiltTestApplication
