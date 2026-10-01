package com.streamcap.mobile.storage

import java.io.InputStream
import java.io.OutputStream

/** Stream only complete packets from the length observed at open time. No source mutation. */
internal object FrozenMediaCopy {
    fun copy(input: InputStream, output: OutputStream, length: Long, flv: Boolean, check: () -> Unit = {}) {
        var remaining = length
        fun read(size: Int, allowPartial: Boolean = false): ByteArray? {
            if (size.toLong() > remaining) return null
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                check()
                val count = input.read(bytes, offset, size - offset)
                if (count < 0) return if (allowPartial && offset > 0) bytes.copyOf(offset) else null
                offset += count
                remaining -= count
            }
            return bytes
        }
        if (flv) {
            val header = read(9) ?: error("FLV头不完整")
            require(header[0] == 70.toByte() && header[1] == 76.toByte() && header[2] == 86.toByte()) { "无效FLV头" }
            val offset = (5..8).fold(0L) { n, i -> (n shl 8) or (header[i].toLong() and 255) }
            require(offset in 9..1_048_576) { "无效FLV头长度" }
            val rest = read((offset - 9 + 4).toInt()) ?: error("FLV头不完整")
            output.write(header); output.write(rest)
            while (true) {
                val tag = read(11) ?: break
                val size = (1..3).fold(0) { n, i -> (n shl 8) or (tag[i].toInt() and 255) }
                val payload = read(size + 4) ?: break
                output.write(tag); output.write(payload)
            }
        } else {
            while (remaining >= 188) {
                val size = minOf(188L * 256, remaining / 188 * 188).toInt()
                val packets = read(size, allowPartial = true) ?: break
                output.write(packets, 0, packets.size / 188 * 188)
                if (packets.size < size) break
            }
        }
    }
}
