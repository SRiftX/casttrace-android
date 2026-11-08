package com.streamcap.mobile.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialStoreAndroidTest {
    @Test
    fun fakeCookiesForMultiplePlatformsRoundTripThroughKeystoreAsCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CredentialStore(context)
        val fakeCookies = mapOf(
            "douyin" to "qa_session_fake=alpha; qa_token_fake=beta",
            "bilibili" to "qa_bili_fake=gamma; qa_sess_fake=delta",
            "huya" to "qa_huya_fake=epsilon",
        )
        try {
            val persisted = context.getSharedPreferences("platform_credentials", 0)
            fakeCookies.forEach { (platform, cookie) ->
                store.set(platform, cookie)
                assertEquals(cookie, store.get(platform)?.cookie)
                assertFalse(persisted.getString("cookie_$platform", "").orEmpty().contains("qa_"))
            }
            store.set("抖音", "Cookie: qa_session_fake=alpha\r\nqa_token_fake=beta")
            assertEquals(fakeCookies.getValue("douyin"), store.get("douyin")?.cookie)
        } finally {
            fakeCookies.keys.forEach(store::clear)
        }
    }
}
