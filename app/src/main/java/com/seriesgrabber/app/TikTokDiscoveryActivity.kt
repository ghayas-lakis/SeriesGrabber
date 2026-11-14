package com.seriesgrabber.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
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

class TikTokDiscoveryActivity : Activity() {
    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var browser: WebView
    private lateinit var findButton: Button
    private var found = linkedMapOf<String, String>()
    private var scanning = false

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

        fun label(value: String, size: Float = 16f): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(Color.rgb(30, 30, 30))
                setPadding(0, dp(8), 0, dp(8))
            }

        content.addView(label("SeriesGrabber — TikTok series finder", 22f))
        content.addView(label("Paste ONE TikTok episode link or a creator profile link. The app will look for the series.", 15f))

        urlInput = EditText(this).apply {
            hint = "Paste TikTok link here"
            textSize = 16f
            singleLine = true
            setText(intent.getStringExtra("url") ?: intent.getStringExtra("android.intent.extra.TEXT") ?: "")
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

        content.addView(label("TikTok page preview", 17f))
        browser = WebView(this).apply {
            setBackgroundColor(Color.rgb(248, 248, 248))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
            webChromeClient = WebChromeClient()
            addJavascriptInterface(object {
                @JavascriptInterface fun report(json: String) {
                    post {
                        runCatching {
                            val array = JSONArray(json)
                            for (i in 0 until array.length()) {
                                val obj = array.optJSONObject(i) ?: continue
                                val u = obj.optString("url")
                                val type = obj.optString("type")
                                if (u.isNotBlank()) found[u] = type
                            }
                        }
                        scanning = false
                        findButton.isEnabled = true
                        findButton.text = "FIND SERIES AND EPISODES"
                        statusText.text = "Scan finished. Found ${found.count { it.value == "episode" }} episode links and ${found.count { it.value == "series" }} series links."
                        refreshResults()
                    }
                }
            }, "SeriesGrabberBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    statusText.text = "Loading TikTok page…"
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        scanning = false
                        findButton.isEnabled = true
                        statusText.text = "TikTok page failed to load. Check internet, or sign in using the preview."
                    }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (!scanning) return
                    statusText.text = "Page loaded. Scrolling to collect episode links…"
                    val js = """
                        (function(){
                          let n=0, seen={};
                          function collect(){
                            document.querySelectorAll('a[href]').forEach(a=>{
                              let u=a.href;
                              if(!u || !u.includes('tiktok.com')) return;
                              let type=null;
                              if(/\/video\//.test(u)) type='episode';
                              else if(/\/(series|collection|playlist)\//i.test(u)) type='series';
                              if(type) seen[u.split('?')[0]]={url:u.split('?')[0],type:type};
                            });
                            window.scrollBy(0, Math.max(window.innerHeight*0.8,450));
                            n++;
                            if(n<25) setTimeout(collect,1000);
                            else window.SeriesGrabberBridge.report(JSON.stringify(Object.values(seen)));
                          }
                          collect();
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(js, null)
                }
            }
        }
        content.addView(browser, LinearLayout.LayoutParams(-1, dp(360)))
        content.addView(label("DETECTED LINKS", 18f))
        resultText = label("No links found yet.", 14f)
        content.addView(resultText)
        content.addView(label("If TikTok asks for sign-in or consent, complete it in the preview, then press Find again. This prototype reads links exposed on the page; full automatic downloading and merging are not connected yet.", 13f))
    }

    private fun startScan() {
        val url = urlInput.text.toString().trim()
        if (!url.contains("tiktok.com", ignoreCase = true)) {
            statusText.text = "Please paste a valid TikTok URL first."
            return
        }
        found.clear()
        refreshResults()
        scanning = true
        findButton.isEnabled = false
        findButton.text = "SCANNING…"
        statusText.text = "Opening TikTok…"
        browser.loadUrl(if (url.startsWith("http")) url else "https://$url")
    }

    private fun refreshResults() {
        resultText.text = if (found.isEmpty()) "No links detected. TikTok may hide links until you sign in, or its page layout may not expose episode URLs." else
            found.entries.mapIndexed { i, e -> "${i + 1}. [${e.value}] ${e.key}" }.joinToString("\n\n")
    }

    override fun onDestroy() {
        if (::browser.isInitialized) browser.destroy()
        super.onDestroy()
    }
}
