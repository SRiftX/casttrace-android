package com.streamcap.mobile.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "streamcap_settings")

data class UserSettings(
    val defaultQuality: String = "原画",
    val defaultFormat: String = "MP4",
    val storageDirectoryUri: String? = null,
    val checkIntervalSeconds: Int = 60,
    val allowMobileData: Boolean = true,
    val resumeAfterBoot: Boolean = false,
    val language: String = "简体中文",
    val includeTitleInFileName: Boolean = true,
    val fileNameTemplate: String = "{anchor_name}_{title}_{time}",
    val removeEmojiFromFileName: Boolean = false,
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
        val includeTitleInFileName = booleanPreferencesKey("include_title_in_file_name")
        val fileNameTemplate = stringPreferencesKey("file_name_template")
        val removeEmojiFromFileName = booleanPreferencesKey("remove_emoji_from_file_name")
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
            includeTitleInFileName = values[Keys.includeTitleInFileName] ?: true,
            fileNameTemplate = values[Keys.fileNameTemplate] ?: "{anchor_name}_{title}_{time}",
            removeEmojiFromFileName = values[Keys.removeEmojiFromFileName] ?: false,
        )
    }

    suspend fun setAllowMobileData(value: Boolean) {
        context.dataStore.edit { it[Keys.allowMobileData] = value }
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

    suspend fun setIncludeTitleInFileName(value: Boolean) {
        context.dataStore.edit { it[Keys.includeTitleInFileName] = value }
    }

    suspend fun setFileNameTemplate(value: String) {
        context.dataStore.edit { it[Keys.fileNameTemplate] = value.take(120) }
    }

    suspend fun setRemoveEmojiFromFileName(value: Boolean) {
        context.dataStore.edit { it[Keys.removeEmojiFromFileName] = value }
    }
}
