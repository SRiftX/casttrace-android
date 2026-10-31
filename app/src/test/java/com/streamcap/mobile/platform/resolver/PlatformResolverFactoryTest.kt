package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.platform.PlatformCatalog
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlatformResolverFactoryTest {
    @Test
    fun factoryCreatesExactlyOneResolverForEveryNonDiscontinuedPlatform() {
        val resolvers = PlatformResolverFactory.create(OkHttpClient())
        val expectedIds = PlatformCatalog.resolverEntries
            .map { it.resolverId }
            .toSet()

        assertEquals(expectedIds, resolvers.map { it.platformId }.toSet())
        assertEquals(resolvers.size, resolvers.map { it.platformId }.toSet().size)
    }

    @Test
    fun platformNamesAndAliasesResolveThroughCatalogMetadata() {
        val resolvers = PlatformResolverFactory.create(OkHttpClient())
        val registry = ResolverRegistry(resolvers)

        PlatformCatalog.resolverEntries.forEach { entry ->
                val resolver = registry.resolverForPlatform(entry.resolverId)
                assertSame(resolver, registry.resolverForPlatform(entry.key))
                assertSame(resolver, registry.resolverForPlatform(entry.name))
                entry.aliases.forEach { alias ->
                    assertSame(resolver, registry.resolverForPlatform(alias))
                }
            }
    }

    @Test
    fun everyCatalogDomainSelectsItsConfiguredResolver() {
        val registry = ResolverRegistry(PlatformResolverFactory.create(OkHttpClient()))

        PlatformCatalog.resolverEntries.forEach { entry ->
            entry.domains.filterNot { it.startsWith(".") }.forEach { domain ->
                val resolver = registry.resolverFor("https://www.$domain/")
                assertEquals(entry.resolverId, resolver.platformId)
            }
        }
    }

    @Test
    fun platformNamesNormalizeToStableCredentialKeys() {
        assertEquals("douyin", PlatformCatalog.canonicalPlatform("抖音直播"))
        assertEquals("bilibili", PlatformCatalog.canonicalPlatform("B站"))
        assertEquals("direct", PlatformCatalog.canonicalPlatform("direct"))
        assertEquals("custom", PlatformCatalog.canonicalPlatform("custom"))
    }
}
