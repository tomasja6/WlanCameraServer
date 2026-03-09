package skoda.app.wlancameraserver.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import skoda.app.wlancameraserver.model.WsMessage
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest

/**
 * Obsluha jednoho WebSocket klienta (raw TCP, RFC 6455).
 */
class WsServerClient(
    val clientId: String,
    private val socket: Socket,
    private val gson: Gson,
    private val listener: WsServer.WsServerListener
) {
    private val TAG = "WsServerClient[$clientId]"
    @Volatile private var running = false
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    fun start() {
        try {
            inputStream = socket.getInputStream()
            outputStream = socket.getOutputStream()

            // HTTP Upgrade handshake
            if (!performHandshake()) {
                Log.w(TAG, "Handshake failed")
                close()
                return
            }

            running = true
            listener.onClientConnected(clientId)
            readLoop()
        } catch (e: Exception) {
            Log.w(TAG, "Client error", e)
        } finally {
            listener.onClientDisconnected(clientId)
            close()
        }
    }

    private fun performHandshake(): Boolean {
        val reader = BufferedReader(InputStreamReader(inputStream))
        val headers = mutableMapOf<String, String>()
        var line: String?
        // Read request line
        reader.readLine() ?: return false
        // Read headers
        while (true) {
            line = reader.readLine()
            if (line.isNullOrEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
        }

        val key = headers["sec-websocket-key"] ?: return false
        val acceptKey = generateAcceptKey(key)

        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
        outputStream?.write(response.toByteArray(Charsets.UTF_8))
        outputStream?.flush()
        return true
    }

    private fun generateAcceptKey(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update((key + magic).toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sha1.digest(), Base64.NO_WRAP)
    }

    private fun readLoop() {
        val input = inputStream ?: return
        while (running && !socket.isClosed) {
            try {
                val frame = readFrame(input) ?: break
                if (frame.isNotEmpty()) {
                    try {
                        val msg = gson.fromJson(frame, WsMessage::class.java)
                        listener.onMessage(clientId, msg)
                    } catch (e: Exception) {
                        Log.w(TAG, "JSON parse error: $frame", e)
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Read error", e)
                break
            }
        }
    }

    private fun readFrame(input: InputStream): String? {
        val b0 = input.read()
        if (b0 == -1) return null
        val b1 = input.read()
        if (b1 == -1) return null

        val opcode = b0 and 0x0F
        if (opcode == 8) return null  // close frame

        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()

        if (len == 126L) {
            len = ((input.read() shl 8) or input.read()).toLong()
        } else if (len == 127L) {
            len = 0L
            for (i in 0 until 8) len = (len shl 8) or input.read().toLong()
        }

        val mask = if (masked) ByteArray(4) { input.read().toByte() } else null
        val payload = ByteArray(len.toInt())
        var offset = 0
        while (offset < payload.size) {
            val read = input.read(payload, offset, payload.size - offset)
            if (read == -1) return null
            offset += read
        }

        if (masked && mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }

        return String(payload, Charsets.UTF_8)
    }

    fun send(json: String) {
        try {
            val output = outputStream ?: return
            val payload = json.toByteArray(Charsets.UTF_8)
            val frame = buildTextFrame(payload)
            synchronized(this) {
                output.write(frame)
                output.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Send error", e)
        }
    }

    private fun buildTextFrame(payload: ByteArray): ByteArray {
        val len = payload.size
        val header = when {
            len < 126 -> byteArrayOf(0x81.toByte(), len.toByte())
            len < 65536 -> byteArrayOf(
                0x81.toByte(), 126.toByte(),
                (len shr 8).toByte(), (len and 0xFF).toByte()
            )
            else -> {
                val b = ByteArray(10)
                b[0] = 0x81.toByte(); b[1] = 127.toByte()
                for (i in 0 until 8) b[2 + i] = ((len shr (56 - 8 * i)) and 0xFF).toByte()
                b
            }
        }
        return header + payload
    }

    fun close() {
        running = false
        try { socket.close() } catch (e: Exception) { /* ignore */ }
    }
}
