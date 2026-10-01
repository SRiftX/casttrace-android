package com.streamcap.mobile.ui.recordings

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private data class MediaSummary(val duration: Long?, val specification: String, val cover: Bitmap?)
private val summaries = LruCache<String, MediaSummary>(24)
private val readers = Semaphore(2)

private suspend fun readSummary(context: Context, uri: String): MediaSummary = readers.withPermit {
    withContext(Dispatchers.IO) {
        summaries.get(uri) ?: run {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                MediaSummary(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                    if (width != null && height != null) "实际 ${width}×$height" else "实际规格未知",
                    runCatching { if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(3_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90) else null }.getOrNull(),
                ).also { summaries.put(uri, it) }
            } catch (_: Exception) { MediaSummary(null, "规格暂不可读取", null) }
            finally { retriever.release() }
        }
    }
}

@Composable
fun RecordingCover(uri: String?) {
    val context = LocalContext.current
    var summary by remember(uri) { mutableStateOf<MediaSummary?>(null) }
    LaunchedEffect(uri) { summary = uri?.let { readSummary(context, it) } }
    Column {
        Box(Modifier.size(80.dp, 52.dp)) {
            val cover = summary?.cover
            if (cover != null) Image(cover.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Outlined.VideoFile, null, Modifier.size(48.dp))
        }
    }
}

@Composable
fun SegmentDescription(uri: String, index: Int) {
    val context = LocalContext.current
    var summary by remember(uri) { mutableStateOf<MediaSummary?>(null) }
    LaunchedEffect(uri) { summary = readSummary(context, uri) }
    Text("第 ${index + 1} 段 · ${summary?.duration?.let { com.streamcap.mobile.ui.common.formatDuration(it / 1000) } ?: "时长待读取"} · ${summary?.specification ?: "读取规格中"}")
}

@Composable
fun ActualSpecification(uri: String?) {
    val context = LocalContext.current
    var summary by remember(uri) { mutableStateOf<MediaSummary?>(null) }
    LaunchedEffect(uri) { summary = uri?.let { readSummary(context, it) } }
    Text(summary?.specification ?: "实际规格待保存后读取", style = MaterialTheme.typography.labelSmall)
}
