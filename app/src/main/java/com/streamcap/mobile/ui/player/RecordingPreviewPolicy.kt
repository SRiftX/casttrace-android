package com.streamcap.mobile.ui.player

/** Shared by every platform that records through the player. */
internal object RecordingPreviewPolicy {
    private const val MAX_SNAPSHOT_BYTES = 64L * 1024 * 1024

    enum class TailAction { SNAPSHOT, DIRECT, DEFER }

    fun activeTailAction(bytes: Long, hasPublishedPart: Boolean): TailAction = when {
        bytes <= MAX_SNAPSHOT_BYTES -> TailAction.SNAPSHOT
        hasPublishedPart -> TailAction.DEFER
        else -> TailAction.DIRECT
    }
}
