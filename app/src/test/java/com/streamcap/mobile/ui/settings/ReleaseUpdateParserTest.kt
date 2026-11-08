package com.streamcap.mobile.ui.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdateParserTest {
    @Test
    fun findsOnlyNewStableReleaseApkWithSha256() {
        val update = ReleaseUpdateParser.parseLatestRelease(release(), "0.2.19")

        assertEquals("0.2.20", update?.versionName)
        assertEquals("a".repeat(64), update?.sha256)
        assertTrue(update?.apkUrl.orEmpty().startsWith("https://github.com/SRiftX/casttrace-android/releases/download/"))
    }

    @Test
    fun comparesNumericVersionsAndIgnoresSameOrOlderRelease() {
        assertTrue(ReleaseUpdateParser.isNewer("0.2.20", "0.2.19"))
        assertTrue(ReleaseUpdateParser.isNewer("0.10.0", "0.9.99"))
        assertFalse(ReleaseUpdateParser.isNewer("0.2.19", "0.2.19"))
        assertFalse(ReleaseUpdateParser.isNewer("0.2.18", "0.2.19"))
    }

    @Test
    fun rejectsPrereleasesAndUntrustedAssetLinks() {
        assertNull(ReleaseUpdateParser.parseLatestRelease(release(prerelease = true), "0.2.19"))
        assertNull(ReleaseUpdateParser.parseLatestRelease(release(url = "https://evil.example/update.apk"), "0.2.19"))
        assertNull(ReleaseUpdateParser.parseLatestRelease(release(digest = ""), "0.2.19"))
    }

    private fun release(
        prerelease: Boolean = false,
        url: String = "https://github.com/SRiftX/casttrace-android/releases/download/v0.2.20/casttrace-0.2.20-release.apk",
        digest: String = "sha256:${"a".repeat(64)}",
    ) = JSONObject()
        .put("tag_name", "v0.2.20")
        .put("draft", false)
        .put("prerelease", prerelease)
        .put("assets", JSONArray().put(JSONObject()
            .put("name", "casttrace-0.2.20-release.apk")
            .put("browser_download_url", url)
            .put("digest", digest)))
        .toString()
}
