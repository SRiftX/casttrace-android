package com.streamcap.mobile.ui.reminders

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
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
import com.streamcap.mobile.platform.PlatformCatalog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.os.SystemClock
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.LayoutInflater
import com.streamcap.mobile.R
import com.streamcap.mobile.danmaku.DanmakuEvent
import com.streamcap.mobile.danmaku.DanmakuPresentation
import com.streamcap.mobile.data.preferences.DanmakuAppearance
import com.streamcap.mobile.ui.player.ScrollingDanmakuOverlay
import com.streamcap.mobile.ui.player.DanmakuSettingsPanel
import com.streamcap.mobile.domain.model.LiveReminder
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

private val PlayerInk = Color.Black
private val PlayerPanel = Color(0xFF202020)
private val PlayerElevatedPanel = Color(0xFF272727)
private val PlayerAccent = Color(0xFFB4DECD)
private val PlayerRed = Color(0xFFFF8C83)
private const val PREVIEW_MIN_ZOOM_SCALE = 0.5f
private const val PREVIEW_MAX_ZOOM_SCALE = 3f

private enum class PlayerScaleMode(val label: String) {
    FIT("适应"),
    FILL("拉伸"),
    CROP("裁切"),
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
fun LiveReminderPlayerScreen(
    reminderId: Long,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val viewModel: LiveReminderPlayerViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val danmakuAppearance by viewModel.danmakuAppearance.collectAsStateWithLifecycle(initialValue = DanmakuAppearance())
    var retry by remember(reminderId) { mutableIntStateOf(0) }
    val reminders = state.reminder
    val sources = state.sources
    val sourceIndex = state.sourceIndex
    val loading = state.loading
    val errorMessage = state.errorMessage
    val selectedQuality = state.quality
    val qualityOptions = remember(reminders?.platform, sources, selectedQuality) {
        previewQualityOptions(reminders?.platform.orEmpty(), sources, selectedQuality)
    }
    var fullscreen by rememberSaveable(reminderId) { mutableStateOf(false) }
    var controlsVisible by rememberSaveable(reminderId) { mutableStateOf(true) }
    var controlsInteraction by remember(reminderId) { mutableIntStateOf(0) }
    var scaleMode by rememberSaveable(reminderId) { mutableStateOf(PlayerScaleMode.FIT) }
    val originalOrientation by rememberSaveable(reminderId) {
        mutableIntStateOf(activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    LaunchedEffect(reminderId, reminders?.normalizedUrl, selectedQuality, retry) {
        if (reminders != null) viewModel.resolveSources()
    }
    LaunchedEffect(reminders?.id, reminders?.normalizedUrl) { viewModel.startLiveDanmaku() }
    DisposableEffect(viewModel) { onDispose { viewModel.stopLiveDanmaku() } }
    LaunchedEffect(fullscreen, controlsVisible, controlsInteraction) {
        if (fullscreen && controlsVisible) {
            kotlinx.coroutines.delay(5_000)
            controlsVisible = false
        }
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.let {
                WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars())
                if (!it.isChangingConfigurations) it.requestedOrientation = originalOrientation
            }
        }
    }
    DisposableEffect(activity, fullscreen) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity?.requestedOrientation = originalOrientation
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }

    val source = sources.getOrNull(sourceIndex)
    var playbackError by remember(source) { mutableStateOf<String?>(null) }
    var zoomScale by rememberSaveable(reminderId) { mutableFloatStateOf(1f) }
    var danmakuVisible by rememberSaveable(reminderId) { mutableStateOf(true) }
    var chatVisible by rememberSaveable(reminderId) { mutableStateOf(false) }
    var showDanmakuSettings by rememberSaveable(reminderId) { mutableStateOf(false) }
    val trackSelector = remember(context) { DefaultTrackSelector(context) }
    LaunchedEffect(trackSelector, selectedQuality) {
        val (maxWidth, maxHeight) = previewMaxVideoSize(selectedQuality)
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setMaxVideoSize(maxWidth, maxHeight)
            .build()
    }
    val player = remember(source, trackSelector) {
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
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(DefaultMediaSourceFactory(guarded))
                .build()
        }
    }

    val revealControls: () -> Unit = {
        controlsVisible = true
        controlsInteraction = controlsInteraction + 1
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val switched = viewModel.onSourcePlaybackFailed(sourceIndex)
                playbackError = if (!switched && sources.size > 1) {
                    "此画质下的线路均无法播放，请降低画质或重新解析"
                } else "播放失败：${error.errorCodeName}"
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

    BackHandler {
        if (fullscreen) {
            fullscreen = false
            controlsVisible = true
        } else onBack()
    }
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
    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            LiveVideoFrame(
                modifier = Modifier.fillMaxSize(),
                player = player,
                loading = loading,
                error = playbackError ?: errorMessage,
                retryEnabled = !loading,
                onRetry = { retry++ },
                danmaku = state.liveDanmaku,
                danmakuVisible = danmakuVisible,
                danmakuAppearance = danmakuAppearance,
                zoomScale = zoomScale,
                onZoomChange = { zoomScale = clampPreviewZoom(it) },
                scaleMode = scaleMode,
                onChangeSource = if (sources.size > 1) viewModel::nextSource else null,
                fullscreen = true,
                onTap = { if (controlsVisible) controlsVisible = false else revealControls() },
            )
            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .78f))
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { revealControls(); fullscreen = false }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "退出全屏", tint = Color.White)
                        }
                        Text(reminders?.anchorName ?: "直播间", Modifier.weight(1f), color = Color.White,
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { revealControls(); retry++ }, enabled = !loading) {
                            Icon(Icons.Outlined.Refresh, "重新解析", tint = Color.White)
                        }
                    }
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (chatVisible) LiveChatPanel(state.liveDanmaku, danmakuAppearance,
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(132.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            color = PlayerPanel.copy(alpha = .9f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            LiveViewerControls(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                platform = reminders?.platform.orEmpty(),
                                qualityOptions = qualityOptions,
                                selectedQuality = selectedQuality,
                                fullscreen = true,
                                sources = sources,
                                sourceIndex = sourceIndex,
                                zoomScale = zoomScale,
                                scaleMode = scaleMode,
                                danmakuVisible = danmakuVisible,
                                chatVisible = chatVisible,
                                danmakuStatus = state.danmakuStatus,
                                onSelectSource = viewModel::selectSource,
                                onSelectQuality = viewModel::selectQuality,
                                onSelectScaleMode = { scaleMode = it },
                                onZoomChange = { zoomScale = clampPreviewZoom(it) },
                                onToggleDanmaku = { danmakuVisible = !danmakuVisible },
                                onToggleChat = { chatVisible = !chatVisible },
                                onReconnectDanmaku = viewModel::startLiveDanmaku,
                                onOpenDanmakuSettings = { showDanmakuSettings = true },
                                onToggleFullscreen = { fullscreen = false },
                                onInteraction = revealControls,
                            )
                        }
                    }
                }
            }
        }
    } else Column(
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
                        onZoomChange = { zoomScale = clampPreviewZoom(it) },
                        scaleMode = scaleMode,
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
                        scaleMode = scaleMode,
                        danmakuVisible = danmakuVisible,
                        chatVisible = chatVisible,
                        danmakuStatus = state.danmakuStatus,
                        onSelectSource = viewModel::selectSource,
                        onZoomChange = { zoomScale = clampPreviewZoom(it) },
                        onToggleDanmaku = { danmakuVisible = !danmakuVisible },
                        onToggleChat = { chatVisible = !chatVisible },
                        onReconnectDanmaku = viewModel::startLiveDanmaku,
                        onOpenDanmakuSettings = { showDanmakuSettings = true },
                        platform = reminders?.platform.orEmpty(),
                        qualityOptions = qualityOptions,
                        selectedQuality = selectedQuality,
                        onSelectQuality = viewModel::selectQuality,
                        onSelectScaleMode = { scaleMode = it },
                        fullscreen = false,
                        onToggleFullscreen = { fullscreen = true },
                        modifier = Modifier,
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
                        onZoomChange = { zoomScale = clampPreviewZoom(it) },
                        scaleMode = scaleMode,
                        onChangeSource = if (sources.size > 1) {
                            { viewModel.nextSource() }
                        } else null,
                    )
                    LiveViewerControls(
                        sources = sources,
                        sourceIndex = sourceIndex,
                        zoomScale = zoomScale,
                        scaleMode = scaleMode,
                        danmakuVisible = danmakuVisible,
                        chatVisible = chatVisible,
                        danmakuStatus = state.danmakuStatus,
                        onSelectSource = viewModel::selectSource,
                        onZoomChange = { zoomScale = clampPreviewZoom(it) },
                        onToggleDanmaku = { danmakuVisible = !danmakuVisible },
                        onToggleChat = { chatVisible = !chatVisible },
                        onReconnectDanmaku = viewModel::startLiveDanmaku,
                        onOpenDanmakuSettings = { showDanmakuSettings = true },
                        platform = reminders?.platform.orEmpty(),
                        qualityOptions = qualityOptions,
                        selectedQuality = selectedQuality,
                        onSelectQuality = viewModel::selectQuality,
                        onSelectScaleMode = { scaleMode = it },
                        fullscreen = false,
                        onToggleFullscreen = { fullscreen = true },
                        modifier = Modifier,
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
    if (showDanmakuSettings) {
        DanmakuSettingsPanel(
            appearance = danmakuAppearance,
            onDismiss = { showDanmakuSettings = false },
            onChange = viewModel::setDanmakuAppearance,
        )
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
    scaleMode: PlayerScaleMode,
    onChangeSource: (() -> Unit)?,
    fullscreen: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    val latestZoomScale by rememberUpdatedState(zoomScale)
    val latestOnZoomChange by rememberUpdatedState(onZoomChange)
    Box(modifier.then(if (fullscreen) Modifier else Modifier.clip(RoundedCornerShape(4.dp)))
        .background(Color.Black).pointerInput(onTap) {
            if (onTap != null) detectTapGestures(onTap = { onTap() })
        }.pointerInput(Unit) {
        detectTransformGestures { _, _, zoom, _ -> latestOnZoomChange(clampPreviewZoom(latestZoomScale * zoom)) }
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer(scaleX = zoomScale, scaleY = zoomScale)) {
        if (player != null) {
            AndroidView(
                factory = { viewContext ->
                    (LayoutInflater.from(viewContext).inflate(R.layout.live_reminder_player_view, null, false) as PlayerView).apply {
                        this.player = player
                        keepScreenOn = true
                        resizeMode = scaleMode.toResizeMode()
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = {
                    it.player = player
                    it.resizeMode = scaleMode.toResizeMode()
                },
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
    modifier: Modifier,
    platform: String,
    qualityOptions: List<String>,
    selectedQuality: String,
    fullscreen: Boolean,
    sources: List<StreamSource>,
    sourceIndex: Int,
    zoomScale: Float,
    scaleMode: PlayerScaleMode,
    danmakuVisible: Boolean,
    chatVisible: Boolean,
    danmakuStatus: String,
    onSelectSource: (Int) -> Unit,
    onSelectQuality: (String) -> Unit,
    onSelectScaleMode: (PlayerScaleMode) -> Unit,
    onZoomChange: (Float) -> Unit,
    onToggleDanmaku: () -> Unit,
    onToggleChat: () -> Unit,
    onReconnectDanmaku: () -> Unit,
    onOpenDanmakuSettings: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onInteraction: () -> Unit = {},
) {
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var scaleMenuExpanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
            Box {
                TextButton(onClick = { onInteraction(); qualityMenuExpanded = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.HighQuality, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(selectedQuality, maxLines = 1)
                }
                DropdownMenu(expanded = qualityMenuExpanded, onDismissRequest = { qualityMenuExpanded = false }) {
                    qualityOptions.forEach { quality ->
                        DropdownMenuItem(
                            text = { Text(quality) },
                            onClick = { onInteraction(); qualityMenuExpanded = false; onSelectQuality(quality) },
                        )
                    }
                }
            }
            if (sources.size > 1) Box {
                TextButton(onClick = { onInteraction(); sourceMenuExpanded = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("线路 ${sourceIndex + 1}", maxLines = 1)
                }
                DropdownMenu(expanded = sourceMenuExpanded, onDismissRequest = { sourceMenuExpanded = false }) {
                    sources.forEachIndexed { index, source ->
                        DropdownMenuItem(
                            text = { Text(previewRouteLabel(source, index)) },
                            onClick = { onInteraction(); sourceMenuExpanded = false; onSelectSource(index) },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { onInteraction(); scaleMenuExpanded = true }) {
                    Icon(Icons.Outlined.AspectRatio, contentDescription = "画面显示模式")
                }
                DropdownMenu(expanded = scaleMenuExpanded, onDismissRequest = { scaleMenuExpanded = false }) {
                    PlayerScaleMode.values().forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            trailingIcon = { if (mode == scaleMode) Icon(Icons.Outlined.Check, contentDescription = null) },
                            onClick = { onInteraction(); scaleMenuExpanded = false; onSelectScaleMode(mode) },
                        )
                    }
                }
            }
            IconButton(
                onClick = { onInteraction(); onZoomChange(clampPreviewZoom(zoomScale - .25f)) },
                enabled = zoomScale > PREVIEW_MIN_ZOOM_SCALE,
            ) {
                Icon(Icons.Outlined.ZoomOut, contentDescription = "缩小画面")
            }
            Text("${(zoomScale * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Color.White)
            IconButton(
                onClick = { onInteraction(); onZoomChange(clampPreviewZoom(zoomScale + .25f)) },
                enabled = zoomScale < PREVIEW_MAX_ZOOM_SCALE,
            ) {
                Icon(Icons.Outlined.ZoomIn, contentDescription = "放大画面")
            }
            IconButton(onClick = { onInteraction(); onZoomChange(1f) }, enabled = zoomScale != 1f) {
                Icon(Icons.Outlined.FitScreen, contentDescription = "适应画面")
            }
            IconButton(onClick = { onInteraction(); onToggleDanmaku() }) {
                Icon(
                    Icons.Outlined.ClosedCaption,
                    contentDescription = if (danmakuVisible) "关闭弹幕" else "显示弹幕",
                    tint = if (danmakuVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onInteraction(); onToggleChat() }) {
                Icon(
                    Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = if (chatVisible) "隐藏聊天" else "显示聊天",
                    tint = if (chatVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onInteraction(); onReconnectDanmaku() }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重新连接弹幕")
            }
            IconButton(onClick = { onInteraction(); onOpenDanmakuSettings() }) {
                Icon(Icons.Outlined.Tune, contentDescription = "弹幕设置")
            }
            }
            IconButton(onClick = { onInteraction(); onToggleFullscreen() }) {
                Icon(
                    if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    contentDescription = if (fullscreen) "退出全屏" else "全屏播放",
                )
            }
        }
        Text("$platform · $danmakuStatus", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun previewQualityOptions(
    platform: String,
    sources: List<StreamSource>,
    selectedQuality: String? = null,
): List<String> {
    val base = if (PlatformCatalog.find(platform) != null) {
        listOf("原画", "蓝光", "超清", "高清", "标清", "流畅")
    } else emptyList()
    val returned = sources.map(StreamSource::quality).filter(String::isNotBlank).distinct()
    return (base + returned + listOfNotNull(selectedQuality?.takeIf(String::isNotBlank)) + "原画").distinct()
}

internal fun previewMaxVideoSize(quality: String): Pair<Int, Int> = when (quality.lowercase()) {
    "蓝光", "bd", "1080p" -> 1920 to 1080
    "超清", "uhd", "720p" -> 1280 to 720
    "高清", "hd", "480p" -> 854 to 480
    "标清", "sd", "360p" -> 640 to 360
    "流畅", "ld", "240p", "音频优先" -> 426 to 240
    else -> Int.MAX_VALUE to Int.MAX_VALUE
}

internal fun previewRouteLabel(source: StreamSource, index: Int): String {
    val host = runCatching { java.net.URI(source.url).host?.removePrefix("www.") }
        .getOrNull()?.takeIf(String::isNotBlank)
    return buildString {
        append("线路 ${index + 1} · ${source.type.name}")
        host?.let { append(" · "); append(it) }
    }
}

internal fun clampPreviewZoom(scale: Float): Float = scale.coerceIn(PREVIEW_MIN_ZOOM_SCALE, PREVIEW_MAX_ZOOM_SCALE)

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerScaleMode.toResizeMode(): Int = when (this) {
    PlayerScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    PlayerScaleMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    PlayerScaleMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.findActivity()
    else -> null
}

internal fun nextPreviewSourceIndex(sourceCount: Int, failedIndexes: Set<Int>, currentIndex: Int): Int? {
    if (sourceCount <= 0 || currentIndex !in 0 until sourceCount) return null
    return (1..sourceCount).asSequence()
        .map { (currentIndex + it) % sourceCount }
        .firstOrNull { it !in failedIndexes }
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
