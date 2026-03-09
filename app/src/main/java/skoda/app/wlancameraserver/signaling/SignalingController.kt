package skoda.app.wlancameraserver.signaling

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import skoda.app.wlancameraserver.data.ReceiverRepository
import skoda.app.wlancameraserver.model.*
import skoda.app.wlancameraserver.network.WsServer
import skoda.app.wlancameraserver.network.UdpBeaconManager
import skoda.app.wlancameraserver.webrtc.WebRtcManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hlavní signalizační logika – zpracovává WS zprávy, HELLO/OFFER/ICE,
 * spravuje trusted senders, session tokeny.
 */
class SignalingController(
    private val context: Context,
    private val repository: ReceiverRepository,
    private val eventListener: SignalingEventListener
) : WsServer.WsServerListener {

    interface SignalingEventListener {
        fun onStateChanged(state: AppState)
        fun onCameraConnected(cameraId: String)
        fun onCameraDisconnected()
        fun onError(msg: String)
        fun onOffer(sdp: String, clientId: String)
        fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?, clientId: String)
        fun onApprovalRequired(cameraId: String)   // nové párování – čeká na OK uživatele
    }

    private val TAG = "SignalingController"
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var wsServer: WsServer? = null
    private val udpBeacon = UdpBeaconManager(context)

    private var currentSessionId: String = ""
    private var currentToken: String = ""
    private var tokenExpiryMs: Long = 0L
    private var connectedClientId: String? = null
    private var connectedCameraId: String? = null

    // clientId → cameraId pro nová párování čekající na schválení uživatelem
    private val pendingApproval = mutableMapOf<String, String>()

    val receiverId: String = repository.getReceiverId()

    // Aktuální IP v hotspot síti
    var serverIp: String = "192.168.43.1"
        private set

    var ssid: String = ""
    var psk: String = ""

    private val settings get() = repository.getSettings()

    // ─── Start / Stop ─────────────────────────────────────────────────────────

    fun startServer(hotspotSsid: String, hotspotPsk: String, hotspotIp: String) {
        ssid = hotspotSsid
        psk = hotspotPsk
        serverIp = hotspotIp.ifEmpty { detectIp() }

        newSession()

        val port = settings.wsPort
        wsServer?.stop()
        wsServer = WsServer(port, this)
        wsServer!!.start()

        // UDP beacon
        udpBeacon.apply {
            this.receiverId = this@SignalingController.receiverId
            this.wsPort = port
            this.ipHint = serverIp
            this.sessionId = currentSessionId
            this.acceptTrusted = settings.autoAcceptTrusted
            this.busy = false
        }
        udpBeacon.start()

        mainHandler.post { eventListener.onStateChanged(AppState.WAITING_FOR_SENDER) }
        Log.i(TAG, "Server started ws://$serverIp:$port/ws")
    }

    fun stopServer() {
        wsServer?.stop()
        wsServer = null
        udpBeacon.stop()
        pendingApproval.clear()
        connectedClientId = null
        connectedCameraId = null
        mainHandler.post { eventListener.onStateChanged(AppState.IDLE) }
    }

    // ─── Session / Token ─────────────────────────────────────────────────────

    fun newSession() {
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        currentSessionId = "S-$ts"
        renewToken()
        udpBeacon.sessionId = currentSessionId
        Log.d(TAG, "New session: $currentSessionId token: $currentToken")
    }

    fun renewToken() {
        val bytes = ByteArray(5)
        java.security.SecureRandom().nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        currentToken = "T-$hex"
        tokenExpiryMs = System.currentTimeMillis() + settings.wsPort.let { 120_000L }
    }

    fun buildQrPayload(): QrPayload {
        if (System.currentTimeMillis() > tokenExpiryMs) renewToken()
        return QrPayload(
            receiverId = receiverId,
            ssid = ssid,
            psk = psk,
            wsUrl = "ws://$serverIp:${settings.wsPort}/ws",
            sessionId = currentSessionId,
            token = currentToken,
            tokenExpSec = 120
        )
    }

    fun getCurrentSessionId() = currentSessionId
    fun getCurrentToken() = currentToken
    fun getTokenExpiryMs() = tokenExpiryMs
    fun getConnectedCameraId() = connectedCameraId

    /**
     * Voláno při přerušení WebRTC spojení ze strany vysílače.
     * Uvolní connectedClientId/cameraId tak aby se tatáž nebo jiná kamera
     * mohla znovu připojit bez odpovědi BUSY.
     * WS server a hotspot zůstávají aktivní.
     */
    fun resetForReconnect() {
        val prevCamera = connectedCameraId
        connectedClientId = null
        connectedCameraId = null
        udpBeacon.busy = false
        Log.i(TAG, "resetForReconnect() – uvolněno connectedClientId (was camera=$prevCamera)")
        mainHandler.post { eventListener.onStateChanged(AppState.WAITING_FOR_SENDER) }
    }

    // ─── WsServerListener ────────────────────────────────────────────────────

    override fun onClientConnected(clientId: String) {
        Log.i(TAG, "WS client connected: $clientId")
        mainHandler.post { eventListener.onStateChanged(AppState.AUTHENTICATING) }
    }

    override fun onClientDisconnected(clientId: String) {
        Log.i(TAG, "WS client disconnected: $clientId")
        // Pokud se odpojil čekající klient
        pendingApproval.remove(clientId)
        if (clientId == connectedClientId) {
            connectedClientId = null
            connectedCameraId = null
            udpBeacon.busy = false
            mainHandler.post {
                eventListener.onCameraDisconnected()
                eventListener.onStateChanged(AppState.WAITING_FOR_SENDER)
            }
        }
    }

    override fun onMessage(clientId: String, message: WsMessage) {
        Log.d(TAG, "MSG from $clientId: ${message.type}")
        when (message.type) {
            "HELLO" -> handleHello(clientId, message)
            "OFFER" -> handleOffer(clientId, message)
            "ICE" -> handleIce(clientId, message)
            else -> Log.w(TAG, "Unknown message type: ${message.type}")
        }
    }

    override fun onServerError(e: Exception) {
        Log.e(TAG, "Server error", e)
        mainHandler.post { eventListener.onError(e.message ?: "Server error") }
    }

    // ─── HELLO ───────────────────────────────────────────────────────────────

    private fun handleHello(clientId: String, msg: WsMessage) {
        val cameraId = msg.cameraId ?: run {
            sendError(clientId, "INVALID_HELLO", "Missing cameraId")
            return
        }

        // Pokud už je někdo připojen – zkontroluj zda nejde o reconnect stejné kamery
        if (connectedClientId != null && connectedClientId != clientId) {
            if (settings.singleCameraOnly) {
                // Dovolit reconnect pokud jde o stejnou kameru (WebRTC přerušení)
                if (connectedCameraId != null && connectedCameraId == cameraId) {
                    Log.i(TAG, "Reconnect stejné kamery $cameraId – uvolňuji staré spojení")
                    wsServer?.disconnectClient(connectedClientId!!)
                    connectedClientId = null
                    connectedCameraId = null
                    udpBeacon.busy = false
                } else {
                    Log.w(TAG, "BUSY – připojena jiná kamera $connectedCameraId, odmítám $cameraId")
                    sendError(clientId, "BUSY", "Another camera is connected")
                    wsServer?.disconnectClient(clientId)
                    return
                }
            }
        }

        val trusted = repository.findTrustedSender(cameraId)

        if (msg.proof != null && trusted != null && settings.autoAcceptTrusted) {
            // ── Flow 2: Trusted reconnect přes beacon/proof ──────────────────
            val expectedProof = hmacBase64(udpBeacon.currentChallenge, trusted.sharedSecret)
            if (expectedProof != msg.proof) {
                sendError(clientId, "INVALID_PROOF", "Proof mismatch")
                wsServer?.disconnectClient(clientId)
                return
            }
            // Trusted → přijmout okamžitě, posílat HELLO_ACK + rovnou OFFER může přijít
            acceptClient(clientId, cameraId, isTrusted = true)

        } else if (msg.token != null) {
            // ── Flow 1: Nové párování přes QR token ──────────────────────────
            if (msg.sessionId != currentSessionId) {
                sendError(clientId, "INVALID_SESSION", "Session mismatch")
                wsServer?.disconnectClient(clientId)
                return
            }
            if (System.currentTimeMillis() > tokenExpiryMs) {
                sendError(clientId, "TOKEN_EXPIRED", "Token has expired")
                wsServer?.disconnectClient(clientId)
                return
            }
            if (msg.token != currentToken) {
                sendError(clientId, "INVALID_TOKEN", "Token mismatch")
                wsServer?.disconnectClient(clientId)
                return
            }
            // Invalidovat token (jednorázový)
            currentToken = "USED"

            // Uložit do pendingApproval a počkat na schválení uživatelem
            pendingApproval[clientId] = cameraId

            // Poslat HELLO_ACK { trusted: false } – kamera teď čeká na APPROVED
            val ack = WsMessage(
                type = "HELLO_ACK",
                trusted = false,
                receiverId = receiverId,
                policy = Policy(singleCameraOnly = settings.singleCameraOnly)
            )
            wsServer?.sendToClient(clientId, ack)
            Log.i(TAG, "Sent HELLO_ACK(trusted=false) to $cameraId, waiting for user approval")

            // Přejít do stavu čekání na schválení
            mainHandler.post {
                eventListener.onStateChanged(AppState.PENDING_APPROVAL)
                eventListener.onApprovalRequired(cameraId)
            }

        } else {
            sendError(clientId, "UNAUTHORIZED", "No token or proof provided")
            wsServer?.disconnectClient(clientId)
        }
    }

    private fun acceptClient(clientId: String, cameraId: String, isTrusted: Boolean) {
        connectedClientId = clientId
        connectedCameraId = cameraId
        udpBeacon.busy = settings.singleCameraOnly
        repository.updateLastSeen(cameraId)

        if (!isTrusted) {
            // Nové párování – uložit TrustedSender se sharedSecret
            val secret = generateSharedSecret()
            repository.saveTrustedSender(
                TrustedSender(
                    cameraId = cameraId,
                    sharedSecret = secret,
                    pairedAt = System.currentTimeMillis(),
                    lastSeenAt = System.currentTimeMillis()
                )
            )
            // Poslat APPROVED – kamera teprve teď může odeslat OFFER
            wsServer?.sendToClient(clientId, WsMessage(type = "APPROVED", receiverId = receiverId))
            Log.i(TAG, "Sent APPROVED to $cameraId")
        } else {
            // Trusted reconnect – HELLO_ACK { trusted: true } již byl odeslán,
            // kamera může rovnou odeslat OFFER (nepotřebuje APPROVED)
            val ack = WsMessage(
                type = "HELLO_ACK",
                trusted = true,
                receiverId = receiverId,
                policy = Policy(singleCameraOnly = settings.singleCameraOnly)
            )
            wsServer?.sendToClient(clientId, ack)
            Log.i(TAG, "Sent HELLO_ACK(trusted=true) to $cameraId")
        }

        mainHandler.post {
            eventListener.onCameraConnected(cameraId)
            eventListener.onStateChanged(AppState.NEGOTIATING_WEBRTC)
        }
        Log.i(TAG, "Camera $cameraId accepted (trusted=$isTrusted)")
    }

    // ─── OFFER / ICE ─────────────────────────────────────────────────────────

    private fun handleOffer(clientId: String, msg: WsMessage) {
        if (clientId != connectedClientId) {
            sendError(clientId, "NOT_AUTHENTICATED", "Send HELLO first")
            return
        }
        val sdp = msg.sdp ?: return
        mainHandler.post { eventListener.onOffer(sdp, clientId) }
    }

    private fun handleIce(clientId: String, msg: WsMessage) {
        if (clientId != connectedClientId) return
        val candidate = msg.candidate ?: return
        mainHandler.post {
            eventListener.onIceCandidate(
                candidate,
                msg.sdpMid,
                msg.sdpMLineIndex,
                clientId
            )
        }
    }

    fun sendAnswer(clientId: String, sdp: String) {
        ioExecutor.execute {
            wsServer?.sendToClient(clientId, WsMessage(type = "ANSWER", sdp = sdp))
        }
    }

    fun sendIce(clientId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        ioExecutor.execute {
            wsServer?.sendToClient(
                clientId, WsMessage(
                    type = "ICE",
                    candidate = candidate,
                    sdpMid = sdpMid,
                    sdpMLineIndex = sdpMLineIndex
                )
            )
        }
    }

    /**
     * Voláno z UI po kliknutí "Přijmout" – pošle APPROVED kameře a dokončí přijetí.
     * Odesílání se provádí na IO vlákně (socket write nesmí být na main threadu).
     */
    fun approvePendingClient(cameraId: String) {
        val entry = pendingApproval.entries.find { it.value == cameraId } ?: run {
            Log.w(TAG, "approvePendingClient: no pending client for cameraId=$cameraId")
            return
        }
        val clientId = entry.key
        pendingApproval.remove(clientId)
        Log.i(TAG, "User approved cameraId=$cameraId clientId=$clientId")
        ioExecutor.execute {
            acceptClient(clientId, cameraId, isTrusted = false)
        }
    }

    /**
     * Voláno z UI po kliknutí "Odmítnout" – pošle REJECTED a odpojí klienta.
     * Odesílání se provádí na IO vlákně (socket write nesmí být na main threadu).
     */
    fun rejectPendingClient(cameraId: String) {
        val entry = pendingApproval.entries.find { it.value == cameraId } ?: run {
            Log.w(TAG, "rejectPendingClient: no pending client for cameraId=$cameraId")
            return
        }
        val clientId = entry.key
        pendingApproval.remove(clientId)
        ioExecutor.execute {
            wsServer?.sendToClient(clientId, WsMessage(type = "REJECTED", code = "USER_REJECTED"))
            wsServer?.disconnectClient(clientId)
            mainHandler.post { eventListener.onStateChanged(AppState.WAITING_FOR_SENDER) }
            Log.i(TAG, "User rejected cameraId=$cameraId")
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun sendError(clientId: String, code: String, msg: String) {
        wsServer?.sendToClient(clientId, WsMessage(type = "ERROR", code = code, message = msg))
    }

    private fun hmacBase64(data: String, secretBase64: String): String {
        val key = Base64.decode(secretBase64, Base64.DEFAULT)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val result = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    private fun generateSharedSecret(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun detectIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { !it.isLoopback && it.isUp }
                ?.flatMap { it.inetAddresses.toList() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull {
                    !it.isLoopbackAddress &&
                    (it.hostAddress?.startsWith("192.168") == true ||
                     it.hostAddress?.startsWith("10.") == true ||
                     it.hostAddress?.let { addr ->
                         val second = addr.split(".").getOrNull(1)?.toIntOrNull() ?: 0
                         addr.startsWith("172.") && second in 16..31
                     } == true)
                }
                ?.hostAddress ?: "192.168.43.1"
        } catch (e: Exception) {
            "192.168.43.1"
        }
    }
}
