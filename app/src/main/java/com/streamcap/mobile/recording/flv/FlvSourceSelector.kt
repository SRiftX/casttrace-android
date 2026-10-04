package com.streamcap.mobile.recording.flv

import com.streamcap.mobile.domain.model.StreamSource
import java.net.URI

internal object FlvSourceSelector {
    fun select(sources: List<StreamSource>, previous: StreamSource, receivedMedia: Boolean): StreamSource? {
        if (sources.isEmpty()) return null
        fun host(source: StreamSource): String = runCatching {
            URI(source.url).host ?: source.url.substringBefore('?')
        }.getOrDefault(source.url.substringBefore('?'))
        // A URL that already delivered media normally ended because the live
        // server rotated its chunk/connection. Keep that CDN host and refresh
        // its signed URL; moving to AL/TX can select a 302/close route on
        // devices such as Mate 20. Only an empty response should switch CDN.
        val previousUrl = previous.url
        val alternateHost = sources.firstOrNull { host(it) != host(previous) && it.url != previousUrl }
        val refreshedSameHost = sources.firstOrNull { host(it) == host(previous) && it.url != previousUrl }
        return if (receivedMedia) refreshedSameHost ?: previous.takeIf { sources.any { it.url == previousUrl } }
        else alternateHost ?: refreshedSameHost
    }
}
