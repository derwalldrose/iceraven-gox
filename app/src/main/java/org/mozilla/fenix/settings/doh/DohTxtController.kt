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
 * Website-edge overlay. Does not persist over [Settings.dohProviderUrl].
 * When enabled, the selected DoH URL stays on 443 and gains `?txt=<host>` so
 * echdoh rewrites CF **site** A + HTTPS RR to that TXT `ip:port`.
 * Disabling restores the stored DoH URL and protection mode.
 */
internal object DohTxtController {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L

    @Volatile private var boundSettings: Settings? = null
    @Volatile private var boundEngine: Engine? = null
    @Volatile private var lastBuilt: String = ""

    private val refresh = Runnable {
        val settings = boundSettings ?: return@Runnable
        val engine = boundEngine ?: return@Runnable
        applyFromSettings(settings, engine)
    }

    fun applyFromSettings(settings: Settings, engine: Engine, onStatus: (String) -> Unit = {}) {
        boundSettings = settings
        boundEngine = engine
        main.removeCallbacks(refresh)
        if (txtHost(settings) == null) {
            lastBuilt = ""
            main.post {
                restoreOriginal(settings, engine)
                onStatus("")
            }
            return
        }
        io.execute {
            val status = runCatching { applyBlocking(settings, engine) }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    Log.w(TAG, "dohtxt apply failed", error)
                    error.message?.take(80) ?: "TXT 查询失败"
                },
            )
            main.post {
                onStatus(status)
                if (txtHost(settings) != null) {
                    main.removeCallbacks(refresh)
                    main.postDelayed(refresh, REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    private fun txtHost(settings: Settings): String? {
        val mode = settings.getDohSettingsMode()
        if (mode == Engine.DohSettingsMode.OFF || mode == Engine.DohSettingsMode.DEFAULT) return null
        return settings.dohTxtHost.split(',', ' ', ';')
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun applyBlocking(settings: Settings, engine: Engine): String {
        val host = txtHost(settings) ?: return ""
        val live = DohTxtLookup.lookup(listOf(host)).firstOrNull()
        if (live != null) {
            settings.dohTxtCachedEndpoint = live.toString()
            settings.dohTxtCachedHost = host
        }
        val built = withTxtParam(settings.dohProviderUrl, host) ?: return "需要 https 自定义 DoH"
        main.post {
            applyUri(settings, engine, built)
        }
        return when {
            live == null -> "$host → DoH ?txt="
            live.fromTxt -> "$host → TXT ${live}"
            else -> "$host → A ${live.ip}"
        }
    }

    private fun applyUri(settings: Settings, engine: Engine, built: String) {
        if (settings.getDohSettingsMode() == Engine.DohSettingsMode.OFF) {
            restoreOriginal(settings, engine)
            return
        }
        clearResolverPin()
        val engineMode = settings.getDohSettingsMode()
        if (built != lastBuilt || engine.settings.dohProviderUrl != built) {
            engine.settings.dohSettingsMode = engineMode
            engine.settings.dohProviderUrl = built
            lastBuilt = built
        }
    }

    internal fun withTxtParam(raw: String, host: String): String? {
        val uri = Uri.parse(raw)
        val name = uri.host?.lowercase() ?: return null
        if (uri.scheme != "https" || name.isBlank()) return null
        val b = uri.buildUpon().clearQuery()
        for (key in uri.queryParameterNames) {
            if (key.equals("txt", ignoreCase = true)) continue
            for (value in uri.getQueryParameters(key)) {
                b.appendQueryParameter(key, value)
            }
        }
        b.appendQueryParameter("txt", host)
        return b.build().toString()
    }

    fun restoreOriginal(settings: Settings, engine: Engine) {
        clearResolverPin()
        engine.settings.dohProviderUrl = settings.dohProviderUrl
        engine.settings.dohSettingsMode = settings.getDohSettingsMode()
    }

    private fun clearResolverPin() {
        setIntPref("network.proxy.type", 0)
        setStringPref("network.trr.confirmationNS", "example.com")
        setStringPref("network.trr.bootstrapAddr", "")
        setStringPref("network.trr.bootstrapAddress", "")
    }

    private fun setIntPref(name: String, value: Int) {
        val defaults = GeckoBundle(1)
        defaults.putInt(name, value)
        EventDispatcher.getInstance().dispatch("GeckoView:SetDefaultPrefs", defaults)
        val item = GeckoBundle(4)
        item.putString("pref", name)
        item.putString("branch", "user")
        item.putInt("type", PREF_TYPE_INT)
        item.putInt("value", value)
        val request = GeckoBundle(1)
        request.putBundleArray("prefs", arrayOf(item))
        EventDispatcher.getInstance().dispatch("GeckoView:Preferences:SetPref", request)
    }

    private fun setStringPref(name: String, value: String) {
        val defaults = GeckoBundle(1)
        defaults.putString(name, value)
        EventDispatcher.getInstance().dispatch("GeckoView:SetDefaultPrefs", defaults)
        val item = GeckoBundle(4)
        item.putString("pref", name)
        item.putString("branch", "user")
        item.putInt("type", PREF_TYPE_STRING)
        item.putString("value", value)
        val request = GeckoBundle(1)
        request.putBundleArray("prefs", arrayOf(item))
        EventDispatcher.getInstance().dispatch("GeckoView:Preferences:SetPref", request)
    }

    private const val PREF_TYPE_STRING = 32
    private const val PREF_TYPE_INT = 64
    private const val TAG = "DohTxt"
}
