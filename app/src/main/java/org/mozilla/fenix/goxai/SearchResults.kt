package org.mozilla.fenix.goxai

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.mozilla.fenix.ext.components
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension

/** Read-only tools in a separate cookie context, sharing browser runtime and DoH. */
class BrowserSearchTools(private val context: Context) {
    private val runtime get() = context.components.core.geckoRuntime
    private var session: GeckoSession? = null
    private var pending: CompletableDeferred<JSONObject>? = null
    private val contextId = "gox-ai-" + java.util.UUID.randomUUID().toString()

    private suspend fun initialize() {
        if (session != null) return
        val installed = CompletableDeferred<WebExtension>()
        runtime.webExtensionController.ensureBuiltIn("resource://android/assets/gox-search/", "gox-search@local")
            .accept({ value -> if (value != null) installed.complete(value) else installed.completeExceptionally(IllegalStateException("读取扩展安装失败")) },
                { error -> installed.completeExceptionally(error ?: IllegalStateException("读取扩展安装失败，Gecko 未返回异常详情")) })
        val extension = installed.await()
        session = GeckoSession(GeckoSessionSettings.Builder().contextId(contextId).build()).also { tab ->
            tab.webExtensionController.setMessageDelegate(extension, object : WebExtension.MessageDelegate {
                override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                    if (sender.session == tab && message is JSONObject) pending?.complete(message)
                    return null
                }
            }, "gox_search")
            tab.open(runtime)
        }
    }

    private suspend fun read(url: String): JSONObject = withTimeout(35_000) {
        val uri = android.net.Uri.parse(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "仅允许 HTTPS 网页" }
        initialize()
        val response = CompletableDeferred<JSONObject>()
        pending = response
        session!!.loadUri(url)
        response.await()
    }

    suspend fun search(query: String): JSONObject {
        val template = context.getSharedPreferences("gox_ai", 0).getString("search_template", DEFAULT_SEARCH).orEmpty()
        require(template.startsWith("https://") && template.contains("{query}")) { "搜索地址必须是包含 {query} 的 HTTPS 模板" }
        return read(template.replace("{query}", java.net.URLEncoder.encode(query.take(1000), "UTF-8")))
    }
    suspend fun openResult(result: JSONObject): JSONObject = read(result.getString("url"))
    fun close() { pending?.cancel(); pending = null; session?.close(); session = null }
    companion object { const val DEFAULT_SEARCH = "https://www.bing.com/search?q={query}" }
}

/** Explicit app-orchestrated tool loop; does not assume model tool_calls support. */
object SearchResults {
    suspend fun fetch(context: Context, query: String, progress: (String) -> Unit = {}): String {
        val tools = BrowserSearchTools(context)
        try {
            progress("正在后台搜索")
            val search = tools.search(query)
            val results = search.optJSONArray("results")
            if (results == null || results.length() == 0) throw IllegalStateException("搜索页没有可读结果，可能需要验证码；请在浏览器打开搜索页检查")
            val collected = mutableListOf<String>()
            for (i in 0 until minOf(results.length(), 4)) {
                val item = results.getJSONObject(i)
                collected += "[${i+1}] ${item.optString("title")}\n${item.optString("url")}\n${item.optString("snippet") }"
                if (i < 2) {
                    progress("正在读取来源 ${i+1}")
                    try {
                        val page = tools.openResult(item)
                        collected += "来源正文：${page.optString("url")}\n${page.optString("text").take(7000)}"
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) { collected += "该来源读取超时，仅有摘要。" }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { collected += "该来源正文读取失败，仅有搜索摘要。" }
                }
            }
            progress("正在回答")
            return collected.joinToString("\n\n")
        } finally { tools.close() }
    }
}
