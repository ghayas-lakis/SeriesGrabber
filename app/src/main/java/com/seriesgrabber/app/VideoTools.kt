package com.seriesgrabber.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VideoKind { EPISODE, AD, REVIEW }

data class VideoAnalysis(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val suggestedKind: VideoKind,
    val confidence: Int,
    val reason: String
)

object VideoAnalyzer {
    fun analyze(context: Context, uri: Uri, name: String): VideoAnalysis {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val lowerName = name.lowercase(Locale.ROOT)
            val adWords = listOf("ad", "advert", "sponsor", "promo", "promotion", "offer", "discount", "shop now")
            val matchedWord = adWords.firstOrNull { word ->
                if (word == "ad") Regex("(^|[ _.-])ad([ _.-]|$)").containsMatchIn(lowerName) else lowerName.contains(word)
            }
            if (matchedWord != null) return VideoAnalysis(duration, width, height, VideoKind.AD, 92, "Filename contains an advertising keyword")
            val seconds = duration / 1000
            return when {
                duration <= 0L -> VideoAnalysis(duration, width, height, VideoKind.REVIEW, 0, "Could not read duration; please review")
                seconds <= 15 -> VideoAnalysis(duration, width, height, VideoKind.AD, 65, "Very short clip; could be an advert or a short episode")
                seconds <= 35 -> VideoAnalysis(duration, width, height, VideoKind.REVIEW, 55, "Short clip; manual confirmation recommended")
                seconds >= 90 -> VideoAnalysis(duration, width, height, VideoKind.EPISODE, 82, "Longer clip is more likely to be an episode")
                seconds >= 45 -> VideoAnalysis(duration, width, height, VideoKind.EPISODE, 68, "Duration suggests an episode, but verify it")
                else -> VideoAnalysis(duration, width, height, VideoKind.REVIEW, 50, "Duration alone is not enough to decide")
            }
        } finally {
            runCatching { retriever.release() }
        }
    }
}

@UnstableApi
object VideoMerger {
    fun export(context: Context, uris: List<Uri>, onStatus: (String) -> Unit) {
        require(uris.size >= 2) { "Select at least two episode videos to merge." }
        val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir
        if (!outputDir.exists()) outputDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(outputDir, "SeriesGrabber_$stamp.mp4")
        val clips = uris.map { uri -> EditedMediaItem.Builder(MediaItem.fromUri(uri)).build() }
        val sequence = EditedMediaItemSequence.Builder(clips).build()
        val composition = Composition.Builder(listOf(sequence)).build()
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    try {
                        val savedUri = copyToGallery(context, outputFile)
                        outputFile.delete()
                        onStatus("Export complete. Saved to Movies/SeriesGrabber. Output: $savedUri")
                    } catch (error: Exception) {
                        onStatus("Export finished, but could not copy to Gallery: ${error.message}. File: ${outputFile.absolutePath}")
                    }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    outputFile.delete()
                    onStatus("Merge failed: ${exportException.message ?: "unsupported or incompatible video format"}")
                }
            })
            .build()
        transformer.start(composition, outputFile.absolutePath)
        onStatus("Merging ${uris.size} videos… Keep the app open until export finishes.")
    }

    private fun copyToGallery(context: Context, file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SeriesGrabber")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create output video in Gallery")
        try {
            resolver.openOutputStream(uri)?.use { output -> FileInputStream(file).use { input -> input.copyTo(output) } }
                ?: throw IOException("Could not open Gallery output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, ready, null, null)
            }
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}