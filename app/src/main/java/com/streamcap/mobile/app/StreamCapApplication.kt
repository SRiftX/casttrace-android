package com.streamcap.mobile.app

import android.app.Application
import com.streamcap.mobile.service.RoomMonitorWorker

class StreamCapApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        RoomMonitorWorker.cancelLegacySchedule(this)
    }
}
