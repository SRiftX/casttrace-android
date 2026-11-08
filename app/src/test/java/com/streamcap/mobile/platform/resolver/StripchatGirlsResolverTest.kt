package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.platform.PlatformCatalog
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StripchatGirlsResolverTest {
    @Test
    fun catalogRoutesStripchatDomainsAndAliasesToOneResolver() {
        val resolver = ResolverRegistry(PlatformResolverFactory.create(OkHttpClient()))
        assertEquals("stripchatgirls", resolver.resolverFor("https://stripchat.com/model/room").platformId)
        assertEquals("stripchatgirls", resolver.resolverFor("https://www.stripchatgirls.com/model/room").platformId)
        assertEquals("stripchatgirls", PlatformCatalog.canonicalPlatform("Stripchat Girls"))
        assertTrue(resolver.resolverForPlatform("Stripchat").supportsCredentials)
        assertThrows(UnsupportedPlatformException::class.java) {
            resolver.resolverFor("https://notstripchat.com/model/room")
        }
    }

    @Test
    fun genericPageResolverFindsPublicHlsMedia() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            "<title>Example room</title><script>const stream='https://cdn.example/live/master.m3u8?token=abc';</script>",
        ))
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        try {
            val resolver = StripchatGirlsResolver(
                OkHttpClient.Builder()
                    .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                    .build(),
            )
            val url = server.url("/model").newBuilder().host("localhost").build().toString()
            val room = LiveRoom(
                id = 1,
                originalUrl = url,
                normalizedUrl = url,
                platform = "stripchatgirls",
                anchorName = "room",
                roomTitle = null,
                enabled = true,
                autoRecord = false,
                quality = "原画",
                mediaType = "视频",
                outputFormat = "MP4",
                flvDirectDownload = false,
                storageDirectoryUri = null,
                segmentEnabled = true,
                segmentSeconds = 1800,
                scheduledMonitoringEnabled = false,
                notificationsEnabled = true,
                checkIntervalSeconds = 60,
                liveStatus = LiveStatus.LIVE,
                taskStatus = TaskStatus.WAITING,
                lastCheckAt = null,
                createdAt = 0,
                updatedAt = 0,
            )
            assertTrue(resolver.getStreams(room).single().url.startsWith("https://cdn.example/live/master.m3u8"))
        } finally {
            server.shutdown()
        }
    }
}
