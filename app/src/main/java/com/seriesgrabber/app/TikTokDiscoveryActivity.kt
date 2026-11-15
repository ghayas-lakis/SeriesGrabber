package com.seriesgrabber.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Resolves TikTok short links and inspects public creator/video pages.
 * TikTok has no stable public endpoint for enumerating every Series episode,
 * so discovered videos are candidates for user review, not verified episodes.
 */
class TikTokDiscoveryActivity : Activity() {
    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var resultContainer: LinearLayout
    private lateinit var browser: WebView
    private lateinit var findButton: Button
    private val executor = Executors.newSingleThreadExecutor()
    private val found = linkedMapOf<String, String>()
    private var scanning = false
    private var scanGeneration = 0

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 16f): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.rgb(30, 30, 30))
            setPadding(0, dp(7), 0, dp(7))
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)

        content.addView(label("SeriesGrabber — TikTok series finder", 22f))
        content.addView(label("Paste one episode link or a creator profile link. The app resolves the link and looks for public videos from that creator.", 15f))
        urlInput = EditText(this).apply {
            hint = "Paste TikTok episode or profile link"
            textSize = 16f
            setSingleLine(true)
            setText(intent.getStringExtra("url") ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
            setTextColor(Color.BLACK)
            setHintTextColor(Color.DKGRAY)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(245, 245, 245))
        }
        content.addView(urlInput, LinearLayout.LayoutParams(-1, dp(56)))

        findButton = Button(this).apply {
            text = "FIND SERIES AND EPISODES"
            textSize = 15f
            isAllCaps = false
            setOnClickListener { startScan() }
        }
        content.addView(findButton, LinearLayout.LayoutParams(-1, dp(54)))
        statusText = label("Ready. Paste a TikTok link and press Find.", 15f)
        content.addView(statusText)
        content.addView(label("TikTok page preview (sign in here if requested)", 17f))

        browser = WebView(this).apply {
            setBackgroundColor(Color.rgb(248, 248, 248))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
            webChromeClient = WebChromeClient()
            addJavascriptInterface(object {
                @JavascriptInterface
                fun report(json: String) {
                    post {
                        if (!scanning) return@post
                        runCatching {
                            val array = JSONArray(json)
                            for (i in 0 until array.length()) {
                                val obj = array.optJSONObject(i) ?: continue
                                val u = obj.optString("url").trim()
                                val type = obj.optString("type", "episode")
                                if (u.startsWith("https://www.tiktok.com/") &&
                                    (u.contains("/video/") || type == "series")) {
                                    found[u.substringBefore("?")] = type
                                }
                            }
                        }
                        finishScan()
                    }
                }
            }, "SeriesGrabberBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (target.startsWith("http://", true) || target.startsWith("https://", true)) return false
                    if (target.startsWith("snssdk://", true) || target.startsWith("tiktok://", true)) {
                        statusText.text = "TikTok offered an app-only link. Staying on the web page…"
                        return true
                    }
                    return true
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (scanning) statusText.text = "TikTok page loading…"
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame && scanning) {
                        statusText.text = "TikTok did not load in the preview. Try signing in through TikTok, then scan again."
                        finishScan()
                    }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (!scanning) return
                    statusText.text = "Page loaded. Checking creator videos and Series links…"
                    runPageScanner(view)
                }
            }
        }
        content.addView(browser, LinearLayout.LayoutParams(-1, dp(390)))
        content.addView(label("DISCOVERED LINKS", 18f))
        content.addView(label("Video links are candidates, not confirmed episodes. Review them because a creator may post more than one series.", 13f))
        resultContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(resultContainer)
        content.addView(label("This tool only collects links exposed by TikTok's public page. It does not bypass login, private content, DRM, or download restrictions.", 13f))
    }

    private fun startScan() {
        val input = urlInput.text.toString().trim()
        if (!input.contains("tiktok.com", ignoreCase = true)) {
            statusText.text = "Please paste a valid TikTok URL first."
            return
        }
        scanGeneration++
        val generation = scanGeneration
        found.clear()
        resultContainer.removeAllViews()
        scanning = true
        findButton.isEnabled = false
        findButton.text = "RESOLVING LINK…"
        statusText.text = "Resolving TikTok short link…"

        executor.execute {
            val resolved = resolveTikTokUrl(input)
            runOnUiThread {
                if (generation != scanGeneration || isFinishing) return@runOnUiThread
                val clean = resolved ?: input
                val videoId = Regex("/video/(\\d+)", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.getOrNull(1)
                val creator = Regex("tiktok\\.com/@([^/?#]+)", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.getOrNull(1)
                if (videoId != null) found["https://www.tiktok.com/video/$videoId"] = "episode"
                val target = when {
                    !creator.isNullOrBlank() -> "https://www.tiktok.com/@$creator"
                    videoId != null -> "https://www.tiktok.com/embed/v2/$videoId"
                    clean.startsWith("http", true) -> clean
                    else -> "https://$clean"
                }
                findButton.text = "SCANNING PUBLIC PAGE…"
                statusText.text = if (creator != null) {
                    "Link resolved. Checking public videos from @$creator. Review candidates for the same series."
                } else {
                    "Link resolved where possible. Checking the public video page…"
                }
                browser.loadUrl(target)
            }
        }
    }

    /** Follow HTTP redirects and recognize app deep-link video IDs when present. */
    private fun resolveTikTokUrl(input: String): String? {
        var current = if (input.startsWith("http", true)) input else "https://$input"
        repeat(7) {
            try {
                val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 12000
                    readTimeout = 12000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/122.0.0.0 Mobile Safari/537.36")
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                }
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (code in 300..399 && !location.isNullOrBlank()) {
                    val next = URL(URL(current), location).toString()
                    if (next.startsWith("snssdk://", true) || next.startsWith("tiktok://", true)) {
                        val id = Regex("(?:detail/|video/)(\\d+)", RegexOption.IGNORE_CASE).find(next)?.groupValues?.getOrNull(1)
                        return if (id != null) "https://www.tiktok.com/video/$id" else current
                    }
                    current = next
                } else return current
            } catch (_: Exception) {
                return current
            }
        }
        return current
    }

    private fun runPageScanner(view: WebView) {
        val js = """
            (function(){
              const found = new Map();
              const add = (url) => {
                try {
                  const u = new URL(url, location.href);
                  if (!/(^|\.)tiktok\.com$/i.test(u.hostname)) return;
                  if (/\/video\/\d+/.test(u.pathname)) {
                    u.search = ''; u.hash = '';
                    found.set(u.href, {url:u.href, type:'episode'});
                  } else if (/\/(series|collection|playlist)\//i.test(u.pathname)) {
                    u.search = ''; u.hash = '';
                    found.set(u.href, {url:u.href, type:'series'});
                  }
                } catch(e) {}
              };
              document.querySelectorAll('a[href]').forEach(a => add(a.href));
              const html = document.documentElement ? document.documentElement.innerHTML : '';
              const handle = (location.pathname.match(/@([^/]+)/) || [])[1];
              const pathRe = /@([A-Za-z0-9._-]+)\/video\/(\d{15,22})/g;
              let m;
              while ((m = pathRe.exec(html)) !== null) add('https://www.tiktok.com/@' + m[1] + '/video/' + m[2]);
              if (handle) {
                const ids = new Set();
                const idRe = /["'](?:id|itemId|aweme_id)["']\s*:\s*["'](\d{15,22})["']/g;
                while ((m = idRe.exec(html)) !== null) ids.add(m[1]);
                ids.forEach(id => add('https://www.tiktok.com/@' + handle + '/video/' + id));
              }
              let pass = 0;
              function scanMore() {
                document.querySelectorAll('a[href]').forEach(a => add(a.href));
                window.scrollBy(0, Math.max(window.innerHeight * 0.85, 500));
                pass++;
                if (pass < 18) setTimeout(scanMore, 850);
                else window.SeriesGrabberBridge.report(JSON.stringify(Array.from(found.values())));
              }
              scanMore();
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun finishScan() {
        if (!scanning) return
        scanning = false
        findButton.isEnabled = true
        findButton.text = "FIND SERIES AND EPISODES"
        val episodes = found.values.count { it == "episode" }
        val series = found.values.count { it == "series" }
        statusText.text = "Scan finished: $episodes video links and $series Series links found."
        renderResults()
    }

    private fun renderResults() {
        resultContainer.removeAllViews()
        if (found.isEmpty()) {
            resultContainer.addView(label("No public video links were exposed by this page. Sign in through the preview and try again, or paste the creator profile link.", 14f))
            return
        }
        found.entries.forEachIndexed { index, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(Color.rgb(246, 246, 246))
            }
            val heading = (index + 1).toString() + ". " +
                (if (entry.value == "series") "SERIES LINK" else "VIDEO CANDIDATE")
            row.addView(label(heading, 13f))
            row.addView(TextView(this).apply {
                text = entry.key
                textSize = 12f
                setTextColor(Color.DKGRAY)
                setTextIsSelectable(true)
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this@TikTokDiscoveryActivity).apply {
                text = "OPEN"
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.key))) }
            })
            actions.addView(Button(this@TikTokDiscoveryActivity).apply {
                text = "SHARE"
                setOnClickListener {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, entry.key)
                    }
                    startActivity(Intent.createChooser(send, "Share TikTok link"))
                }
            })
            row.addView(actions)
            resultContainer.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
    }

    override fun onDestroy() {
        scanGeneration++
        if (::browser.isInitialized) {
            browser.stopLoading()
            browser.removeJavascriptInterface("SeriesGrabberBridge")
            browser.destroy()
        }
        executor.shutdownNow()
        super.onDestroy()
    }
}
