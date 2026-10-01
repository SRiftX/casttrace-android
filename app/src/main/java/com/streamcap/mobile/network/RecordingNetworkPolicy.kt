package com.streamcap.mobile.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.streamcap.mobile.data.preferences.AppPreferences
import kotlinx.coroutines.flow.first
import java.io.IOException

class RecordingNetworkPolicy(context: Context, private val preferences: AppPreferences) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    suspend fun allowsRequests(): Boolean {
        val allowMobile = preferences.settings.first().allowMobileData
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return permits(allowMobile, capabilities != null,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
    }

    suspend fun requireAllowed() {
        if (!allowsRequests()) throw NetworkPolicyException()
    }

    companion object {
        fun permits(allowMobile: Boolean, connected: Boolean, unmetered: Boolean, cellular: Boolean): Boolean =
            allowMobile || (connected && unmetered && !cellular)
    }
}

class NetworkPolicyException : IOException("仅 Wi-Fi 模式：当前网络不可用或按流量计费，已暂停网络访问")
