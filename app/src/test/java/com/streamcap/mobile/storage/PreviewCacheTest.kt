package com.streamcap.mobile.storage

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PreviewCacheTest {
    @Test fun manualCleanupProtectsActivePlayerAndReportsOnlyDeletedBytes() {
        val directory = Files.createTempDirectory("preview-owned-test").toFile()
        try {
            val preview = directory.resolve("recording-preview-test.mp4").apply { writeText("12345") }
            val recovery = directory.resolve("recording.flv.part").apply { writeText("keep") }
            PreviewCache.acquire()
            try {
                assertTrue(runCatching { PreviewCache.clearUnused(directory) }.isFailure)
                assertTrue(preview.exists())
            } finally { PreviewCache.release() }
            assertEquals(5L, PreviewCache.clearUnused(directory))
            assertTrue(recovery.exists())
            assertEquals(0L, PreviewCache.clearUnused(directory))
        } finally { directory.deleteRecursively() }
    }
    @Test fun cleanupRemovesOnlyOwnedPreviewFiles() {
        val directory = Files.createTempDirectory("preview-cache-test").toFile()
        try {
            val copy = directory.resolve("recording-preview-123.part").apply { writeText("partial") }
            val movie = directory.resolve("recording-preview-123.mp4").apply { writeText("indexed") }
            val staging = directory.resolve(".recording-preview-123.writing.mp4").apply { writeText("interrupted mux") }
            val other = directory.resolve("user.mp4").apply { writeText("keep") }
            val folder = directory.resolve("recording-preview-folder.mp4").apply { mkdir() }
            PreviewCache.cleanAbandoned(directory)
            assertFalse(copy.exists())
            assertFalse(movie.exists())
            assertFalse(staging.exists())
            assertTrue(other.exists())
            assertTrue(folder.exists())
        } finally { directory.deleteRecursively() }
    }
}
