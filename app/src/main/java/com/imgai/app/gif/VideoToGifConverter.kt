package com.imgai.app.gif

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Convert a video to an animated GIF using MediaMetadataRetriever.
 *
 * @param context context
 * @param videoUri input video uri
 * @param outputDir where to save the gif
 * @param width target width (height auto-scaled to preserve aspect ratio), <=0 for source width
 * @param fps frames per second to extract (1-10 recommended)
 * @param maxDurationMs max video duration to convert (0 = full video)
 * @param progress callback (0..100)
 */
class VideoToGifConverter(
    private val context: Context,
    private val videoUri: Uri,
    private val outputDir: File,
    private val width: Int = 480,
    private val fps: Int = 5,
    private val maxDurationMs: Long = 0L,
    private val progress: ((percent: Int, message: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "VideoToGifConverter"
        private const val MAX_DIMENSION = 720
    }

    suspend fun convert(): File = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
        } catch (e: Exception) {
            progress?.invoke(-1, "无法读取视频: ${e.message}")
            throw e
        }

        try {
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs == 0L) {
                progress?.invoke(-1, "无法获取视频时长")
                throw IllegalStateException("无法获取视频时长")
            }

            val srcWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val srcHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (srcWidth == 0 || srcHeight == 0) {
                progress?.invoke(-1, "无法获取视频尺寸")
                throw IllegalStateException("无法获取视频尺寸")
            }

            Log.i(TAG, "Video: ${srcWidth}x${srcHeight}, ${durationMs}ms")

            // Calculate target dimensions
            val targetWidth: Int
            val targetHeight: Int
            if (width <= 0 || width >= srcWidth) {
                // Use source but cap at MAX_DIMENSION
                if (srcWidth > MAX_DIMENSION) {
                    val ratio = MAX_DIMENSION.toFloat() / srcWidth
                    targetWidth = MAX_DIMENSION
                    targetHeight = (srcHeight * ratio).toInt()
                } else {
                    targetWidth = srcWidth
                    targetHeight = srcHeight
                }
            } else {
                val ratio = width.toFloat() / srcWidth
                targetWidth = width
                targetHeight = (srcHeight * ratio).toInt()
            }

            // Calculate extraction params
            val effectiveDuration = if (maxDurationMs > 0) minOf(durationMs, maxDurationMs) else durationMs
            val frameIntervalMs = (1000f / fps).toLong()
            val totalFrames = (effectiveDuration / frameIntervalMs).toInt().coerceIn(1, 300)

            Log.i(TAG, "GIF: ${targetWidth}x${targetHeight}, ${fps}fps, ${totalFrames} frames, interval=${frameIntervalMs}ms")

            val encoder = AnimatedGifEncoder().apply {
                setSize(targetWidth, targetHeight)
                setDelay((1000 / fps))
                setRepeat(0) // infinite loop
                start()
            }

            var extractedCount = 0

            for (frameIndex in 0 until totalFrames) {
                coroutineContext.ensureActive()

                val timeUs = (frameIndex * frameIntervalMs * 1000).coerceAtMost(effectiveDuration * 1000)
                val bitmap = try {
                    retriever.getFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get frame at ${timeUs}us", e)
                    null
                }

                if (bitmap != null) {
                    val scaledBitmap = if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
                        val sb = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                        if (sb != bitmap) bitmap.recycle()
                        sb
                    } else {
                        bitmap
                    }

                    encoder.addFrame(scaledBitmap)
                    scaledBitmap.recycle()
                    extractedCount++
                }

                val percent = ((frameIndex + 1) * 100 / totalFrames)
                progress?.invoke(percent, "提取帧 ${frameIndex + 1}/$totalFrames")
            }

            retriever.release()

            val gifBytes = encoder.finish()
            if (gifBytes == null || gifBytes.isEmpty()) {
                progress?.invoke(-1, "GIF 生成失败")
                throw IllegalStateException("GIF 生成失败")
            }

            // Save
            if (!outputDir.exists()) outputDir.mkdirs()
            val outputFile = File(outputDir, "imgai_${System.currentTimeMillis()}.gif")
            FileOutputStream(outputFile).use { it.write(gifBytes) }

            val sizeKb = outputFile.length() / 1024
            Log.i(TAG, "GIF saved: ${outputFile.absolutePath}, ${sizeKb}KB, ${extractedCount} frames")

            progress?.invoke(100, "完成！${extractedCount} 帧, ${sizeKb}KB")
            outputFile
        } catch (e: Exception) {
            try { retriever.release() } catch (_: Exception) {}
            if (progress != null && e is IllegalStateException) throw e
            progress?.invoke(-1, "转换失败: ${e.message}")
            throw e
        }
    }
}
