package com.streamcap.mobile.danmaku

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

/** WBI algorithm reference: rust-srec (MIT), attribution in assets/licenses/rust-srec-MIT.txt. */
internal object BilibiliWbi {
    private val mixin = intArrayOf(46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13)

    fun sign(params: Map<String, String>, imgKey: String, subKey: String, timestamp: Long): String {
        val keys = imgKey + subKey
        require(keys.length >= 59) { "B站签名参数无效" }
        val salt = mixin.map { keys[it] }.joinToString("")
        val url = "https://api.live.bilibili.com/".toHttpUrl().newBuilder()
        (params + ("wts" to timestamp.toString())).toSortedMap().forEach { (name, value) ->
            url.addQueryParameter(name, value.filter { it !in "!'()*" })
        }
        val query = requireNotNull(url.build().encodedQuery)
        val digest = MessageDigest.getInstance("MD5").digest((query + salt).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$query&w_rid=$digest"
    }
}
