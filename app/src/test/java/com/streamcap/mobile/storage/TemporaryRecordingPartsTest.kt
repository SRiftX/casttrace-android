package com.streamcap.mobile.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TemporaryRecordingPartsTest {
    @Test fun indicesRemainStableAfterEarlierPartsWereRemoved() {
        val first = File("record.flv.part")
        assertEquals(1, TemporaryRecordingParts.index(first, first))
        assertEquals(3, TemporaryRecordingParts.index(first, File("record_003.flv.part")))
        assertEquals(2, TemporaryRecordingParts.index(first, File("record.flv_002.part")))
        assertNull(TemporaryRecordingParts.index(first, File("other_003.flv.part")))
        val files = listOf(first, File("record_002.flv.part"), File("record_003.flv.part"))
        assertEquals(listOf(files.last()), files.filter { TemporaryRecordingParts.index(first, it)!! > 2 })
    }

    @Test fun familyReturnsMatchingTemporaryPartsInNumericOrder() {
        val directory = Files.createTempDirectory("recording-parts").toFile()
        try {
            val first = File(directory, "record.flv.part")
            val second = File(directory, "record.flv_002.part")
            val third = File(directory, "record_003.flv.part")
            val unrelated = File(directory, "other.flv.part")
            listOf(first, second, third, unrelated).forEach { it.writeText("media") }

            assertEquals(listOf(first, second, third), TemporaryRecordingParts.family(first))
        } finally {
            directory.deleteRecursively()
        }
    }
}
