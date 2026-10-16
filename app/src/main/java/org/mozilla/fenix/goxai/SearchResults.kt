package org.mozilla.fenix.goxai

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** JSON search APIs only: no browser sessions, RSS, HTML extraction or page loading. */
object SearchResults {
    const val DEFAULT_ENDPOINT = "https://api.tavily.com/search"
    suspend fun fetch(context: Context, query: String, progress: (String) -> Unit = {}): String {
        progress("正在调用搜索 API")
        val prefs = context.getSharedPreferences("gox_ai", 0)
        val provider = prefs.getString("search_provider", "tavily").orEmpty()
        val endpoint = prefs.getString("search_api_url", DEFAULT_ENDPOINT).orEmpty()
        val key = prefs.getString("search_api_key", "").orEmpty()
        require(provider in setOf("tavily", "searxng")) { "搜索类型须为 tavily 或 searxng" }
        require(provider != "tavily" || key.isNotBlank()) { "请在 AI 设置填写 Tavily 搜索密钥（与聊天密钥独立）" }
        val uri = android.net.Uri.parse(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "搜索 API 必须使用 HTTPS" }
        val output = request(provider, endpoint, key, query)
        progress("正在回答")
        return output
    }

    private suspend fun request(provider: String, endpoint: String, key: String, query: String): String = suspendCancellableCoroutine { cont ->
        val active = AtomicReference<HttpURLConnection?>()
        cont.invokeOnCancellation { active.getAndSet(null)?.disconnect() }
        thread(name = "Gox-Search-API", isDaemon = true) {
            var connection: HttpURLConnection? = null
            try {
                val address = if (provider == "searxng") endpoint + (if (endpoint.contains('?')) "&" else "?") +
                    "format=json&language=zh-CN&q=" + java.net.URLEncoder.encode(query.take(1000), "UTF-8") else endpoint
                connection = URL(address).openConnection() as HttpURLConnection
                active.set(connection)
                if (!cont.isActive) return@thread
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 25_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "Gox/Search-API")
                if (key.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $key")
                if (provider == "tavily") {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    val payload = JSONObject().put("query", query.take(1000)).put("search_depth", "basic")
                        .put("max_results", 5).put("include_answer", false).put("include_raw_content", false)
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("搜索 API HTTP $code" + when (code) {
                    401,403 -> "：检查搜索密钥或接口权限"
                    429 -> "：搜索额度或频率受限"
                    else -> ""
                })
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val results = JSONObject(body).optJSONArray("results") ?: throw IllegalStateException("搜索 API 缺少 results 数组")
                val sources = (0 until minOf(results.length(), 5)).mapNotNull { i ->
                    val item = results.getJSONObject(i)
                    val url = item.optString("url")
                    if (!url.startsWith("https://") && !url.startsWith("http://")) null
                    else "[${i+1}] ${item.optString("title")}\n$url\n${item.optString("content").take(3500)}"
                }
                if (sources.isEmpty()) throw IllegalStateException("搜索 API 未返回有效来源")
                if (cont.isActive) cont.resume(sources.joinToString("\n\n"))
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            } finally { active.compareAndSet(connection, null); connection?.disconnect() }
        }
    }
}
