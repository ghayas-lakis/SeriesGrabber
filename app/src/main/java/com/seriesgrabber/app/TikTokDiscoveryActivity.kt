package com.seriesgrabber.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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
        setContent {
            MaterialTheme {
                var input by remember { mutableStateOf(intent.getStringExtra("url") ?: "") }
                var status by remember { mutableStateOf("Paste a TikTok profile, series, or episode link.") }
                var links by remember { mutableStateOf(listOf<TikTokFoundLink>()) }
                var webView by remember { mutableStateOf<WebView?>(null) }
                var scanning by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TikTok series finder", style = MaterialTheme.typography.headlineSmall)
                    Text("Scan a public profile or series page for episode and series links.")
                    OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(), label = { Text("TikTok link") }, singleLine = true)
                    Button(onClick = {
                        val normalized = input.trim()
                        if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
                            status = "Enter a complete TikTok URL."
                        } else {
                            links = emptyList()
                            scanning = true
                            status = "Opening TikTok…"
                            webView?.let { view ->
                                view.tag = "scanning"
                                view.loadUrl(normalized)
                            } ?: run { scanning = false; status = "Scanner is still starting. Try again." }
                        }
                    }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) { Text(if (scanning) "Scanning…" else "Find series and episodes") }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
                                webChromeClient = WebChromeClient()
                                addJavascriptInterface(object {
                                    @JavascriptInterface fun report(json: String, done: Boolean) {
                                        post {
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
                                                status = "Scan finished: ${links.count { it.type == "episode" }} episode links and ${links.count { it.type == "series" }} series links found. Select a series below to scan its episodes."
                                            } else {
                                                status = "Scrolling through the page and collecting links… ${links.size} found"
                                            }
                                        }
                                    }
                                }, "SeriesGrabberBridge")
                                webView = this
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView, url: String) {
                                        super.onPageFinished(view, url)
                                        if (view.tag != "scanning") return
                                        status = "Page loaded. Scanning visible and lazy-loaded cards…"
                                        val js = """
                                            (function(){
                                              let n=0, seen={};
                                              function collect(){
                                                document.querySelectorAll('a[href]').forEach(a=>{
                                                  let u=a.href;
                                                  if(!u || !u.includes('tiktok.com')) return;
                                                  let type=null;
                                                  if(/\\/video\\//.test(u)) type='episode';
                                                  else if(/\\/(series|collection)\\//i.test(u) || /series/i.test(a.innerText||'')) type='series';
                                                  if(type) seen[u.split('?')[0]]={url:u.split('?')[0],type:type};
                                                });
                                                window.scrollBy(0, Math.max(window.innerHeight*0.85,500));
                                                n++;
                                                if(n<28) setTimeout(collect,1100);
                                                else {
                                                  let arr=Object.values(seen);
                                                  window.SeriesGrabberBridge.report(JSON.stringify(arr),true);
                                                }
                                              }
                                              collect();
                                            })();
                                        """.trimIndent()
                                        view.evaluateJavascript(js, null)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(1.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        items(links, key = { it.url }) { item ->
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(if (item.type == "series") "Series / collection" else "Episode", style = MaterialTheme.typography.titleSmall)
                                    Text(item.url, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { input = item.url; links = emptyList(); scanning = true; status = "Opening selected series…"; webView?.apply { tag = "scanning"; loadUrl(item.url) } }) { Text("Scan this link") }
                                        TextButton(onClick = { android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(item.url)).also { startActivity(it) } }) { Text("Open") }
                                    }
                                }
                            }
                        }
                    }
                    Text("Note: TikTok may require its own app, sign-in, or consent for some pages. This scanner only reads links exposed by the public page; it does not bypass access controls or DRM. Episode downloading is not yet connected to this discovery screen.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
