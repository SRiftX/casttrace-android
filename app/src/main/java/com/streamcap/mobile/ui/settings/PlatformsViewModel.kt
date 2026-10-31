package com.streamcap.mobile.ui.settings

import androidx.lifecycle.ViewModel
import com.streamcap.mobile.danmaku.DanmakuCapability
import com.streamcap.mobile.danmaku.RealDanmakuClientFactory
import com.streamcap.mobile.data.security.CredentialStore
import com.streamcap.mobile.platform.resolver.ResolverRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PlatformsViewModel @Inject constructor(
    private val resolverRegistry: ResolverRegistry,
    private val danmakuClients: RealDanmakuClientFactory,
    private val credentialStore: CredentialStore,
) : ViewModel() {
    val resolverIds: Set<String> = resolverRegistry.platformIds

    fun supportsCredentials(platform: String): Boolean =
        runCatching { resolverRegistry.resolverForPlatform(platform).supportsCredentials }.getOrDefault(false)

    fun danmakuCapability(platform: String): DanmakuCapability = danmakuClients.capability(platform)
    fun credentialCookie(platform: String): String? = credentialStore.get(platform)?.cookie
    fun hasCredential(platform: String): Boolean = credentialStore.get(platform) != null

    fun saveCredential(platform: String, cookie: String) {
        if (cookie.isBlank()) credentialStore.clear(platform) else credentialStore.set(platform, cookie.trim())
    }
}
