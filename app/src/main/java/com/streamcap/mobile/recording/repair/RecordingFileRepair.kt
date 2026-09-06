package com.streamcap.mobile.recording.repair

import java.io.File
import java.io.RandomAccessFile

/** Removes bytes that were left behind when a recorder was interrupted mid packet. */
object RecordingFileRepair {
    fun repairTransportStream(file: File): Long {
        if (!file.exists()) return 0L
        val length = file.length()
        if (!looksLikeTransportStream(file)) return length
        val repaired = length - (length % TS_PACKET_BYTES)
        if (repaired != length) RandomAccessFile(file, "rw").use { it.setLength(repaired) }
        return repaired
    }

    private fun looksLikeTransportStream(file: File): Boolean = runCatching {
        if (file.length() < TS_PACKET_BYTES) return@runCatching false
        RandomAccessFile(file, "r").use { input ->
            if (input.read() != 0x47) return@use false
            input.seek(TS_PACKET_BYTES)
            input.read() == 0x47
        }
    }.getOrDefault(false)

    fun repairFlv(file: File): Long {
        if (!file.exists() || file.length() < FLV_HEADER_BYTES) return 0L
        val length = file.length()
        val lastComplete = RandomAccessFile(file, "rw").use { input ->
            val header = ByteArray(9)
            input.readFully(header)
            if (!header.copyOfRange(0, 3).contentEquals(FLV_SIGNATURE)) return@use 0L
            val dataOffset = readInt(header, 5).toLong()
            if (dataOffset < 9 || dataOffset > 1024 || dataOffset + 4 > length) return@use 0L
            var position = dataOffset + 4
            val tagHeader = ByteArray(11)
            while (position + tagHeader.size + 4 <= length) {
                input.seek(position)
                input.readFully(tagHeader)
                val bodySize = readUInt24(tagHeader, 1)
                val next = position + tagHeader.size + bodySize + 4L
                if (next > length) break
                position = next
            }
            position
        }
        if (lastComplete > 0L && lastComplete < length) {
            RandomAccessFile(file, "rw").use { it.setLength(lastComplete) }
        }
        return lastComplete
    }

    private fun readUInt24(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 16) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            (bytes[offset + 2].toLong() and 0xff)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private const val TS_PACKET_BYTES = 188L
    private const val FLV_HEADER_BYTES = 9L
    private val FLV_SIGNATURE = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte())
}
