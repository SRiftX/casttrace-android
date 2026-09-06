package com.streamcap.mobile.recording

import com.streamcap.mobile.recording.repair.RecordingFileRepair
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingFileRepairTest {
    @Test
    fun trimsIncompleteTsPacketOnly() {
        val file = Files.createTempFile("streamcap-repair", ".ts").toFile()
        try {
            val bytes = ByteArray(188 * 2 + 17) { it.toByte() }
            bytes[0] = 0x47
            bytes[188] = 0x47
            file.writeBytes(bytes)
            assertEquals(188L * 2, RecordingFileRepair.repairTransportStream(file))
            assertEquals(188L * 2, file.length())
        } finally {
            file.delete()
        }
    }

    @Test
    fun trimsIncompleteFlvTagAndKeepsCompleteTags() {
        val file = Files.createTempFile("streamcap-repair", ".flv").toFile()
        val complete = flvTag(0, byteArrayOf(0x17, 0x00))
        val incomplete = flvTag(1_000, byteArrayOf(0x17, 0x01)).copyOf(8)
        val expected = flvHeader() + complete
        try {
            file.writeBytes(expected + incomplete)
            assertEquals(expected.size.toLong(), RecordingFileRepair.repairFlv(file))
            assertArrayEquals(expected, file.readBytes())
        } finally {
            file.delete()
        }
    }

    private fun flvHeader() = byteArrayOf(
        'F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, 5,
        0, 0, 0, 9,
        0, 0, 0, 0,
    )

    private fun flvTag(timestamp: Int, payload: ByteArray): ByteArray {
        val size = payload.size
        val header = byteArrayOf(
            9,
            (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(),
            (timestamp ushr 16).toByte(), (timestamp ushr 8).toByte(), timestamp.toByte(), (timestamp ushr 24).toByte(),
            0, 0, 0,
        )
        val tagSize = 11 + size
        return header + payload + byteArrayOf(0, (tagSize ushr 16).toByte(), (tagSize ushr 8).toByte(), tagSize.toByte())
    }
}
