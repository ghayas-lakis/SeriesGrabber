package com.seriesgrabber.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** Lightweight live view over Android's system download queue. */
class DownloadQueueActivity : Activity() {
    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val previousBytes = mutableMapOf<Long, Pair<Long, Long>>()
    private val manager by lazy { getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    private val refresh = object : Runnable {
        override fun run() {
            renderQueue()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val title = TextView(this).apply {
            text = "Downloads"
            textSize = 23f
            setTextColor(0xff111111.toInt())
        }
        root.addView(title)
        root.addView(TextView(this).apply {
            text = "Live progress from Android Download Manager. Remove deletes the download and its saved file."
            textSize = 12f
            setPadding(0, dp(6), 0, dp(8))
        })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "Refresh"
            setOnClickListener { renderQueue() }
        })
        setContentView(root)
    }

    private fun renderQueue() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val query = DownloadManager.Query()
        manager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                list.addView(TextView(this).apply { text = "No downloads yet."; textSize = 15f })
                return@use
            }
            do {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "Video download"
                if (!title.startsWith("SeriesGrabber_")) continue
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val now = System.currentTimeMillis()
                val old = previousBytes[id]
                val speed = if (old != null && now > old.second) ((done - old.first).coerceAtLeast(0L) * 1000L / (now - old.second)) else 0L
                previousBytes[id] = done to now
                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                val state = when (status) {
                    DownloadManager.STATUS_PENDING -> "Waiting"
                    DownloadManager.STATUS_RUNNING -> "Downloading"
                    DownloadManager.STATUS_PAUSED -> "Paused by Android"
                    DownloadManager.STATUS_SUCCESSFUL -> "Completed"
                    DownloadManager.STATUS_FAILED -> "Failed"
                    else -> "Queued"
                }
                val eta = if (speed > 0 && total > done) formatTime((total - done) / speed) else "—"
                val details = if (total > 0) "${formatSize(done)} / ${formatSize(total)} • ${if (pct >= 0) "$pct%" else ""}" else formatSize(done)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    setBackgroundColor(0xfff1f3f5.toInt())
                }
                card.addView(TextView(this).apply { text = title; textSize = 14f; setTextColor(0xff111111.toInt()) })
                card.addView(TextView(this).apply {
                    text = "$state  •  $details\nSpeed: ${formatSize(speed)}/s  •  ETA: $eta"
                    textSize = 12f
                    setTextColor(0xff444444.toInt())
                })
                val actions = LinearLayout(this).apply { gravity = Gravity.END }
                actions.addView(Button(this@DownloadQueueActivity).apply {
                    text = "Remove"
                    setOnClickListener {
                        runCatching {
                            manager.getUriForDownloadedFile(id)?.let { contentResolver.delete(it, null, null) }
                            manager.remove(id)
                        }
                        previousBytes.remove(id)
                        renderQueue()
                    }
                })
                card.addView(actions)
                list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            } while (cursor.moveToNext())
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    private fun formatTime(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }
}
