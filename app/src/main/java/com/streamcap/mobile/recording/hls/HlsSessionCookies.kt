package com.streamcap.mobile.recording.hls

import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.Response

/** Ephemeral CDN session cookies, isolated to one recording or source probe. */
internal class HlsSessionCookies : Interceptor {
    private val cookies = mutableListOf<Cookie>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val now = System.currentTimeMillis()
        val values = synchronized(cookies) {
            cookies.removeAll { it.expiresAt <= now }
            cookies.filter { it.matches(request.url) }.associate { it.name to it.value }.toMutableMap()
        }
        // Explicit account credentials take precedence over anonymous CDN cookies.
        request.header("Cookie")?.split(';')?.forEach {
            val pair = it.trim().split('=', limit = 2)
            if (pair.size == 2) values[pair[0]] = pair[1]
        }
        val next = request.newBuilder().apply {
            if (values.isNotEmpty()) header("Cookie", values.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }.build()
        val response = chain.proceed(next)
        val received = Cookie.parseAll(response.request.url, response.headers)
        synchronized(cookies) {
            received.forEach { cookie ->
                cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > now) cookies += cookie
            }
            while (cookies.size > 256) cookies.removeAt(0)
        }
        return response
    }
}
