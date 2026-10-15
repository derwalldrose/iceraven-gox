package org.mozilla.fenix.goxai

import org.mozilla.fenix.R

import android.annotation.SuppressLint
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject

class AiChatFragment : Fragment() {
    private lateinit var state: AiConversationState
    private var web: WebView? = null
    private var ready = false
    private var removeObserver: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private val redraw = Runnable { scheduled = false; render() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View =
        inflater.inflate(R.layout.fragment_ai_chat, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, saved: Bundle?) {
        state = requireContext().aiConversation
        val prefs = requireContext().getSharedPreferences("gox_ai", 0)
        view.findViewById<TextView>(R.id.ai_model).text = prefs.getString("ai_model", AiDefaults.MODEL)
        view.findViewById<View>(R.id.ai_settings).setOnClickListener { (activity as? AiChatActivity)?.showSettings() }
        view.findViewById<android.widget.CheckBox>(R.id.ai_search).apply {
            isChecked = state.searchEnabled
            setOnCheckedChangeListener { _, checked -> state.searchEnabled = checked }
        }
        val input = view.findViewById<EditText>(R.id.ai_input)
        input.setText(state.draft)
        view.findViewById<View>(R.id.ai_composer).setOnClickListener {
            input.requestFocus()
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        val assets = requireContext().assets
        web = view.findViewById<WebView>(R.id.ai_messages).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host == "gox-chat.invalid" && uri.path?.matches(Regex("/ai/[A-Za-z0-9.-]+")) == true) {
                        val file = uri.path!!.removePrefix("/")
                        val mime = when { file.endsWith(".js") -> "application/javascript"; file.endsWith(".css") -> "text/css"; else -> "text/html" }
                        return try { WebResourceResponse(mime, "UTF-8", assets.open(file)) } catch (_: Exception) { blocked() }
                    }
                    return blocked()
                }
                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame && request.hasGesture() && request.url.scheme == "gox-action") {
                        val parts = request.url.pathSegments
                        val index = parts.firstOrNull()?.toIntOrNull()
                        if (index != null && index in state.messages.indices) {
                            when (request.url.host) {
                                "copy" -> copy(state.messages[index].content)
                                "copy-code" -> {
                                    val codeIndex = parts.getOrNull(1)?.toIntOrNull()
                                    if (codeIndex != null && codeIndex >= 0) web?.evaluateJavascript("Array.from(document.querySelectorAll('article'))[$index].querySelectorAll('pre code')[$codeIndex]?.textContent || ''") { result ->
                                        runCatching { copy(JSONArray("[$result]").getString(0)) }
                                    }
                                }
                                "retry" -> state.retry(index)
                            }
                        }
                    }
                    if (request.isForMainFrame && request.hasGesture() && request.url.scheme == "https") {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
                    }
                    return true
                }
                override fun onPageFinished(v: WebView?, url: String?) {
                    if (url == PAGE) { ready = true; render() }
                }
            }
            loadUrl(PAGE)
        }
        view.findViewById<TextView>(R.id.ai_clear).setOnClickListener { state.clear() }
        view.findViewById<TextView>(R.id.ai_send).setOnClickListener {
            if (state.isGenerating) state.stop()
            else {
                if (Build.VERSION.SDK_INT >= 33 && requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2401)
                }
                val text = input.text.toString()
                if (state.send(text)) { input.setText(""); state.draft = "" }
            }
        }
        removeObserver = state.observe {
            view.findViewById<TextView>(R.id.ai_send).text = if (state.isGenerating) "停止" else "发送"
            if (!scheduled) { scheduled = true; handler.postDelayed(redraw, 80) }
        }
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), "".byteInputStream())
    private fun copy(text: String) {
        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Gox AI", text))
    }

    private fun render() {
        if (!ready) return
        val array = JSONArray()
        state.messages.forEach { array.put(JSONObject().put("role", it.role).put("content", it.content).put("status", it.status)) }
        web?.evaluateJavascript("GoxChat.update(JSON.parse(${JSONObject.quote(array.toString())}),false)", null)
    }

    override fun onDestroyView() {
        state.draft = view?.findViewById<EditText>(R.id.ai_input)?.text?.toString().orEmpty()
        removeObserver?.invoke(); removeObserver = null
        handler.removeCallbacks(redraw); scheduled = false; ready = false
        web?.stopLoading(); web?.destroy(); web = null
        super.onDestroyView()
    }

    companion object { private const val PAGE = "https://gox-chat.invalid/ai/renderer.html" }
}
