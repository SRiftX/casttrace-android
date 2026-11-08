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
    fun fakeDouyinCookieRoundTripsThroughKeystoreAsCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CredentialStore(context)
        val fakeCookie = "qa_session_fake=alpha; qa_token_fake=beta"
        val copiedHeader = "Cookie: qa_session_fake=alpha\r\nqa_token_fake=beta"
        try {
            store.set("douyin", copiedHeader)

            assertEquals(fakeCookie, store.get("抖音")?.cookie)
            val persisted = context.getSharedPreferences("platform_credentials", 0)
                .getString("cookie_douyin", "").orEmpty()
            assertFalse(persisted.contains("qa_session_fake"))
            assertFalse(persisted.contains("qa_token_fake"))
        } finally {
            store.clear("douyin")
        }
    }
}
