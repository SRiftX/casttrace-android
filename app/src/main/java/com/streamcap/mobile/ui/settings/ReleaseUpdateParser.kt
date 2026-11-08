package com.streamcap.mobile.ui.settings

import java.net.URI
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

    fun releaseTagFromPageUrl(url: String): String? = runCatching {
        val page = URI(url)
        if (page.scheme != "https" || !page.host.equals("github.com", ignoreCase = true)) return null
        val path = page.path.split('/').filter(String::isNotEmpty)
        if (path.size != 5 || !path[0].equals("SRiftX", ignoreCase = true) ||
            !path[1].equals("casttrace-android", ignoreCase = true) || path[2] != "releases" ||
            path[3] != "tag" || !path[4].matches(Regex("v\\d+(?:\\.\\d+)*"))) return null
        path[4]
    }.getOrNull()

    fun parseLatestReleasePage(pageUrl: String, checksums: String, currentVersion: String): ReleaseUpdate? {
        val tag = releaseTagFromPageUrl(pageUrl) ?: return null
        val version = tag.removePrefix("v")
        if (!isNewer(version, currentVersion)) return null
        val assetName = "casttrace-$version-release.apk"
        val digestPattern = Regex("^([a-fA-F0-9]{64})\\s+\\*?${Regex.escape(assetName)}\\s*$")
        val digest = checksums.lineSequence().firstNotNullOfOrNull { line ->
            digestPattern.matchEntire(line)?.groupValues?.get(1)?.lowercase()
        } ?: return null
        return ReleaseUpdate(
            versionName = version,
            apkUrl = "https://github.com/SRiftX/casttrace-android/releases/download/$tag/$assetName",
            sha256 = digest,
        )
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
