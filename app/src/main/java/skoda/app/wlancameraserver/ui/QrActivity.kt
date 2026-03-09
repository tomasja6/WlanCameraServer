package skoda.app.wlancameraserver.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import skoda.app.wlancameraserver.databinding.ActivityQrBinding
import skoda.app.wlancameraserver.model.QrPayload

class QrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrBinding
    private var countDownTimer: CountDownTimer? = null

    companion object {
        const val EXTRA_QR_JSON = "extra_qr_json"
        const val EXTRA_EXPIRY_MS = "extra_expiry_ms"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val json = intent.getStringExtra(EXTRA_QR_JSON) ?: run {
            Toast.makeText(this, "No QR data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val expiryMs = intent.getLongExtra(EXTRA_EXPIRY_MS, System.currentTimeMillis() + 120_000L)

        displayQr(json)
        startCountdown(expiryMs)

        binding.btnRenewQr.setOnClickListener {
            // Vrátíme se zpět - MainActivity zavolá renewQr a znovu otevře QrActivity
            setResult(RESULT_OK)
            finish()
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun displayQr(json: String) {
        try {
            val encoder = BarcodeEncoder()
            val bitmap: Bitmap = encoder.encodeBitmap(json, BarcodeFormat.QR_CODE, 600, 600)
            binding.ivQrCode.setImageBitmap(bitmap)

            val payload = Gson().fromJson(json, QrPayload::class.java)
            binding.tvSsid.text = "SSID: ${payload.ssid}"
            binding.tvPsk.text = "Password: ${payload.psk}"
            binding.tvWsUrl.text = "WS: ${payload.wsUrl}"
            binding.tvSessionId.text = "Session: ${payload.sessionId}"
        } catch (e: Exception) {
            Toast.makeText(this, "QR generation error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCountdown(expiryMs: Long) {
        countDownTimer?.cancel()
        val remaining = expiryMs - System.currentTimeMillis()
        if (remaining <= 0) {
            binding.tvCountdown.text = "Token expired"
            return
        }
        countDownTimer = object : CountDownTimer(remaining, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = millisUntilFinished / 1000
                binding.tvCountdown.text = "Expires in: ${secs}s"
            }
            override fun onFinish() {
                binding.tvCountdown.text = "Token expired"
            }
        }.start()
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        super.onDestroy()
    }
}
