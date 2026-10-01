package com.streamcap.mobile.storage

import java.io.File
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
}
