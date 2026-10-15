package org.mozilla.fenix.goxai

import android.os.Bundle
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

private var conversation: AiConversationState? = null
val Context.aiConversation: AiConversationState
    get() = conversation ?: AiConversationState(applicationContext).also { conversation = it }

/** Separate page so browser tabs and navigation remain untouched. */
class AiChatActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = android.widget.FrameLayout(this).apply { id = android.R.id.custom }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction().replace(root.id, AiChatFragment()).commit()
    }

    fun showSettings() {
        val prefs = getSharedPreferences("gox_ai", 0)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,16,32,16) }
        fun field(key: String, hint: String, default: String, secret: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint
            setText(prefs.getString(key, default))
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            form.addView(this)
        }
        val base = field("ai_base_url", "API 地址（包含 /v1）", AiDefaults.BASE_URL)
        val model = field("ai_model", "模型", AiDefaults.MODEL)
        val key = field("ai_api_key", "API Key", "", true)
        AlertDialog.Builder(this).setTitle("AI 接口设置").setView(form)
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                val uri = android.net.Uri.parse(base.text.toString().trim())
                if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
                    android.widget.Toast.makeText(this, "接口必须是有效 HTTPS 地址", android.widget.Toast.LENGTH_LONG).show()
                } else prefs.edit().putString("ai_base_url", base.text.toString().trim().trimEnd('/'))
                    .putString("ai_model", model.text.toString().trim()).putString("ai_api_key",key.text.toString().trim()).apply()
            }.show()
    }
}
