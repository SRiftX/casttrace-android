package com.streamcap.mobile.storage

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class FrozenMediaCopyTest {
    @Test fun unknownPublishedSizePreservesLastCompleteTsPackets() {
        val bytes = ByteArray(188 * 300 + 7) { it.toByte() }
        val output = ByteArrayOutputStream()
        FrozenMediaCopy.copy(bytes.inputStream(), output, Long.MAX_VALUE, false)
        assertArrayEquals(bytes.copyOf(188 * 300), output.toByteArray())
    }
    @Test fun tsFreezesLengthAndDropsIncompletePacket() {
        val bytes = ByteArray(188 * 4) { it.toByte() }
        val output = ByteArrayOutputStream()
        FrozenMediaCopy.copy(bytes.inputStream(), output, 188 * 2L + 17, false)
        assertArrayEquals(bytes.copyOf(188 * 2), output.toByteArray())
    }

    @Test fun flvDropsIncompleteTagWithoutCopyingGrowingTail() {
        val header = byteArrayOf(70, 76, 86, 1, 5, 0, 0, 0, 9, 0, 0, 0, 0)
        val tag = byteArrayOf(9, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 23, 1, 0, 0, 0, 13)
        val bytes = header + tag + tag
        val output = ByteArrayOutputStream()
        FrozenMediaCopy.copy(bytes.inputStream(), output, (bytes.size - 1).toLong(), true)
        assertArrayEquals(header + tag, output.toByteArray())
    }
}
