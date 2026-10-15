package org.mozilla.fenix.goxai

import org.mozilla.fenix.R

import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Credentials and transport never enter the renderer. Cancellation disconnects blocked IO. */
object AiChatClient {
    data class Message(val role: String, var content: String, var status: String = "")

    fun requestBody(model: String, system: String, messages: List<Message>): String {
        val list = JSONArray()
        if (system.isNotBlank()) list.put(JSONObject().put("role", "system").put("content", system))
        messages.filter { it.role in setOf("user", "assistant") && it.content.isNotBlank() && it.status !in setOf("welcome", "error") }
            .forEach { list.put(JSONObject().put("role", it.role).put("content", it.content)) }
        return JSONObject().put("model", model).put("stream", true).put("messages", list).toString()
    }

    /** SSE framing: comments ignored, data lines joined, EOF dispatched, DONE terminates. */
    fun readEvents(reader: BufferedReader, isActive: () -> Boolean = { true }, onEvent: (String) -> Boolean) {
        val data = mutableListOf<String>()
        var eventType = ""
        fun dispatch(): Boolean {
            if (data.isEmpty()) { eventType = ""; return true }
            val event = data.joinToString("\n")
            data.clear()
            if (eventType == "error") throw IllegalStateException("API returned an SSE error")
            eventType = ""
            return event.trim() != "[DONE]" && onEvent(event)
        }
        while (isActive()) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) { if (!dispatch()) return }
            else if (line.startsWith("data:")) data += line.substring(5).removePrefix(" ")
            else if (line.startsWith("event:")) eventType = line.substring(6).trim()
        }
        if (isActive()) dispatch()
    }

    fun delta(data: String): String {
        val json = JSONObject(data)
        if (json.has("error")) throw IllegalStateException("API returned a stream error")
        val choices = json.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val part = choices.getJSONObject(0).optJSONObject("delta") ?: return ""
        return if (part.isNull("content")) "" else part.optString("content", "")
    }

    suspend fun stream(baseUrl: String, apiKey: String, model: String, system: String,
                       messages: List<Message>, onDelta: (String) -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        continuation.invokeOnCancellation { connection.getAndSet(null)?.disconnect() }
        thread(name = "Gox-AI-stream", isDaemon = true) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
                require(url.protocol == "https" || (url.protocol == "http" && url.host in setOf("localhost", "127.0.0.1"))) { "AI endpoint must use HTTPS" }
                require(url.userInfo == null) { "Endpoint must not contain credentials" }
                conn = url.openConnection() as HttpURLConnection
                connection.set(conn)
                if (!continuation.isActive) return@thread
                conn.instanceFollowRedirects = false
                conn.requestMethod = "POST"
                conn.connectTimeout = 20_000
                conn.readTimeout = 120_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.setRequestProperty("User-Agent", "Gox/AI-chat")
                if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.outputStream.use { it.write(requestBody(model, system, messages).toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    readEvents(reader, { continuation.isActive }) { event ->
                        val token = delta(event)
                        if (token.isNotEmpty() && continuation.isActive) onDelta(token)
                        continuation.isActive
                    }
                }
                if (continuation.isActive) continuation.resume(Unit)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            } finally {
                connection.compareAndSet(conn, null)
                conn?.disconnect()
            }
        }
    }
}
