package com.seriesgrabber.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

/**
 * Private-only in-app browser. It deliberately has no history UI or persistent
 * browser profile. Downloads are separate user-owned files.
 */
class TikTokDiscoveryActivity : Activity() {
    private lateinit var browser: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var results: TextView
    private val candidates = linkedSetOf<String>()
    private val mediaCandidates = linkedSetOf<String>()
    private var lastScannedUrl = ""

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(6))
        }
        val header = TextView(this).apply {
            text = "SeriesGrabber  •  PRIVATE BROWSER"
            textSize = 17f
            setTextColor(Color.BLACK)
            setPadding(dp(6), dp(6), dp(6), dp(8))
        }
        root.addView(header)

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        address = EditText(this).apply {
            hint = "Enter website or search"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.BLACK)
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(10), 0, dp(10), 0)
            setBackgroundColor(Color.rgb(243, 245, 247))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { navigate(); true } else false
            }
        }
        addressRow.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
        addressRow.addView(button("GO") { navigate() }, LinearLayout.LayoutParams(dp(58), dp(48)))
        root.addView(addressRow)

        status = TextView(this).apply {
            text = "Private session. Pages and searches are not saved in app history."
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        root.addView(status)

        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = false
            settings.setGeolocationEnabled(false)
            settings.saveFormData = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            settings.userAgentString = WebSettingsCompat.mobileUserAgent(settings.userAgentString)
            CookieManager.getInstance().setAcceptCookie(true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return false
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    address.setText(url)
                    status.text = "Loading privately…"
                }
                override fun onPageFinished(view: WebView, url: String) {
                    address.setText(url)
                    status.text = "Private page loaded. Use Detect videos to collect visible links."
                    scanPage(false)
                }
            }
        }
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(button("‹") { if (browser.canGoBack()) browser.goBack() })
        controls.addView(button("›") { if (browser.canGoForward()) browser.goForward() })
        controls.addView(button("↻") { browser.reload() })
        controls.addView(button("Detect videos") { scanPage(true) })
        controls.addView(button("Download") { chooseAndDownload() })
        controls.addView(button("Queue") { startActivity(Intent(this, DownloadQueueActivity::class.java)) })
        root.addView(controls)

        results = TextView(this).apply {
            text = "Detected links will appear here. Swipe through episodes and use Detect videos to collect links from each loaded page."
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(dp(6), dp(5), dp(6), dp(5))
            maxLines = 4
        }
        root.addView(results)
        setContentView(root)

        val initial = intent.getStringExtra("url") ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!initial.isNullOrBlank()) {
            address.setText(initial)
            navigate()
        } else {
            browser.loadUrl("https://www.google.com")
        }
    }

    private fun navigate() {
        var value = address.text.toString().trim()
        if (value.isBlank()) return
        if (!value.contains("://")) {
            value = if (value.contains(".") && !value.contains(" ")) "https://$value"
                else "https://www.google.com/search?q=" + Uri.encode(value)
        }
        browser.loadUrl(value)
    }

    private fun scanPage(showStatus: Boolean) {
        val script = """
          (function(){
            const out = new Set();
            const add = (raw) => {
              try {
                const u = new URL(raw, location.href);
                if (!/^https?:$/.test(u.protocol)) return;
                if (/(^|\\.)tiktok\\.com$/i.test(u.hostname) && /\\/video\\/\\d+/.test(u.pathname)) {
                  u.search=''; u.hash=''; out.add(u.href);
                }
                if (u.hostname.endsWith('dailymotion.com') && /\\/video\\//.test(u.pathname)) {
                  u.search=''; u.hash=''; out.add(u.href);
                }
              } catch(e) {}
            };
            document.querySelectorAll('a[href]').forEach(a => add(a.href));
            document.querySelectorAll('video').forEach(v => {
              if (v.currentSrc) add(v.currentSrc);
              if (v.src) add(v.src);
              v.querySelectorAll('source[src]').forEach(s => add(s.src));
            });
            return JSON.stringify(Array.from(out));
          })();
        """.trimIndent()
        browser.evaluateJavascript(script) { raw ->
            runCatching {
                val decoded = if (raw == null || raw == "null") "[]" else org.json.JSONTokener(raw).nextValue() as String
                val array = JSONArray(decoded)
                var added = 0
                for (i in 0 until array.length()) {
                    val link = array.optString(i)
                    if (link.startsWith("https://") || link.startsWith("http://")) {
                        if (candidates.add(link)) added++
                    }
                }
                lastScannedUrl = browser.url ?: ""
                results.text = "Collected ${candidates.size} unique video link(s). ${if (added > 0) "$added new" else "No new"} on this scan.\n" +
                    candidates.toList().takeLast(8).joinToString("\n")
                if (showStatus) status.text = "Page scan complete. Review the collected links before any download."
            }.onFailure {
                if (showStatus) status.text = "This page did not expose readable video links to the browser."
            }
        }
        // Only direct, ordinary HTTP(S) media files are offered to DownloadManager.
        browser.evaluateJavascript("""
            (function() {
              const found = new Set();
              document.querySelectorAll('video').forEach(v => {
                [v.currentSrc, v.src].filter(Boolean).forEach(u => {
                  try {
                    const x = new URL(u, location.href);
                    if (/^https?:$/.test(x.protocol) && /\\.(mp4|m4v|webm|mov)$/i.test(x.pathname)) found.add(x.href);
                  } catch(e) {}
                });
                v.querySelectorAll('source[src]').forEach(s => {
                  try {
                    const x = new URL(s.src, location.href);
                    if (/^https?:$/.test(x.protocol) && /\\.(mp4|m4v|webm|mov)$/i.test(x.pathname)) found.add(x.href);
                  } catch(e) {}
                });
              });
              return JSON.stringify(Array.from(found));
            })();
        """.trimIndent()) { raw ->
            runCatching {
                val json = org.json.JSONTokener(raw ?: "[]").nextValue() as String
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) mediaCandidates.add(arr.getString(i))
                results.text = "Episode links: ${candidates.size} • Direct media files: ${mediaCandidates.size}\n" +
                    mediaCandidates.toList().takeLast(4).joinToString("\n")
            }
        }
    }

    private fun chooseAndDownload() {
        val files = mediaCandidates.toList()
        if (files.isEmpty()) {
            status.text = "No direct downloadable video file was exposed by this page. A TikTok video page link is not itself a media file."
            return
        }
        val checked = BooleanArray(files.size) { true }
        val labels = files.mapIndexed { i, u -> "Video ${i + 1}: ${Uri.parse(u).lastPathSegment ?: "media"}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose videos to download")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Download selected") { _, _ ->
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                var count = 0
                files.forEachIndexed { i, url ->
                    if (checked[i]) runCatching {
                        val path = Uri.parse(url).lastPathSegment ?: "video.mp4"
                        val name = "SeriesGrabber_${System.currentTimeMillis()}_$path"
                        val req = DownloadManager.Request(Uri.parse(url))
                            .setTitle(name)
                            .setDescription("SeriesGrabber video download")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setAllowedOverMetered(true)
                            .setAllowedOverRoaming(false)
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "SeriesGrabber/$name")
                        dm.enqueue(req)
                        count++
                    }
                }
                status.text = "$count download(s) queued. Use Queue to monitor progress."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        if (::browser.isInitialized) {
            browser.stopLoading()
            browser.loadUrl("about:blank")
            browser.clearHistory()
            browser.clearCache(true)
            browser.clearFormData()
            browser.removeAllViews()
            browser.destroy()
        }
        // Clear the shared WebView cookie jar when this private browser closes.
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        super.onDestroy()
    }
}

/** Keep a mobile browser user-agent without pretending to be a desktop browser. */
private object WebSettingsCompat {
    fun mobileUserAgent(current: String): String =
        current.replace("; wv", "").ifBlank { "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36" }
}
