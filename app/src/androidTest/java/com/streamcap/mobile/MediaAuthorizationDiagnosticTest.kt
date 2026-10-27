package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.platform.resolver.DESKTOP_USER_AGENT
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.Request
import org.junit.Test

/** Receive-only diagnostics; logs status/reason without playback URLs or cookies. */
class MediaAuthorizationDiagnosticTest {
    @Test fun inspectCdnRejection() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StreamCapApplication
        val client = app.container.httpClient
        val url = requireNotNull(InstrumentationRegistry.getArguments().getString("authorizationUrl"))
        val id = app.container.repository.rooms.first().firstOrNull { it.normalizedUrl == url }?.id
            ?: app.container.repository.addRoom(url)
        val source = app.container.repository.streams(id).first()
        for (mode in listOf("current", "no-origin", "no-referer", "minimal", "http1")) {
            val headers = source.headers.toMutableMap()
            if (mode == "no-origin") headers.remove("Origin")
            if (mode == "no-referer") headers.remove("Referer")
            if (mode == "minimal") { headers.clear(); headers["User-Agent"] = DESKTOP_USER_AGENT }
            val http = if (mode == "http1") client.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build() else client
            val request = Request.Builder().url(source.url).apply { headers.forEach(::header) }.build()
            http.newCall(request).execute().use { response ->
                val body = response.peekBody(512).string()
                val reason = Regex("<(?:Code|Message)>([^<]{1,160})</(?:Code|Message)>").findAll(body)
                    .map { it.groupValues[1] }.joinToString(" ")
                android.util.Log.i("MediaAuth", "RESULT mode=$mode http=${response.code} protocol=${response.protocol} reason=$reason")
            }
        }
    }
}
