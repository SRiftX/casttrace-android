package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.platform.PlatformCatalog

class ResolverRegistry(
    private val resolvers: List<PlatformResolver>,
) {
    val platformIds: Set<String> get() = resolvers.mapTo(linkedSetOf()) { it.platformId }
    fun supports(url: String): Boolean = resolvers.any { it.supports(url) }
    fun resolverFor(url: String): PlatformResolver = resolvers.firstOrNull { it.supports(url) }
        ?: throw UnsupportedPlatformException(url)

    fun resolverForPlatform(platform: String): PlatformResolver {
        val entry = PlatformCatalog.find(platform)
        val resolver = entry?.let { selected ->
            resolvers.firstOrNull { it.platformId == selected.resolverId }
        } ?: resolvers.firstOrNull { platform.startsWith(it.platformId, ignoreCase = true) }
        return resolver ?: throw UnsupportedPlatformException(platform)
    }
}
