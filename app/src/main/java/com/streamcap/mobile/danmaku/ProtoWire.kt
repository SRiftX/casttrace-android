package com.streamcap.mobile.danmaku

import java.io.ByteArrayOutputStream

/** Small bounded protobuf wire reader used by the platform adapters. */
internal object ProtoWire {
    data class Field(val number: Int, val wireType: Int, val value: Any)

    fun fields(bytes: ByteArray, maxFields: Int = 4096): List<Field> {
        val reader = Reader(bytes)
        val result = ArrayList<Field>()
        while (result.size < maxFields) {
            val field = reader.next() ?: break
            result += field
        }
        return result
    }

    fun firstBytes(bytes: ByteArray, number: Int): ByteArray? =
        fields(bytes).firstOrNull { it.number == number && it.wireType == 2 }?.value as? ByteArray

    fun firstString(bytes: ByteArray, number: Int): String? =
        firstBytes(bytes, number)?.toString(Charsets.UTF_8)

    fun firstLong(bytes: ByteArray, number: Int): Long? =
        fields(bytes).firstOrNull { it.number == number && it.wireType == 0 }?.value as? Long

    fun repeatedBytes(bytes: ByteArray, number: Int): List<ByteArray> =
        fields(bytes).filter { it.number == number && it.wireType == 2 }.mapNotNull { it.value as? ByteArray }

    fun encodeBytes(number: Int, value: ByteArray): ByteArray = buildField(number, 2) {
        writeVarint(value.size.toLong())
        write(value)
    }

    fun encodeString(number: Int, value: String): ByteArray = encodeBytes(number, value.toByteArray(Charsets.UTF_8))

    fun encodeVarint(number: Int, value: Long): ByteArray = buildField(number, 0) { writeVarint(value) }

    private fun buildField(number: Int, wireType: Int, body: ByteArrayOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().apply {
            writeVarint((number shl 3 or wireType).toLong())
            body()
        }.toByteArray()

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var remaining = value
        while (remaining ushr 7 != 0L) {
            write(((remaining and 0x7fL) or 0x80L).toInt())
            remaining = remaining ushr 7
        }
        write(remaining.toInt())
    }

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0

        fun next(): Field? {
            if (offset >= bytes.size) return null
            val key = readVarint() ?: return null
            val number = (key ushr 3).toInt()
            val wireType = (key and 7).toInt()
            if (number <= 0) return null
            val value: Any = when (wireType) {
                0 -> readVarint() ?: return null
                1 -> readFixed(8) ?: return null
                2 -> {
                    val length = readVarint()?.toInt() ?: return null
                    if (length < 0 || length > MAX_BYTES || offset + length > bytes.size) return null
                    bytes.copyOfRange(offset, offset + length).also { offset += length }
                }
                5 -> readFixed(4) ?: return null
                else -> return null
            }
            return Field(number, wireType, value)
        }

        private fun readVarint(): Long? {
            var result = 0L
            var shift = 0
            repeat(10) {
                if (offset >= bytes.size) return null
                val b = bytes[offset++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return null
        }

        private fun readFixed(size: Int): ByteArray? {
            if (offset + size > bytes.size) return null
            return bytes.copyOfRange(offset, offset + size).also { offset += size }
        }
    }

    private const val MAX_BYTES = 4 * 1024 * 1024
}
