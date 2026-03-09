package skoda.app.wlancameraserver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import org.webrtc.IceCandidate
import org.webrtc.VideoTrack
import skoda.app.wlancameraserver.R
import skoda.app.wlancameraserver.data.ReceiverRepository
import skoda.app.wlancameraserver.hotspot.HotspotManager
import skoda.app.wlancameraserver.model.AppState
import skoda.app.wlancameraserver.model.QrPayload
import skoda.app.wlancameraserver.signaling.SignalingController
import skoda.app.wlancameraserver.ui.MainActivity
import skoda.app.wlancameraserver.webrtc.WebRtcManager

/**
 * Foreground service - drží hotspot, WS server a WebRTC živé na pozadí.
 */
class ReceiverForegroundService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): ReceiverForegroundService = this@ReceiverForegroundService
    }

    private val TAG = "ReceiverService"
    private val binder = LocalBinder()

    lateinit var repository: ReceiverRepository
    lateinit var hotspotManager: HotspotManager
    lateinit var signalingController: SignalingController
    lateinit var webRtcManager: WebRtcManager

    // LiveData pro UI
    val appState = MutableLiveData<AppState>(AppState.IDLE)
    val ssid = MutableLiveData<String>("")
    val psk = MutableLiveData<String>("")
    val serverIp = MutableLiveData<String>("")
    val connectedCameraId = MutableLiveData<String?>(null)
    val errorMessage = MutableLiveData<String?>(null)
    val videoTrack = MutableLiveData<VideoTrack?>(null)
    val connectedClientId = MutableLiveData<String?>(null)
    /** Non-null pokud čeká na schválení uživatelem (nové párování přes QR) */
    val pendingCameraId = MutableLiveData<String?>(null)

    // ─── Reconnect stav ───────────────────────────────────────────────────────
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private var intentionallyStopped = false

    /** Prodlevy pro reconnect: 2s, 5s, 10s, 15s, 30s (pak stále 30s) */
    private val RECONNECT_DELAYS = listOf(2_000L, 5_000L, 10_000L, 15_000L, 30_000L)

    private val reconnectRunnable = Runnable {
        if (!intentionallyStopped && appState.value == AppState.WAITING_FOR_SENDER) {
            Log.i(TAG, "Reconnect attempt $reconnectAttempt – resetting WebRTC, waiting for new OFFER")
            resetWebRtcForReconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = ReceiverRepository(applicationContext)
        hotspotManager = HotspotManager(applicationContext)
        webRtcManager = WebRtcManager(applicationContext, createWebRtcListener())
        signalingController = SignalingController(applicationContext, repository, createSignalingListener())
        Log.i(TAG, "Service created")
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startReceiver()
            ACTION_STOP -> stopReceiver()
        }
        return START_STICKY
    }

    // ─── Start / Stop ─────────────────────────────────────────────────────────

    fun startReceiver() {
        val state = appState.value
        if (state != AppState.IDLE && state != AppState.ERROR) {
            Log.w(TAG, "startReceiver ignorován – aktuální stav: $state")
            return
        }
        intentionallyStopped = false
        reconnectAttempt = 0
        startForeground(NOTIF_ID, buildNotification("Starting..."))
        appState.postValue(AppState.STARTING_HOTSPOT)

        val settings = repository.getSettings()
        hotspotManager.useSystemHotspot = settings.useSystemHotspot
        val manualSsid = settings.manualSsid.ifBlank { null }
        val manualPsk  = settings.manualPsk.ifBlank { null }

        hotspotManager.startHotspot(object : HotspotManager.HotspotCallback {
            override fun onStarted(hotspotSsid: String, hotspotPsk: String) {
                // Vždy ukládáme SKUTEČNÉ SSID+PSK které systém použil –
                // kamera se musí připojit na tuto síť, jinak spojení selže
                repository.saveHotspotCredentials(hotspotSsid, hotspotPsk)

                ssid.postValue(hotspotSsid)
                psk.postValue(hotspotPsk)
                appState.postValue(AppState.HOTSPOT_READY)

                val ip = detectHotspotIp()
                serverIp.postValue(ip)

                appState.postValue(AppState.STARTING_SERVER)
                signalingController.startServer(hotspotSsid, hotspotPsk, ip)
                updateNotification("Waiting for camera (SSID: $hotspotSsid)")
            }

            override fun onStopped() {
                appState.postValue(AppState.IDLE)
                updateNotification("Stopped")
            }

            override fun onFailed(reason: String) {
                appState.postValue(AppState.ERROR)
                errorMessage.postValue(reason)
                updateNotification("Error: $reason")
            }
        }, manualSsid, manualPsk)
    }

    fun stopReceiver() {
        intentionallyStopped = true
        cancelReconnect()
        signalingController.stopServer()
        webRtcManager.close()
        hotspotManager.stopHotspot()
        appState.postValue(AppState.IDLE)
        connectedCameraId.postValue(null)
        connectedClientId.postValue(null)
        pendingCameraId.postValue(null)
        videoTrack.postValue(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Receiver stopped")
    }

    fun buildQrPayload(): QrPayload = signalingController.buildQrPayload()

    fun renewQr() {
        signalingController.newSession()
    }

    /** Uživatel klikl "Přijmout" – pošle APPROVED kameře */
    fun approvePendingCamera() {
        val cameraId = pendingCameraId.value ?: return
        pendingCameraId.postValue(null)
        signalingController.approvePendingClient(cameraId)
    }

    /** Uživatel klikl "Odmítnout" – pošle REJECTED kameře */
    fun rejectPendingCamera() {
        val cameraId = pendingCameraId.value ?: return
        pendingCameraId.postValue(null)
        signalingController.rejectPendingClient(cameraId)
    }

    // ─── Reconnect ────────────────────────────────────────────────────────────

    /**
     * Naplánuje resetování WebRTC a čekání na nový OFFER od vysílače.
     * WS server a hotspot zůstávají aktivní – vysílač se může znovu připojit.
     */
    private fun scheduleWebRtcReconnect(reason: String) {
        if (intentionallyStopped) return
        cancelReconnect()

        // Okamžitě uvolnit connectedClientId v signaling controlleru
        // → kamera se může ihned znovu připojit na WS bez odpovědi BUSY
        signalingController.resetForReconnect()

        val delay = RECONNECT_DELAYS.getOrElse(reconnectAttempt) { RECONNECT_DELAYS.last() }
        reconnectAttempt++

        Log.i(TAG, "WebRTC odpojení ($reason) – reconnect pokus $reconnectAttempt za ${delay}ms")
        updateNotification("Hledám vysílač... (pokus $reconnectAttempt)")
        appState.postValue(AppState.WAITING_FOR_SENDER)

        reconnectHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun cancelReconnect() {
        reconnectHandler.removeCallbacks(reconnectRunnable)
    }

    /**
     * Uzavře stávající PeerConnection a připraví WebRTC pro nový příchozí OFFER.
     * WS klient (kamera) musí po znovu-připojení poslat nový OFFER.
     */
    private fun resetWebRtcForReconnect() {
        Log.i(TAG, "resetWebRtcForReconnect()")
        videoTrack.postValue(null)
        connectedClientId.postValue(null)
        // Zavřít starý PeerConnection – NERELEASOVAT factory (EGL kontext zůstane)
        webRtcManager.close()
        // Signaling server stále běží, kamera se může znovu připojit
        // a pošle nový OFFER → onOffer callback to zpracuje
        appState.postValue(AppState.WAITING_FOR_SENDER)
        updateNotification("Waiting for camera (SSID: ${ssid.value})")
        Log.i(TAG, "WebRTC reset – čekám na nový OFFER od vysílače")
    }

    // ─── Listeners ────────────────────────────────────────────────────────────

    private fun createSignalingListener() = object : SignalingController.SignalingEventListener {
        override fun onStateChanged(state: AppState) {
            appState.postValue(state)
            val msg = when (state) {
                AppState.WAITING_FOR_SENDER -> "Waiting for camera (SSID: ${ssid.value})"
                AppState.PENDING_APPROVAL   -> "Camera waiting for approval..."
                AppState.STREAMING          -> "Streaming: ${connectedCameraId.value}"
                AppState.NEGOTIATING_WEBRTC -> "Negotiating WebRTC..."
                else -> state.name
            }
            updateNotification(msg)
        }

        override fun onCameraConnected(cameraId: String) {
            connectedCameraId.postValue(cameraId)
            // Úspěšné nové připojení – reset reconnect čítače
            reconnectAttempt = 0
            cancelReconnect()
        }

        override fun onCameraDisconnected() {
            connectedCameraId.postValue(null)
            connectedClientId.postValue(null)
            pendingCameraId.postValue(null)
            videoTrack.postValue(null)
            webRtcManager.close()
            // WS klient se odpojil – signaling již provedl reset connectedClientId
            // Jen resetujeme WebRTC a čekáme na nové připojení
            if (!intentionallyStopped) {
                cancelReconnect()
                resetWebRtcForReconnect()
            }
        }

        override fun onError(msg: String) {
            errorMessage.postValue(msg)
        }

        override fun onApprovalRequired(cameraId: String) {
            pendingCameraId.postValue(cameraId)
            updateNotification("Camera $cameraId wants to connect – approve in app")
        }

        override fun onOffer(sdp: String, clientId: String) {
            // Přišel nový OFFER (ať už poprvé nebo po reconnectu)
            Log.i(TAG, "Received OFFER from clientId=$clientId – vytváříme PeerConnection")
            cancelReconnect()
            reconnectAttempt = 0
            connectedClientId.postValue(clientId)
            // Vždy vytvořit novou PeerConnection (stará mohla být close())
            webRtcManager.createPeerConnection()
            webRtcManager.handleRemoteOffer(sdp) { answerSdp ->
                signalingController.sendAnswer(clientId, answerSdp)
            }
        }

        override fun onIceCandidate(
            candidate: String, sdpMid: String?, sdpMLineIndex: Int?, clientId: String
        ) {
            webRtcManager.addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
        }
    }

    private fun createWebRtcListener() = object : WebRtcManager.WebRtcListener {
        override fun onAnswer(sdp: String) { /* handled in signaling */ }

        override fun onLocalIceCandidate(candidate: IceCandidate) {
            val cid = connectedClientId.value ?: return
            signalingController.sendIce(cid, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
        }

        override fun onVideoTrack(track: VideoTrack) {
            videoTrack.postValue(track)
            appState.postValue(AppState.STREAMING)
            // Stream aktivní – reset reconnect čítače
            reconnectAttempt = 0
            cancelReconnect()
            Log.i(TAG, "Video stream aktivní")
        }

        override fun onConnected() {
            Log.i(TAG, "WebRTC connected")
            reconnectAttempt = 0
            cancelReconnect()
        }

        override fun onDisconnected() {
            // Vysílač přerušil stream (síťové přerušení, pozastavení, atd.)
            Log.w(TAG, "WebRTC disconnected – plánuji reconnect")
            videoTrack.postValue(null)
            webRtcManager.close()
            scheduleWebRtcReconnect("WebRTC DISCONNECTED")
        }

        override fun onConnectionFailed() {
            // ICE selhání – těžší stav, reset PeerConnection
            Log.e(TAG, "WebRTC connection failed – plánuji reconnect")
            videoTrack.postValue(null)
            webRtcManager.close()
            scheduleWebRtcReconnect("WebRTC FAILED")
        }
    }

    // ─── Notifikace ──────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        createNotificationChannel()
        val pi = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("WLAN Camera Server")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Receiver Service", NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun detectHotspotIp(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.filterIsInstance<java.net.Inet4Address>()
                ?.firstOrNull {
                    !it.isLoopbackAddress &&
                    (it.hostAddress?.startsWith("192.168") == true ||
                     it.hostAddress?.startsWith("10.") == true)
                }
                ?.hostAddress ?: "192.168.43.1"
        } catch (e: Exception) {
            "192.168.43.1"
        }
    }

    override fun onDestroy() {
        stopReceiver()
        webRtcManager.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "skoda.wlancam.START"
        const val ACTION_STOP  = "skoda.wlancam.STOP"
        const val CHANNEL_ID   = "receiver_channel"
        const val NOTIF_ID     = 1001
    }
}
