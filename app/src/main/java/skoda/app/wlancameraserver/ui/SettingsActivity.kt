package skoda.app.wlancameraserver.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import skoda.app.wlancameraserver.data.ReceiverRepository
import skoda.app.wlancameraserver.databinding.ActivitySettingsBinding
import skoda.app.wlancameraserver.model.AppSettings

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: ReceiverRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ReceiverRepository(this)
        loadSettings()

        binding.btnSaveSettings.setOnClickListener { saveSettings() }
        binding.btnBackSettings.setOnClickListener { finish() }
        binding.btnResetHotspot.setOnClickListener {
            repository.clearHotspotCredentials()
            updateHotspotCredentialsUi()
            Toast.makeText(this, "Wi-Fi údaje resetovány – nové se uloží při příštím startu", Toast.LENGTH_LONG).show()
        }

        // Přepínač viditelnosti manuálního pole
        binding.switchSystemHotspot.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutManualCredentials.visibility =
                if (isChecked) View.GONE else View.VISIBLE
        }

        updateHotspotCredentialsUi()
    }

    private fun loadSettings() {
        val s = repository.getSettings()
        binding.switchSingleCamera.isChecked = s.singleCameraOnly
        binding.switchAutoAccept.isChecked = s.autoAcceptTrusted
        binding.etWsPort.setText(s.wsPort.toString())
        binding.etUdpPort.setText(s.udpBeaconPort.toString())
        binding.switchVideoFill.isChecked = true // default fill
        binding.switchDebugOverlay.isChecked = false
        binding.switchSystemHotspot.isChecked = s.useSystemHotspot
        binding.etManualSsid.setText(s.manualSsid)
        binding.etManualPsk.setText(s.manualPsk)
        binding.layoutManualCredentials.visibility =
            if (s.useSystemHotspot) View.GONE else View.VISIBLE
    }

    private fun saveSettings() {
        val wsPort = binding.etWsPort.text.toString().toIntOrNull() ?: 8888
        val udpPort = binding.etUdpPort.text.toString().toIntOrNull() ?: 39500
        val useSystem = binding.switchSystemHotspot.isChecked
        val settings = AppSettings(
            singleCameraOnly = binding.switchSingleCamera.isChecked,
            autoAcceptTrusted = binding.switchAutoAccept.isChecked,
            wsPort = wsPort,
            udpBeaconPort = udpPort,
            useSystemHotspot = useSystem,
            manualSsid = binding.etManualSsid.text.toString().trim(),
            manualPsk = binding.etManualPsk.text.toString()
        )
        repository.saveSettings(settings)
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun updateHotspotCredentialsUi() {
        val savedSsid = repository.getSavedSsid()
        val savedPsk = repository.getSavedPsk()
        binding.tvSavedSsid.text = if (savedSsid != null) "SSID: $savedSsid" else "SSID: (zatím nespuštěno)"
        binding.tvSavedPsk.text = if (savedPsk != null) "Heslo: $savedPsk" else "Heslo: (zatím nespuštěno)"
    }
}
