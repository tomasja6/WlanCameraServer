package skoda.app.wlancameraserver.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import skoda.app.wlancameraserver.model.AppSettings
import skoda.app.wlancameraserver.model.TrustedSender
import java.util.UUID

/**
 * Persistovaná úložiště: identita receiveru, trusted senders, nastavení.
 */
class ReceiverRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("receiver_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ─── Identita ────────────────────────────────────────────────────────────

    fun getReceiverId(): String {
        val existing = prefs.getString(KEY_RECEIVER_ID, null)
        if (existing != null) return existing
        val newId = "R-" + UUID.randomUUID().toString().take(8)
        prefs.edit().putString(KEY_RECEIVER_ID, newId).apply()
        return newId
    }

    // ─── Trusted senders ─────────────────────────────────────────────────────

    fun getTrustedSenders(): MutableList<TrustedSender> {
        val json = prefs.getString(KEY_TRUSTED, "[]") ?: "[]"
        val type = object : TypeToken<MutableList<TrustedSender>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveTrustedSender(sender: TrustedSender) {
        val list = getTrustedSenders()
        val idx = list.indexOfFirst { it.cameraId == sender.cameraId }
        if (idx >= 0) list[idx] = sender else list.add(sender)
        prefs.edit().putString(KEY_TRUSTED, gson.toJson(list)).apply()
    }

    fun findTrustedSender(cameraId: String): TrustedSender? =
        getTrustedSenders().find { it.cameraId == cameraId && it.allowed }

    fun updateLastSeen(cameraId: String) {
        val list = getTrustedSenders()
        val idx = list.indexOfFirst { it.cameraId == cameraId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(lastSeenAt = System.currentTimeMillis())
            prefs.edit().putString(KEY_TRUSTED, gson.toJson(list)).apply()
        }
    }

    // ─── Pevné Wi-Fi přihlašovací údaje ─────────────────────────────────────

    /**
     * Vrátí uložené SSID, nebo null pokud ještě nebylo uloženo.
     */
    fun getSavedSsid(): String? = prefs.getString(KEY_HOTSPOT_SSID, null)

    /**
     * Vrátí uložené heslo, nebo null pokud ještě nebylo uloženo.
     */
    fun getSavedPsk(): String? = prefs.getString(KEY_HOTSPOT_PSK, null)

    /**
     * Uloží SSID a heslo napoprvé (volá se po prvním úspěšném spuštění hotspotu).
     */
    fun saveHotspotCredentials(ssid: String, psk: String) {
        prefs.edit()
            .putString(KEY_HOTSPOT_SSID, ssid)
            .putString(KEY_HOTSPOT_PSK, psk)
            .apply()
    }

    /**
     * Smaže uložené přihlašovací údaje (pro reset).
     */
    fun clearHotspotCredentials() {
        prefs.edit()
            .remove(KEY_HOTSPOT_SSID)
            .remove(KEY_HOTSPOT_PSK)
            .apply()
    }

    // ─── Nastavení ───────────────────────────────────────────────────────────

    fun getSettings(): AppSettings {
        return AppSettings(
            autoAcceptTrusted = prefs.getBoolean(KEY_AUTO_ACCEPT, true),
            singleCameraOnly = prefs.getBoolean(KEY_SINGLE_CAMERA, true),
            wsPort = prefs.getInt(KEY_WS_PORT, 8888),
            udpBeaconPort = prefs.getInt(KEY_UDP_PORT, 39500),
            useSystemHotspot = prefs.getBoolean(KEY_USE_SYSTEM_HOTSPOT, true),
            manualSsid = prefs.getString(KEY_MANUAL_SSID, "") ?: "",
            manualPsk = prefs.getString(KEY_MANUAL_PSK, "") ?: ""
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_AUTO_ACCEPT, settings.autoAcceptTrusted)
            .putBoolean(KEY_SINGLE_CAMERA, settings.singleCameraOnly)
            .putInt(KEY_WS_PORT, settings.wsPort)
            .putInt(KEY_UDP_PORT, settings.udpBeaconPort)
            .putBoolean(KEY_USE_SYSTEM_HOTSPOT, settings.useSystemHotspot)
            .putString(KEY_MANUAL_SSID, settings.manualSsid)
            .putString(KEY_MANUAL_PSK, settings.manualPsk)
            .apply()
    }

    companion object {
        private const val KEY_RECEIVER_ID = "receiver_id"
        private const val KEY_TRUSTED = "trusted_senders"
        private const val KEY_AUTO_ACCEPT = "auto_accept_trusted"
        private const val KEY_SINGLE_CAMERA = "single_camera_only"
        private const val KEY_WS_PORT = "ws_port"
        private const val KEY_UDP_PORT = "udp_beacon_port"
        private const val KEY_HOTSPOT_SSID = "hotspot_ssid"
        private const val KEY_HOTSPOT_PSK = "hotspot_psk"
        private const val KEY_USE_SYSTEM_HOTSPOT = "use_system_hotspot"
        private const val KEY_MANUAL_SSID = "manual_ssid"
        private const val KEY_MANUAL_PSK = "manual_psk"
    }
}
