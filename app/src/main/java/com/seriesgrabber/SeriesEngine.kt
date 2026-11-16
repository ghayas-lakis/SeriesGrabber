package com.seriesgrabber

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Clip(
    val url: String,
    val id: String,
    val uploader: String?,
    val duration: Double,
    val text: String,
    val part: Int?,
    val index: Int,
)

class SeriesEngine(context: Context) {
    private val ctx = context.applicationContext

    // "Part 3", "pt3", "Ep. 12", "Episode 4", "#part5", "3/20"
    private val partRe = Regex(
        """(?:\bpart|\bpt|\bep(?:isode)?|#part|#ep)\.?\s*#?\s*(\d{1,4})\b|\b(\d{1,3})\s*/\s*\d{1,3}\b""",
        RegexOption.IGNORE_CASE
    )
    private val adRe = Regex(
        """#ad\b|#sponsored|#promo|#paidpartnership|paid partnership|#advertisement|\bsponsored\b|use code|download the app""",
        RegexOption.IGNORE_CASE
    )

    /** Full pipeline. Returns where the final file was saved. */
    suspend fun run(
        source: String,
        name: String,
        requireMarker: Boolean,
        reverse: Boolean,
        log: (String) -> Unit,
        progress: (Int) -> Unit,
    ): String {
        val work = File(ctx.cacheDir, "work").apply { deleteRecursively(); mkdirs() }
        try {
            // 1. engine
            progress(0)
            withContext(Dispatchers.IO) {
                log("Preparing download engine (first start can take a minute)...")
                YoutubeDL.getInstance().init(ctx)
                FFmpeg.getInstance().init(ctx)
                runCatching { YoutubeDL.getInstance().updateYoutubeDL(ctx) }
            }

            // 2. list + metadata
            val urls = withContext(Dispatchers.IO) { listUrls(source) }
            log("Found ${urls.size} videos. Reading details...")
            val clips = withContext(Dispatchers.IO) {
                urls.mapIndexedNotNull { i, u ->
                    ensureActive()
                    progress(2 + 18 * (i + 1) / urls.size)
                    fetchMeta(u, i + 1, log)
                }
            }

            // 3. filter ads / unrelated videos
            val (kept, skipped) = classify(clips, requireMarker)
            skipped.forEach { (c, why) -> log("Skipped ${c.id}: $why") }
            if (kept.isEmpty()) error("Nothing left after filtering.")
            val ordered = order(kept, reverse)
            log("Keeping ${ordered.size} series videos, skipped ${skipped.size}.")

            // 4. download
            val dir = File(work, "raw").apply { mkdirs() }
            val files = withContext(Dispatchers.IO) {
                ordered.mapIndexedNotNull { i, c ->
                    ensureActive()
                    log("Downloading ${i + 1}/${ordered.size} (part ${c.part ?: "?"})")
                    progress(20 + 60 * (i + 1) / ordered.size)
                    download(c, dir, log)
                }
            }
            if (files.isEmpty()) error("No videos could be downloaded.")

            // 5. merge (hardware accelerated, normalised to one size/format)
            log("Merging ${files.size} clips into one MP4...")
            progress(85)
            val merged = File(work, "merged.mp4")
            merge(files, merged)

            // 6. save to Movies/SeriesGrabber
            val fileName = (name.ifBlank { "series_${System.currentTimeMillis() / 1000}" }
                .replace(Regex("[^A-Za-z0-9 _.-]"), "_")) + ".mp4"
            val saved = withContext(Dispatchers.IO) { saveToMovies(merged, fileName) }
            progress(100)
            return saved
        } finally {
            work.deleteRecursively()
        }
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }

    private fun listUrls(source: String): List<String> {
        val req = YoutubeDLRequest(source)
        req.addOption("--flat-playlist")
        req.addOption("--dump-single-json")
        req.addOption("--no-warnings")
        val json = JSONObject(YoutubeDL.getInstance().execute(req).out.trim())
        val entries = json.optJSONArray("entries")
            ?: return listOf(json.str("webpage_url") ?: source)
        val out = mutableListOf<String>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            (e.str("url") ?: e.str("webpage_url"))?.let { out += it }
        }
        return out
    }

    private fun fetchMeta(url: String, index: Int, log: (String) -> Unit): Clip? = try {
        val req = YoutubeDLRequest(url)
        req.addOption("--dump-single-json")
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        val j = JSONObject(YoutubeDL.getInstance().execute(req).out.trim())
        val text = "${j.str("title") ?: ""} ${j.str("description") ?: ""}"
        val m = partRe.find(text)
        val part = m?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() }
        Clip(
            url = j.str("webpage_url") ?: url,
            id = j.str("id") ?: "v$index",
            uploader = j.str("uploader_id") ?: j.str("uploader"),
            duration = j.optDouble("duration", 0.0),
            text = text,
            part = part,
            index = index,
        )
    } catch (e: Exception) {
        log("Could not read $url: ${e.message?.take(80)}")
        null
    }

    private fun classify(clips: List<Clip>, requireMarker: Boolean): Pair<List<Clip>, List<Pair<Clip, String>>> {
        // series creator = most common uploader
        val creator = clips.mapNotNull { it.uploader }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val kept = mutableListOf<Clip>()
        val skipped = mutableListOf<Pair<Clip, String>>()
        val seenParts = mutableSetOf<Int>()
        for (c in clips) {
            when {
                adRe.containsMatchIn(c.text) -> skipped += c to "ad keywords in caption"
                creator != null && c.uploader != creator -> skipped += c to "different uploader (${c.uploader})"
                requireMarker && c.part == null -> skipped += c to "no Part/Ep number"
                c.part != null && !seenParts.add(c.part) -> skipped += c to "duplicate part ${c.part}"
                else -> kept += c
            }
        }
        return kept to skipped
    }

    private fun order(clips: List<Clip>, reverse: Boolean): List<Clip> =
        if (clips.all { it.part != null }) clips.sortedBy { it.part }
        else clips.sortedBy { it.index }.let { if (reverse) it.reversed() else it }

    private fun download(c: Clip, dir: File, log: (String) -> Unit): File? = try {
        val req = YoutubeDLRequest(c.url)
        req.addOption("-o", "${dir.absolutePath}/${c.id}.%(ext)s")
        req.addOption("-S", "vcodec:h264,res")
        req.addOption("--no-playlist")
        req.addOption("--merge-output-format", "mp4")
        req.addOption("--retries", "5")
        YoutubeDL.getInstance().execute(req)
        dir.listFiles()?.firstOrNull { it.name.startsWith("${c.id}.") && it.extension == "mp4" }
    } catch (e: Exception) {
        log("Download failed for ${c.id}: ${e.message?.take(80)}")
        null
    }

    private fun targetSize(first: File): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(first.absolutePath)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 720
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1280
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            (w - w % 2) to (h - h % 2)
        } finally {
            r.release()
        }
    }

    private suspend fun merge(files: List<File>, output: File) {
        val (w, h) = targetSize(files.first())
        // Transformer must be started from a thread with a Looper -> main thread
        withContext(Dispatchers.Main) {
            val items = files.map { f ->
                EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f)))
                    .setEffects(
                        Effects(
                            emptyList(),
                            listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
                        )
                    )
                    .build()
            }
            val sequence = EditedMediaItemSequence.Builder(*items.toTypedArray()).build()
            val composition = Composition.Builder(sequence).build()

            suspendCancellableCoroutine<Unit> { cont ->
                val transformer = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                cont.invokeOnCancellation { transformer.cancel() }
                transformer.start(composition, output.absolutePath)
            }
        }
    }

    private fun saveToMovies(file: File, displayName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SeriesGrabber")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create file in Movies")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Movies/SeriesGrabber/$displayName"
    }
}