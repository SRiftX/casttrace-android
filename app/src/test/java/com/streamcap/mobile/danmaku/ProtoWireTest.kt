package com.streamcap.mobile.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ProtoWireTest {
    @Test fun encodesAndReadsLengthDelimitedAndVarintFields() {
        val bytes = ProtoWire.encodeVarint(1, 42) + ProtoWire.encodeBytes(2, "聊天".toByteArray())
        assertEquals(42L, ProtoWire.firstLong(bytes, 1))
        assertArrayEquals("聊天".toByteArray(), ProtoWire.firstBytes(bytes, 2))
    }

    @Test fun skipsUnknownWireFieldsWithoutLosingFollowingMessages() {
        val bytes = ProtoWire.encodeBytes(99, ByteArray(128)) + ProtoWire.encodeBytes(3, "message".toByteArray())
        assertEquals(listOf(99, 3), ProtoWire.fields(bytes).map { it.number })
        assertEquals("message", ProtoWire.firstString(bytes, 3))
    }

    @Test fun buildsDouyinPushFrameFieldLayout() {
        val chat = ProtoWire.encodeBytes(1, ProtoWire.encodeVarint(2, 7)) +
            ProtoWire.encodeBytes(2, ProtoWire.encodeVarint(1, 9)) +
            ProtoWire.encodeString(3, "hello")
        val message = ProtoWire.encodeString(1, "WebcastChatMessage") +
            ProtoWire.encodeBytes(2, chat) + ProtoWire.encodeVarint(3, 11)
        val response = ProtoWire.encodeBytes(1, message) +
            ProtoWire.encodeString(5, "cursor") + ProtoWire.encodeVarint(9, 1)
        val frame = ProtoWire.encodeVarint(2, 42) + ProtoWire.encodeString(7, "msg") +
            ProtoWire.encodeBytes(8, response)
        assertEquals(42L, ProtoWire.firstLong(frame, 2))
        assertEquals("msg", ProtoWire.firstString(frame, 7))
        assertArrayEquals(response, ProtoWire.firstBytes(frame, 8))
    }
}
