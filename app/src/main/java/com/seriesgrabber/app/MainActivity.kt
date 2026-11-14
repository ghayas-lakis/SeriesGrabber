package com.seriesgrabber.app

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.util.UnstableApi

private class VideoItem(val uri: Uri, val name: String) {
    var analysis by mutableStateOf<VideoAnalysis?>(null)
    var kind by mutableStateOf(VideoKind.REVIEW)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SeriesGrabberScreen(::displayName) { uri ->
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } } }
    }
    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "Video"
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun SeriesGrabberScreen(readName: (Uri) -> String, retainAccess: (Uri) -> Unit) {
    val context = LocalContext.current
    val videos = remember { mutableStateListOf<VideoItem>() }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("Import videos, then run detection and review its suggestions.") }
    var analyzing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        var added = 0
        selected.forEach { uri ->
            runCatching { retainAccess(uri) }
            if (videos.none { it.uri == uri }) { videos.add(VideoItem(uri, readName(uri))); added++ }
        }
        message = if (added > 0) "$added video(s) added. Run detection next." else "No new videos selected."
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SeriesGrabber", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Detect episodes, exclude adverts, and combine your selected clips.")
            Button(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) { Text("＋  Add videos") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(enabled = videos.isNotEmpty() && !analyzing, onClick = {
                    scope.launch {
                        analyzing = true
                        message = "Analyzing ${videos.size} videos…"
                        val results = withContext(Dispatchers.IO) { videos.map { it to VideoAnalyzer.analyze(context, it.uri, it.name) } }
                        results.forEach { (item, result) -> item.analysis = result; item.kind = result.suggestedKind }
                        analyzing = false
                        message = "Detection finished. Review the suggestions before merging."
                    }
                }, modifier = Modifier.weight(1f)) { Text(if (analyzing) "Analyzing…" else "Detect episodes") }
                Button(enabled = videos.count { it.kind == VideoKind.EPISODE } >= 2 && !exporting, onClick = {
                    val episodeUris = videos.filter { it.kind == VideoKind.EPISODE }.map { it.uri }
                    exporting = true
                    try {
                        VideoMerger.export(context, episodeUris) { result -> message = result; exporting = result.startsWith("Merging") }
                    } catch (error: Exception) {
                        message = error.message ?: "Could not start merge"
                        exporting = false
                    }
                }, modifier = Modifier.weight(1f)) { Text(if (exporting) "Merging…" else "Merge episodes") }
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Videos: ${videos.size}")
                Text("Episodes: ${videos.count { it.kind == VideoKind.EPISODE }}  •  Ads: ${videos.count { it.kind == VideoKind.AD }}")
            }
            if (videos.isEmpty()) {
                ElevatedCard(Modifier.fillMaxWidth()) { Text("Your imported videos will appear here.", Modifier.padding(18.dp)) }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(videos, key = { it.uri.toString() }) { item ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                                val result = item.analysis
                                if (result == null) Text("Not analyzed yet", style = MaterialTheme.typography.bodySmall)
                                else Text("${result.durationMs / 1000}s • ${result.width}×${result.height} • confidence ${result.confidence}% — ${result.reason}", style = MaterialTheme.typography.bodySmall)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilterChip(selected = item.kind == VideoKind.EPISODE, onClick = { item.kind = VideoKind.EPISODE }, label = { Text("Episode") })
                                    FilterChip(selected = item.kind == VideoKind.AD, onClick = { item.kind = VideoKind.AD }, label = { Text("Ad") })
                                    FilterChip(selected = item.kind == VideoKind.REVIEW, onClick = { item.kind = VideoKind.REVIEW }, label = { Text("Review") })
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(enabled = videos.indexOf(item) > 0, onClick = { val i = videos.indexOf(item); videos.removeAt(i); videos.add(i - 1, item) }) { Text("Move up") }
                                    TextButton(enabled = videos.indexOf(item) < videos.lastIndex, onClick = { val i = videos.indexOf(item); videos.removeAt(i); videos.add(i + 1, item) }) { Text("Move down") }
                                    TextButton(onClick = { videos.remove(item) }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
            }
            Text("Detection is a first-pass heuristic using filename and duration, not AI story recognition. Only items marked Episode are merged. The merge uses Android Media3 and exports to Movies/SeriesGrabber.", style = MaterialTheme.typography.bodySmall)
        }
    }
}