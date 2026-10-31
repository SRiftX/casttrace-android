package com.streamcap.mobile.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.LiveReminder
import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.service.RecordingService
import com.streamcap.mobile.ui.rooms.RoomLinkInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.content.Context
import javax.inject.Inject

data class ReminderAddResult(
    val added: Int,
    val skipped: Int,
    val failures: List<String>,
)

@HiltViewModel
class LiveRemindersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: StreamCapRepository,
) : ViewModel() {
    val reminders: StateFlow<List<LiveReminder>> = repository.reminders.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val rooms: StateFlow<List<LiveRoom>> = repository.rooms.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun addLinks(rawText: String, onComplete: (ReminderAddResult) -> Unit) {
        viewModelScope.launch {
            var added = 0
            var skipped = 0
            val failures = mutableListOf<String>()
            RoomLinkInput.lines(rawText).forEach { line ->
                val url = RoomLinkInput.extract(line)
                if (url == null) {
                    failures += "未识别链接：$line"
                } else {
                    try {
                        if (repository.addReminder(url)) added++ else skipped++
                    } catch (error: Exception) {
                        failures += error.message ?: "链接解析失败：$line"
                    }
                }
            }
            if (added > 0) RecordingService.monitor(context)
            onComplete(ReminderAddResult(added, skipped, failures))
        }
    }

    fun importRooms(ids: Set<Long>, onComplete: (Int) -> Unit) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var added = 0
            ids.forEach { roomId -> if (repository.importRoomAsReminder(roomId)) added++ }
            RecordingService.monitor(context)
            onComplete(added)
        }
    }

    fun setEnabled(reminderId: Long, enabled: Boolean, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.setReminderEnabled(reminderId, enabled)
            RecordingService.monitor(context)
            onComplete()
        }
    }

    fun delete(reminder: LiveReminder, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteReminder(reminder.id)
            RecordingService.monitor(context)
            onComplete()
        }
    }
}
