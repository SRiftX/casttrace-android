package com.streamcap.mobile.danmaku

import org.junit.Assert.*
import org.junit.Test

class TarsWireTest {
    @Test fun readsIndependentWireFixtureWithNestedSenderAndUnknownFields() {
        // Naked MessageNotice: sender struct, negative/zero fields, UTF-8 text.
        val fixture = byteArrayOf(0x0a, 0x00, 0x2a, 0x26, 3, 0x62, 0x6f, 0x62, 0x0b,
            0x1c, 0x20, -1, 0x36, 2, 0x68, 0x69, 0xf6.toByte(), 20, 1, 0x78)
        val parsed = TarsWire.decode(fixture)
        assertEquals("hi", parsed[3])
        assertEquals("bob", (parsed[0] as Map<*, *>)[2])
        assertEquals(-1L, parsed[2])
        assertEquals("x", parsed[20])
    }

    @Test fun preservesLongIdsAndByteBuffersAndLists() {
        val bytes = TarsWire.encode(mapOf(0 to 7, 1 to byteArrayOf(-1, 0, 1),
            5 to 7_599_999_999_999_999_999L, 16 to listOf("chat:123", "live:123")))
        val parsed = TarsWire.decode(bytes)
        assertArrayEquals(byteArrayOf(-1, 0, 1), parsed[1] as ByteArray)
        assertEquals(7_599_999_999_999_999_999L, parsed[5])
        assertEquals(listOf("chat:123", "live:123"), parsed[16])
    }

    @Test fun rejectsTruncatedAndExcessiveLengths() {
        val bad = listOf(byteArrayOf(0x0d, 0, 2, 0x7f, -1, -1, -1), byteArrayOf(0x06, 5, 1))
        bad.forEach { bytes -> assertTrue(runCatching { TarsWire.decode(bytes) }.isFailure) }
    }
}
