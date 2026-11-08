package com.streamcap.mobile.data.security

import org.junit.Assert.assertEquals
import org.junit.Test

class CookieNormalizationTest {
    @Test
    fun stripsCopiedHeaderPrefixAndFoldsLineBreaks() {
        assertEquals("sessionid=abc; sid=def", normalizeCookie("  Cookie: sessionid=abc\r\nsid=def  "))
    }

    @Test
    fun leavesCookiePairsAndCaseInsensitiveHeaderPrefixIntact() {
        assertEquals("sessionid=abc; sid=def", normalizeCookie("cOoKiE : sessionid=abc; sid=def"))
    }
}
