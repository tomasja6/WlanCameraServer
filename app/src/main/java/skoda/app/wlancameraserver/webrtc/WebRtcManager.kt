package skoda.app.wlancameraserver.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.*

/**
 * Správa WebRTC PeerConnection (receive-only).
 */
class WebRtcManager(
    private val context: Context,
    private val listener: WebRtcListener
) {
    interface WebRtcListener {
        fun onAnswer(sdp: String)
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onVideoTrack(track: VideoTrack)
        fun onConnectionFailed()
        fun onConnected()
        fun onDisconnected()
    }

    private val TAG = "WebRtcManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null

    @Volatile private var initialized = false

    fun init() {
        if (initialized) return
        eglBase = EglBase.create()

        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
            )
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
            )
            .createPeerConnectionFactory()

        initialized = true
        Log.i(TAG, "WebRTC initialized")
    }

    fun getEglBase(): EglBase? = eglBase

    fun createPeerConnection() {
        if (!initialized) init()

        val iceServers = emptyList<PeerConnection.IceServer>() // LAN only

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {
                    Log.d(TAG, "onSignalingChange: $state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "onIceConnectionChange: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            mainHandler.post { listener.onConnected() }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            mainHandler.post { listener.onDisconnected() }
                        }
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED -> {
                            mainHandler.post { listener.onConnectionFailed() }
                        }
                        else -> {}
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "Local ICE candidate: ${candidate.sdp}")
                    mainHandler.post { listener.onLocalIceCandidate(candidate) }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

                override fun onAddStream(stream: MediaStream) {}

                override fun onRemoveStream(stream: MediaStream) {}

                override fun onDataChannel(dc: DataChannel) {}

                override fun onRenegotiationNeeded() {}

                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    Log.d(TAG, "onAddTrack: ${receiver.track()?.kind()}")
                    val track = receiver.track()
                    if (track is VideoTrack) {
                        mainHandler.post { listener.onVideoTrack(track) }
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    Log.d(TAG, "onConnectionChange: $newState")
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED ->
                            mainHandler.post { listener.onConnected() }
                        PeerConnection.PeerConnectionState.DISCONNECTED ->
                            mainHandler.post { listener.onDisconnected() }
                        PeerConnection.PeerConnectionState.FAILED ->
                            mainHandler.post { listener.onConnectionFailed() }
                        PeerConnection.PeerConnectionState.CLOSED ->
                            mainHandler.post { listener.onDisconnected() }
                        else -> {}
                    }
                }
            }
        )

        // Receive-only transceiver pro video
        val transceiverInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
        )
        peerConnection?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, transceiverInit)

        Log.i(TAG, "PeerConnection created")
    }

    fun handleRemoteOffer(sdpString: String, onAnswer: (String) -> Unit) {
        val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                // Create answer
                val constraints = MediaConstraints()
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        answer ?: return
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                Log.d(TAG, "Local description set (answer)")
                                mainHandler.post { onAnswer(answer.description) }
                            }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {
                                Log.e(TAG, "setLocalDesc failed: $p0")
                            }
                        }, answer)
                    }
                    override fun onCreateFailure(p0: String?) {
                        Log.e(TAG, "createAnswer failed: $p0")
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                }, constraints)
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                Log.e(TAG, "setRemoteDesc failed: $p0")
            }
        }, sdp)
    }

    fun addRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        val iceCandidate = IceCandidate(
            sdpMid ?: "0",
            sdpMLineIndex ?: 0,
            candidate
        )
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun initVideoRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase?.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
    }

    fun attachVideoTrack(track: VideoTrack, renderer: SurfaceViewRenderer) {
        track.addSink(renderer)
    }

    fun detachVideoTrack(track: VideoTrack, renderer: SurfaceViewRenderer) {
        track.removeSink(renderer)
    }

    fun close() {
        try {
            peerConnection?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close error", e)
        }
        peerConnection = null
        Log.i(TAG, "PeerConnection closed")
    }

    fun release() {
        close()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
        initialized = false
        Log.i(TAG, "WebRTC released")
    }
}
