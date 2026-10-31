package com.streamcap.mobile.danmaku

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.*
import com.streamcap.mobile.data.preferences.DanmakuAppearance
import com.streamcap.mobile.storage.RecordingStorage
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
class DanmakuVideoExporter(
    private val context: Context,
    private val storage: RecordingStorage = RecordingStorage(context),
) {
    suspend fun export(
        source: Uri, destination: Uri, events: List<DanmakuEvent>, appearance: DanmakuAppearance,
        mediaOffsetMs: Long = 0, onProgress: (String) -> Unit = {},
    ) {
        require(source != destination) { "导出位置不能是原文件" }
        require(events.any { it.mediaTimeMs != null }) { "这场录制没有可用弹幕" }
        val output = withContext(Dispatchers.IO) { File.createTempFile("danmaku-export-", ".mp4", context.cacheDir) }
        try {
            withContext(Dispatchers.IO) { checkExportSpace() }
            withContext(Dispatchers.Main.immediate) {
                coroutineScope {
                    var transformer: Transformer? = null
                    val progressJob = launch {
                        val progress = ProgressHolder()
                        while (isActive) {
                            withContext(Dispatchers.IO) { checkExportSpace() }
                            val state = transformer?.getProgress(progress)
                            onProgress(if (state == Transformer.PROGRESS_STATE_AVAILABLE) "正在合成弹幕 ${progress.progress}%" else "正在准备弹幕合成")
                            delay(500)
                        }
                    }
                    try {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            val overlay = VideoDanmakuOverlay(events, appearance.normalized(), mediaOffsetMs)
                            val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
                                .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
                                .build()
                            transformer = Transformer.Builder(context)
                                .setVideoMimeType(MimeTypes.VIDEO_H264)
                                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                                .setPortraitEncodingEnabled(true)
                                .addListener(object : Transformer.Listener {
                                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                        if (continuation.isActive) continuation.resume(Unit)
                                    }
                                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                        if (continuation.isActive) continuation.resumeWithException(exportException)
                                    }
                                }).build()
                            try { transformer!!.start(item, output.absolutePath) }
                            catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                        }
                    } finally {
                        progressJob.cancel()
                        // Transformer is bound to this looper, including cancellation.
                        transformer?.cancel()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            check(output.length() > 0) { "弹幕合成文件为空" }
            storage.exportCopy(Uri.fromFile(output), destination) { bytes -> onProgress("正在保存带弹幕视频 ${bytes / 1_048_576} MB") }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { storage.deletePublished(destination.toString()) } }
            throw failure
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { output.delete() }
        }
    }

    private fun checkExportSpace() {
        check(context.cacheDir.usableSpace > 256L * 1024 * 1024) {
            "空间不足，弹幕合成需要额外空间，请清理后重试"
        }
    }
}

@OptIn(UnstableApi::class)
internal class VideoDanmakuOverlay(
    private val events: List<DanmakuEvent>, private val appearance: DanmakuAppearance,
    private val mediaOffsetMs: Long,
) : BitmapOverlay() {
    private var bitmap: Bitmap? = null
    private lateinit var canvas: Canvas
    private lateinit var timeline: ScrollingDanmakuTimeline
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private var inset = 0f
    private var lineSpacing = 0f
    private var outline = 0f

    override fun configure(videoSize: Size) {
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(videoSize.width, videoSize.height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(requireNotNull(bitmap))
        val scale = videoSize.height / 360f
        paint.textSize = appearance.fontSizeSp * scale
        inset = 12f * scale
        outline = 1.5f * scale
        val lineHeight = paint.fontMetrics.bottom - paint.fontMetrics.top + 8f * scale
        val availableHeight = (videoSize.height * appearance.area - inset).coerceAtLeast(lineHeight)
        val lanes = (availableHeight / lineHeight).toInt().coerceIn(1, appearance.laneLimit)
        lineSpacing = lineHeight
        timeline = ScrollingDanmakuTimeline(events.mapNotNull { event ->
            if (appearance.density < 1f && (event.eventId ?: "${event.receivedElapsedMs}:${event.text}").hashCode().toUInt().toLong() % 10_000 >= (appearance.density * 10_000).toLong()) return@mapNotNull null
            val time = event.mediaTimeMs ?: return@mapNotNull null
            val formatted = DanmakuPresentation.text(event, appearance) ?: return@mapNotNull null
            val text = TextUtils.ellipsize(formatted, paint,
                videoSize.width * 1.5f, TextUtils.TruncateAt.END).toString()
            TimedBullet(text, time, paint.measureText(text))
        }, videoSize.width.toFloat(), lanes, gap = 24f * scale, travelMs = (6500 / appearance.speed).toLong())
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val time = presentationTimeUs / 1000 + mediaOffsetMs - appearance.timeOffsetMs
        timeline.visibleAt(time).forEach { bullet ->
            val x = timeline.x(bullet, time)
            val y = inset + bullet.lane * lineSpacing - paint.fontMetrics.top
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outline
            paint.color = android.graphics.Color.argb((210 * appearance.opacity).toInt(), 0, 0, 0)
            canvas.drawText(bullet.text, x, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = ((255 * appearance.opacity).toInt() shl 24) or appearance.colorRgb
            canvas.drawText(bullet.text, x, y, paint)
        }
        return requireNotNull(bitmap)
    }

    override fun release() {
        super.release()
        bitmap?.recycle()
        bitmap = null
    }
}
