package com.streamcap.mobile.storage

import java.io.File

object PreviewCache {
    private var owners = 0
    @Synchronized fun acquire() { owners++ }
    @Synchronized fun release() { owners = (owners - 1).coerceAtLeast(0) }
    @Synchronized fun clearUnused(cacheDirectory: File): Long {
        check(owners == 0) { "播放器仍在使用预览，请退出播放器后重试" }
        val files = previewFiles(cacheDirectory)
        var freed = 0L
        files.forEach { val bytes = it.length(); if (it.delete()) freed += bytes }
        return freed
    }
    fun previewFiles(cacheDirectory: File): List<File> = requireNotNull(cacheDirectory.listFiles()) { "无法读取预览缓存目录" }.filter {
        it.isFile && ((it.name.startsWith("recording-preview-") && it.extension in setOf("part", "mp4")) ||
            (it.name.startsWith(".recording-preview-") && it.name.endsWith(".writing.mp4")))
    }

    /** Called once at process startup, before any player can own these files. */
    fun cleanAbandoned(cacheDirectory: File) {
        cacheDirectory.listFiles()?.filter {
            it.isFile && ((it.name.startsWith("recording-preview-") && it.extension in setOf("part", "mp4")) ||
                (it.name.startsWith(".recording-preview-") && it.name.endsWith(".writing.mp4")))
        }?.forEach { it.delete() }
    }
}
