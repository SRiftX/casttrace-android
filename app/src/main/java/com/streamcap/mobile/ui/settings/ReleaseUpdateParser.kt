package com.streamcap.mobile.ui.settings

import org.json.JSONObject

data class ReleaseUpdate(
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
)

data class UpdateUiState(
    val message: String = "正在检查更新…",
    val update: ReleaseUpdate? = null,
    val isBusy: Boolean = false,
    val downloaded: Boolean = false,
)

internal object ReleaseUpdateParser {
    fun parseLatestRelease(json: String, currentVersion: String): ReleaseUpdate? {
        val release = JSONObject(json)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name").removePrefix("v")
        if (!isNewer(tag, currentVersion)) return null
        val assets = release.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") != "casttrace-$tag-release.apk") continue
            val url = asset.optString("browser_download_url")
            val digest = asset.optString("digest").removePrefix("sha256:")
            if (!url.startsWith("https://github.com/SRiftX/casttrace-android/releases/download/") ||
                !digest.matches(Regex("[a-fA-F0-9]{64}"))) return null
            return ReleaseUpdate(tag, url, digest.lowercase())
        }
        return null
    }

    internal fun isNewer(candidate: String, current: String): Boolean {
        val candidateParts = candidate.removePrefix("v").split('.').map { it.toIntOrNull() ?: return false }
        val currentParts = current.removePrefix("v").split('.').map { it.toIntOrNull() ?: return false }
        if (candidateParts.isEmpty() || currentParts.isEmpty()) return false
        val size = maxOf(candidateParts.size, currentParts.size)
        for (index in 0 until size) {
            val left = candidateParts.getOrElse(index) { 0 }
            val right = currentParts.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}
