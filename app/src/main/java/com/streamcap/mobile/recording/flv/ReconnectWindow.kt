package com.streamcap.mobile.recording.flv

/** Empty-start fast failure; established recordings have a resettable monotonic grace period. */
internal class ReconnectWindow(private val graceNanos: Long = 90_000_000_000L) {
    private var emptyAttempts = 0
    private var emptySince: Long? = null
    fun shouldContinue(newMedia: Boolean, hasMedia: Boolean, nowNanos: Long): Boolean {
        if (newMedia) {
            emptyAttempts = 0
            emptySince = null
            return true
        }
        emptyAttempts++
        val since = emptySince ?: nowNanos.also { emptySince = it }
        return emptyAttempts < 3 || (hasMedia && nowNanos - since < graceNanos)
    }
}
