package com.seriesgrabber.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray

data class TikTokFoundLink(val url: String, val type: String)

class TikTokDiscoveryActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent.getStringExtra("android.intent.extra.TEXT").orEmpty()
        setContent {
            MaterialTheme {
                var input by remember {
                    mutableStateOf(intent.getStringExtra("url") ?: sharedText)
                }
                var status by remember { mutableStateOf("Paste a TikTok profile, series, or episode link, then tap Find.") }
                var links by remember { mutableStateOf(listOf<TikTokFoundLink>()) }
                var webView by remember { mutableStateOf<WebView?>(null) }
                var scanning by remember { mutableStateOf(false) }
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("TikTok series finder", style = MaterialTheme.typography.headlineSmall)
                        Text("One link should lead to the complete drama. Start with an episode or creator profile.")
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("TikTok link") },
                            placeholder = { Text("https://www.tiktok.com/@creator/...") },
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val url = input.trim()
                                if (!url.contains("tiktok.com", ignoreCase = true)) {
                                    status = "Please paste a valid TikTok link."
                                } else {
                                    links = emptyList()
                                    scanning = true
                                    status = "Opening TikTok page…"
                                    val view = webView
                                    if (view == null) {
                                        scanning = false
                                        status = "Browser is still initializing. Wait a moment and tap Find again."
                                    } else {
                                        view.tag = "scanning"
                                        view.loadUrl(if (url.startsWith("http")) url else "https://$url")
                                    }
                                }
                            },
                            enabled = !scanning,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (scanning) "Scanning TikTok…" else "Find series and episodes") }

                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        Text("TikTok page preview", style = MaterialTheme.typography.titleSmall)
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.loadsImagesAutomatically = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
                                    webChromeClient = WebChromeClient()
                                    val scannerView = this
                                    addJavascriptInterface(object {
                                        @JavascriptInterface
                                        fun report(json: String, done: Boolean) {
                                            scannerView.post {
                                                val parsed = runCatching {
                                                    val array = JSONArray(json)
                                                    (0 until array.length()).mapNotNull { i ->
                                                        val obj = array.optJSONObject(i) ?: return@mapNotNull null
                                                        val url = obj.optString("url")
                                                        val type = obj.optString("type")
                                                        if (url.isBlank()) null else TikTokFoundLink(url, type)
                                                    }
                                                }.getOrDefault(emptyList())
                                                links = (links + parsed).distinctBy { it.url }
                                                if (done) {
                                                    scanning = false
                                                    status = "Scan finished: ${links.count { it.type == "episode" }} episode links and ${links.count { it.type == "series" }} series links found."
                                                } else {
                                                    status = "Scanning page… ${links.size} links found."
                                                }
                                            }
                                        }
                                    }, "SeriesGrabberBridge")
                                    webView = this
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            if (view.tag == "scanning") status = "TikTok page loading…"
                                        }

                                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                            super.onReceivedError(view, request, error)
                                            if (request.isForMainFrame) {
                                                scanning = false
                                                status = "TikTok could not load this page. Try opening the link in TikTok or Chrome, or check your connection."
                                            }
                                        }

                                        override fun onPageFinished(view: WebView, url: String) {
                                            super.onPageFinished(view, url)
                                            if (view.tag != "scanning") return
                                            status = "Page loaded. Scrolling to reveal episode cards…"
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
                                                    window.scrollBy(0, Math.max(window.innerHeight*0.85,500));
                                                    n++;
                                                    if(n<20) setTimeout(collect,1000);
                                                    else window.SeriesGrabberBridge.report(JSON.stringify(Object.values(seen)),true);
                                                  }
                                                  collect();
                                                })();
                                            """.trimIndent()
                                            view.evaluateJavascript(js, null)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(190.dp)
                        )
                        Text("Found links (${links.size})", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(links, key = { it.url }) { item ->
                                ElevatedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(if (item.type == "series") "Series / collection" else "Episode")
                                        Text(item.url, style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = {
                                            input = item.url
                                            scanning = true
                                            status = "Opening selected link…"
                                            webView?.apply { tag = "scanning"; loadUrl(item.url) }
                                        }) { Text("Scan this link") }
                                    }
                                }
                            }
                        }
                        Text(
                            "If TikTok shows a login or consent screen, complete it in the preview and tap Find again. Discovery only reads links visible on the page; downloading and merging are separate features still under development.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
