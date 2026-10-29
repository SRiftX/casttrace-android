package com.streamcap.mobile.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.streamcap.mobile.danmaku.DanmakuAssetManager
import com.streamcap.mobile.danmaku.DanmakuEvent
import com.streamcap.mobile.danmaku.DanmakuPresentation
import com.streamcap.mobile.danmaku.DanmakuEventPage
import com.streamcap.mobile.danmaku.DanmakuEventType
import com.streamcap.mobile.danmaku.DanmakuFilter
import com.streamcap.mobile.danmaku.DanmakuCoverage
import com.streamcap.mobile.danmaku.DanmakuSubtitleExporter
import com.streamcap.mobile.danmaku.DanmakuSubtitleFormat
import com.streamcap.mobile.data.preferences.DanmakuAppearance
import java.io.File
import kotlinx.coroutines.launch

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.domain.model.*
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordingPlayerScreen(recordingId: Long, container: AppContainer, onBack: () -> Unit) {
    val recording by remember(recordingId) {
        container.repository.observeRecording(recordingId).map { session ->
            if (session == null) null else withContext(Dispatchers.IO) {
                val explicit = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }
                val discovered = runCatching {
                    container.recordingStorage.publishedSiblingParts(explicit, session.roomName, session.startedAt)
                }.getOrDefault(explicit)
                if (discovered.size > explicit.size) session.copy(outputUri = discovered.first(), outputUris = discovered) else session
            }
        }
    }.collectAsStateWithLifecycle(null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var exportFormat by remember { mutableStateOf(DanmakuSubtitleFormat.ASS) }
    var showExportMenu by remember { mutableStateOf(false) }
    val exportAppearance by container.preferences.danmakuAppearance.collectAsStateWithLifecycle(DanmakuAppearance())
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destination ->
        if (destination != null) scope.launch {
            try {
                val current = requireNotNull(recording)
                val path = requireNotNull(current.danmakuPath)
                val temp = File(context.cacheDir, "danmaku-export-${current.id}.${exportFormat.name.lowercase()}")
                try {
                    withContext(Dispatchers.IO) {
                        val exportJob = kotlinx.coroutines.currentCoroutineContext()
                        DanmakuSubtitleExporter.exportFile(File(path), current.id, temp, exportFormat, exportAppearance) { !exportJob.isActive }
                        requireNotNull(context.contentResolver.openOutputStream(destination, "w")) { "导出目录不可写" }.use { output ->
                            temp.inputStream().use { it.copyTo(output) }
                        }
                    }
                    snackbar.showSnackbar("弹幕文件已导出")
                } finally { temp.delete() }
            } catch (failure: Exception) { snackbar.showSnackbar("导出弹幕失败：${failure.message}") }
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB4DECD), surface = Color(0xFF202020),
        surfaceContainer = Color(0xFF272727), background = Color.Black)) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            recording?.let { Playback(it, container, onBack, exportMenuOpen = showExportMenu, onExport = { showExportMenu = true }) }
                ?: IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding()) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Color.White)
                }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
        if (showExportMenu) AlertDialog(onDismissRequest = { showExportMenu = false }, title = { Text("导出弹幕") },
            text = { Column { DanmakuSubtitleFormat.entries.forEach { format ->
                TextButton(onClick = {
                    showExportMenu = false; exportFormat = format
                    exportPicker.launch("弹幕_${recordingId}.${if (format == DanmakuSubtitleFormat.COVERAGE) "coverage.json" else format.name.lowercase()}")
                }, modifier = Modifier.fillMaxWidth()) { Text("导出 ${format.name}") }
            } } }, confirmButton = { TextButton(onClick = { showExportMenu = false }) { Text("取消") } })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DanmakuSpeechSheet(sessionId: Long, container: AppContainer, filter: DanmakuFilter,
                               onFilter: (DanmakuFilter) -> Unit, eventCount: Long,
                               onDismiss: () -> Unit, onSeek: (DanmakuEvent) -> Unit) {
    val context = LocalContext.current
    var requestedPage by remember(sessionId, filter) { mutableIntStateOf(0) }
    var page by remember(sessionId, filter) { mutableStateOf(DanmakuEventPage(emptyList(), 0L, 0L, 0L)) }
    var loading by remember(sessionId, filter) { mutableStateOf(false) }
    var problem by remember(sessionId, filter) { mutableStateOf<String?>(null) }
    var typeMenu by remember { mutableStateOf(false) }
    var coverage by remember(sessionId) { mutableStateOf(DanmakuCoverage(null, emptyMap())) }
    var userCount by remember(sessionId, filter.user) { mutableStateOf<Long?>(null) }
    LaunchedEffect(sessionId) {
        coverage = DanmakuAssetManager(context, container.repository).coverage(sessionId)
    }
    LaunchedEffect(sessionId, filter.user) {
        if (filter.user.isNotBlank()) {
            userCount = DanmakuAssetManager(context, container.repository).countMatching(sessionId, DanmakuFilter(user = filter.user))
        }
    }
    LaunchedEffect(sessionId, filter, requestedPage) {
        loading = true
        problem = null
        try {
            val next = DanmakuAssetManager(context, container.repository).eventsPage(
                sessionId, if (requestedPage == 0) 0L else page.nextOffset ?: 0L, 100, filter)
            page = next.copy(events = (if (requestedPage == 0) next.events else page.events + next.events).takeLast(500))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            problem = failure.message ?: "读取发言失败"
        } finally { loading = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.86f).padding(horizontal = 16.dp)) {
            Text("发言列表", style = MaterialTheme.typography.titleMedium)
            Text("已记录 $eventCount 条 · 丢弃 ${coverage.dropped?.toString() ?: "未知"} 条 · 当前显示 ${page.events.size} 条 · 已扫描 ${page.scannedBytes / 1024} / ${page.totalBytes / 1024} KiB",
                style = MaterialTheme.typography.labelSmall)
            if (filter.user.isNotBlank()) Text("该用户发言 ${userCount?.toString() ?: "统计中"} 条", style = MaterialTheme.typography.labelSmall)
            if (coverage.eventTypes.isNotEmpty()) Text(
                coverage.eventTypes.filterValues { it > 0L }.entries.joinToString(" · ") { (type, count) -> "${type.displayName()} $count" },
                style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(filter.query, { onFilter(filter.copy(query = it)) }, label = { Text("搜索内容") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(filter.user, { onFilter(filter.copy(user = it)) }, label = { Text("用户") },
                    singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(filter.platform, { onFilter(filter.copy(platform = it)) }, label = { Text("平台") },
                    singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { typeMenu = true }) {
                        Text("事件：${filter.eventType?.displayName() ?: "全部"}")
                        Icon(Icons.Outlined.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        (listOf<DanmakuEventType?>(null) + DanmakuEventType.entries).forEach { type ->
                            DropdownMenuItem(text = { Text(type?.displayName() ?: "全部") }, onClick = {
                                onFilter(filter.copy(eventType = type)); typeMenu = false
                            })
                        }
                    }
                }
                if (filter != DanmakuFilter()) TextButton(onClick = { onFilter(DanmakuFilter()) }) { Text("清除筛选") }
            }
            if (problem != null) Text(problem.orEmpty(), color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.weight(1f)) {
                items(page.events) { event ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = event.mediaTimeMs != null) { onSeek(event) }
                        .padding(vertical = 8.dp)) {
                        Text("${event.mediaTimeMs?.let(::timeLabel) ?: "时间未知"}  ${event.platform}  ${event.eventType.displayName()}",
                            style = MaterialTheme.typography.labelSmall)
                        Text((event.user?.name ?: event.userName ?: event.userId ?: "匿名") + "：" + event.text,
                            maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        com.streamcap.mobile.danmaku.DanmakuPresentation.valueLabel(event)?.let { value ->
                            Text(value, style = MaterialTheme.typography.labelSmall)
                        }
                        val name = event.user?.name ?: event.userName
                        if (!name.isNullOrBlank()) TextButton(onClick = { onFilter(filter.copy(user = name)) }) {
                            Text("查看该用户")
                        }
                    }
                    HorizontalDivider()
                }
                item {
                    if (loading) CircularProgressIndicator(Modifier.padding(16.dp))
                    else if (page.nextOffset != null) TextButton(onClick = { requestedPage++ }) { Text("加载更多") }
                    else if (page.events.isEmpty()) Text("没有匹配的发言", Modifier.padding(16.dp))
                }
            }
        }
    }
}

private fun DanmakuEventType.displayName(): String = when (this) {
    DanmakuEventType.CHAT -> "发言"
    DanmakuEventType.EMOTE -> "表情"
    DanmakuEventType.GIFT -> "礼物"
    DanmakuEventType.SUPER_CHAT -> "醒目留言"
    DanmakuEventType.GUARD -> "守护"
    DanmakuEventType.LIKE -> "点赞"
    DanmakuEventType.FOLLOW -> "关注"
    DanmakuEventType.SHARE -> "分享"
    DanmakuEventType.SYSTEM -> "系统"
    DanmakuEventType.UNKNOWN -> "其他"
}

@OptIn(ExperimentalLayoutApi::class)
@AndroidXOptIn(markerClass = [UnstableApi::class])
@Composable
private fun Playback(recording: RecordingSession, container: AppContainer, onBack: () -> Unit, exportMenuOpen: Boolean, onExport: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progressStore = remember { PlaybackProgressStore(context) }
    var progress by remember(recording.id) { mutableStateOf(progressStore.read(recording.id)) }
    var pageIndex by remember(recording.id) { mutableIntStateOf(progressStore.readPart(recording.id)) }
    var pageCount by remember(recording.id) { mutableIntStateOf(0) }
    var openedPartCount by remember(recording.id) { mutableIntStateOf(recording.outputUris.size) }
    var live by remember(recording.id) { mutableStateOf(false) }
    var fullscreen by rememberSaveable(recording.id) { mutableStateOf(false) }
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val originalOrientation = rememberSaveable { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    DisposableEffect(activity) {
        onDispose { if (activity?.isChangingConfigurations != true) activity?.requestedOrientation = originalOrientation }
    }
    DisposableEffect(activity, fullscreen) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) controller?.hide(WindowInsetsCompat.Type.systemBars())
        else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler(fullscreen) { fullscreen = false }
    var requestedProgress by remember { mutableStateOf<PlaybackProgress?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var knownDurations by remember { mutableStateOf<List<Long>>(emptyList()) }
    var headers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var liveFailures by remember(recording.id, live) { mutableIntStateOf(0) }
    var danmakuEvents by remember(recording.id, recording.danmakuPath) { mutableStateOf<List<DanmakuEvent>>(emptyList()) }
    var pageStartMs by remember(recording.id) { mutableLongStateOf(0L) }
    var pageStarts by remember(recording.id) { mutableStateOf<List<Long>>(emptyList()) }
    var danmakuWindow by remember(recording.id) { mutableLongStateOf(0L) }
    var showSpeech by remember(recording.id) { mutableStateOf(false) }
    var filter by remember(recording.id) { mutableStateOf(DanmakuFilter()) }
    var seekRequest by remember(recording.id) { mutableStateOf<Long?>(null) }
    var showDanmaku by rememberSaveable(recording.id) { mutableStateOf(progressStore.readDanmakuVisible()) }
    var separateDanmaku by rememberSaveable(recording.id) { mutableStateOf(false) }
    var showDanmakuSettings by remember(recording.id) { mutableStateOf(false) }
    val danmakuAppearance by container.preferences.danmakuAppearance.collectAsStateWithLifecycle(DanmakuAppearance())
    val liveDanmakuWindow = if (live) (recording.durationSeconds.coerceAtLeast(0L) / 10L) * 10_000L else 0L
    LaunchedEffect(recording.danmakuPath, pageStartMs, pageIndex, danmakuWindow, live, liveDanmakuWindow, filter, danmakuAppearance.timeOffsetMs) {
        danmakuEvents = if (recording.danmakuPath == null) emptyList() else try {
            val window = if (live) liveDanmakuWindow else pageStartMs + danmakuWindow
            val from = (window - if (live) 120_000L else 10_000L).coerceAtLeast(0L)
            DanmakuAssetManager(context, container.repository)
                .eventsWindow(recording.id, from - danmakuAppearance.timeOffsetMs,
                    window + 130_000L - danmakuAppearance.timeOffsetMs, filter = filter)
                .map { if (live) it else it.copy(mediaTimeMs = it.mediaTimeMs?.plus(danmakuAppearance.timeOffsetMs)?.minus(pageStartMs)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { emptyList() }
    }
    LaunchedEffect(error, live) {
        if (live && error != null && liveFailures < 3) {
            delay(1500)
            liveFailures++
            retry++
        }
    }
    // A checkpoint changes the database list, not the user's current playback.
    // Keep the opened snapshot stable until an explicit refresh or mode change.
    val resolver = remember(recording.id, live, retry, pageIndex) {
        RecordingPlaybackSourceResolver(context)
    }
    DisposableEffect(resolver) { onDispose { resolver.close() } }
    LaunchedEffect(recording.id, live, retry, pageIndex) {
        loading = true
        error = null
        items = emptyList()
        knownDurations = emptyList()
        try {
            if (live) {
                val source = withContext(Dispatchers.IO) {
                    container.repository.streams(recording.roomId).let { sources ->
                        val hls = sources.filter { it.type == StreamType.HLS }
                        val flv = sources.filter { it.type == StreamType.FLV }
                        if (recording.streamType == StreamType.FLV && flv.isNotEmpty()) {
                            flv[retry.mod(flv.size)]
                        } else if (hls.isNotEmpty()) {
                            try { HlsSourceSelector(container.httpClient).select(hls) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { sources.firstOrNull { it.type == StreamType.FLV } ?: throw failure }
                        } else sources.firstOrNull()
                    }
                } ?: error("没有可用的直播线路")
                headers = source.headers
                items = listOf(MediaItem.Builder().setUri(source.url)
                    .setMimeType(if (source.type == StreamType.HLS) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_FLV)
                    .build())
            } else {
                val (pages, durations) = withContext(Dispatchers.IO) {
                    val durations = container.recordingStorage.publishedPartDurationsSeconds(recording.outputUris)
                        .map { it * 1_000L }
                    (if (recording.status in setOf(TaskStatus.STARTING, TaskStatus.RECORDING, TaskStatus.RECONNECTING) && recording.outputUris.isNotEmpty())
                        listOf(recording.outputUris) else PlaybackPages.group(recording.outputUris, durations)) to durations
                }
                pageCount = pages.size
                var elapsed = 0L
                pageStarts = pages.mapIndexed { pageNumber, page ->
                    val start = elapsed
                    elapsed += durations.drop(pages.take(pageNumber).sumOf { it.size })
                        .take(page.size).sumOf { it.takeIf { length -> length > 0L } ?: 3_600_000L }
                    start
                }
                val selectedPage = pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                val precedingParts = pages.take(selectedPage).sumOf { it.size }
                pageStartMs = pageStarts.getOrElse(selectedPage) {
                    durations.take(precedingParts).sumOf { it.takeIf { length -> length > 0L } ?: 3_600_000L }
                }
                if (pageIndex != selectedPage) {
                    pageIndex = selectedPage
                    progressStore.writePart(recording.id, selectedPage)
                }
                val activePreview = recording.status in setOf(TaskStatus.STARTING, TaskStatus.RECORDING, TaskStatus.RECONNECTING)
                val paging = pages.isNotEmpty() && !activePreview
                val selected = pages.getOrNull(selectedPage).orEmpty()
                val uris = withContext(Dispatchers.IO) {
                    resolver.resolve(selected.firstOrNull() ?: recording.outputUri, selected,
                        if (paging) null else recording.temporaryPath, recording.containerFormat,
                        includeActiveTail = activePreview,
                        refreshPublished = {
                            if (paging) selected else container.repository.session(recording.id)?.let { current ->
                                container.recordingStorage.publishedSiblingParts(
                                    current.outputUris.ifEmpty { listOfNotNull(current.outputUri) }, current.roomName, current.startedAt)
                            }.orEmpty()
                        })
                }
                if (uris.size < selected.distinct().size) {
                    requestedProgress = PlaybackProgress(0, false)
                    error = "部分分段无法读取，已从可用内容开头暂停；请检查目录权限。"
                }
                check(uris.isNotEmpty()) { "没有可读取的媒体文件，录制可能尚未保存" }
                openedPartCount = recording.outputUris.size
                headers = emptyMap()
                // ExoPlayer loads later playlist windows lazily. Resolve saved
                // durations before enabling the global seek bar, otherwise a
                // two-part recording initially appears to be only its first part.
                knownDurations = withContext(Dispatchers.IO) {
                    container.recordingStorage.publishedPartDurationsSeconds(uris.map(Uri::toString))
                        .map { it * 1_000L }
                }
                items = uris.map { uri ->
                    val rawName = uri.lastPathSegment.orEmpty().lowercase()
                    val mimeType = when {
                        rawName.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
                        rawName.endsWith(".ts") || rawName.contains(".ts.") -> MimeTypes.VIDEO_MP2T
                        rawName.endsWith(".flv") || rawName.contains(".flv.") -> MimeTypes.VIDEO_FLV
                        recording.containerFormat.equals("ts", true) -> MimeTypes.VIDEO_MP2T
                        recording.containerFormat.equals("flv", true) -> MimeTypes.VIDEO_FLV
                        else -> MimeTypes.VIDEO_MP4
                    }
                    MediaItem.Builder()
                        .setUri(uri)
                        .setMimeType(mimeType)
                        .build()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "读取播放源失败"
        } finally { loading = false }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else if (items.isNotEmpty()) {
            key(items, retry) {
                VideoSurface(items, headers, live, knownDurations, container.networkPolicy, Modifier.fillMaxSize(),
                    danmakuEvents = if (showDanmaku) danmakuEvents else emptyList(),
                    danmakuAppearance = danmakuAppearance,
                    title = recording.roomName, fullscreen = fullscreen,
                    activeRecording = recording.status in setOf(TaskStatus.STARTING, TaskStatus.RECORDING, TaskStatus.RECONNECTING),
                    onBack = { if (fullscreen) fullscreen = false else onBack() },
                    onFullscreen = { fullscreen = !fullscreen },
                    onRotate = {
                        activity?.requestedOrientation = if (configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    },
                    hasDanmaku = recording.danmakuPath != null, showDanmaku = showDanmaku,
                    separateDanmaku = separateDanmaku && showDanmaku && !fullscreen,
                    onLayoutToggle = {
                        separateDanmaku = !separateDanmaku
                        if (separateDanmaku && !showDanmaku) {
                            showDanmaku = true
                            progressStore.writeDanmakuVisible(true)
                        }
                    },
                    onDanmakuToggle = { showDanmaku = !showDanmaku; progressStore.writeDanmakuVisible(showDanmaku) },
                    externalPanelOpen = showDanmakuSettings || showSpeech || exportMenuOpen || error != null,
                    onDanmakuSettings = { showDanmakuSettings = true }, onExport = onExport,
                    onSpeech = { showSpeech = true }, seekRequest = seekRequest,
                    onSwitchSource = { live = !live },
                    onRefresh = { retry++ }, hasNewParts = recording.outputUris.size > openedPartCount,
                    pageIndex = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)), pageCount = pageCount,
                    onSelectPage = { index ->
                        if (index != pageIndex && index in 0 until pageCount) {
                            progress = PlaybackProgress(0, true)
                            requestedProgress = progress
                            progressStore.write(recording.id, progress)
                            progressStore.writePart(recording.id, index)
                            pageIndex = index
                        }
                    },
                    initialProgress = requestedProgress ?: progress,
                    onProgress = {
                        if (progressStore.readPart(recording.id) == pageIndex) {
                            progress = it
                            danmakuWindow = (it.positionMs.coerceAtLeast(0L) / 120_000L) * 120_000L
                            progressStore.write(recording.id, it)
                        }
                    },
                    onReady = {
                        liveFailures = 0
                        danmakuWindow = (progress.positionMs.coerceAtLeast(0L) / 120_000L) * 120_000L
                        requestedProgress?.let { progress = it; progressStore.write(recording.id, it); requestedProgress = null }
                        seekRequest = null
                    }) { error = it }
            }
        }
        if (loading || items.isEmpty()) Row(Modifier.align(Alignment.TopStart).safeDrawingPadding(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (fullscreen) fullscreen = false else onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text(recording.roomName, Modifier.weight(1f), maxLines = 1)
            if (recording.danmakuPath != null) IconButton(onClick = {
                separateDanmaku = !separateDanmaku
                if (separateDanmaku) showDanmaku = true
            }) { Icon(Icons.Outlined.ViewAgenda, "上方视频下方弹幕") }
            TextButton(onClick = { live = !live }) { Text(if (live) "已录内容" else "直播预览") }
        }
        if (!loading && items.isEmpty()) TextButton(onClick = { retry++ }, modifier = Modifier.align(Alignment.Center)) {
            Icon(Icons.Outlined.Refresh, null)
            Text("重试播放")
        }
    }
    if (showDanmakuSettings) DanmakuSettingsPanel(danmakuAppearance, onDismiss = { showDanmakuSettings = false }) { value ->
        scope.launch { container.preferences.setDanmakuAppearance(value) }
    }
    if (showSpeech && recording.danmakuPath != null) DanmakuSpeechSheet(
        recording.id, container, filter, onFilter = { filter = it },
        eventCount = recording.danmakuEventCount,
        onDismiss = { showSpeech = false }, onSeek = { event ->
            event.mediaTimeMs?.let { time ->
                val targetPage = pageStarts.indexOfLast { it <= time }.coerceAtLeast(0)
                val target = (time - pageStarts.getOrElse(targetPage) { 0L }).coerceAtLeast(0L)
                showSpeech = false
                live = false
                if (targetPage != pageIndex) {
                    progress = PlaybackProgress(target, true)
                    requestedProgress = progress
                    progressStore.writePart(recording.id, targetPage)
                    pageIndex = targetPage
                } else seekRequest = target
            }
        })
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("播放提示") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { retry++ }) { Text("重试播放") } },
            dismissButton = { TextButton(onClick = { error = null }) { Text("关闭") } })
    }
}

@AndroidXOptIn(markerClass = [UnstableApi::class])
@Composable
private fun VideoSurface(items: List<MediaItem>, headers: Map<String, String>, live: Boolean, knownDurations: List<Long>,
                         networkPolicy: com.streamcap.mobile.network.RecordingNetworkPolicy,
                         modifier: Modifier, danmakuEvents: List<DanmakuEvent>, danmakuAppearance: DanmakuAppearance,
                         title: String, fullscreen: Boolean, activeRecording: Boolean,
                         onBack: () -> Unit, onFullscreen: () -> Unit, onRotate: () -> Unit,
                         hasDanmaku: Boolean, showDanmaku: Boolean, onDanmakuToggle: () -> Unit,
                         separateDanmaku: Boolean, onLayoutToggle: () -> Unit,
                         externalPanelOpen: Boolean,
                         onDanmakuSettings: () -> Unit, onExport: () -> Unit, onSpeech: () -> Unit,
                         seekRequest: Long?, onSwitchSource: () -> Unit,
                         onRefresh: () -> Unit, hasNewParts: Boolean,
                         pageIndex: Int, pageCount: Int, onSelectPage: (Int) -> Unit,
                         initialProgress: PlaybackProgress, onProgress: (PlaybackProgress) -> Unit, onReady: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    // Recreate the data source when a live retry returns different request headers.
    val player = remember(headers) {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        val guarded = androidx.media3.datasource.ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http)) { spec ->
            if (spec.uri.scheme in setOf("http", "https")) {
                kotlinx.coroutines.runBlocking { networkPolicy.requireAllowed() }
            }
            spec
        }
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(guarded))
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build()
    }
    val reportProgress by rememberUpdatedState(onProgress)
    var stoppedProgress by remember(player) { mutableStateOf<PlaybackProgress?>(null) }
    // Capture before releasing; global milliseconds remain stable when new segments append.
    fun snapshot(): PlaybackProgress {
        val before = knownDurations.take(player.currentMediaItemIndex.coerceAtLeast(0)).sum()
        val total = knownDurations.sum()
        val value = (before + player.currentPosition).coerceAtLeast(0)
        return PlaybackProgress(if (total > 0 && value >= total - 1000) 0 else value, player.playWhenReady)
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var currentWindow by remember { mutableIntStateOf(0) }
    var timelineDurations by remember { mutableStateOf<List<Long>>(emptyList()) }
    var playing by remember { mutableStateOf(false) }
    var seekable by remember { mutableStateOf(false) }
    var renderedFirstFrame by remember(player) { mutableStateOf(false) }
    val optionsStore = remember { PlaybackProgressStore(context) }
    val options = remember { optionsStore.readOptions() }
    var speed by rememberSaveable { mutableFloatStateOf(options.speed) }
    var volume by rememberSaveable { mutableFloatStateOf(options.volume) }
    var muted by rememberSaveable { mutableStateOf(options.muted) }
    LaunchedEffect(speed, volume, muted) { optionsStore.writeOptions(PlaybackOptions(speed, volume, muted)) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<String?>(null) }
    var videoProperties by remember { mutableStateOf("") }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { renderedFirstFrame = true; onReady() }
            override fun onPlaybackStateChanged(state: Int) {
                if (!live && state == Player.STATE_ENDED && pageIndex + 1 < pageCount) onSelectPage(pageIndex + 1)
            }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("StreamCapPlayer", "Playback failed", error)
                if (!live && player.hasNextMediaItem()) {
                    android.util.Log.w("StreamCapPlayer", "当前分段失败，自动切换下一段")
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    onError("播放失败：${error.errorCodeName}")
                }
            }
        }
        player.addListener(listener)
        onDispose { if (!live && player.playbackState != Player.STATE_IDLE) reportProgress(stoppedProgress ?: snapshot()); player.removeListener(listener); player.release() }
    }
    // Items can change while this composable stays in the slot (for example after
    // a failed preview retry), so loading them belongs in an effect keyed by items.
    LaunchedEffect(player, items) {
        renderedFirstFrame = false
        player.setMediaItems(items, /* resetPosition = */ true)
        if (!live && knownDurations.all { it > 0 } && knownDurations.isNotEmpty()) {
            val resume = initialProgress.positionMs.takeIf { it < knownDurations.sum() - 1000 } ?: 0L
            seekGlobal(player, knownDurations, resume)
        }
        player.prepare()
        player.setPlaybackSpeed(if (live) 1f else speed)
        player.volume = if (muted) 0f else volume
        player.playWhenReady = live || initialProgress.playWhenReady
    }
    LaunchedEffect(player, items, live) {
        if (live && items.isNotEmpty()) {
            delay(45_000)
            if (!renderedFirstFrame && player.playbackState != Player.STATE_ENDED) {
                onError("直播预览在 45 秒内没有收到首帧，可能是线路过期、平台限流或网络不可用；请点击重试")
            }
        }
    }
    LaunchedEffect(player, seekRequest) {
        seekRequest?.let { target ->
            seekGlobal(player, knownDurations, target)
            player.play()
        }
    }
    LaunchedEffect(player, live) {
        while (!live && isActive) {
            delay(5000)
            if (stoppedProgress == null && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED)) reportProgress(snapshot())
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                stoppedProgress = snapshot()
                if (!live) reportProgress(requireNotNull(stoppedProgress))
                player.pause()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                stoppedProgress?.let { player.playWhenReady = it.playWhenReady }
                stoppedProgress = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player, live) {
        while (live && isActive) {
            if (!withContext(Dispatchers.IO) { networkPolicy.allowsRequests() }) {
                player.stop()
                onError("仅 Wi-Fi 模式：当前网络不可用或按流量计费，已停止直播预览")
                break
            }
            delay(1_000)
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            val timeline = player.currentTimeline
            val windows = if (timeline.isEmpty) knownDurations else buildList(timeline.windowCount) {
                val window = Timeline.Window()
                for (index in 0 until timeline.windowCount) {
                    timeline.getWindow(index, window)
                    add(window.durationUs.takeIf { it != C.TIME_UNSET }?.div(1_000L)?.coerceAtLeast(0L)
                        ?: knownDurations.getOrElse(index) { 0L })
                }
            }
            timelineDurations = windows
            currentWindow = player.currentMediaItemIndex.coerceAtLeast(0)
            duration = windows.sum().takeIf { it > 0L } ?: player.duration.takeIf { it > 0 } ?: 0L
            val beforeCurrent = windows.take(currentWindow).sum()
            position = (beforeCurrent + player.currentPosition).coerceAtLeast(0L).coerceAtMost(duration)
            playing = player.isPlaying
            seekable = player.isCurrentMediaItemSeekable
            videoProperties = player.videoFormat?.let { format ->
                buildString {
                    if (format.width > 0 && format.height > 0) append("实际视频 ${format.width}×${format.height}")
                    if (format.frameRate > 0) append(" · ${"%.1f".format(format.frameRate)} fps")
                    format.sampleMimeType?.let { append(" · $it") }
                }
            }.orEmpty()
            delay(250)
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(externalPanelOpen) { if (externalPanelOpen) controlsVisible = true }
    LaunchedEffect(controlsVisible, playing, dragging, panel, menuOpen, externalPanelOpen, interaction) {
        if (controlsVisible && playing && dragging == null && panel == null && !menuOpen && !externalPanelOpen) {
            delay(3500)
            controlsVisible = false
        }
    }
    fun touch() { interaction++; controlsVisible = true }
    BoxWithConstraints(modifier.background(Color.Black)) {
        val videoHeight = maxWidth * 9f / 16f
        val videoTop = if (separateDanmaku) 72.dp else 0.dp
        AndroidView(factory = { PlayerView(it).apply {
            this.player = player
            useController = false
            keepScreenOn = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShutterBackgroundColor(android.graphics.Color.BLACK)
        } }, update = { it.player = player }, modifier = if (separateDanmaku) Modifier.padding(top = videoTop).fillMaxWidth().height(videoHeight) else Modifier.fillMaxSize())
        if (separateDanmaku) {
            val currentMediaMs = timelineDurations.take(player.currentMediaItemIndex.coerceAtLeast(0)).sum() + player.currentPosition
            val visible = danmakuEvents.filter { event ->
                live || event.mediaTimeMs?.let { it <= currentMediaMs } == true
            }.takeLast(80)
            val listState = rememberLazyListState()
            val newest = visible.lastOrNull()
            LaunchedEffect(newest?.eventId, newest?.receivedWallMs, newest?.text) {
                if (newest != null) listState.animateScrollToItem(0)
            }
            LazyColumn(state = listState, reverseLayout = true,
                modifier = Modifier.fillMaxSize().padding(top = videoTop + videoHeight)
                .background(Color(0xFF3A332D)).padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 14.dp, bottom = if (controlsVisible) 112.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(visible.asReversed()) { event ->
                    val message = DanmakuPresentation.text(event, danmakuAppearance) ?: return@items
                    Surface(
                        color = when (event.eventType) {
                            DanmakuEventType.GIFT, DanmakuEventType.SUPER_CHAT, DanmakuEventType.GUARD -> Color(0xFF62452F)
                            else -> Color(0xFF4B443D)
                        }, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth(0.94f),
                    ) {
                        Text(message, color = Color(0xFFF5F1EC), style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                    }
                }
            }
        }
        if (!separateDanmaku && !live && danmakuEvents.isNotEmpty()) ScrollingDanmakuOverlay(danmakuEvents,
            appearance = danmakuAppearance,
            videoAspectRatio = player.videoSize.let {
                if (it.width > 0 && it.height > 0) it.width * it.pixelWidthHeightRatio / it.height else 16f / 9f
            },
            mediaPosition = {
                timelineDurations.take(player.currentMediaItemIndex.coerceAtLeast(0)).sum() + player.currentPosition
            }, modifier = Modifier.fillMaxSize())
        Box((if (separateDanmaku) Modifier.padding(top = videoTop).fillMaxWidth().height(videoHeight) else Modifier.fillMaxSize()).semantics { contentDescription = "播放画面" }
            .pointerInput(player) {
                detectTapGestures(onTap = { controlsVisible = !controlsVisible; interaction++ },
                    onDoubleTap = { if (player.playWhenReady) player.pause() else player.play(); touch() })
            })
        androidx.compose.animation.AnimatedVisibility(visible = controlsVisible || separateDanmaku,
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .78f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", { if (!live) reportProgress(snapshot()); onBack() })
                Column(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(if (live) "直播预览" else if (activeRecording) "已保存内容 · 新片段保存后可刷新" else "已录内容", style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFBBBBBB))
                }
                if (hasDanmaku) PlayerIcon(Icons.Outlined.Subtitles,
                    if (showDanmaku) "隐藏弹幕" else "显示弹幕", { onDanmakuToggle(); touch() },
                    tint = if (showDanmaku) MaterialTheme.colorScheme.primary else Color.White)
                if (hasDanmaku) PlayerIcon(Icons.Outlined.ViewAgenda,
                    if (separateDanmaku) "弹幕覆盖视频" else "上方视频下方弹幕",
                    { onLayoutToggle(); touch() },
                    tint = if (separateDanmaku) MaterialTheme.colorScheme.primary else Color.White)
                PlayerIcon(Icons.Outlined.ScreenRotation, "旋转屏幕", { if (!live) reportProgress(snapshot()); onRotate(); touch() })
                Box {
                    PlayerIcon(Icons.Outlined.Settings, "播放设置", { menuOpen = true; touch() })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false; touch() }) {
                        fun close() { menuOpen = false; touch() }
                        DropdownMenuItem(text = { Text("倍速 ${speed}x") }, enabled = !live,
                            leadingIcon = { Icon(Icons.Outlined.Speed, null) }, onClick = { close(); panel = "speed" })
                        DropdownMenuItem(text = { Text("音量") }, leadingIcon = { Icon(Icons.Outlined.VolumeUp, null) },
                            onClick = { close(); panel = "volume" })
                        DropdownMenuItem(text = { Text("弹幕设置") }, leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                            onClick = { close(); onDanmakuSettings() })
                        if (hasDanmaku) {
                            DropdownMenuItem(text = { Text(if (separateDanmaku) "弹幕覆盖视频" else "视频上方 · 弹幕下方") },
                                leadingIcon = { Icon(Icons.Outlined.ViewAgenda, null) }, onClick = { close(); onLayoutToggle() })
                            DropdownMenuItem(text = { Text("发言列表") }, leadingIcon = { Icon(Icons.Outlined.Chat, null) },
                                onClick = { close(); onSpeech() })
                            DropdownMenuItem(text = { Text("导出弹幕") }, leadingIcon = { Icon(Icons.Outlined.Download, null) },
                                onClick = { close(); onExport() })
                        }
                        if (!live) {
                            DropdownMenuItem(text = { Text("分段 ${pageIndex + 1}/${pageCount.coerceAtLeast(1)}") },
                                leadingIcon = { Icon(Icons.Outlined.VideoLibrary, null) }, onClick = { close(); panel = "parts" })
                            DropdownMenuItem(text = { Text("从头播放") }, leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                                onClick = { close(); seekGlobal(player, timelineDurations, 0); player.play() })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(if (live) "切换到已录内容" else "切换到直播预览") },
                            leadingIcon = { Icon(Icons.Outlined.SwapHoriz, null) }, onClick = { close(); if (!live) reportProgress(snapshot()); onSwitchSource() })
                        DropdownMenuItem(text = { Text(if (live) "刷新直播预览" else if (hasNewParts) "有新片段 · 刷新" else "刷新已录内容") },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = { close(); if (!live) reportProgress(snapshot()); onRefresh() })
                        DropdownMenuItem(text = { Text("视频信息") }, leadingIcon = { Icon(Icons.Outlined.Info, null) },
                            onClick = { close(); panel = "info" })
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = controlsVisible,
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)) {
            BoxWithConstraints(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .82f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 4.dp)) {
                val wide = maxWidth >= 600.dp
                val timeOnOwnLine = maxWidth < 380.dp && !live
                val time = if (live) "直播" else "${timeLabel(position)} / ${timeLabel(duration)}"
                Column {
                    if (!live) Slider(value = dragging ?: position.toFloat().coerceAtMost(duration.toFloat()),
                        onValueChange = { dragging = it; touch() }, onValueChangeFinished = {
                            dragging?.let { target -> seekGlobal(player, timelineDurations, target.toLong()) }
                            dragging = null; touch()
                        }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = seekable && duration > 0,
                        modifier = Modifier.fillMaxWidth().height(32.dp)
                            .semantics { contentDescription = "播放进度" })
                    if (timeOnOwnLine) Text(time, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), maxLines = 1)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PlayerIcon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            if (playing) "暂停" else "播放", {
                                if (player.playbackState == Player.STATE_ENDED) {
                                    seekGlobal(player, timelineDurations, 0); player.play()
                                } else if (player.playWhenReady) player.pause() else player.play()
                                touch()
                            })
                        if (!live) {
                            if (wide && pageCount > 1) PlayerIcon(Icons.Outlined.SkipPrevious, "上一段",
                                { onSelectPage(pageIndex - 1); touch() }, enabled = pageIndex > 0)
                            PlayerIcon(Icons.Outlined.Replay10, "后退10秒", { player.seekBack(); touch() }, enabled = seekable)
                            PlayerIcon(Icons.Outlined.Forward10, "快进10秒", { player.seekForward(); touch() }, enabled = seekable)
                            if (wide && pageCount > 1) PlayerIcon(Icons.Outlined.SkipNext, "下一段",
                                { onSelectPage(pageIndex + 1); touch() }, enabled = pageIndex + 1 < pageCount)
                        }
                        if (timeOnOwnLine) Spacer(Modifier.weight(1f)) else Text(time,
                            style = MaterialTheme.typography.labelMedium, maxLines = 1,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                        if (wide) {
                            PlayerIcon(if (muted || volume == 0f) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                                "音量", { panel = "volume"; touch() })
                            TextButton(onClick = { panel = "speed"; touch() }, enabled = !live) { Text("${speed}x") }
                        }
                        PlayerIcon(Icons.Outlined.Tune, "弹幕设置", { onDanmakuSettings(); touch() })
                        PlayerIcon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                            if (fullscreen) "退出全屏" else "全屏", { onFullscreen(); touch() })
                    }
                }
            }
        }
    }
    panel?.let { selected ->
        AlertDialog(onDismissRequest = { panel = null; touch() },
            title = { Text(when (selected) { "volume" -> "音量"; "speed" -> "播放倍速"; "parts" -> "分段"; else -> "视频信息" }) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    when (selected) {
                        "volume" -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PlayerIcon(if (muted || volume == 0f) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                                    if (muted) "取消静音" else "静音", {
                                        muted = !muted; player.volume = if (muted) 0f else volume
                                    })
                                Text("${if (muted) 0 else (volume * 100).toInt()}%")
                            }
                            Slider(value = if (muted) 0f else volume, onValueChange = {
                                volume = it; muted = false; player.volume = it
                            }, valueRange = 0f..1f, modifier = Modifier.semantics { contentDescription = "播放音量" })
                        }
                        "speed" -> listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).forEach { rate ->
                            Row(Modifier.fillMaxWidth().clickable {
                                speed = rate; player.setPlaybackSpeed(rate); panel = null; touch()
                            }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(speed == rate, onClick = {
                                    speed = rate; player.setPlaybackSpeed(rate); panel = null; touch()
                                })
                                Text("${rate}x")
                            }
                        }
                        "parts" -> (0 until pageCount.coerceAtLeast(items.size)).forEach { index ->
                            Row(Modifier.fillMaxWidth().clickable { onSelectPage(index); panel = null; touch() },
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(pageIndex == index, onClick = { onSelectPage(index); panel = null; touch() })
                                Text("第 ${index + 1} 段")
                            }
                        }
                        else -> Text(videoProperties.ifBlank { "视频规格暂不可用" } + "\n分段 ${pageIndex + 1}/${pageCount.coerceAtLeast(1)}")
                    }
                }
            }, confirmButton = { TextButton(onClick = { panel = null; touch() }) { Text("完成") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit,
                       enabled: Boolean = true, tint: Color = Color.White) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = .35f))
        }
    }
}

private fun timeLabel(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    return if (totalSeconds >= 3_600L) {
        "%02d:%02d:%02d".format(totalSeconds / 3_600L, totalSeconds / 60L % 60L, totalSeconds % 60L)
    } else {
        "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }
}

private fun seekGlobal(player: Player, durations: List<Long>, targetMs: Long) {
    if (durations.isEmpty()) {
        player.seekTo(targetMs.coerceAtLeast(0L))
        return
    }
    var remaining = targetMs.coerceAtLeast(0L)
    durations.forEachIndexed { index, segmentDuration ->
        val isLast = index == durations.lastIndex
        if (isLast || remaining <= segmentDuration) {
            player.seekTo(index, remaining.coerceIn(0L, segmentDuration.coerceAtLeast(0L)))
            return
        }
        remaining -= segmentDuration
    }
}
