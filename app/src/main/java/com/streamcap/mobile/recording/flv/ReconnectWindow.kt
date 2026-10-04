package com.streamcap.mobile.recording.flv

/** Empty-start fast failure; established recordings have a resettable monotonic grace period. */
internal class ReconnectWindow(private val graceNanos: Long = 90_000_000_000L) {
    private var emptyAttempts = 0
    private var emptySince: Long? = null
    private var burstSince: Long? = null
    private var burstAttempts = 0
    fun shouldContinue(newMedia: Boolean, hasMedia: Boolean, nowNanos: Long): Boolean {
        // One frame followed by EOF does not make a connection healthy.
        if (burstSince?.let { nowNanos - it >= 120_000_000_000L } == true) {
            burstSince = null
            burstAttempts = 0
        }
        if (burstSince == null) burstSince = nowNanos
        if (newMedia) {
            emptyAttempts = 0
            emptySince = null
            burstAttempts = 0
            return true
        }
        burstAttempts++
        if (burstAttempts >= 12) return false
        emptyAttempts++
        val since = emptySince ?: nowNanos.also { emptySince = it }
        return emptyAttempts < 3 || (hasMedia && nowNanos - since < graceNanos)
    }
}
