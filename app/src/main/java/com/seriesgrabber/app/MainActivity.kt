package com.seriesgrabber.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class VideoItem(val uri: Uri, val name: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme {
            SeriesGrabberScreen(::displayName) { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } }
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

@Composable
private fun SeriesGrabberScreen(readName: (Uri) -> String, retainAccess: (Uri) -> Unit) {
    val videos = remember { mutableStateListOf<VideoItem>() }
    var message by remember { mutableStateOf("Import videos that you are permitted to use.") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        var added = 0
        selected.forEach { uri ->
            runCatching { retainAccess(uri) }
            if (videos.none { it.uri == uri }) { videos.add(VideoItem(uri, readName(uri))); added++ }
        }
        message = if (added > 0) "$added video(s) added." else "No new videos selected."
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("SeriesGrabber", style = MaterialTheme.typography.headlineMedium)
            Text("Collect and arrange episodes into a series.")
            Button(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) { Text("＋  Add videos") }
            Text(message, style = MaterialTheme.typography.bodySmall)
            Text("Selected videos (${videos.size})", style = MaterialTheme.typography.titleMedium)
            if (videos.isEmpty()) {
                ElevatedCard(Modifier.fillMaxWidth()) { Text("Your imported videos will appear here.", Modifier.padding(18.dp)) }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(videos, key = { _, item -> item.uri.toString() }) { index, item ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Episode ${index + 1}", style = MaterialTheme.typography.labelMedium)
                                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(enabled = index > 0, onClick = { val moved = videos.removeAt(index); videos.add(index - 1, moved) }) { Text("Move up") }
                                    TextButton(enabled = index < videos.lastIndex, onClick = { val moved = videos.removeAt(index); videos.add(index + 1, moved) }) { Text("Move down") }
                                    TextButton(onClick = { videos.removeAt(index) }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
            }
            Text("Prototype milestone: local import and manual ordering. Episode/ad detection and MP4 export are next.", style = MaterialTheme.typography.bodySmall)
        }
    }
}