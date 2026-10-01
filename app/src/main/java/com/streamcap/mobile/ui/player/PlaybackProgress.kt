package com.streamcap.mobile.ui.player

import android.content.Context

data class PlaybackProgress(val positionMs: Long = 0, val playWhenReady: Boolean = true)

data class PlaybackOptions(val speed: Float = 1f, val volume: Float = 1f, val muted: Boolean = false)

class PlaybackProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences("playback_progress", Context.MODE_PRIVATE)
    fun read(id: Long) = PlaybackProgress(preferences.getLong("$id:position", 0), preferences.getBoolean("$id:playing", true))
    fun write(id: Long, progress: PlaybackProgress) {
        preferences.edit().putLong("$id:position", progress.positionMs.coerceAtLeast(0))
            .putBoolean("$id:playing", progress.playWhenReady).apply()
    }
    fun readOptions() = PlaybackOptions(preferences.getFloat("speed", 1f).coerceIn(.25f, 4f),
        preferences.getFloat("volume", 1f).coerceIn(0f, 1f), preferences.getBoolean("muted", false))
    fun writeOptions(options: PlaybackOptions) {
        preferences.edit().putFloat("speed", options.speed).putFloat("volume", options.volume)
            .putBoolean("muted", options.muted).apply()
    }
    fun readDanmakuVisible() = preferences.getBoolean("danmakuVisible", true)
    fun writeDanmakuVisible(visible: Boolean) { preferences.edit().putBoolean("danmakuVisible", visible).apply() }
}
