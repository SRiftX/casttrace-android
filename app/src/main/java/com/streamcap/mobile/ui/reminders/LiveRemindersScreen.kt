package com.streamcap.mobile.ui.reminders

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.domain.model.LiveReminder
import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.notification.LiveReminderNotifications
import com.streamcap.mobile.service.RecordingService
import com.streamcap.mobile.ui.common.formatDateTime
import com.streamcap.mobile.ui.rooms.RoomLinkInput
import com.streamcap.mobile.ui.theme.LiveTeal
import kotlinx.coroutines.launch

@Composable
fun LiveRemindersScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onWatch: (Long) -> Unit,
) {
    val context = LocalContext.current
    val reminders by container.repository.reminders.collectAsStateWithLifecycle(emptyList())
    val rooms by container.repository.rooms.collectAsStateWithLifecycle(emptyList())
    val notifications = remember(context) { LiveReminderNotifications(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationRefresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val canNotify = remember(notifications, notificationRefresh) { notifications.canNotify }
    val scope = rememberCoroutineScope()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var linkText by rememberSaveable { mutableStateOf("") }
    var selectedFilterName by rememberSaveable { mutableStateOf(ReminderFilter.ALL.name) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var addMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var filtersExpanded by rememberSaveable { mutableStateOf(true) }
    var compactView by rememberSaveable { mutableStateOf(true) }
    var selectedRoomIds by remember { mutableStateOf(setOf<Long>()) }
    var busy by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val monitoringCount = reminders.count { it.enabled }
    val selectedFilter = ReminderFilter.entries.firstOrNull { it.name == selectedFilterName } ?: ReminderFilter.ALL
    val sortedReminders = remember(reminders) {
        reminders.sortedWith(
            compareByDescending<LiveReminder> { it.enabled && it.liveStatus == LiveStatus.LIVE }
                .thenByDescending { it.lastCheckAt ?: 0L }
                .thenBy { it.anchorName.lowercase() },
        )
    }
    val visibleReminders = remember(sortedReminders, selectedFilter, searchQuery) {
        val query = searchQuery.trim()
        sortedReminders.filter { reminder ->
            val matchesFilter = when (selectedFilter) {
                ReminderFilter.ALL -> true
                ReminderFilter.LIVE -> reminder.enabled && reminder.liveStatus == LiveStatus.LIVE
                ReminderFilter.MONITORING -> reminder.enabled && reminder.liveStatus != LiveStatus.LIVE
                ReminderFilter.PAUSED -> !reminder.enabled
            }
            val matchesSearch = query.isBlank() || reminder.anchorName.contains(query, ignoreCase = true) ||
                reminder.platform.contains(query, ignoreCase = true) || reminder.roomTitle.orEmpty().contains(query, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    fun refreshMonitoring() = RecordingService.monitor(context)

    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }

    fun addLinks() {
        val lines = RoomLinkInput.lines(linkText)
        if (lines.isEmpty()) {
            dialogError = "请输入直播间链接"
            return
        }
        if (RoomLinkInput.exceedsLimit(linkText)) {
            dialogError = "一次最多添加 200 个链接"
            return
        }
        busy = true
        dialogError = null
        scope.launch {
            var added = 0
            var skipped = 0
            val failures = mutableListOf<String>()
            lines.forEach { line ->
                val url = RoomLinkInput.extract(line)
                if (url == null) {
                    failures += "未识别链接：$line"
                } else {
                    try {
                        if (container.repository.addReminder(url)) added++ else skipped++
                    } catch (error: Exception) {
                        failures += error.message ?: "链接解析失败：$line"
                    }
                }
            }
            if (added > 0) refreshMonitoring()
            busy = false
            if (added > 0 || skipped > 0) {
                feedback = "已添加 $added 位，跳过重复 $skipped 位"
                linkText = ""
                showAddDialog = false
            }
            dialogError = failures.take(3).joinToString("\n").ifBlank { null }
        }
    }

    fun importRooms(ids: Set<Long>) {
        if (ids.isEmpty()) return
        busy = true
        scope.launch {
            var added = 0
            ids.forEach { roomId -> if (container.repository.importRoomAsReminder(roomId)) added++ }
            refreshMonitoring()
            busy = false
            feedback = "已导入 $added 位主播"
            showImportDialog = false
            selectedRoomIds = emptySet()
        }
    }

    fun setEnabled(reminder: LiveReminder, enabled: Boolean) {
        scope.launch {
            container.repository.setReminderEnabled(reminder.id, enabled)
            if (!enabled) notifications.cancel(reminder.id)
            refreshMonitoring()
        }
    }

    fun delete(reminder: LiveReminder) {
        scope.launch {
            container.repository.deleteReminder(reminder.id)
            notifications.cancel(reminder.id)
            refreshMonitoring()
            feedback = "已移除 ${reminder.anchorName}"
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding).widthIn(max = 1360.dp).align(Alignment.TopCenter),
            contentPadding = PaddingValues(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("CastTrace", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Normal)
                            Text("开播提醒控制台", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            searchExpanded = !searchExpanded
                            if (!searchExpanded) searchQuery = ""
                        }) {
                            Icon(
                                if (searchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                                contentDescription = if (searchExpanded) "关闭搜索" else "搜索主播",
                            )
                        }
                        Box {
                            IconButton(onClick = { addMenuExpanded = true }) {
                                Icon(Icons.Outlined.Add, contentDescription = "添加主播")
                            }
                            DropdownMenu(expanded = addMenuExpanded, onDismissRequest = { addMenuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("添加直播间") },
                                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                                    onClick = { addMenuExpanded = false; dialogError = null; showAddDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("从录播台导入") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                                    onClick = { addMenuExpanded = false; selectedRoomIds = emptySet(); showImportDialog = true },
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(shape = RoundedCornerShape(5.dp), color = Color(0xFF5B5D63)) {
                                Box(Modifier.size(width = 38.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        monitoringCount.toString(),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color(0xFF00C8FF),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.size(8.dp).background(if (canNotify) LiveTeal else MaterialTheme.colorScheme.error, CircleShape))
                        }
                    }
                    if (searchExpanded) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索主播或平台") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                                }
                            },
                        )
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("提醒控制", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { filtersExpanded = !filtersExpanded }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Outlined.ExpandMore,
                                contentDescription = if (filtersExpanded) "收起状态筛选" else "展开状态筛选",
                                modifier = Modifier.rotate(if (filtersExpanded) 0f else -90f),
                            )
                        }
                    }
                    if (filtersExpanded) {
                        Text("状态筛选", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ReminderFilters(selectedFilter = selectedFilter, counts = reminders.counts()) {
                            selectedFilterName = it.name
                        }
                    }
                    if (!canNotify) NotificationStatus(false, Modifier.fillMaxWidth(), ::openNotificationSettings)
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("主播", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${visibleReminders.size} / ${reminders.size} 位", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { compactView = !compactView }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text(if (compactView) "紧凑视图" else "舒展视图", maxLines = 1)
                    }
                }
            }
            feedback?.let { message ->
                item {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Text(message, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            when {
                reminders.isEmpty() -> item {
                    EmptyReminders(
                        Modifier.fillMaxWidth(),
                        title = "还没有主播",
                        description = "添加一个直播地址，开始检查开播状态。",
                    )
                }
                visibleReminders.isEmpty() -> item {
                    EmptyReminders(
                        Modifier.fillMaxWidth(),
                        title = "没有匹配的主播",
                        description = "调整搜索内容或状态筛选，查看其他主播。",
                    )
                }
                else -> items(visibleReminders, key = LiveReminder::id) { reminder ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        ReminderRow(
                            reminder = reminder,
                            compact = compactView,
                            onWatch = { onWatch(reminder.id) },
                            onEnabledChange = { setEnabled(reminder, it) },
                            onDelete = { delete(reminder) },
                        )
                    }
                }
            }
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { if (!busy) showAddDialog = false },
                title = { Text("添加主播") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = linkText,
                            onValueChange = { linkText = it; dialogError = null },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 128.dp, max = 220.dp),
                            placeholder = { Text("每行一个直播间链接") },
                            supportingText = { Text("支持分享链接和批量添加，最多 200 行") },
                            minLines = 4,
                        )
                        dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    Button(onClick = ::addLinks, enabled = !busy) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("添加")
                    }
                },
                dismissButton = { TextButton(onClick = { showAddDialog = false }, enabled = !busy) { Text("取消") } },
            )
        }

        if (showImportDialog) {
            val existingUrls = remember(reminders) { reminders.mapTo(mutableSetOf()) { it.normalizedUrl } }
            AlertDialog(
                onDismissRequest = { if (!busy) showImportDialog = false },
                title = { Text("从录播台导入") },
                text = {
                    if (rooms.isEmpty()) {
                        Text("录播台里还没有直播间")
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 440.dp)) {
                            items(rooms, key = LiveRoom::id) { room ->
                                val exists = room.normalizedUrl in existingUrls
                                val checked = room.id in selectedRoomIds
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable(enabled = !exists && !busy) {
                                            selectedRoomIds = if (checked) selectedRoomIds - room.id else selectedRoomIds + room.id
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = exists || checked, onCheckedChange = {
                                        if (!exists) selectedRoomIds = if (checked) selectedRoomIds - room.id else selectedRoomIds + room.id
                                    }, enabled = !exists && !busy)
                                    Column(Modifier.weight(1f)) {
                                        Text(room.anchorName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (exists) "${room.platform} · 已添加" else room.platform,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { importRooms(selectedRoomIds) }, enabled = selectedRoomIds.isNotEmpty() && !busy) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("导入 ${selectedRoomIds.size} 位")
                    }
                },
                dismissButton = { TextButton(onClick = { showImportDialog = false }, enabled = !busy) { Text("取消") } },
            )
        }
    }
}

private enum class ReminderFilter(val label: String) {
    ALL("全部"),
    LIVE("直播中"),
    MONITORING("提醒中"),
    PAUSED("已暂停"),
}

@Composable
private fun NotificationStatus(
    canNotify: Boolean,
    modifier: Modifier,
    onOpenSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clickable(enabled = !canNotify, onClick = onOpenSettings),
        shape = RoundedCornerShape(16.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = CircleShape, color = scheme.primary.copy(alpha = 0.12f), modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (canNotify) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsNone,
                        contentDescription = null,
                        tint = if (canNotify) scheme.primary else scheme.error,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("开播通知", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (canNotify) "强提醒已开启" else "通知权限未开启",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (canNotify) scheme.onSurfaceVariant else scheme.error,
                )
            }
            if (!canNotify) Text("设置", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
    }
}

private fun List<LiveReminder>.counts(): Map<ReminderFilter, Int> = mapOf(
    ReminderFilter.ALL to size,
    ReminderFilter.LIVE to count { it.enabled && it.liveStatus == LiveStatus.LIVE },
    ReminderFilter.MONITORING to count { it.enabled && it.liveStatus != LiveStatus.LIVE },
    ReminderFilter.PAUSED to count { !it.enabled },
)

@Composable
private fun ReminderFilters(selectedFilter: ReminderFilter, counts: Map<ReminderFilter, Int>, onSelect: (ReminderFilter) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(ReminderFilter.entries) { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onSelect(filter) },
                label = { Text("${filter.label} ${counts[filter] ?: 0}", maxLines = 1) },
            )
        }
    }
}

@Composable
private fun EmptyReminders(modifier: Modifier, title: String, description: String) {
    Surface(
        modifier = modifier.heightIn(min = 152.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp).heightIn(min = 104.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReminderRow(
    reminder: LiveReminder,
    compact: Boolean,
    onWatch: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val isLive = reminder.enabled && reminder.liveStatus == LiveStatus.LIVE
    val scheme = MaterialTheme.colorScheme
    val statusColor = when {
        !reminder.enabled -> scheme.onSurfaceVariant
        reminder.liveStatus == LiveStatus.LIVE || reminder.liveStatus == LiveStatus.ERROR -> scheme.error
        reminder.liveStatus == LiveStatus.CHECKING -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    val statusText = when {
        !reminder.enabled -> "已暂停"
        reminder.liveStatus == LiveStatus.LIVE -> "直播中"
        reminder.liveStatus == LiveStatus.CHECKING -> "检查中"
        reminder.liveStatus == LiveStatus.ERROR -> "检查异常"
        else -> "未开播"
    }
    val avatarColor = platformAccent(reminder.platform)
    var menuExpanded by remember(reminder.id) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = avatarColor.copy(alpha = 0.14f), modifier = Modifier.size(if (compact) 40.dp else 48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        reminder.anchorName.take(1).ifBlank { "播" },
                        color = avatarColor,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    reminder.anchorName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(reminder.platform)
                        if (isLive && !reminder.roomTitle.isNullOrBlank()) append(" · ${reminder.roomTitle}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = reminder.enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = scheme.primary,
                    checkedTrackColor = scheme.primary.copy(alpha = 0.32f),
                ),
            )
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作：${reminder.anchorName}",
                        tint = scheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("移除主播") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).background(statusColor, CircleShape))
            Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor)
            Text("·", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text(
                reminder.lastCheckAt?.let { formatDateTime(it) } ?: "尚未检查",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isLive) {
                TextButton(
                    onClick = onWatch,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("观看", maxLines = 1)
                }
            }
        }
    }
}

private fun platformAccent(platform: String): Color = when {
    "虎牙" in platform -> Color(0xFFC96A27)
    "抖音" in platform -> Color(0xFF34393B)
    "哔哩" in platform || "B站" in platform -> Color(0xFFD25A91)
    "斗鱼" in platform -> Color(0xFF4B79B9)
    else -> Color(0xFF526D32)
}
