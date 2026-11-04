package com.streamcap.mobile.recording.hls

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Ephemeral CDN session cookies, isolated to one recording or source probe. */
internal class HlsSessionCookies : Interceptor {
    private val cookies = mutableListOf<Cookie>()

    fun snapshot(): List<Cookie> = synchronized(cookies) {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        cookies.toList()
    }

    fun requestCookieHeader(url: HttpUrl, explicitCookieHeader: String? = null): String? =
        hlsCookieHeader(snapshot(), url, explicitCookieHeader)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cookieHeader = requestCookieHeader(request.url, request.header("Cookie"))
        val next = if (cookieHeader == null) request else request.newBuilder().header("Cookie", cookieHeader).build()
        val response = chain.proceed(next)
        val received = Cookie.parseAll(response.request.url, response.headers)
        val now = System.currentTimeMillis()
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

internal fun hlsCookieHeader(
    cookies: List<Cookie>,
    url: HttpUrl,
    explicitCookieHeader: String? = null,
): String? {
    val now = System.currentTimeMillis()
    val values = cookies.asSequence()
        .filter { it.expiresAt > now && it.matches(url) }
        .associate { it.name to it.value }
        .toMutableMap()
    if (values.isEmpty()) return null

    // Explicit account credentials take precedence over anonymous CDN cookies.
    explicitCookieHeader?.split(';')?.forEach {
        val pair = it.trim().split('=', limit = 2)
        if (pair.size == 2) values[pair[0]] = pair[1]
    }
    return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
