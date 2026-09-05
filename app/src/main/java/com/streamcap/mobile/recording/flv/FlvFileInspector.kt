package com.streamcap.mobile.recording.flv

import java.io.File
import java.io.InputStream

object FlvFileInspector {
    fun hasMediaTags(file: File): Boolean = runCatching {
        file.inputStream().buffered().use(::hasMediaTags)
    }.getOrDefault(false)

    fun hasMediaTags(input: InputStream): Boolean {
        val header = input.readExactOrNull(9) ?: return false
        if (!header.copyOfRange(0, 3).contentEquals(FLV_SIGNATURE)) return false

        val dataOffset = ((header[5].toLong() and 0xff) shl 24) or
            ((header[6].toLong() and 0xff) shl 16) or
            ((header[7].toLong() and 0xff) shl 8) or (header[8].toLong() and 0xff)
        if (dataOffset < 9 || dataOffset > MAX_HEADER_BYTES) return false
        if (!input.skipExact(dataOffset - 9 + 4)) return false

        var scannedBytes = dataOffset + 4
        while (scannedBytes < MAX_SCAN_BYTES) {
            val tagHeader = input.readExactOrNull(11) ?: return false
            val type = tagHeader[0].toInt() and 0xff
            val dataSize = ((tagHeader[1].toInt() and 0xff) shl 16) or
                ((tagHeader[2].toInt() and 0xff) shl 8) or (tagHeader[3].toInt() and 0xff)
            if (!input.skipExact(dataSize.toLong() + 4)) return false
            scannedBytes += 11L + dataSize + 4
            if ((type == 8 || type == 9) && dataSize > 0) return true
        }
        return false
    }

    private fun InputStream.readExactOrNull(size: Int): ByteArray? {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(result, offset, size - offset)
            if (read < 0) return null
            offset += read
        }
        return result
    }

    private fun InputStream.skipExact(byteCount: Long): Boolean {
        var remaining = byteCount
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) return false
            remaining -= read
        }
        return true
    }

    private const val MAX_HEADER_BYTES = 1_024L
    private const val MAX_SCAN_BYTES = 1_048_576L
    private val FLV_SIGNATURE = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte())
}
