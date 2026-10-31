package com.streamcap.mobile.recording.flv

/** Preserve a track's existing clock when a refreshed CDN starts a different timestamp epoch. */
internal fun flvTimelineOffset(raw: Long, offset: Long, last: Long, firstAfterReconnect: Boolean,
                               elapsedSincePreviousPacketMillis: Long? = null): Long {
    if (last == Long.MIN_VALUE) return offset
    val normalized = raw + offset
    val backwardsReset = last - normalized >= 2_000L
    val forwardStep = normalized - last
    val forwardsReset = (firstAfterReconnect && forwardStep >= 10_000L) ||
        (elapsedSincePreviousPacketMillis != null &&
            forwardStep > maxOf(10_000L, elapsedSincePreviousPacketMillis.coerceAtLeast(0) + 5_000L))
    return if (backwardsReset || forwardsReset) last + 1L - raw else offset
}
