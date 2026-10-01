package com.streamcap.mobile.recording.flv

import com.streamcap.mobile.domain.model.StreamSource
import java.net.URI

internal object FlvSourceSelector {
    fun select(sources: List<StreamSource>, previous: StreamSource, receivedMedia: Boolean): StreamSource? {
        if (sources.isEmpty()) return null
        fun route(source: StreamSource): String = runCatching {
            val uri = URI(source.url)
            "${uri.host}:${uri.port}${uri.path}"
        }.getOrDefault(source.url.substringBefore('?'))
        val routes = sources.distinctBy(::route)
        val current = routes.indexOfFirst { route(it) == route(previous) }
        // Different CDN encoders can replay content with different packet hashes.
        // Renew the working route before switching encoders.
        return if (receivedMedia && current >= 0) routes[current]
        else routes[(current + 1) % routes.size]
    }
}
