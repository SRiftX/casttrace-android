package com.streamcap.mobile.recording

import com.streamcap.mobile.recording.ffmpeg.independentTrackInputs
import org.junit.Assert.*
import org.junit.Test

class IndependentHlsTracksTest {
    @Test fun matchesAudioGroupAndResolvesRelativeTracks() {
        val master = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="other",URI="wrong.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",DEFAULT=YES,URI="audio/chunk.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=500000,AUDIO="sound"
video/chunk.m3u8
"""
        assertEquals("https://cdn.test/live/video/chunk.m3u8" to "https://cdn.test/live/audio/chunk.m3u8",
            independentTrackInputs("https://cdn.test/live/master.m3u8?token=master", master))
    }
    @Test fun muxedMediaPlaylistDoesNotCreateAnotherInput() {
        assertNull(independentTrackInputs("https://cdn.test/media.m3u8", "#EXTM3U\n#EXTINF:2,\nsegment.ts"))
    }
    @Test fun missingExternalAudioFailsInsteadOfSilentlyRecordingVideoOnly() {
        assertTrue(runCatching { independentTrackInputs("https://cdn.test/master.m3u8",
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"absent\"\nv.m3u8") }.isFailure)
    }
}
