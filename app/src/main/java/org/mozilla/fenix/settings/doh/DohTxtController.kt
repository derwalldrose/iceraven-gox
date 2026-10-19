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
 * TXT is looked up on a timer and cached; the UI does not require a manual apply.
 */
internal object DohTxtController {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    const val DEFAULT_TXT_HOST = "ustxt.270376.xyz"
    const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L

    @Volatile private var boundSettings: Settings? = null
    @Volatile private var boundEngine: Engine? = null
    @Volatile private var lastBuilt: String = ""
    @Volatile private var lastBootstrap: String = ""

    private val refresh = Runnable {
        val settings = boundSettings ?: return@Runnable
        val engine = boundEngine ?: return@Runnable
        applyFromSettings(settings, engine)
    }

    fun applyFromSettings(settings: Settings, engine: Engine, onStatus: (String) -> Unit = {}) {
        boundSettings = settings
        boundEngine = engine
        main.removeCallbacks(refresh)
        if (!settings.dohTxtEnabled) {
            lastBuilt = ""
            lastBootstrap = ""
            restoreOriginal(settings, engine)
            onStatus("")
            return
        }
        io.execute {
            val status = runCatching { applyBlocking(settings, engine) }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    Log.w(TAG, "dohtxt apply failed", error)
                    cachedStatus(settings, engine, error.message?.take(80) ?: "TXT 查询失败")
                },
            )
            main.post {
                onStatus(status)
                if (settings.dohTxtEnabled) {
                    main.removeCallbacks(refresh)
                    main.postDelayed(refresh, REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    private fun applyBlocking(settings: Settings, engine: Engine): String {
        val mode = settings.getDohSettingsMode()
        if (mode == Engine.DohSettingsMode.OFF) {
            restoreOriginal(settings, engine)
            return "Off 时 dohtxt 不生效"
        }
        val uri = Uri.parse(settings.dohTxtUri.ifBlank { settings.dohProviderUrl })
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
            return "需要 https://域名/ice"
        }
        val hosts = settings.dohTxtHost.split(',', ' ', ';').filter { it.isNotBlank() }
        val live = DohTxtLookup.lookup(hosts.ifEmpty { listOf(DEFAULT_TXT_HOST) }).firstOrNull()
        val cached = DohTxtLookup.parsePayload(settings.dohTxtCachedEndpoint)
        val endpoint = live ?: cached ?: return "TXT 无 ip:port"
        if (live != null) {
            settings.dohTxtCachedEndpoint = live.toString()
        }
        val built = uri.buildUpon().encodedAuthority("${uri.host}:${endpoint.port}").build().toString()
        main.post {
            if (built != lastBuilt || endpoint.ip != lastBootstrap) {
                engine.settings.dohProviderUrl = built
                engine.settings.dohSettingsMode = mode
                setBootstrap(endpoint.ip)
                lastBuilt = built
                lastBootstrap = endpoint.ip
            }
        }
        return if (live != null) endpoint.toString() else "缓存 ${endpoint}"
    }

    private fun cachedStatus(settings: Settings, engine: Engine, error: String): String {
        val cached = DohTxtLookup.parsePayload(settings.dohTxtCachedEndpoint) ?: return error
        val uri = Uri.parse(settings.dohTxtUri.ifBlank { settings.dohProviderUrl })
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return error
        val built = uri.buildUpon().encodedAuthority("${uri.host}:${cached.port}").build().toString()
        val mode = settings.getDohSettingsMode()
        main.post {
            engine.settings.dohProviderUrl = built
            engine.settings.dohSettingsMode = mode
            setBootstrap(cached.ip)
            lastBuilt = built
            lastBootstrap = cached.ip
        }
        return "缓存 ${cached}"
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
