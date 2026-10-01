package com.streamcap.mobile.danmaku

data class MediaSegment(
    val id: String,
    val clockEpochId: String,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val startPlayableMs: Long,
    val durationMs: Long,
)

class TimelineMapper(private val segments: List<MediaSegment>, private val deltaMs: Long = 0L) {
    fun map(event: DanmakuEvent): DanmakuEvent {
        val segment = segments.firstOrNull {
            it.clockEpochId == event.clockEpochId && event.receivedElapsedMs in it.startElapsedMs..it.endElapsedMs
        } ?: return event.copy(mediaTimeMs = null, timeQuality = "unmapped")
        val elapsed = event.receivedElapsedMs - segment.startElapsedMs
        val span = (segment.endElapsedMs - segment.startElapsedMs).coerceAtLeast(1)
        val position = segment.startPlayableMs + elapsed * segment.durationMs / span + deltaMs
        return event.copy(mediaTimeMs = position, timeQuality = "approximate")
    }
}
