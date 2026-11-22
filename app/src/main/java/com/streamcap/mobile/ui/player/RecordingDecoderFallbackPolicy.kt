package com.streamcap.mobile.ui.player

import androidx.media3.common.PlaybackException

internal object RecordingDecoderFallbackPolicy {
    fun shouldTrySoftware(errorCode: Int, live: Boolean, alreadyTried: Boolean): Boolean =
        !live && !alreadyTried && errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
}
