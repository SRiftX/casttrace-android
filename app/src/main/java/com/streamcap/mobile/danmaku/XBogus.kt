package com.streamcap.mobile.danmaku

import java.security.MessageDigest
import java.security.SecureRandom

internal object XBogus {
    private const val ALPHABET = "Dkdpgh4ZKsQB80/Mfvw36XI1R25+WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe"
    private val random = SecureRandom()

    fun generate(signatureInput: String, counter: Int = 1): String {
        val stub = md5Hex(signatureInput).toByteArray(Charsets.US_ASCII)
        val md5Last2 = md5(hexToBytes(stub)).let { byteArrayOf(it[14], it[15]) }
        val random1 = random.nextInt(256)
        val random2 = random.nextInt(256) * 255 / 256
        val payload = byteArrayOf(
            (counter and 0x3f).toByte(), 0, 1, 0x0e, 0x45, 0x3f,
            md5Last2[0], md5Last2[1], random2.toByte(), 0,
        )
        payload[9] = payload.take(9).fold(0) { acc, value -> acc xor (value.toInt() and 0xff) }.toByte()
        rc4(random2, payload)
        val bytes = byteArrayOf((0x40 or (random1 and 0x1f)).toByte(), random2.toByte()) + payload
        return encode(bytes)
    }

    private fun md5Hex(value: String): String = md5(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun md5(value: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(value)

    private fun hexToBytes(value: ByteArray): ByteArray = ByteArray(value.size / 2) { index ->
        ((Character.digit(value[index * 2].toInt().toChar(), 16) shl 4) or
            Character.digit(value[index * 2 + 1].toInt().toChar(), 16)).toByte()
    }

    private fun rc4(key: Int, data: ByteArray) {
        val state = IntArray(256) { it }
        var j = 0
        for (i in state.indices) {
            j = (j + state[i] + key) and 255
            val swap = state[i]; state[i] = state[j]; state[j] = swap
        }
        var i = 0
        j = 0
        data.indices.forEach { index ->
            i = (i + 1) and 255
            j = (j + state[i]) and 255
            val swap = state[i]; state[i] = state[j]; state[j] = swap
            data[index] = (data[index].toInt() xor state[(state[i] + state[j]) and 255]).toByte()
        }
    }

    private fun encode(data: ByteArray): String {
        val standard = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var index = 0
        while (index < data.size) {
            val a = data[index++].toInt() and 255
            val b = if (index < data.size) data[index++].toInt() and 255 else 0
            val c = if (index < data.size) data[index++].toInt() and 255 else 0
            out.append(ALPHABET[standard.indexOf(standard[(a ushr 2) and 63])])
            out.append(ALPHABET[standard.indexOf(standard[((a shl 4) or (b ushr 4)) and 63])])
            out.append(ALPHABET[standard.indexOf(standard[((b shl 2) or (c ushr 6)) and 63])])
            out.append(ALPHABET[standard.indexOf(standard[c and 63])])
        }
        return out.toString()
    }
}
