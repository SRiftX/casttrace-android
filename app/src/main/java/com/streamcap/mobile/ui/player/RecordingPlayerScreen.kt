package com.streamcap.mobile.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.domain.model.*
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingPlayerScreen(recordingId: Long, container: AppContainer, onBack: () -> Unit) {
    val recording by container.repository.observeRecording(recordingId).collectAsStateWithLifecycle(null)
    Scaffold(topBar = {
        TopAppBar(title = { Text(recording?.roomName ?: "播放") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        })
    }) { padding ->
        recording?.let { Playback(it, container, padding) }
    }
}

@AndroidXOptIn(markerClass = [UnstableApi::class])
@Composable
private fun Playback(recording: RecordingSession, container: AppContainer, padding: PaddingValues) {
    val context = LocalContext.current
    var live by remember(recording.id) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var headers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var liveFailures by remember(recording.id, live) { mutableIntStateOf(0) }
    LaunchedEffect(error, live) {
        if (live && error != null && liveFailures < 3) {
            delay(1500)
            liveFailures++
            retry++
        }
    }
    val resolver = remember(recording.id) { RecordingPlaybackSourceResolver(context) }
    DisposableEffect(resolver) { onDispose { resolver.close() } }
    LaunchedEffect(recording.id, live, recording.outputUri, recording.outputUris, retry) {
        loading = true
        error = null
        items = emptyList()
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
                val uris = withContext(Dispatchers.IO) {
                    resolver.resolve(recording.outputUri, recording.outputUris,
                        recording.temporaryPath, recording.containerFormat)
                }
                check(uris.isNotEmpty()) { "没有可读取的媒体文件，录制可能尚未保存" }
                headers = emptyMap()
                items = uris.map { uri ->
                    val rawName = uri.lastPathSegment.orEmpty().lowercase()
                    val mimeType = when {
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
    Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !live, onClick = { live = false }, label = { Text("已录内容") })
            FilterChip(selected = live, onClick = { live = true }, label = { Text("直播预览") })
        }
        if (loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (items.isNotEmpty()) {
            key(items, retry) {
                VideoSurface(items, headers, live, Modifier.weight(1f).fillMaxWidth(),
                    onReady = { liveFailures = 0 }) { error = it }
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2)
            IconButton(onClick = { retry++ }) { Icon(Icons.Outlined.Refresh, "重试播放") }
        }
    }
}

@AndroidXOptIn(markerClass = [UnstableApi::class])
@Composable
private fun VideoSurface(items: List<MediaItem>, headers: Map<String, String>, live: Boolean,
                         modifier: Modifier, onReady: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        onDispose {
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // Recreate the data source when a live retry returns different request headers.
    val player = remember(headers) {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build()
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var currentWindow by remember { mutableIntStateOf(0) }
    var timelineDurations by remember { mutableStateOf<List<Long>>(emptyList()) }
    var playing by remember { mutableStateOf(false) }
    var seekable by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var volume by remember { mutableFloatStateOf(1f) }
    var speedMenu by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { onReady() }
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
        onDispose { player.removeListener(listener); player.release() }
    }
    // Items can change while this composable stays in the slot (for example after
    // a failed preview retry), so loading them belongs in an effect keyed by items.
    LaunchedEffect(player, items) {
        player.setMediaItems(items, /* resetPosition = */ true)
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(player) {
        while (isActive) {
            val timeline = player.currentTimeline
            val windows = if (timeline.isEmpty) emptyList() else buildList(timeline.windowCount) {
                val window = Timeline.Window()
                for (index in 0 until timeline.windowCount) {
                    timeline.getWindow(index, window)
                    add(window.durationUs.takeIf { it != C.TIME_UNSET }?.div(1_000L)?.coerceAtLeast(0L) ?: 0L)
                }
            }
            timelineDurations = windows
            currentWindow = player.currentMediaItemIndex.coerceAtLeast(0)
            duration = windows.sum().takeIf { it > 0L } ?: player.duration.takeIf { it > 0 } ?: 0L
            val beforeCurrent = windows.take(currentWindow).sum()
            position = (beforeCurrent + player.currentPosition).coerceAtLeast(0L).coerceAtMost(duration)
            playing = player.isPlaying
            seekable = player.isCurrentMediaItemSeekable
            delay(250)
        }
    }
    Column(modifier) {
        AndroidView(factory = { PlayerView(it).apply {
            this.player = player
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        } }, update = { it.player = player }, modifier = Modifier.weight(1f).fillMaxWidth())
        Slider(value = dragging ?: position.toFloat().coerceAtMost(duration.toFloat()),
            onValueChange = { dragging = it }, onValueChangeFinished = {
                dragging?.let { target -> seekGlobal(player, timelineDurations, target.toLong()) }
                dragging = null
            }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = !live && seekable && duration > 0)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.VolumeDown, contentDescription = "音量")
            Slider(value = volume, onValueChange = {
                volume = it
                player.volume = it
            }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.VolumeUp, contentDescription = null)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { player.seekToPreviousMediaItem() }, enabled = player.hasPreviousMediaItem()) {
                Icon(Icons.Outlined.SkipPrevious, "上一段")
            }
            IconButton(onClick = { player.seekBack() }, enabled = !live && seekable) { Icon(Icons.Outlined.Replay10, "后退10秒") }
            IconButton(onClick = { if (player.playWhenReady) player.pause() else player.play() }) {
                Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "暂停" else "播放")
            }
            IconButton(onClick = { player.seekForward() }, enabled = !live && seekable) { Icon(Icons.Outlined.Forward10, "快进10秒") }
            IconButton(onClick = { player.seekToNextMediaItem() }, enabled = player.hasNextMediaItem()) {
                Icon(Icons.Outlined.SkipNext, "下一段")
            }
            Text(if (live) "直播" else "${timeLabel(position)} / ${timeLabel(duration)}")
            IconButton(onClick = {
                fullscreen = !fullscreen
                (context as? Activity)?.requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }) {
                Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, if (fullscreen) "退出全屏" else "全屏")
            }
            Box {
                TextButton(onClick = { speedMenu = true }) { Text("${speed}x") }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).forEach { rate ->
                        DropdownMenuItem(text = { Text("${rate}x") }, onClick = {
                            player.setPlaybackSpeed(rate); speed = rate; speedMenu = false
                        })
                    }
                }
            }
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
