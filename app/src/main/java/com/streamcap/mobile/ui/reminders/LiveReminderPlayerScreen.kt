package com.streamcap.mobile.ui.reminders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.domain.model.LiveReminder
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val PlayerInk = Color.Black
private val PlayerPanel = Color(0xFF202020)
private val PlayerElevatedPanel = Color(0xFF272727)
private val PlayerAccent = Color(0xFFB4DECD)
private val PlayerRed = Color(0xFFFF8C83)

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
fun LiveReminderPlayerScreen(
    reminderId: Long,
    container: AppContainer,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val reminders by remember(reminderId) {
        container.repository.reminders.map { list -> list.firstOrNull { it.id == reminderId } }
    }.collectAsStateWithLifecycle(null)
    var retry by remember(reminderId) { mutableIntStateOf(0) }
    var sources by remember(reminderId) { mutableStateOf<List<StreamSource>>(emptyList()) }
    var sourceIndex by remember(reminderId) { mutableIntStateOf(0) }
    var loading by remember(reminderId) { mutableStateOf(true) }
    var errorMessage by remember(reminderId) { mutableStateOf<String?>(null) }

    LaunchedEffect(reminderId, reminders?.originalUrl, retry) {
        val reminder = reminders
        if (reminder == null) {
            loading = false
            errorMessage = "这个主播提醒已不存在"
            sources = emptyList()
            return@LaunchedEffect
        }
        loading = true
        errorMessage = null
        sources = emptyList()
        try {
            val resolved = withContext(Dispatchers.IO) {
                container.networkPolicy.requireAllowed()
                container.repository.streams(reminder)
            }
            val playable = resolved.filterNot(StreamSource::requiresFfmpeg)
            if (playable.isEmpty()) {
                error(if (resolved.isEmpty()) "主播目前没有可用直播流" else "此直播线路暂不支持应用内播放")
            }
            val hls = playable.filter { it.type == StreamType.HLS }
            val preferred = if (hls.isNotEmpty()) {
                try {
                    withContext(Dispatchers.IO) { HlsSourceSelector(container.httpClient).select(hls) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    hls.first()
                }
            } else playable.first()
            sources = playable
            sourceIndex = playable.indexOf(preferred).coerceAtLeast(0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            errorMessage = failure.message ?: "直播线路解析失败"
        } finally {
            loading = false
        }
    }

    val source = sources.getOrNull(sourceIndex)
    var playbackError by remember(source) { mutableStateOf<String?>(null) }
    val player = remember(source) {
        source?.let { selected ->
            val http = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(selected.headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
            val guarded = ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http)) { spec ->
                if (spec.uri.scheme in setOf("http", "https")) {
                    kotlinx.coroutines.runBlocking { container.networkPolicy.requireAllowed() }
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
                        onChangeSource = if (sources.size > 1) {
                            { sourceIndex = (sourceIndex + 1) % sources.size }
                        } else null,
                    )
                    Spacer(Modifier.width(26.dp))
                    LiveRoomDetails(
                        modifier = Modifier.widthIn(min = 190.dp, max = 270.dp),
                        reminder = reminders,
                        source = source,
                        sourceIndex = sourceIndex,
                        sourceCount = sources.size,
                        error = playbackError ?: errorMessage,
                        onChangeSource = if (sources.size > 1) {
                            { sourceIndex = (sourceIndex + 1) % sources.size }
                        } else null,
                    )
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    LiveVideoFrame(
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        player = player,
                        loading = loading,
                        error = playbackError ?: errorMessage,
                        retryEnabled = !loading,
                        onRetry = { retry++ },
                        onChangeSource = if (sources.size > 1) {
                            { sourceIndex = (sourceIndex + 1) % sources.size }
                        } else null,
                    )
                    LiveRoomDetails(
                        modifier = Modifier.fillMaxWidth(),
                        reminder = reminders,
                        source = source,
                        sourceIndex = sourceIndex,
                        sourceCount = sources.size,
                        error = playbackError ?: errorMessage,
                        onChangeSource = if (sources.size > 1) {
                            { sourceIndex = (sourceIndex + 1) % sources.size }
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
    onChangeSource: (() -> Unit)?,
) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        if (player != null) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = true
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

private fun StreamSource.mimeType(): String? = when (type) {
    StreamType.HLS -> MimeTypes.APPLICATION_M3U8
    StreamType.FLV -> MimeTypes.VIDEO_FLV
    StreamType.DASH -> MimeTypes.APPLICATION_MPD
    StreamType.UNKNOWN -> null
}
