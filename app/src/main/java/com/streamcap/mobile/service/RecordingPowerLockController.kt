package com.streamcap.mobile.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Owns the wake and Wi-Fi locks for one recording service instance. */
@ServiceScoped
class RecordingPowerLockController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val guard = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var refreshJob: Job? = null
    private var closed = false

    fun initialize() = synchronized(guard) {
        if (wakeLock != null) return@synchronized
        wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "StreamCap:Recording",
        ).apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION")
        wifiLock = context.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "StreamCap:Transfer")
            ?.apply { setReferenceCounted(false) }
    }

    fun acquire(
        scope: CoroutineScope,
        shouldKeepAlive: () -> Boolean,
        shouldHoldDuringMonitoring: () -> Boolean,
    ) = synchronized(guard) {
        if (closed) return@synchronized
        acquireLocks()
        if (refreshJob == null) {
            refreshJob = scope.launch {
                while (shouldKeepAlive()) {
                    delay(WAKE_LOCK_REFRESH_MILLIS)
                    synchronized(guard) {
                        if (!closed && shouldHoldDuringMonitoring() && shouldKeepAlive()) acquireLocks()
                    }
                }
                synchronized(guard) { refreshJob = null }
            }
        }
    }

    fun release() = synchronized(guard) {
        refreshJob?.cancel()
        refreshJob = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    fun close() = synchronized(guard) {
        closed = true
        refreshJob?.cancel()
        refreshJob = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private fun acquireLocks() {
        wakeLock?.let { if (!it.isHeld) it.acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
        wifiLock?.let { lock -> runCatching { if (!lock.isHeld) lock.acquire() } }
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        const val WAKE_LOCK_REFRESH_MILLIS = 8 * 60 * 1_000L
    }
}
