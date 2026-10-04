package com.streamcap.mobile.recording

import com.streamcap.mobile.domain.model.MAX_SEGMENT_SECONDS
import com.streamcap.mobile.domain.model.MAX_UNPUBLISHED_SEGMENT_SECONDS
import com.streamcap.mobile.domain.model.MIN_SEGMENT_SECONDS

object RecordingSegmentPolicy {
    fun effectiveSeconds(enabled: Boolean, requestedSeconds: Int): Int =
        if (enabled) requestedSeconds.coerceIn(MIN_SEGMENT_SECONDS, MAX_SEGMENT_SECONDS)
            .coerceAtMost(MAX_UNPUBLISHED_SEGMENT_SECONDS)
        else MAX_UNPUBLISHED_SEGMENT_SECONDS
}
