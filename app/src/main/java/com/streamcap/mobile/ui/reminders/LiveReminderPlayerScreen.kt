package com.streamcap.mobile.ui.reminders

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.ComponentActivity
import com.streamcap.mobile.platform.PlatformCatalog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
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
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
import android.view.LayoutInflater
import android.widget.Toast
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
import kotlin.math.roundToInt
import com.streamcap.mobile.app.MainActivity

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

private enum class LivePlayerSheet {
    QUALITY,
    ROUTE,
    DISPLAY,
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
    val inPictureInPicture = (activity as? MainActivity)?.inPictureInPicture == true
    var controlsVisible by rememberSaveable(reminderId) { mutableStateOf(true) }
    var controlsInteraction by remember(reminderId) { mutableIntStateOf(0) }
    var activePlaybackSheet by remember { mutableStateOf<LivePlayerSheet?>(null) }
    var showDanmakuSettings by rememberSaveable(reminderId) { mutableStateOf(false) }
    var scaleMode by rememberSaveable(reminderId) { mutableStateOf(PlayerScaleMode.FIT) }
    val originalOrientation by rememberSaveable(reminderId) {
        mutableIntStateOf(activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    LaunchedEffect(reminderId, reminders?.normalizedUrl, selectedQuality, retry) {
        if (reminders != null) viewModel.resolveSources()
    }
    LaunchedEffect(reminders?.id, reminders?.normalizedUrl) { viewModel.startLiveDanmaku() }
    DisposableEffect(viewModel) { onDispose { viewModel.stopLiveDanmaku() } }
    LaunchedEffect(fullscreen, controlsVisible, controlsInteraction, inPictureInPicture, activePlaybackSheet, showDanmakuSettings) {
        if (!inPictureInPicture && controlsVisible && activePlaybackSheet == null && !showDanmakuSettings) {
            kotlinx.coroutines.delay(5_000)
            controlsVisible = false
        }
    }
    val pipActivity = activity as? ComponentActivity
    val pictureInPictureSupported = remember(pipActivity) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            pipActivity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true
    }
    val enterPictureInPicture: () -> Unit = {
        if (pictureInPictureSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val entered = runCatching {
                pipActivity?.enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build(),
                ) == true
            }.getOrDefault(false)
            if (!entered) {
                Toast.makeText(context, "无法进入画中画，请在系统设置中允许 CastTrace 使用画中画", Toast.LENGTH_SHORT).show()
            }
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
    DisposableEffect(activity, fullscreen, inPictureInPicture) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen && !inPictureInPicture) {
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
    var actualVideoSize by remember(source) { mutableStateOf<String?>(null) }
    var zoomScale by rememberSaveable(reminderId) { mutableFloatStateOf(1f) }
    var danmakuVisible by rememberSaveable(reminderId) { mutableStateOf(true) }
    var chatVisible by rememberSaveable(reminderId) { mutableStateOf(false) }
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
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                actualVideoSize = previewResolutionLabel(
                    videoSize.width,
                    videoSize.height,
                    videoSize.unappliedRotationDegrees,
                )
            }

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
                    if (pipActivity?.isInPictureInPictureMode != true) {
                        resumePlayback = player.playWhenReady
                        player.pause()
                    }
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
    if (inPictureInPicture) {
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
            onChangeSource = null,
            fullscreen = true,
        )
    } else if (fullscreen) {
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
                            state.danmakuStatus, viewModel::startLiveDanmaku,
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(132.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            color = PlayerPanel.copy(alpha = .9f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            LiveViewerControls(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                platform = reminders?.platform.orEmpty(),
                                selectedQuality = selectedQuality,
                                fullscreen = true,
                                sources = sources,
                                sourceIndex = sourceIndex,
                                scaleMode = scaleMode,
                                danmakuVisible = danmakuVisible,
                                chatVisible = chatVisible,
                                danmakuStatus = state.danmakuStatus,
                                actualResolution = actualVideoSize,
                                onOpenQuality = { activePlaybackSheet = LivePlayerSheet.QUALITY },
                                onOpenRoute = { activePlaybackSheet = LivePlayerSheet.ROUTE },
                                onOpenDisplay = { activePlaybackSheet = LivePlayerSheet.DISPLAY },
                                onToggleDanmaku = { danmakuVisible = !danmakuVisible },
                                onToggleChat = { chatVisible = !chatVisible },
                                onReconnectDanmaku = viewModel::startLiveDanmaku,
                                onOpenDanmakuSettings = { showDanmakuSettings = true },
                                pictureInPictureSupported = pictureInPictureSupported,
                                onEnterPictureInPicture = enterPictureInPicture,
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
                    Box(Modifier.width(videoWidth).aspectRatio(16f / 9f)) {
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
                            onTap = { if (controlsVisible) controlsVisible = false else revealControls() },
                            onDoubleTap = { revealControls(); fullscreen = true },
                        )
                        androidx.compose.animation.AnimatedVisibility(
                            visible = controlsVisible,
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            LiveViewerControls(
                                modifier = Modifier.fillMaxWidth().background(
                                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f))),
                                ),
                                platform = reminders?.platform.orEmpty(),
                                selectedQuality = selectedQuality,
                                fullscreen = false,
                                sources = sources,
                                sourceIndex = sourceIndex,
                                scaleMode = scaleMode,
                                danmakuVisible = danmakuVisible,
                                chatVisible = chatVisible,
                                danmakuStatus = state.danmakuStatus,
                                actualResolution = actualVideoSize,
                                onOpenQuality = { activePlaybackSheet = LivePlayerSheet.QUALITY },
                                onOpenRoute = { activePlaybackSheet = LivePlayerSheet.ROUTE },
                                onOpenDisplay = { activePlaybackSheet = LivePlayerSheet.DISPLAY },
                                onToggleDanmaku = { danmakuVisible = !danmakuVisible },
                                onToggleChat = { chatVisible = !chatVisible },
                                onReconnectDanmaku = viewModel::startLiveDanmaku,
                                onOpenDanmakuSettings = { showDanmakuSettings = true },
                                pictureInPictureSupported = pictureInPictureSupported,
                                onEnterPictureInPicture = enterPictureInPicture,
                                onToggleFullscreen = { fullscreen = true },
                                onInteraction = revealControls,
                            )
                        }
                    }
                    Spacer(Modifier.width(26.dp))
                    Column(Modifier.widthIn(min = 190.dp, max = 270.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (chatVisible) LiveChatPanel(state.liveDanmaku, danmakuAppearance,
                        state.danmakuStatus, viewModel::startLiveDanmaku, Modifier.fillMaxWidth())
                    LiveRoomDetails(
                        modifier = Modifier.fillMaxWidth(),
                        reminder = reminders,
                        source = source,
                        actualResolution = actualVideoSize,
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
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
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
                            onTap = { if (controlsVisible) controlsVisible = false else revealControls() },
                            onDoubleTap = { revealControls(); fullscreen = true },
                        )
                        androidx.compose.animation.AnimatedVisibility(
                            visible = controlsVisible,
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            LiveViewerControls(
                                modifier = Modifier.fillMaxWidth().background(
                                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f))),
                                ),
                                platform = reminders?.platform.orEmpty(),
                                selectedQuality = selectedQuality,
                                fullscreen = false,
                                sources = sources,
                                sourceIndex = sourceIndex,
                                scaleMode = scaleMode,
                                danmakuVisible = danmakuVisible,
                                chatVisible = chatVisible,
                                danmakuStatus = state.danmakuStatus,
                                actualResolution = actualVideoSize,
                                onOpenQuality = { activePlaybackSheet = LivePlayerSheet.QUALITY },
                                onOpenRoute = { activePlaybackSheet = LivePlayerSheet.ROUTE },
                                onOpenDisplay = { activePlaybackSheet = LivePlayerSheet.DISPLAY },
                                onToggleDanmaku = { danmakuVisible = !danmakuVisible },
                                onToggleChat = { chatVisible = !chatVisible },
                                onReconnectDanmaku = viewModel::startLiveDanmaku,
                                onOpenDanmakuSettings = { showDanmakuSettings = true },
                                pictureInPictureSupported = pictureInPictureSupported,
                                onEnterPictureInPicture = enterPictureInPicture,
                                onToggleFullscreen = { fullscreen = true },
                                onInteraction = revealControls,
                            )
                        }
                    }
                    if (chatVisible) LiveChatPanel(state.liveDanmaku, danmakuAppearance,
                        state.danmakuStatus, viewModel::startLiveDanmaku, Modifier.fillMaxWidth())
                    LiveRoomDetails(
                        modifier = Modifier.fillMaxWidth(),
                        reminder = reminders,
                        source = source,
                        actualResolution = actualVideoSize,
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
    activePlaybackSheet?.let { sheet ->
        LivePlaybackSheet(
            sheet = sheet,
            qualityOptions = qualityOptions,
            selectedQuality = selectedQuality,
            sources = sources,
            sourceIndex = sourceIndex,
            zoomScale = zoomScale,
            scaleMode = scaleMode,
            onDismiss = { activePlaybackSheet = null },
            onSelectQuality = { quality -> activePlaybackSheet = null; viewModel.selectQuality(quality) },
            onSelectSource = { index -> activePlaybackSheet = null; viewModel.selectSource(index) },
            onSelectScaleMode = { scaleMode = it },
            onZoomChange = { zoomScale = clampPreviewZoom(it) },
            onResetZoom = { zoomScale = 1f },
        )
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
    onDoubleTap: (() -> Unit)? = null,
) {
    val latestZoomScale by rememberUpdatedState(zoomScale)
    val latestOnZoomChange by rememberUpdatedState(onZoomChange)
    Box(modifier.then(if (fullscreen) Modifier else Modifier.clip(RoundedCornerShape(4.dp)))
        .background(Color.Black).pointerInput(onTap, onDoubleTap) {
            if (onTap != null || onDoubleTap != null) {
                detectTapGestures(
                    onTap = { onTap?.invoke() },
                    onDoubleTap = { onDoubleTap?.invoke() },
                )
            }
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
    selectedQuality: String,
    fullscreen: Boolean,
    sources: List<StreamSource>,
    sourceIndex: Int,
    scaleMode: PlayerScaleMode,
    danmakuVisible: Boolean,
    chatVisible: Boolean,
    danmakuStatus: String,
    actualResolution: String?,
    onOpenQuality: () -> Unit,
    onOpenRoute: () -> Unit,
    onOpenDisplay: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onToggleChat: () -> Unit,
    onReconnectDanmaku: () -> Unit,
    onOpenDanmakuSettings: () -> Unit,
    pictureInPictureSupported: Boolean,
    onEnterPictureInPicture: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onInteraction: () -> Unit = {},
) {
    Column(modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                TextButton(onClick = { onInteraction(); onOpenQuality() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.HighQuality, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(selectedQuality, maxLines = 1)
                }
                if (sources.size > 1) {
                    TextButton(onClick = { onInteraction(); onOpenRoute() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("线路 ${sourceIndex + 1}", maxLines = 1)
                }
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
                IconButton(onClick = { onInteraction(); onOpenDisplay() }) {
                    Icon(Icons.Outlined.AspectRatio, contentDescription = "画面设置，当前${scaleMode.label}")
                }
                IconButton(onClick = { onInteraction(); onReconnectDanmaku() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "重新连接弹幕")
                }
                IconButton(onClick = { onInteraction(); onOpenDanmakuSettings() }) {
                    Icon(Icons.Outlined.Tune, contentDescription = "弹幕设置")
                }
            }
            if (pictureInPictureSupported) {
                IconButton(onClick = { onInteraction(); onEnterPictureInPicture() }) {
                    Icon(Icons.Outlined.PictureInPictureAlt, contentDescription = "缩小为小窗播放")
                }
            }
            IconButton(onClick = { onInteraction(); onToggleFullscreen() }) {
                Icon(
                    if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    contentDescription = if (fullscreen) "退出全屏" else "全屏播放",
                )
            }
        }
        val playbackStatus = listOfNotNull(platform, danmakuStatus, actualResolution).joinToString(" · ")
        Text(playbackStatus, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LivePlaybackSheet(
    sheet: LivePlayerSheet,
    qualityOptions: List<String>,
    selectedQuality: String,
    sources: List<StreamSource>,
    sourceIndex: Int,
    zoomScale: Float,
    scaleMode: PlayerScaleMode,
    onDismiss: () -> Unit,
    onSelectQuality: (String) -> Unit,
    onSelectSource: (Int) -> Unit,
    onSelectScaleMode: (PlayerScaleMode) -> Unit,
    onZoomChange: (Float) -> Unit,
    onResetZoom: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = PlayerPanel) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                when (sheet) {
                    LivePlayerSheet.QUALITY -> "切换清晰度"
                    LivePlayerSheet.ROUTE -> "切换线路"
                    LivePlayerSheet.DISPLAY -> "画面设置"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            when (sheet) {
                LivePlayerSheet.QUALITY -> Column(
                    Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                ) {
                    qualityOptions.forEach { quality ->
                        ListItem(
                            headlineContent = { Text(quality) },
                            trailingContent = {
                                if (quality == selectedQuality) Icon(Icons.Outlined.Check, contentDescription = "已选择")
                            },
                            modifier = Modifier.clickable { onSelectQuality(quality) },
                        )
                    }
                }
                LivePlayerSheet.ROUTE -> Column(
                    Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                ) {
                    sources.forEachIndexed { index, source ->
                        ListItem(
                            headlineContent = { Text("线路 ${index + 1}") },
                            supportingContent = { Text(previewRouteLabel(source, index)) },
                            trailingContent = {
                                if (index == sourceIndex) Icon(Icons.Outlined.Check, contentDescription = "已选择")
                            },
                            modifier = Modifier.clickable { onSelectSource(index) },
                        )
                    }
                }
                LivePlayerSheet.DISPLAY -> Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PlayerScaleMode.values().forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelectScaleMode(mode) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == scaleMode, onClick = { onSelectScaleMode(mode) })
                            Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("缩放 ${(zoomScale * 100).roundToInt()}%", Modifier.weight(1f))
                        TextButton(onClick = onResetZoom, enabled = zoomScale != 1f) {
                            Icon(Icons.Outlined.FitScreen, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("重置")
                        }
                    }
                    Slider(
                        value = zoomScale,
                        onValueChange = onZoomChange,
                        valueRange = PREVIEW_MIN_ZOOM_SCALE..PREVIEW_MAX_ZOOM_SCALE,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
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

internal fun previewResolutionLabel(width: Int, height: Int, rotationDegrees: Int = 0): String? {
    if (width <= 0 || height <= 0) return null
    val rotation = ((rotationDegrees % 360) + 360) % 360
    val (displayWidth, displayHeight) = if (rotation == 90 || rotation == 270) {
        height to width
    } else {
        width to height
    }
    return "$displayWidth × $displayHeight"
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
private fun LiveChatPanel(
    events: List<DanmakuEvent>,
    appearance: DanmakuAppearance,
    status: String,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(176.dp),
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF262626),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(status, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onReconnect, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.width(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重连", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (events.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    Text("暂时没有收到直播间弹幕", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp),
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
}

@Composable
private fun LiveRoomDetails(
    modifier: Modifier,
    reminder: LiveReminder?,
    source: StreamSource?,
    actualResolution: String?,
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
                actualResolution?.let {
                    Text("实际分辨率 $it", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
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
