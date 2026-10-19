package org.mozilla.fenix.settings.doh

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import mozilla.components.concept.engine.Engine
import org.mozilla.fenix.utils.Settings
import org.mozilla.gecko.EventDispatcher
import org.mozilla.gecko.util.GeckoBundle
import java.util.concurrent.Executors

/**
 * Optional TXT preferred-edge overlay. Does not persist over [Settings.dohProviderUrl]
 * or protection-level prefs so disabling restores the previous DoH configuration.
 */
internal object DohTxtController {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    const val DEFAULT_TXT_HOST = "ustxt.270376.xyz"

    fun applyFromSettings(settings: Settings, engine: Engine, onStatus: (String) -> Unit = {}) {
        if (!settings.dohTxtEnabled) {
            restoreOriginal(settings, engine)
            onStatus("")
            return
        }
        io.execute {
            val status = runCatching { applyBlocking(settings, engine) }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    Log.w(TAG, "dohtxt apply failed", error)
                    "dohtxt 失败：${error.message?.take(80) ?: "TXT 查询失败"}"
                },
            )
            main.post { onStatus(status) }
        }
    }

    private fun applyBlocking(settings: Settings, engine: Engine): String {
        val mode = settings.getDohSettingsMode()
        if (mode != Engine.DohSettingsMode.INCREASED && mode != Engine.DohSettingsMode.MAX) {
            return "请先选择 Increased 或 Max；dohtxt 不改原档位"
        }
        val uri = Uri.parse(settings.dohTxtUri.ifBlank { settings.dohProviderUrl })
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
            return "请填写 HTTPS DoH 域名（证书主机名），例如 https://xxx/ice"
        }
        val hosts = settings.dohTxtHost.split(',', ' ', ';').filter { it.isNotBlank() }
        val endpoints = DohTxtLookup.lookup(hosts.ifEmpty { listOf(DEFAULT_TXT_HOST) })
        val endpoint = endpoints.firstOrNull() ?: return "TXT 无可用 ip:port：${hosts.joinToString()}"
        val built = uri.buildUpon().encodedAuthority("${uri.host}:${endpoint.port}").build().toString()
        main.post {
            engine.settings.dohProviderUrl = built
            engine.settings.dohSettingsMode = mode
            setBootstrap(endpoint.ip)
        }
        return "dohtxt ${endpoint.ip}:${endpoint.port} → $built"
    }

    fun restoreOriginal(settings: Settings, engine: Engine) {
        engine.settings.dohProviderUrl = settings.dohProviderUrl
        engine.settings.dohSettingsMode = settings.getDohSettingsMode()
        setBootstrap("")
    }

    private fun setBootstrap(ip: String) {
        val prefs = GeckoBundle(1)
        prefs.putString("network.trr.bootstrapAddress", ip)
        EventDispatcher.getInstance().dispatch("GeckoView:SetDefaultPrefs", prefs)
    }

    private const val TAG = "DohTxt"
}
