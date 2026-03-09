package skoda.app.wlancameraserver.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import skoda.app.wlancameraserver.R
import skoda.app.wlancameraserver.databinding.ActivityMainBinding
import skoda.app.wlancameraserver.model.AppState
import skoda.app.wlancameraserver.service.ReceiverForegroundService

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding

    private var service: ReceiverForegroundService? = null
    private var bound = false
    private var currentVideoTrack: VideoTrack? = null
    private var rendererInitialized = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as ReceiverForegroundService.LocalBinder
            service = localBinder.getService()
            bound = true
            observeService()
            Log.i(TAG, "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
            Log.i(TAG, "Service disconnected")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) startReceiver()
        else Toast.makeText(this, "Required permissions denied", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        bindService()
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener { checkPermissionsAndStart() }
        binding.btnStop.setOnClickListener { service?.stopReceiver() }
        binding.btnShowQr.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val payload = svc.buildQrPayload()
            val intent = Intent(this, QrActivity::class.java).apply {
                putExtra(QrActivity.EXTRA_QR_JSON, com.google.gson.Gson().toJson(payload))
                putExtra(QrActivity.EXTRA_EXPIRY_MS, svc.signalingController.getTokenExpiryMs())
            }
            startActivity(intent)
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        val required = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        // Android 13+ notification permission
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
            required.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        required.forEach { perm ->
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm)
            }
        }
        if (needed.isEmpty()) startReceiver()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startReceiver() {
        val intent = Intent(this, ReceiverForegroundService::class.java).apply {
            action = ReceiverForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        // onStartCommand(ACTION_START) ve službě zavolá startReceiver() – nevolat znovu
    }

    private fun bindService() {
        val intent = Intent(this, ReceiverForegroundService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeService() {
        val svc = service ?: return

        svc.appState.observe(this) { state ->
            updateUiForState(state)
        }

        svc.ssid.observe(this) { ssid ->
            binding.tvHotspotStatus.text = if (ssid.isNotEmpty())
                "Hotspot: ON  SSID: $ssid" else "Hotspot: OFF"
        }

        svc.serverIp.observe(this) { ip ->
            binding.tvServerStatus.text = "WS: ${svc.appState.value?.name ?: "-"}  IP: $ip  Port: ${svc.repository.getSettings().wsPort}"
        }

        svc.connectedCameraId.observe(this) { camId ->
            binding.tvCameraStatus.text = if (camId != null) "Camera: $camId" else "Camera: none"
        }

        svc.errorMessage.observe(this) { err ->
            if (!err.isNullOrEmpty()) {
                Toast.makeText(this, "Error: $err", Toast.LENGTH_LONG).show()
            }
        }

        svc.pendingCameraId.observe(this) { cameraId ->
            if (!cameraId.isNullOrEmpty()) {
                showApprovalDialog(cameraId)
            }
        }

        svc.videoTrack.observe(this) { track ->
            if (track != null) {
                attachVideoTrack(track)
            } else {
                detachVideoTrack()
            }
        }
    }

    private fun attachVideoTrack(track: VideoTrack) {
        val svc = service ?: return
        if (!rendererInitialized) {
            svc.webRtcManager.initVideoRenderer(binding.surfaceViewRenderer)
            rendererInitialized = true
        }
        currentVideoTrack?.removeSink(binding.surfaceViewRenderer)
        currentVideoTrack = track
        track.addSink(binding.surfaceViewRenderer)
        binding.surfaceViewRenderer.visibility = View.VISIBLE
        binding.tvStreamStatus.text = "● Connected"
        binding.tvStreamStatus.setTextColor(
            ContextCompat.getColor(this, android.R.color.holo_green_light)
        )
    }

    private fun detachVideoTrack() {
        currentVideoTrack?.removeSink(binding.surfaceViewRenderer)
        currentVideoTrack = null
        binding.surfaceViewRenderer.visibility = View.GONE
        binding.tvStreamStatus.text = "● Waiting..."
        binding.tvStreamStatus.setTextColor(
            ContextCompat.getColor(this, android.R.color.darker_gray)
        )
    }

    private fun showApprovalDialog(cameraId: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Nové připojení")
            .setMessage("Kamera \"$cameraId\" žádá o připojení.\n\nPřijmout?")
            .setCancelable(false)
            .setPositiveButton("Přijmout") { _, _ ->
                service?.approvePendingCamera()
            }
            .setNegativeButton("Odmítnout") { _, _ ->
                service?.rejectPendingCamera()
            }
            .show()
    }

    private fun updateUiForState(state: AppState) {
        Log.d(TAG, "State: $state")
        binding.btnStart.isEnabled = state == AppState.IDLE || state == AppState.ERROR
        binding.btnStop.isEnabled = state != AppState.IDLE
        binding.btnShowQr.isEnabled = state != AppState.IDLE && state != AppState.STARTING_HOTSPOT &&
                state != AppState.ERROR

        binding.tvStateLabel.text = when (state) {
            AppState.IDLE -> "Status: Idle"
            AppState.STARTING_HOTSPOT -> "Status: Starting hotspot..."
            AppState.HOTSPOT_READY -> "Status: Hotspot ready"
            AppState.STARTING_SERVER -> "Status: Starting server..."
            AppState.WAITING_FOR_SENDER -> "Status: Waiting for camera"
            AppState.AUTHENTICATING -> "Status: Authenticating..."
            AppState.PENDING_APPROVAL -> "Status: Waiting for approval..."
            AppState.NEGOTIATING_WEBRTC -> "Status: Negotiating WebRTC..."
            AppState.STREAMING -> "Status: Streaming"
            AppState.REJECTED_BUSY -> "Status: Busy"
            AppState.ERROR -> "Status: Error"
        }

        // Server WS status
        val svc = service
        if (svc != null) {
            binding.tvServerStatus.text = buildString {
                append("WS: ")
                append(if (state != AppState.IDLE && state != AppState.STARTING_HOTSPOT && state != AppState.HOTSPOT_READY)
                    "ON  Port: ${svc.repository.getSettings().wsPort}" else "OFF")
                val ip = svc.serverIp.value
                if (!ip.isNullOrEmpty()) append("  IP: $ip")
            }
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        if (rendererInitialized) {
            try {
                binding.surfaceViewRenderer.release()
            } catch (e: Exception) { /* ignore */ }
        }
        super.onDestroy()
    }
}
