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
 * Gecko Default protection is TRR_MODE_OFF, so the overlay must raise the *engine*
 * mode to INCREASED (TRR_FIRST) without writing that into Settings.
 * Pin the TXT IP with network.trr.bootstrapAddr (bootstrapAddress is pre-FF89).
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
        val cached = DohTxtLookup.parsePayload(settings.dohTxtCachedEndpoint)
        if (cached != null) {
            main.post {
                val status = runCatching { applyEndpoint(settings, engine, cached, cachedLabel = true) }
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
        val cached = DohTxtLookup.parsePayload(settings.dohTxtCachedEndpoint)
        val endpoint = live ?: cached ?: return "TXT 无 ip:port"
        if (live != null) {
            settings.dohTxtCachedEndpoint = live.toString()
        }
        main.post {
            applyEndpoint(settings, engine, endpoint, cachedLabel = live == null)
        }
        return if (live != null) endpoint.toString() else "缓存 ${endpoint}"
    }

    private fun applyEndpoint(
        settings: Settings,
        engine: Engine,
        endpoint: DohTxtLookup.Endpoint,
        cachedLabel: Boolean,
    ): String {
        if (settings.getDohSettingsMode() == Engine.DohSettingsMode.OFF) {
            restoreOriginal(settings, engine)
            return "Off 时 dohtxt 不生效"
        }
        val uri = resolveUri(settings) ?: return "需要 https://域名/ice"
        val built = uri.buildUpon().encodedAuthority("${uri.host}:${endpoint.port}").build().toString()
        val engineMode = overlayMode(settings.getDohSettingsMode())
        if (built != lastBuilt || endpoint.ip != lastBootstrap) {
            engine.settings.dohProviderUrl = built
            engine.settings.dohSettingsMode = engineMode
            setBootstrap(endpoint.ip)
            setConfirmationSkip(true)
            lastBuilt = built
            lastBootstrap = endpoint.ip
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
        engine.settings.dohProviderUrl = settings.dohProviderUrl
        engine.settings.dohSettingsMode = settings.getDohSettingsMode()
        setBootstrap("")
        setConfirmationSkip(false)
    }

    private fun setBootstrap(ip: String) {
        val prefs = GeckoBundle(2)
        // FF89+ name; keep the old key for older GeckoView AARs.
        prefs.putString("network.trr.bootstrapAddr", ip)
        prefs.putString("network.trr.bootstrapAddress", ip)
        EventDispatcher.getInstance().dispatch("GeckoView:SetDefaultPrefs", prefs)
        EventDispatcher.getInstance().dispatch("GeckoView:SetUserPrefs", prefs)
    }

    private fun setConfirmationSkip(skip: Boolean) {
        val prefs = GeckoBundle(1)
        prefs.putString("network.trr.confirmationNS", if (skip) "skip" else "example.com")
        EventDispatcher.getInstance().dispatch("GeckoView:SetDefaultPrefs", prefs)
        EventDispatcher.getInstance().dispatch("GeckoView:SetUserPrefs", prefs)
    }

    private const val TAG = "DohTxt"
}
