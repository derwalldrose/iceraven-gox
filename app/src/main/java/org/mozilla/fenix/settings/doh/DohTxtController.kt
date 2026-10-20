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
 *
 * Apply order matters: Gecko MaybeBootstrap no-ops while mode is TRR_OFF
 * (Default protection), and URI changes start confirmation immediately.
 * Prefs and mode must be in place before Engine.settings.dohProviderUrl is set.
 */
internal object DohTxtController {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    const val DEFAULT_TXT_HOST = "ustxt.270376.xyz"
    const val DEFAULT_DOH_URI = "https://do.new.tg/ice"
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
            main.post {
                restoreOriginal(settings, engine)
                onStatus("")
            }
            return
        }
        val cached = cachedForCurrentHost(settings)
        if (cached != null) {
            main.post {
                val status = runCatching { applyEndpoint(settings, engine, cached, cachedLabel = true, preferred = emptySet()) }
                    .getOrElse { "缓存 ${cached}" }
                onStatus(status)
            }
        }
        io.execute {
            val status = runCatching { applyBlocking(settings, engine) }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    Log.w(TAG, "dohtxt apply failed", error)
                    cached?.let { "缓存 ${it}" } ?: (error.message?.take(80) ?: "TXT 查询失败")
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
        val hosts = settings.dohTxtHost.split(',', ' ', ';').filter { it.isNotBlank() }
        val live = DohTxtLookup.lookup(hosts.ifEmpty { listOf(DEFAULT_TXT_HOST) }).firstOrNull()
        val preferred = DohTxtLookup.lookupA(PREFERRED_A_HOSTS)
        val cached = cachedForCurrentHost(settings)
        val endpoint = live ?: cached ?: return "TXT 无 ip:port"
        if (live != null) {
            settings.dohTxtCachedEndpoint = live.toString()
            settings.dohTxtCachedHost = hosts.firstOrNull() ?: DEFAULT_TXT_HOST
        }
        main.post {
            applyEndpoint(settings, engine, endpoint, cachedLabel = live == null, preferred = preferred)
        }
        return if (live != null) endpoint.toString() else "缓存 ${endpoint}"
    }

    private fun cachedForCurrentHost(settings: Settings): DohTxtLookup.Endpoint? {
        val current = settings.dohTxtHost.split(',', ' ', ';').firstOrNull { it.isNotBlank() }
            ?: DEFAULT_TXT_HOST
        if (settings.dohTxtCachedHost.isNotBlank() && settings.dohTxtCachedHost != current) {
            return null
        }
        return DohTxtLookup.parsePayload(settings.dohTxtCachedEndpoint)
    }

    private fun applyEndpoint(
        settings: Settings,
        engine: Engine,
        endpoint: DohTxtLookup.Endpoint,
        cachedLabel: Boolean,
        preferred: Set<String>,
    ): String {
        if (settings.getDohSettingsMode() == Engine.DohSettingsMode.OFF) {
            restoreOriginal(settings, engine)
            return "Off 时 dohtxt 不生效"
        }
        val uri = resolveUri(settings) ?: return "需要 https://域名/ice"
        val built = uri.buildUpon().encodedAuthority("${uri.host}:${endpoint.port}").build().toString()
        val engineMode = overlayMode(settings.getDohSettingsMode())
        if (built != lastBuilt || endpoint.ip != lastBootstrap) {
            setConfirmationSkip(true)
            setBootstrap(endpoint.ip)
            engine.settings.dohSettingsMode = engineMode
            engine.settings.dohProviderUrl = built
            lastBuilt = built
            lastBootstrap = endpoint.ip
        }
        if (preferred.isNotEmpty()) {
            DohTxtOriginProxy.update(endpoint, uri.host.orEmpty(), uri.encodedPath ?: "/ice", preferred)
            DohTxtOriginProxy.start()
            setOriginProxy(true, uri.host.orEmpty())
        }
        return if (cachedLabel) "缓存 ${endpoint}" else endpoint.toString()
    }

    private fun overlayMode(stored: Engine.DohSettingsMode): Engine.DohSettingsMode =
        when (stored) {
            Engine.DohSettingsMode.MAX -> Engine.DohSettingsMode.MAX
            else -> Engine.DohSettingsMode.INCREASED
        }

    private fun resolveUri(settings: Settings): Uri? {
        val raw = settings.dohTxtUri.ifBlank { DEFAULT_DOH_URI }
        val uri = Uri.parse(raw)
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme != "https" || host.isBlank()) return null
        if (host.contains("ustxt") || host.contains("krtxt") || host.contains("detxt") || host.contains("uktxt")) {
            return null
        }
        return uri
    }

    fun restoreOriginal(settings: Settings, engine: Engine) {
        setOriginProxy(false, "")
        DohTxtOriginProxy.stop()
        setConfirmationSkip(false)
        setBootstrap("")
        engine.settings.dohProviderUrl = settings.dohProviderUrl
        engine.settings.dohSettingsMode = settings.getDohSettingsMode()
    }

    private fun setBootstrap(ip: String) {
        setStringPref("network.trr.bootstrapAddr", ip)
        setStringPref("network.trr.bootstrapAddress", ip)
    }

    private fun setConfirmationSkip(skip: Boolean) {
        setStringPref("network.trr.confirmationNS", if (skip) "skip" else "example.com")
    }

    private fun setOriginProxy(enabled: Boolean, dohHost: String) {
        if (!enabled) {
            setIntPref("network.proxy.type", 0)
            return
        }
        val port = DohTxtOriginProxy.listenPort
        if (port <= 0) return
        setStringPref("network.proxy.http", "127.0.0.1")
        setIntPref("network.proxy.http_port", port)
        setStringPref("network.proxy.ssl", "127.0.0.1")
        setIntPref("network.proxy.ssl_port", port)
        setBoolPref("network.proxy.share_proxy_settings", true)
        val bypass = listOf("localhost", "127.0.0.1", dohHost, "ustxt.270376.xyz", "uktxt.270376.xyz", "krtxt.270376.xyz")
            .filter { it.isNotBlank() }
            .joinToString(", ")
        setStringPref("network.proxy.no_proxies_on", bypass)
        setIntPref("network.proxy.type", 1)
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

    private fun setBoolPref(name: String, value: Boolean) {
        val defaults = GeckoBundle(1)
        defaults.putBoolean(name, value)
        EventDispatcher.getInstance().dispatch("GeckoView:SetDefaultPrefs", defaults)
        val item = GeckoBundle(4)
        item.putString("pref", name)
        item.putString("branch", "user")
        item.putInt("type", PREF_TYPE_BOOL)
        item.putBoolean("value", value)
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
    private const val PREF_TYPE_BOOL = 128
    private val PREFERRED_A_HOSTS = listOf("jp.270376.xyz")
    private const val TAG = "DohTxt"
}
