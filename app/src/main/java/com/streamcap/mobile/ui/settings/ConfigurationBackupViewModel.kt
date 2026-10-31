package com.streamcap.mobile.ui.settings

import androidx.lifecycle.ViewModel
import com.streamcap.mobile.data.database.StreamCapDatabase
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.preferences.BackupPreview
import com.streamcap.mobile.data.preferences.ConfigurationBackup
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.platform.resolver.ResolverRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class ConfigurationBackupViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val repository: StreamCapRepository,
    private val resolverRegistry: ResolverRegistry,
    private val database: StreamCapDatabase,
) : ViewModel() {
    suspend fun export(): String = ConfigurationBackup.export(preferences, repository)
    suspend fun existingNormalizedUrls(): Set<String> = repository.rooms.first().mapTo(mutableSetOf()) { it.normalizedUrl }

    suspend fun restore(preview: BackupPreview): Pair<Int, Int> =
        ConfigurationBackup.restore(preferences, repository, resolverRegistry, database, preview)
}
