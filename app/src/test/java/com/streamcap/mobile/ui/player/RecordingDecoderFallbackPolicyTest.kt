package com.streamcap.mobile.ui.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDecoderFallbackPolicyTest {
    @Test fun retriesRecordingPlaybackWithSoftwareAfterDecoderFailureOnlyOnce() {
        assertTrue(RecordingDecoderFallbackPolicy.shouldTrySoftware(
            PlaybackException.ERROR_CODE_DECODING_FAILED, live = false, alreadyTried = false,
        ))
        assertFalse(RecordingDecoderFallbackPolicy.shouldTrySoftware(
            PlaybackException.ERROR_CODE_DECODING_FAILED, live = false, alreadyTried = true,
        ))
        assertFalse(RecordingDecoderFallbackPolicy.shouldTrySoftware(
            PlaybackException.ERROR_CODE_DECODING_FAILED, live = true, alreadyTried = false,
        ))
        assertFalse(RecordingDecoderFallbackPolicy.shouldTrySoftware(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, live = false, alreadyTried = false,
        ))
    }
}
