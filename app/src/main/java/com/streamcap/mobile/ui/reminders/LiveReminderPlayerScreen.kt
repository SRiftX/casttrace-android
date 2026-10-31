package com.streamcap.mobile.ui.reminders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.os.SystemClock
import android.view.LayoutInflater
import com.streamcap.mobile.R
import com.streamcap.mobile.danmaku.DanmakuEvent
import com.streamcap.mobile.danmaku.DanmakuPresentation
import com.streamcap.mobile.data.preferences.DanmakuAppearance
import com.streamcap.mobile.ui.player.ScrollingDanmakuOverlay
import com.streamcap.mobile.domain.model.LiveReminder
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

private val PlayerInk = Color.Black
private val PlayerPanel = Color(0xFF202020)
private val PlayerElevatedPanel = Color(0xFF272727)
private val PlayerAccent = Color(0xFFB4DECD)
private val PlayerRed = Color(0xFFFF8C83)

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
fun LiveReminderPlayerScreen(
    reminderId: Long,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: LiveReminderPlayerViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val danmakuAppearance by viewModel.danmakuAppearance.collectAsStateWithLifecycle(initialValue = DanmakuAppearance())
    var retry by remember(reminderId) { mutableIntStateOf(0) }
    val reminders = state.reminder
    val sources = state.sources
    val sourceIndex = state.sourceIndex
    val loading = state.loading
    val errorMessage = state.errorMessage

    LaunchedEffect(reminderId, reminders?.normalizedUrl, retry) { viewModel.resolveSources() }
    LaunchedEffect(reminders?.id) { viewModel.startLiveDanmaku() }
    DisposableEffect(viewModel) { onDispose { viewModel.stopLiveDanmaku() } }

    val source = sources.getOrNull(sourceIndex)
    var playbackError by remember(source) { mutableStateOf<String?>(null) }
    var zoomScale by rememberSaveable(reminderId) { mutableFloatStateOf(1f) }
    var danmakuVisible by rememberSaveable(reminderId) { mutableStateOf(true) }
    var chatVisible by rememberSaveable(reminderId) { mutableStateOf(false) }
    val player = remember(source) {
        source?.let { selected ->
            val http = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(selected.headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
            val guarded = ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http)) { spec ->
                if (spec.uri.scheme in setOf("http", "https")) {
                    viewModel.requireNetworkAllowed()
                }
                spec
            }
            ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                .setMediaSourceFactory(DefaultMediaSourceFactory(guarded))
                .build()
        }
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "播放失败：${error.errorCodeName}"
                player.pause()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, source) {
        if (player != null && source != null) {
            playbackError = null
            player.setMediaItem(
                MediaItem.Builder().setUri(source.url).setMimeType(source.mimeType()).build(),
            )
            player.prepare()
            player.playWhenReady = true
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        if (player == null) return@DisposableEffect onDispose { }
        var resumePlayback = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resumePlayback = player.playWhenReady
                    player.pause()
                }
                Lifecycle.Event.ON_START -> if (resumePlayback) {
                    player.play()
                    resumePlayback = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(onBack = onBack)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PlayerAccent,
            surface = PlayerPanel,
            surfaceContainer = PlayerElevatedPanel,
            background = PlayerInk,
            error = PlayerRed,
        ),
        typography = MaterialTheme.typography,
    ) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(contentPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("直播间", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    reminders?.anchorName ?: "主播已移除",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(color = MaterialTheme.colorScheme.error.copy(alpha = .18f), shape = RoundedCornerShape(5.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.width(7.dp).height(7.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.error))
                    Text("LIVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(onClick = { retry++ }, enabled = !loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重新解析", tint = MaterialTheme.colorScheme.primary)
            }
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            val landscape = maxWidth > maxHeight
            if (landscape) {
                val videoHeight = (maxHeight - 24.dp).coerceAtLeast(180.dp)
                val videoWidth = minOf(videoHeight * 16f / 9f, maxWidth * .72f)
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    LiveVideoFrame(
                        modifier = Modifier.width(videoWidth).aspectRatio(16f / 9f),
                        player = player,
                        loading = loading,
                        error = playbackError ?: errorMessage,
                        retryEnabled = !loading,
                        onRetry = { retry++ },
                        danmaku = reminders?.let { state.liveDanmaku } ?: emptyList(),
                        danmakuVisible = danmakuVisible,
                        danmakuAppearance = danmakuAppearance,
                        zoomScale = zoomScale,
                        onZoomChange = { zoomScale = it.coerceIn(1f, 3f) },
                        onChangeSource = if (sources.size > 1) {
                            { viewModel.nextSource() }
                        } else null,
                    )
                    Spacer(Modifier.width(26.dp))
                    Column(Modifier.widthIn(min = 190.dp, max = 270.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiveViewerControls(
                        sources = sources,
                        sourceIndex = sourceIndex,
                        zoomScale = zoomScale,
                        danmakuVisible = danmakuVisible,
                        chatVisible = chatVisible,
                        danmakuStatus = state.danmakuStatus,
                        onSelectSource = viewModel::selectSource,
                        onZoomChange = { zoomScale = it.coerceIn(1f, 3f) },
                        onToggleDanmaku = { danmakuVisible = !danmakuVisible },
                        onToggleChat = { chatVisible = !chatVisible },
                    )
                    if (chatVisible) LiveChatPanel(state.liveDanmaku, danmakuAppearance, Modifier.fillMaxWidth())
                    LiveRoomDetails(
                        modifier = Modifier.fillMaxWidth(),
                        reminder = reminders,
                        source = source,
                        sourceIndex = sourceIndex,
                        sourceCount = sources.size,
                        error = playbackError ?: errorMessage,
                        onChangeSource = if (sources.size > 1) {
                            { viewModel.nextSource() }
                        } else null,
                    )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiveVideoFrame(
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        player = player,
                        loading = loading,
                        error = playbackError ?: errorMessage,
                        retryEnabled = !loading,
                        onRetry = { retry++ },
                        danmaku = reminders?.let { state.liveDanmaku } ?: emptyList(),
                        danmakuVisible = danmakuVisible,
                        danmakuAppearance = danmakuAppearance,
                        zoomScale = zoomScale,
                        onZoomChange = { zoomScale = it.coerceIn(1f, 3f) },
                        onChangeSource = if (sources.size > 1) {
                            { viewModel.nextSource() }
                        } else null,
                    )
                    LiveViewerControls(
                        sources = sources,
                        sourceIndex = sourceIndex,
                        zoomScale = zoomScale,
                        danmakuVisible = danmakuVisible,
                        chatVisible = chatVisible,
                        danmakuStatus = state.danmakuStatus,
                        onSelectSource = viewModel::selectSource,
                        onZoomChange = { zoomScale = it.coerceIn(1f, 3f) },
                        onToggleDanmaku = { danmakuVisible = !danmakuVisible },
                        onToggleChat = { chatVisible = !chatVisible },
                    )
                    if (chatVisible) LiveChatPanel(state.liveDanmaku, danmakuAppearance, Modifier.fillMaxWidth())
                    LiveRoomDetails(
                        modifier = Modifier.fillMaxWidth(),
                        reminder = reminders,
                        source = source,
                        sourceIndex = sourceIndex,
                        sourceCount = sources.size,
                        error = playbackError ?: errorMessage,
                        onChangeSource = if (sources.size > 1) {
                            { viewModel.nextSource() }
                        } else null,
                    )
                }
            }
        }
    }
    }
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun LiveVideoFrame(
    modifier: Modifier,
    player: ExoPlayer?,
    loading: Boolean,
    error: String?,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
    danmaku: List<DanmakuEvent>,
    danmakuVisible: Boolean,
    danmakuAppearance: DanmakuAppearance,
    zoomScale: Float,
    onZoomChange: (Float) -> Unit,
    onChangeSource: (() -> Unit)?,
) {
    val latestZoomScale by rememberUpdatedState(zoomScale)
    val latestOnZoomChange by rememberUpdatedState(onZoomChange)
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black).pointerInput(Unit) {
        detectTransformGestures { _, _, zoom, _ -> latestOnZoomChange(latestZoomScale * zoom) }
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer(scaleX = zoomScale, scaleY = zoomScale)) {
        if (player != null) {
            AndroidView(
                factory = { viewContext ->
                    (LayoutInflater.from(viewContext).inflate(R.layout.live_reminder_player_view, null, false) as PlayerView).apply {
                        this.player = player
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (danmakuVisible && danmaku.isNotEmpty()) {
            ScrollingDanmakuOverlay(
                events = danmaku.map { it.copy(mediaTimeMs = it.receivedElapsedMs) },
                videoAspectRatio = 16f / 9f,
                mediaPosition = SystemClock::elapsedRealtime,
                appearance = danmakuAppearance,
                modifier = Modifier.fillMaxSize(),
            )
        }
        }
        if (loading || error != null) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = .82f)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (loading) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                else {
                    Text(error.orEmpty(), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry, enabled = retryEnabled) { Text("重新解析") }
                        if (onChangeSource != null) TextButton(onClick = onChangeSource) { Text("换线路", color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveViewerControls(
    sources: List<StreamSource>,
    sourceIndex: Int,
    zoomScale: Float,
    danmakuVisible: Boolean,
    chatVisible: Boolean,
    danmakuStatus: String,
    onSelectSource: (Int) -> Unit,
    onZoomChange: (Float) -> Unit,
    onToggleDanmaku: () -> Unit,
    onToggleChat: () -> Unit,
) {
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box {
                TextButton(onClick = { qualityMenuExpanded = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.HighQuality, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(sources.getOrNull(sourceIndex)?.quality ?: "画质", maxLines = 1)
                }
                DropdownMenu(expanded = qualityMenuExpanded, onDismissRequest = { qualityMenuExpanded = false }) {
                    sources.forEachIndexed { index, source ->
                        DropdownMenuItem(
                            text = { Text("${source.quality} · ${source.type.name}") },
                            onClick = { qualityMenuExpanded = false; onSelectSource(index) },
                        )
                    }
                }
            }
            IconButton(onClick = { onZoomChange((zoomScale - .25f).coerceAtLeast(1f)) }, enabled = zoomScale > 1f) {
                Icon(Icons.Outlined.ZoomOut, contentDescription = "缩小画面")
            }
            Text("${(zoomScale * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Color.White)
            IconButton(onClick = { onZoomChange((zoomScale + .25f).coerceAtMost(3f)) }, enabled = zoomScale < 3f) {
                Icon(Icons.Outlined.ZoomIn, contentDescription = "放大画面")
            }
            IconButton(onClick = { onZoomChange(1f) }, enabled = zoomScale != 1f) {
                Icon(Icons.Outlined.FitScreen, contentDescription = "适应画面")
            }
            IconButton(onClick = onToggleDanmaku) {
                Icon(
                    Icons.Outlined.ClosedCaption,
                    contentDescription = if (danmakuVisible) "关闭弹幕" else "显示弹幕",
                    tint = if (danmakuVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleChat) {
                Icon(
                    Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = if (chatVisible) "隐藏聊天" else "显示聊天",
                    tint = if (chatVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(danmakuStatus, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun LiveChatPanel(events: List<DanmakuEvent>, appearance: DanmakuAppearance, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(176.dp),
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF262626),
    ) {
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.CenterStart) {
                Text("正在等待直播间聊天消息", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(events.takeLast(60).asReversed()) { event ->
                    val text = DanmakuPresentation.text(event, appearance) ?: return@items
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(event.userName.orEmpty().ifBlank { "观众" },
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, modifier = Modifier.widthIn(max = 90.dp))
                        Text(text.substringAfter("：", text), color = Color.White,
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveRoomDetails(
    modifier: Modifier,
    reminder: LiveReminder?,
    source: StreamSource?,
    sourceIndex: Int,
    sourceCount: Int,
    error: String?,
    onChangeSource: (() -> Unit)?,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(reminder?.platform ?: "开播提醒", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(reminder?.anchorName ?: "主播已移除", style = MaterialTheme.typography.titleLarge,
            color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        reminder?.roomTitle?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(6.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("播放状态", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        error != null -> "线路异常"
                        source != null -> "正在播放"
                        else -> "连接中"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
                if (source != null) Text(
                    "${source.quality} · ${source.type.name} · 线路 ${sourceIndex + 1}/$sourceCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onChangeSource != null) {
            TextButton(onClick = onChangeSource, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("换线路", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun StreamSource.mimeType(): String? = when (type) {
    StreamType.HLS -> MimeTypes.APPLICATION_M3U8
    StreamType.FLV -> MimeTypes.VIDEO_FLV
    StreamType.DASH -> MimeTypes.APPLICATION_MPD
    StreamType.UNKNOWN -> null
}
