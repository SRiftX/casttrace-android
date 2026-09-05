package com.streamcap.mobile.ui.player

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
                // Let extractors sniff temporary containers; .part is not a media format.
                items = uris.map { MediaItem.fromUri(it) }
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
    val player = remember {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build()
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var seekable by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var speedMenu by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { onReady() }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("StreamCapPlayer", "Playback failed", error)
                onError("播放失败：${error.errorCodeName}")
            }
        }
        player.addListener(listener)
        player.setMediaItems(items)
        player.prepare()
        player.playWhenReady = true
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it > 0 } ?: 0L
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
                dragging?.let { player.seekTo(it.toLong()) }; dragging = null
            }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = !live && seekable && duration > 0)
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
            Box {
                TextButton(onClick = { speedMenu = true }) { Text("${speed}x") }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { rate ->
                        DropdownMenuItem(text = { Text("${rate}x") }, onClick = {
                            player.setPlaybackSpeed(rate); speed = rate; speedMenu = false
                        })
                    }
                }
            }
        }
    }
}

private fun timeLabel(ms: Long): String = "%02d:%02d".format(ms / 60000, ms / 1000 % 60)
