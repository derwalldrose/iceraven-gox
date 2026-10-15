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
    suspend fun fetch(context: Context, query: String): String = withTimeout(30_000) {
        suspendCancellableCoroutine { cont ->
            val web = WebView(context)
            var started = false
            fun cleanup() { web.stopLoading(); web.destroy() }
            web.settings.javaScriptEnabled = true
            web.settings.allowFileAccess = false
            web.settings.allowContentAccess = false
            web.settings.domStorageEnabled = false
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (started || !cont.isActive) return
                    started = true
                    view.evaluateJavascript("""
                        JSON.stringify(Array.from(document.querySelectorAll('li.b_algo')).slice(0,6).map(el => {
                          const a=el.querySelector('h2 a');
                          return a ? {title:a.innerText,url:a.href,snippet:el.innerText.slice(0,1600)} : null;
                        }).filter(Boolean))
                    """.trimIndent()) { result ->
                        if (cont.isActive) {
                            try {
                                val json = JSONArray("[$result]").getString(0)
                                val items = JSONArray(json)
                                if (items.length() == 0) throw IllegalStateException("Bing 未返回可读取的结果，可能需要验证；未进行联网回答")
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
