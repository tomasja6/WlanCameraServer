package skoda.app.wlancameraserver.hotspot

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log

/**
 * Čte přihlašovací údaje (SSID + heslo) z již existujícího systémového hotspotu/AP.
 * Nevytváří žádný hotspot – pouze čte co systém/infotainment již provozuje.
 *
 * Strategie (v pořadí spolehlivosti):
 *  1. WifiManager.getSoftApConfiguration() přes reflexi – na OEM/systémové appce
 *     (infotainment) tato metoda vrátí konfiguraci aktivního AP.
 *  2. Settings.Global / System – někteří OEM výrobci (Škoda/VW) ukládají AP SSID do
 *     systémových nastavení pod různými klíči.
 *  3. Manuálně uložené údaje zadané uživatelem v Settings (spolehlivý fallback).
 *
 * Výsledek je vždy [Result] – buď Success(ssid, psk) nebo Failure(reason).
 */
class SystemHotspotReader(private val context: Context) {

    private val TAG = "SystemHotspotReader"

    data class WifiCredentials(val ssid: String, val psk: String)

    sealed class Result {
        data class Success(val credentials: WifiCredentials) : Result()
        data class Failure(val reason: String) : Result()
    }

    /**
     * Pokusí se přečíst SSID + heslo systémového AP.
     * Volá se ze servisní vrstvy – výsledek ihned (synchronní).
     *
     * @param manualSsid  volitelně SSID zadané ručně uživatelem (fallback)
     * @param manualPsk   volitelně heslo zadané ručně uživatelem (fallback)
     */
    fun read(manualSsid: String? = null, manualPsk: String? = null): Result {

        // ── 1. SoftApConfiguration přes reflexi ──────────────────────────────
        //    WifiManager.getSoftApConfiguration() je @hide / systémové API.
        //    Na infotainmentu (systémová/privilegovaná appka) je přístupné přes reflexi.
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val getSoftApConfig = wm.javaClass.getMethod("getSoftApConfiguration")
            val sac = getSoftApConfig.invoke(wm)
            if (sac != null) {
                // Přečíst SSID (WifiSsid nebo String)
                val rawSsid = try {
                    val wifiSsid = sac.javaClass.getMethod("getWifiSsid").invoke(sac)
                    wifiSsid?.toString()?.trim('"')
                } catch (_: Exception) {
                    try {
                        sac.javaClass.getMethod("getSsid").invoke(sac) as? String
                    } catch (_: Exception) { null }
                }
                // Přečíst heslo
                val psk = try {
                    sac.javaClass.getMethod("getPassphrase").invoke(sac) as? String
                } catch (_: Exception) { null }

                if (!rawSsid.isNullOrBlank() && rawSsid != "null") {
                    Log.i(TAG, "SoftApConfiguration via reflection: SSID=$rawSsid psk=${psk?.take(2)}***")
                    return Result.Success(WifiCredentials(rawSsid, psk ?: ""))
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "getSoftApConfiguration reflexe: SecurityException – ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "getSoftApConfiguration reflexe selhala: ${e.message}")
        }

        // ── 2. Settings.Global / System – OEM klíče pro AP SSID ─────────────
        val globalSsid = readFromGlobalSettings()
        if (globalSsid != null) {
            val psk = readApPasswordFromSettings()
            Log.i(TAG, "Settings.Global/System: SSID=$globalSsid")
            return Result.Success(WifiCredentials(globalSsid, psk ?: ""))
        }

        // ── 3. Ručně zadané údaje (fallback) ─────────────────────────────────
        if (!manualSsid.isNullOrBlank()) {
            Log.i(TAG, "Manual fallback: SSID=$manualSsid")
            return Result.Success(WifiCredentials(manualSsid, manualPsk ?: ""))
        }

        return Result.Failure(
            "Nepodařilo se přečíst SSID systémového hotspotu. " +
            "Zadejte SSID a heslo ručně v Nastavení."
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun readFromGlobalSettings(): String? {
        // Klíče používané různými OEM (Škoda/VW, Samsung, AOSP, ...)
        val globalKeys = listOf(
            "wifi_ap_ssid",               // AOSP / standardní klíč
            "hotspot_ssid",               // některé OEM
            "tethering_wifi_ssid",        // alternativní AOSP
            "wifi_tethering_ssid",        // Škoda/VW infotainment
            "AP_SSID",                    // jiné OEM
        )
        val resolver = context.contentResolver
        for (key in globalKeys) {
            try {
                val value = Settings.Global.getString(resolver, key)
                if (!value.isNullOrBlank() && value != "null") {
                    Log.d(TAG, "Settings.Global[$key] = $value")
                    return value.trim('"')
                }
            } catch (_: Exception) {
                Log.v(TAG, "Settings.Global[$key] not accessible")
            }
        }
        // Zkusit i Settings.System
        val systemKeys = listOf("wifi_ap_ssid", "hotspot_ssid", "tethering_wifi_ssid")
        for (key in systemKeys) {
            try {
                val value = Settings.System.getString(resolver, key)
                if (!value.isNullOrBlank() && value != "null") {
                    Log.d(TAG, "Settings.System[$key] = $value")
                    return value.trim('"')
                }
            } catch (_: Exception) {
                Log.v(TAG, "Settings.System[$key] not accessible")
            }
        }
        return null
    }

    private fun readApPasswordFromSettings(): String? {
        val pskKeys = listOf(
            "wifi_ap_password",
            "hotspot_password",
            "tethering_wifi_password",
            "wifi_tethering_password",
            "AP_PASSWORD",
        )
        val resolver = context.contentResolver
        for (key in pskKeys) {
            try {
                val value = Settings.Global.getString(resolver, key)
                if (!value.isNullOrBlank() && value != "null") return value
            } catch (_: Exception) { }
            try {
                val value = Settings.System.getString(resolver, key)
                if (!value.isNullOrBlank() && value != "null") return value
            } catch (_: Exception) { }
        }
        return null
    }
}

