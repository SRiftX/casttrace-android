package com.streamcap.mobile.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "streamcap_settings")

data class UserSettings(
    val defaultQuality: String = "原画",
    val defaultFormat: String = "MP4",
    val storageDirectoryUri: String? = null,
    val checkIntervalSeconds: Int = 60,
    val allowMobileData: Boolean = true,
    val resumeAfterBoot: Boolean = false,
    val language: String = "简体中文",
    val themePalette: String = "classic",
    val includeTitleInFileName: Boolean = true,
    val fileNameTemplate: String = "{anchor_name}_{title}_{time}",
    val removeEmojiFromFileName: Boolean = false,
    val globalAutoRecordEnabled: Boolean = true,
    val backgroundMonitoringEnabled: Boolean = true,
    val powerSavingMonitoring: Boolean = false,
)

class AppPreferences(private val context: Context) {
    private object Keys {
        val defaultQuality = stringPreferencesKey("default_quality")
        val defaultFormat = stringPreferencesKey("default_format")
        val storageDirectoryUri = stringPreferencesKey("storage_directory_uri")
        val checkInterval = intPreferencesKey("check_interval_seconds")
        val allowMobileData = booleanPreferencesKey("allow_mobile_data")
        val resumeAfterBoot = booleanPreferencesKey("resume_after_boot")
        val language = stringPreferencesKey("language")
        val themePalette = stringPreferencesKey("theme_palette")
        val includeTitleInFileName = booleanPreferencesKey("include_title_in_file_name")
        val fileNameTemplate = stringPreferencesKey("file_name_template")
        val removeEmojiFromFileName = booleanPreferencesKey("remove_emoji_from_file_name")
        val recordingTemplates = stringPreferencesKey("recording_templates")
        val recordingViewMode = stringPreferencesKey("recording_view_mode")
        val globalAutoRecordEnabled = booleanPreferencesKey("global_auto_record_enabled")
        val backgroundMonitoringEnabled = booleanPreferencesKey("background_monitoring_enabled")
        val powerSavingMonitoring = booleanPreferencesKey("power_saving_monitoring")
        val danmakuFontSize = floatPreferencesKey("danmaku_font_size")
        val danmakuOpacity = floatPreferencesKey("danmaku_opacity")
        val danmakuSpeed = floatPreferencesKey("danmaku_speed")
        val danmakuColor = intPreferencesKey("danmaku_color")
        val danmakuLanes = intPreferencesKey("danmaku_lanes")
        val danmakuArea = floatPreferencesKey("danmaku_area")
        val danmakuAutoLanes = booleanPreferencesKey("danmaku_auto_lanes")
        val danmakuShowUserName = booleanPreferencesKey("danmaku_show_user_name")
        val danmakuShowEventTag = booleanPreferencesKey("danmaku_show_event_tag")
        val danmakuMaxCharacters = intPreferencesKey("danmaku_max_characters")
        val danmakuUserPosition = stringPreferencesKey("danmaku_user_position")
        val danmakuShowChat = booleanPreferencesKey("danmaku_show_chat")
        val danmakuShowGift = booleanPreferencesKey("danmaku_show_gift")
        val danmakuShowSuperChat = booleanPreferencesKey("danmaku_show_super_chat")
        val danmakuShowFollow = booleanPreferencesKey("danmaku_show_follow")
        val danmakuDensity = floatPreferencesKey("danmaku_density")
        val danmakuTimeOffsetMs = longPreferencesKey("danmaku_time_offset_ms")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { values ->
        UserSettings(
            defaultQuality = values[Keys.defaultQuality] ?: "原画",
            defaultFormat = values[Keys.defaultFormat] ?: "MP4",
            storageDirectoryUri = values[Keys.storageDirectoryUri],
            checkIntervalSeconds = values[Keys.checkInterval] ?: 60,
            allowMobileData = values[Keys.allowMobileData] ?: true,
            resumeAfterBoot = values[Keys.resumeAfterBoot] ?: false,
            language = values[Keys.language] ?: "简体中文",
            themePalette = values[Keys.themePalette] ?: "classic",
            includeTitleInFileName = values[Keys.includeTitleInFileName] ?: true,
            fileNameTemplate = values[Keys.fileNameTemplate] ?: "{anchor_name}_{title}_{time}",
            removeEmojiFromFileName = values[Keys.removeEmojiFromFileName] ?: false,
            globalAutoRecordEnabled = values[Keys.globalAutoRecordEnabled] ?: true,
            backgroundMonitoringEnabled = values[Keys.backgroundMonitoringEnabled] ?: true,
            powerSavingMonitoring = values[Keys.powerSavingMonitoring] ?: false,
        )
    }

    suspend fun restoreDefaults(format: String, quality: String, interval: Int) {
        context.dataStore.edit {
            it[Keys.defaultFormat] = format
            it[Keys.defaultQuality] = quality
            it[Keys.checkInterval] = interval
            it.remove(Keys.storageDirectoryUri)
        }
    }

    suspend fun setAllowMobileData(value: Boolean) {
        context.dataStore.edit { it[Keys.allowMobileData] = value }
    }

    suspend fun setCheckIntervalSeconds(value: Int) {
        context.dataStore.edit {
            it[Keys.checkInterval] = value.coerceIn(15, 3_600)
        }
    }

    suspend fun setDefaultQuality(value: String) {
        context.dataStore.edit { it[Keys.defaultQuality] = value }
    }

    suspend fun setDefaultFormat(value: String) {
        context.dataStore.edit { it[Keys.defaultFormat] = value }
    }

    suspend fun setStorageDirectoryUri(value: String?) {
        context.dataStore.edit {
            if (value == null) it.remove(Keys.storageDirectoryUri) else it[Keys.storageDirectoryUri] = value
        }
    }

    suspend fun setResumeAfterBoot(value: Boolean) {
        context.dataStore.edit { it[Keys.resumeAfterBoot] = value }
    }

    suspend fun setLanguage(value: String) {
        context.dataStore.edit { it[Keys.language] = value }
    }

    suspend fun setThemePalette(value: String) {
        context.dataStore.edit { it[Keys.themePalette] = value }
    }

    suspend fun setIncludeTitleInFileName(value: Boolean) {
        context.dataStore.edit { it[Keys.includeTitleInFileName] = value }
    }

    suspend fun setFileNameTemplate(value: String) {
        context.dataStore.edit { it[Keys.fileNameTemplate] = value.take(120) }
    }

    suspend fun setRemoveEmojiFromFileName(value: Boolean) {
        context.dataStore.edit { it[Keys.removeEmojiFromFileName] = value }
    }

    suspend fun setGlobalAutoRecordEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.globalAutoRecordEnabled] = value }
    }

    suspend fun setBackgroundMonitoringEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.backgroundMonitoringEnabled] = value }
    }

    suspend fun setPowerSavingMonitoring(value: Boolean) {
        context.dataStore.edit { it[Keys.powerSavingMonitoring] = value }
    }

    val templates: Flow<List<RecordingTemplate>> = context.dataStore.data.map { decodeTemplates(it[Keys.recordingTemplates].orEmpty()) }

    val danmakuAppearance: Flow<DanmakuAppearance> = context.dataStore.data.map { values ->
        DanmakuAppearance(
            values[Keys.danmakuFontSize] ?: 16f, values[Keys.danmakuOpacity] ?: .85f,
            values[Keys.danmakuSpeed] ?: 1f, values[Keys.danmakuColor] ?: 0xFFFFFF,
            values[Keys.danmakuLanes] ?: 12,
            values[Keys.danmakuArea] ?: .65f,
            values[Keys.danmakuAutoLanes] ?: true,
            values[Keys.danmakuShowUserName] ?: true,
            values[Keys.danmakuShowEventTag] ?: true,
            values[Keys.danmakuMaxCharacters] ?: 70,
            runCatching { DanmakuUserPosition.valueOf(values[Keys.danmakuUserPosition] ?: "PREFIX") }.getOrDefault(DanmakuUserPosition.PREFIX),
            values[Keys.danmakuShowChat] ?: true,
            values[Keys.danmakuShowGift] ?: true,
            values[Keys.danmakuShowSuperChat] ?: true,
            values[Keys.danmakuShowFollow] ?: true,
            values[Keys.danmakuDensity] ?: 1f,
            values[Keys.danmakuTimeOffsetMs] ?: 0L,
        ).normalized()
    }

    suspend fun setDanmakuAppearance(value: DanmakuAppearance) {
        val appearance = value.normalized()
        context.dataStore.edit {
            it[Keys.danmakuFontSize] = appearance.fontSizeSp
            it[Keys.danmakuOpacity] = appearance.opacity
            it[Keys.danmakuSpeed] = appearance.speed
            it[Keys.danmakuColor] = appearance.colorRgb
            it[Keys.danmakuLanes] = appearance.lanes
            it[Keys.danmakuArea] = appearance.area
            it[Keys.danmakuAutoLanes] = appearance.autoLanes
            it[Keys.danmakuShowUserName] = appearance.showUserName
            it[Keys.danmakuShowEventTag] = appearance.showEventTag
            it[Keys.danmakuMaxCharacters] = appearance.maxCharacters
            it[Keys.danmakuUserPosition] = appearance.userPosition.name
            it[Keys.danmakuShowChat] = appearance.showChat
            it[Keys.danmakuShowGift] = appearance.showGift
            it[Keys.danmakuShowSuperChat] = appearance.showSuperChat
            it[Keys.danmakuShowFollow] = appearance.showFollow
            it[Keys.danmakuDensity] = appearance.density
            it[Keys.danmakuTimeOffsetMs] = appearance.timeOffsetMs
        }
    }

    val recordingViewMode: Flow<String?> = context.dataStore.data.map { it[Keys.recordingViewMode] }

    suspend fun setRecordingViewMode(value: String) {
        context.dataStore.edit { it[Keys.recordingViewMode] = value }
    }

    suspend fun saveTemplate(template: RecordingTemplate) {
        context.dataStore.edit { values ->
            val existing = decodeTemplates(values[Keys.recordingTemplates].orEmpty()).filterNot { it.name == template.name }
            values[Keys.recordingTemplates] = encodeTemplates((existing + template).takeLast(12))
        }
    }

    suspend fun deleteTemplate(name: String) {
        context.dataStore.edit { values ->
            values[Keys.recordingTemplates] = encodeTemplates(decodeTemplates(values[Keys.recordingTemplates].orEmpty()).filterNot { it.name == name })
        }
    }
}
