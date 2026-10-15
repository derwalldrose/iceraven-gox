package org.mozilla.fenix.goxai

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Real search page retrieval; no fabricated results, credentials or native JS bridge. */
object SearchResults {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(context: Context, query: String): String {
        val rss = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fetchRss(query) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
        return rss ?: fetchPage(context, query)
    }

    private fun fetchRss(query: String): String? {
        val connection = java.net.URL("https://www.bing.com/search?format=rss&q=" +
            java.net.URLEncoder.encode(query.take(1000), "UTF-8")).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (connection.responseCode != 200) return null
            val parser = android.util.Xml.newPullParser()
            parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            connection.inputStream.use { input ->
                parser.setInput(input, "UTF-8")
                val results = mutableListOf<String>()
                var inItem = false
                var title = ""
                var link = ""
                var description = ""
                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && results.size < 6) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "item" -> { inItem = true; title = ""; link = ""; description = "" }
                            "title" -> if (inItem) title = parser.nextText()
                            "link" -> if (inItem) link = parser.nextText()
                            "description" -> if (inItem) description = parser.nextText()
                        }
                    } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "item") {
                        if (title.isNotBlank() && (link.startsWith("https://") || link.startsWith("http://"))) {
                            results += "[${results.size + 1}] $title\n$link\n${description.take(1600)}"
                        }
                        inItem = false
                    }
                    event = parser.next()
                }
                return results.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
            }
        } finally { connection.disconnect() }
    }

    private suspend fun fetchPage(context: Context, query: String): String = withTimeout(30_000) {
        suspendCancellableCoroutine { cont ->
            val web = WebView(context)
            var started = false
            var attempts = 0
            var destroyed = false
            fun cleanup() { if (!destroyed) { destroyed = true; web.stopLoading(); web.destroy() } }
            web.settings.javaScriptEnabled = true
            web.settings.allowFileAccess = false
            web.settings.allowContentAccess = false
            web.settings.domStorageEnabled = false
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (started || !cont.isActive) return
                    started = true
                    view.evaluateJavascript("""
                        JSON.stringify(Array.from(document.querySelectorAll('#b_results .b_algo, li.b_algo')).slice(0,6).map(el => {
                          const a=el.querySelector('h2 a, h3 a');
                          return a ? {title:a.innerText,url:a.href,snippet:el.innerText.slice(0,1600)} : null;
                        }).filter(Boolean))
                    """.trimIndent()) { result ->
                        if (cont.isActive) {
                            try {
                                val json = JSONArray("[$result]").getString(0)
                                val items = JSONArray(json)
                                if (items.length() == 0 && attempts++ < 4) {
                                    started = false
                                    view.postDelayed({ if (cont.isActive && !destroyed) onPageFinished(view, url) }, 1500)
                                    return@evaluateJavascript
                                }
                                if (items.length() == 0) throw IllegalStateException("Bing 网页和 RSS 均未返回可读取结果；可关闭联网搜索使用普通聊天")
                                val content = (0 until items.length()).joinToString("\n\n") { i ->
                                    val obj = items.getJSONObject(i)
                                    "[${i+1}] ${obj.getString("title")}\n${obj.getString("url")}\n${obj.getString("snippet") }"
                                }
                                cont.resume(content)
                            } catch (e: Exception) { cont.resumeWithException(e) }
                        }
                        cleanup()
                    }
                }
            }
            cont.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { cleanup() } }
            web.loadUrl("https://www.bing.com/search?q=" + java.net.URLEncoder.encode(query.take(1000), "UTF-8"))
        }
    }
}
