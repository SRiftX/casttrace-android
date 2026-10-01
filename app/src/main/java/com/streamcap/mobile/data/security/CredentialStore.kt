package com.streamcap.mobile.data.security

import android.content.Context
import android.util.Base64
import com.streamcap.mobile.domain.model.PlatformCredential
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores platform cookies encrypted with an Android Keystore key. */
class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("platform_credentials", Context.MODE_PRIVATE)

    fun get(platform: String): PlatformCredential? {
        val encoded = preferences.getString(key(platform), null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            PlatformCredential(platform, String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), StandardCharsets.UTF_8))
        }.getOrNull()
    }

    fun set(platform: String, cookie: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val ciphertext = cipher.doFinal(cookie.toByteArray(StandardCharsets.UTF_8))
        preferences.edit().putString(
            key(platform),
            Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP),
        ).apply()
    }

    fun clear(platform: String) {
        preferences.edit().remove(key(platform)).apply()
    }

    private fun key(platform: String): String = "cookie_${canonical(platform)}"

    private fun canonical(platform: String): String = when {
        platform.contains("抖音", true) || platform.contains("douyin", true) -> "douyin"
        platform.contains("B站", true) || platform.contains("哔哩", true) || platform.contains("bilibili", true) -> "bilibili"
        platform.contains("快手", true) || platform.contains("kuaishou", true) -> "kuaishou"
        else -> platform.lowercase()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", KEYSTORE).apply {
            init(256)
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "CastTracePlatformCredentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
