package skoda.app.wlancameraserver.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.google.gson.Gson
import skoda.app.wlancameraserver.model.BeaconFlags
import skoda.app.wlancameraserver.model.UdpBeacon
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * UDP beacon - broadcastuje RECEIVER_HERE zprávy do LAN sítě.
 * Challenge se obnovuje každých 7 s.
 */
class UdpBeaconManager(
    private val context: Context,
    private val port: Int = 39500
) {
    private val TAG = "UdpBeaconManager"
    private val gson = Gson()
    private var scheduler: ScheduledExecutorService? = null
    private var beaconFuture: ScheduledFuture<*>? = null
    private var challengeFuture: ScheduledFuture<*>? = null
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile var currentChallenge: String = generateChallenge()
        private set

    var receiverId: String = ""
    var wsPort: Int = 8888
    var ipHint: String = ""
    var sessionId: String = ""
    var acceptTrusted: Boolean = true
    var busy: Boolean = false

    fun start() {
        acquireMulticastLock()
        scheduler = Executors.newScheduledThreadPool(2)
        currentChallenge = generateChallenge()

        // Rotace challenge každých 7 sekund
        challengeFuture = scheduler?.scheduleAtFixedRate({
            currentChallenge = generateChallenge()
            Log.d(TAG, "Challenge rotated: $currentChallenge")
        }, 7, 7, TimeUnit.SECONDS)

        // Beacon každou sekundu
        beaconFuture = scheduler?.scheduleAtFixedRate({
            sendBeacon()
        }, 0, 1000, TimeUnit.MILLISECONDS)

        Log.i(TAG, "UDP Beacon started on port $port")
    }

    private fun sendBeacon() {
        val broadcastAddr = getSubnetBroadcast()
        if (broadcastAddr == null) {
            Log.d(TAG, "sendBeacon: síť není dostupná, přeskakuji")
            return
        }
        try {
            if (socket == null || socket!!.isClosed) {
                socket = DatagramSocket().apply { broadcast = true }
            }
            val beacon = UdpBeacon(
                receiverId = receiverId,
                wsPort = wsPort,
                ipHint = ipHint,
                sessionId = sessionId,
                challenge = currentChallenge,
                flags = BeaconFlags(acceptTrusted = acceptTrusted, busy = busy)
            )
            val json = gson.toJson(beacon)
            val data = json.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(data, data.size, broadcastAddr, port)
            socket?.send(packet)
            Log.v(TAG, "Beacon odeslán na ${broadcastAddr.hostAddress}:$port")
        } catch (e: Exception) {
            Log.w(TAG, "sendBeacon error: ${e.message}")
        }
    }

    /**
     * Vrátí broadcast adresu aktuálního WiFi/AP rozhraní (např. 10.174.189.255),
     * nebo null pokud žádné síťové rozhraní není dostupné.
     */
    private fun getSubnetBroadcast(): InetAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { !it.isLoopback && it.isUp }
                ?.flatMap { iface ->
                    iface.interfaceAddresses
                        .filter { it.address is Inet4Address && !it.address.isLoopbackAddress }
                        .mapNotNull { ifAddr ->
                            ifAddr.broadcast?.also {
                                Log.d(TAG, "Dostupné rozhraní ${iface.name}: ${ifAddr.address.hostAddress} broadcast=${it.hostAddress}")
                            }
                        }
                }
                ?.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "getSubnetBroadcast error: ${e.message}")
            null
        }
    }

    fun stop() {
        beaconFuture?.cancel(true)
        challengeFuture?.cancel(true)
        scheduler?.shutdownNow()
        scheduler = null
        try { socket?.close() } catch (e: Exception) { /* ignore */ }
        socket = null
        releaseMulticastLock()
        Log.i(TAG, "UDP Beacon stopped")
    }

    private fun generateChallenge(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun acquireMulticastLock() {
        val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiMgr?.createMulticastLock("BeaconLock")?.apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (e: Exception) { /* ignore */ }
        multicastLock = null
    }
}
