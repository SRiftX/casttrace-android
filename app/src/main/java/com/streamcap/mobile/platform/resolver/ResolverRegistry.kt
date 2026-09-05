package com.streamcap.mobile.platform.resolver

class ResolverRegistry(
    private val resolvers: List<PlatformResolver>,
) {
    fun resolverFor(url: String): PlatformResolver = resolvers.firstOrNull { it.supports(url) }
        ?: throw UnsupportedPlatformException(url)

    fun resolverForPlatform(platform: String): PlatformResolver = resolvers.firstOrNull {
        platform.startsWith(it.platformId, ignoreCase = true) || it.matchesDisplayName(platform)
    } ?: throw UnsupportedPlatformException(platform)

    private fun PlatformResolver.matchesDisplayName(displayName: String): Boolean = when (platformId) {
        "huya" -> displayName.contains("虎牙")
        "douyin" -> displayName.contains("抖音")
        "bilibili" -> displayName.contains("B站", true) || displayName.contains("哔哩")
        "douyu" -> displayName.contains("斗鱼")
        "kuaishou" -> displayName.contains("快手")
        "direct" -> displayName.startsWith("直链")
        else -> false
    }
}
