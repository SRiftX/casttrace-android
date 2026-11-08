package com.streamcap.mobile.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject

class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
) {
    suspend fun checkLatest(currentVersion: String): ReleaseUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CastTrace-Android")
            .build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "检查更新失败：HTTP ${response.code}" }
            ReleaseUpdateParser.parseLatestRelease(requireNotNull(response.body).string(), currentVersion)
        }
    }

    suspend fun download(update: ReleaseUpdate): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val destination = File(directory, "casttrace-v${update.versionName}.apk")
        val temporary = File(directory, "${destination.name}.part")
        val request = Request.Builder().url(update.apkUrl).header("User-Agent", "CastTrace-Android").build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "下载更新失败：HTTP ${response.code}" }
            val body = requireNotNull(response.body)
            check(body.contentLength() <= MAX_APK_BYTES) { "更新文件大小异常" }
            body.byteStream().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_APK_BYTES) { "更新文件大小异常" }
                        output.write(buffer, 0, count)
                    }
                    check(total > 0) { "更新文件为空" }
                }
            }
        }
        val digest = sha256(temporary)
        check(digest == update.sha256) { "更新文件校验失败，请重新下载" }
        destination.delete()
        check(temporary.renameTo(destination)) { "无法保存更新文件" }
        destination
    }

    suspend fun findCached(update: ReleaseUpdate): File? = withContext(Dispatchers.IO) {
        File(File(context.cacheDir, "updates"), "casttrace-v${update.versionName}.apk")
            .takeIf { it.isFile && it.length() in 1..MAX_APK_BYTES && sha256(it) == update.sha256 }
    }

    fun launchInstaller(apk: File): InstallPrompt {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return InstallPrompt.PERMISSION_REQUIRED
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return InstallPrompt.OPENED
    }

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/SRiftX/casttrace-android/releases/latest"
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

enum class InstallPrompt { OPENED, PERMISSION_REQUIRED }
