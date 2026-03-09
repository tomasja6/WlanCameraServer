package skoda.app.wlancameraserver.model

import com.google.gson.annotations.SerializedName

// ─── Stav aplikace (stavový automat) ─────────────────────────────────────────

enum class AppState {
    IDLE,
    STARTING_HOTSPOT,
    HOTSPOT_READY,
    STARTING_SERVER,
    WAITING_FOR_SENDER,
    AUTHENTICATING,
    PENDING_APPROVAL,       // čeká na schválení uživatelem (nové párování přes QR)
    NEGOTIATING_WEBRTC,
    STREAMING,
    REJECTED_BUSY,
    ERROR
}

// ─── QR Payload ──────────────────────────────────────────────────────────────

data class QrPayload(
    @SerializedName("v") val v: Int = 1,
    @SerializedName("role") val role: String = "receiver",
    @SerializedName("receiverId") val receiverId: String,
    @SerializedName("ssid") val ssid: String,
    @SerializedName("psk") val psk: String,
    @SerializedName("wsUrl") val wsUrl: String,
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("token") val token: String,
    @SerializedName("tokenExpSec") val tokenExpSec: Int = 120
)

// ─── WebSocket zprávy ────────────────────────────────────────────────────────

data class WsMessage(
    @SerializedName("type") val type: String,
    @SerializedName("v") val v: Int? = null,
    @SerializedName("sessionId") val sessionId: String? = null,
    @SerializedName("token") val token: String? = null,
    @SerializedName("cameraId") val cameraId: String? = null,
    @SerializedName("cameraInfo") val cameraInfo: CameraInfo? = null,
    @SerializedName("proof") val proof: String? = null,
    @SerializedName("sdp") val sdp: String? = null,
    @SerializedName("candidate") val candidate: String? = null,
    @SerializedName("sdpMid") val sdpMid: String? = null,
    @SerializedName("sdpMLineIndex") val sdpMLineIndex: Int? = null,
    @SerializedName("trusted") val trusted: Boolean? = null,
    @SerializedName("receiverId") val receiverId: String? = null,
    @SerializedName("policy") val policy: Policy? = null,
    @SerializedName("code") val code: String? = null,
    @SerializedName("message") val message: String? = null
)

data class CameraInfo(
    @SerializedName("model") val model: String? = null,
    @SerializedName("app") val app: String? = null
)

data class Policy(
    @SerializedName("singleCameraOnly") val singleCameraOnly: Boolean = true
)

// ─── UDP Beacon ──────────────────────────────────────────────────────────────

data class UdpBeacon(
    @SerializedName("type") val type: String = "RECEIVER_HERE",
    @SerializedName("v") val v: Int = 1,
    @SerializedName("receiverId") val receiverId: String,
    @SerializedName("wsPort") val wsPort: Int,
    @SerializedName("ipHint") val ipHint: String,
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("challenge") val challenge: String,
    @SerializedName("flags") val flags: BeaconFlags
)

data class BeaconFlags(
    @SerializedName("acceptTrusted") val acceptTrusted: Boolean,
    @SerializedName("busy") val busy: Boolean
)

// ─── Trusted Sender ──────────────────────────────────────────────────────────

data class TrustedSender(
    val cameraId: String,
    val sharedSecret: String,   // base64
    val pairedAt: Long,
    val lastSeenAt: Long,
    val allowed: Boolean = true
)

// ─── Nastavení ───────────────────────────────────────────────────────────────

data class AppSettings(
    val autoAcceptTrusted: Boolean = true,
    val singleCameraOnly: Boolean = true,
    val wsPort: Int = 8888,
    val udpBeaconPort: Int = 39500,
    /** true = čtení systémového hotspotu (infotainment); false = vlastní LocalOnlyHotspot */
    val useSystemHotspot: Boolean = true,
    /** Manuálně zadané SSID – fallback pokud systém SSID neposkytne */
    val manualSsid: String = "",
    /** Manuálně zadané heslo – fallback */
    val manualPsk: String = ""
)
