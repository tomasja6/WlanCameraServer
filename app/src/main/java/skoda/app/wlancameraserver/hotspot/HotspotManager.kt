package skoda.app.wlancameraserver.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Správa Wi-Fi přihlašovacích údajů pro aplikaci.
 *
 * Podporuje dva režimy:
 *
 *  ▶ [useSystemHotspot] = true  (výchozí pro infotainment):
 *    Nespouští žádný vlastní hotspot – pouze přečte SSID+heslo ze systémového
 *    hotspotu/AP který infotainment/zařízení již provozuje.
 *    Využívá [SystemHotspotReader] (SoftApConfiguration → Settings → manuální fallback).
 *
 *  ▶ [useSystemHotspot] = false (legacy režim):
 *    Spustí vlastní LocalOnlyHotspot přes WifiManager (původní chování).
 *    Vhodné pro telefony/tablety bez sdíleného systémového AP.
 */
class HotspotManager(context: Context) {

    interface HotspotCallback {
        fun onStarted(ssid: String, psk: String)
        fun onStopped()
        fun onFailed(reason: String)
    }

    private val TAG = "HotspotManager"
    private val appContext: Context = context.applicationContext
    private val wifiManager: WifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val systemHotspotReader = SystemHotspotReader(appContext)

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var callback: HotspotCallback? = null

    /**
     * true  = čte existující systémový hotspot (výchozí pro infotainment)
     * false = vytváří vlastní LocalOnlyHotspot (legacy pro telefon/tablet)
     */
    var useSystemHotspot: Boolean = true

    // ── Systémový hotspot (infotainment / sdílený AP) ─────────────────────────

    /**
     * Přečte SSID+PSK ze systémového hotspotu a zavolá [cb].
     * Pokud systém data neposkytne, použije [manualSsid]/[manualPsk] jako fallback.
     * Žádný vlastní hotspot se nespouští.
     */
    fun readSystemCredentials(
        cb: HotspotCallback,
        manualSsid: String? = null,
        manualPsk: String? = null
    ) {
        when (val result = systemHotspotReader.read(manualSsid, manualPsk)) {
            is SystemHotspotReader.Result.Success -> {
                val (ssid, psk) = result.credentials
                Log.i(TAG, "System hotspot credentials read: SSID=$ssid")
                Handler(Looper.getMainLooper()).post {
                    cb.onStarted(ssid, psk)
                }
            }
            is SystemHotspotReader.Result.Failure -> {
                Log.e(TAG, "System hotspot read failed: ${result.reason}")
                Handler(Looper.getMainLooper()).post {
                    cb.onFailed(result.reason)
                }
            }
        }
    }

    // ── Unified entry point ───────────────────────────────────────────────────

    /**
     * Spustí hotspot v nastaveném režimu ([useSystemHotspot]).
     *  - true  → zavolá [readSystemCredentials]
     *  - false → spustí LocalOnlyHotspot (původní chování)
     *
     * @param manualSsid  manuálně zadané SSID (fallback pro systémový režim)
     * @param manualPsk   manuálně zadané heslo (fallback pro systémový režim)
     */
    fun startHotspot(
        cb: HotspotCallback,
        manualSsid: String? = null,
        manualPsk: String? = null
    ) {
        if (useSystemHotspot) {
            readSystemCredentials(cb, manualSsid, manualPsk)
        } else {
            startLocalOnlyHotspot(cb)
        }
    }

    // ── Legacy: LocalOnlyHotspot ──────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startLocalOnlyHotspot(cb: HotspotCallback) {
        callback = cb

        val hotspotCallback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                this@HotspotManager.reservation = res

                val sac = res.softApConfiguration
                val actualSsid = sac.wifiSsid?.toString()?.trim('"') ?: "CamHost_Unknown"
                val actualPsk  = sac.passphrase ?: ""

                Log.i(TAG, "LocalOnlyHotspot started SSID=$actualSsid")
                Handler(Looper.getMainLooper()).post {
                    callback?.onStarted(actualSsid, actualPsk)
                }
            }

            override fun onStopped() {
                Log.i(TAG, "LocalOnlyHotspot stopped")
                this@HotspotManager.reservation = null
                Handler(Looper.getMainLooper()).post { callback?.onStopped() }
            }

            override fun onFailed(reason: Int) {
                Log.e(TAG, "LocalOnlyHotspot failed reason=$reason")
                Handler(Looper.getMainLooper()).post {
                    callback?.onFailed("Hotspot failed: $reason")
                }
            }
        }

        try {
            wifiManager.startLocalOnlyHotspot(hotspotCallback, Handler(Looper.getMainLooper()))
            Log.i(TAG, "startLocalOnlyHotspot called")
        } catch (e: Exception) {
            Log.e(TAG, "startLocalOnlyHotspot error", e)
            cb.onFailed(e.message ?: "Unknown error")
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    fun stopHotspot() {
        if (!useSystemHotspot) {
            // V legacy režimu zavřeme rezervaci
            try {
                reservation?.close()
            } catch (e: Exception) {
                Log.w(TAG, "stopHotspot error", e)
            }
            reservation = null
        }
        // V systémovém režimu nic neděláme – hotspot patří systému
    }

    @Suppress("unused")
    fun isActive(): Boolean = if (useSystemHotspot) true else reservation != null
}
