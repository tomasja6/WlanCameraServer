package skoda.app.wlancameraserver.network

import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import skoda.app.wlancameraserver.model.WsMessage
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Jednoduchý WebSocket server postavený nad raw TCP + HTTP upgrade.
 * Naslouchá na daném portu, provádí WebSocket handshake a parsuje JSON zprávy.
 */
class WsServer(
    private val port: Int,
    private val listener: WsServerListener
) {
    interface WsServerListener {
        fun onClientConnected(clientId: String)
        fun onClientDisconnected(clientId: String)
        fun onMessage(clientId: String, message: WsMessage)
        fun onServerError(e: Exception)
    }

    private val TAG = "WsServer"
    private val gson = Gson()
    private val executor = Executors.newCachedThreadPool()
    private val clients = ConcurrentHashMap<String, WsServerClient>()
    private val idCounter = AtomicInteger(0)
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        executor.execute {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 10)
                }
                Log.i(TAG, "WS server listening on port $port")
                while (running) {
                    try {
                        val socket = serverSocket!!.accept()
                        handleNewClient(socket)
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "accept error", e)
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Server error", e)
                    listener.onServerError(e)
                }
            }
        }
    }

    private fun handleNewClient(socket: Socket) {
        val clientId = "client-${idCounter.incrementAndGet()}"
        executor.execute {
            val client = WsServerClient(clientId, socket, gson, listener)
            clients[clientId] = client
            client.start()
            clients.remove(clientId)
        }
    }

    fun sendToClient(clientId: String, message: WsMessage) {
        clients[clientId]?.send(gson.toJson(message))
    }

    fun disconnectClient(clientId: String) {
        clients[clientId]?.close()
        clients.remove(clientId)
    }

    fun stop() {
        running = false
        clients.values.forEach { it.close() }
        clients.clear()
        try { serverSocket?.close() } catch (e: Exception) { /* ignore */ }
    }
}
