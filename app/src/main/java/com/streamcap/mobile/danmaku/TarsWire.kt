package com.streamcap.mobile.danmaku

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Bounded JCE/TARS fields, including nested unknown values used by Huya pushes. */
internal object TarsWire {
    fun decode(bytes: ByteArray): Map<Int, Any> {
        require(bytes.size <= 4 * 1024 * 1024)
        return Reader(ByteBuffer.wrap(bytes)).fields(0)
    }

    fun encode(fields: Map<Int, Any>): ByteArray = ByteArrayOutputStream().apply {
        fields.toSortedMap().forEach { (tag, value) -> value(tag, value) }
    }.toByteArray()

    private fun ByteArrayOutputStream.head(tag: Int, type: Int) {
        require(tag in 0..255)
        if (tag < 15) write((tag shl 4) or type) else { write(0xf0 or type); write(tag) }
    }

    private fun ByteArrayOutputStream.number(tag: Int, n: Long) {
        val type = when (n) { 0L -> 12; in -128..127 -> 0; in -32768..32767 -> 1; in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> 2; else -> 3 }
        head(tag, type)
        val count = when (type) { 0 -> 1; 1 -> 2; 2 -> 4; 3 -> 8; else -> 0 }
        for (i in count - 1 downTo 0) write((n ushr (8 * i)).toInt() and 255)
    }

    private fun ByteArrayOutputStream.value(tag: Int, v: Any) {
        when (v) {
            is Number -> number(tag, v.toLong())
            is Boolean -> number(tag, if (v) 1 else 0)
            is String -> {
                val bytes = v.toByteArray(Charsets.UTF_8)
                if (bytes.size <= 255) { head(tag, 6); write(bytes.size) }
                else { head(tag, 7); write(ByteBuffer.allocate(4).putInt(bytes.size).array()) }
                write(bytes)
            }
            is ByteArray -> { head(tag, 13); head(0, 0); number(0, v.size.toLong()); write(v) }
            is List<*> -> { head(tag, 9); number(0, v.size.toLong()); v.forEach { value(0, requireNotNull(it)) } }
            is Map<*, *> -> {
                head(tag, 10)
                v.entries.sortedBy { it.key as Int }.forEach { value(it.key as Int, requireNotNull(it.value)) }
                head(0, 11)
            }
            is Pairs -> {
                head(tag, 8); number(0, v.entries.size.toLong())
                v.entries.forEach { (key, value) -> value(0, key); value(1, value) }
            }
            else -> error("Unsupported TARS value")
        }
    }

    data class Pairs(val entries: List<Pair<Any, Any>>)

    private class Reader(private val input: ByteBuffer) {
        private var remainingFields = 16_384
        fun fields(depth: Int): Map<Int, Any> = buildMap {
            require(depth <= 32) { "TARS nesting limit" }
            while (input.hasRemaining()) {
                val (tag, type) = head()
                if (type == 11) return@buildMap
                put(tag, value(type, depth))
            }
        }

        private fun head(): Pair<Int, Int> {
            check(--remainingFields >= 0) { "TARS field limit" }
            val b = input.get().toInt() and 255
            return (if (b ushr 4 == 15) input.get().toInt() and 255 else b ushr 4) to (b and 15)
        }

        private fun length(): Int {
            val n = (value(head().second, 0) as Number).toLong()
            require(n in 0..1_048_576 && n <= input.remaining()) { "Invalid TARS length" }
            return n.toInt()
        }

        private fun bytes(n: Int): ByteArray {
            require(n >= 0 && n <= input.remaining())
            return ByteArray(n).also(input::get)
        }

        private fun value(type: Int, depth: Int): Any {
            require(depth <= 32)
            return when (type) {
                0 -> input.get().toLong()
                1 -> input.short.toLong()
                2 -> input.int.toLong()
                3 -> input.long
                4 -> input.float
                5 -> input.double
                6 -> bytes(input.get().toInt() and 255).toString(Charsets.UTF_8)
                7 -> bytes(input.int).toString(Charsets.UTF_8)
                8 -> Pairs(List(length().also { require(it <= 4096) }) {
                    value(head().second, depth + 1) to value(head().second, depth + 1)
                })
                9 -> List(length().also { require(it <= 4096) }) { value(head().second, depth + 1) }
                10 -> fields(depth + 1)
                12 -> 0L
                13 -> { require(head().second == 0); bytes(length()) }
                else -> error("Unknown TARS type $type")
            }
        }
    }
}
