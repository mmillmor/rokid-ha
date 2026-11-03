package com.milliesoft.rokid_ha

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.milliesoft.rokid_ha.bt.PhoneBridgeService
import com.milliesoft.rokid_ha.data.ConfigRepository
import com.milliesoft.rokid_ha.input.RokidKeyMapper
import com.milliesoft.rokid_ha.ui.HaHudViewModel
import com.milliesoft.rokid_ha.ui.PhoneCompanionScreen
import com.milliesoft.rokid_ha.ui.RokidHudScreen
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val viewModel: HaHudViewModel by viewModels()
    private var isGlassesHudMode by mutableStateOf(true)

    private val requestMicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startOrStopHaVoiceSession()
        } else {
            viewModel.showTemporaryBanner("MIC PERMISSION REQUIRED FOR VOICE")
        }
    }

    private val requestBluetoothPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.reloadConfiguration()
        if (!isGlassesHudMode) {
            val cfg = ConfigRepository(this).loadConfig()
            if (cfg.isConfigured && !cfg.demoMode) {
                startPhoneBridgeService()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Auto-detect Rokid Glasses vs Android Phone
        isGlassesHudMode = detectRokidGlassesHardware()

        // Keep waveguide display active while HUD is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Request Bluetooth runtime permission on Android 12+ (API 31+)
        ensureBluetoothPermissions()

        // Check if launched with ADB configuration extras (--es ha_url ... --es ha_token ...)
        handleConfigurationIntent(intent)

        // If running on the Android Phone and already configured, auto-start the Bluetooth Bridge
        if (!isGlassesHudMode) {
            val cfg = ConfigRepository(this).loadConfig()
            if (cfg.isConfigured && !cfg.demoMode) {
                startPhoneBridgeService()
            }
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val bridgeStatus by PhoneBridgeService.bridgeStatus.collectAsState()

            if (isGlassesHudMode) {
                RokidHudScreen(
                    uiState = uiState,
                    onGesture = { gesture ->
                        viewModel.handleGesture(
                            gesture = gesture,
                            onTriggerVoiceRecognition = { triggerVoiceRecognition() },
                            onOpenWifiSettings = { openWifiSettings() },
                            onExitApp = { finishAndRemoveTask() }
                        )
                    },
                    onAdjustTemp = { thermostat, delta -> viewModel.adjustThermostatTemp(thermostat, delta) },
                    onSelectHvacMode = { thermostat, mode -> viewModel.setThermostatMode(thermostat.entityId, mode) },
                    onToggleLight = { light -> viewModel.toggleLight(light) },
                    onAdjustBrightness = { light, delta -> viewModel.adjustLightBrightness(light, delta) },
                    onQrScanned = { qrText -> viewModel.onQrCodeScanned(qrText) },
                    onCancelQr = { viewModel.cancelQrScan() },
                    onTriggerVoice = { triggerVoiceRecognition() }
                )
            } else {
                PhoneCompanionScreen(
                    initialConfig = uiState.config,
                    bridgeStatus = bridgeStatus,
                    onSaveAndStartBridge = { haUrl, token ->
                        val repo = ConfigRepository(this)
                        val updated = repo.loadConfig().copy(
                            haUrl = haUrl.trim().trimEnd('/'),
                            accessToken = token.trim(),
                            demoMode = false
                        )
                        repo.saveConfig(updated)
                        viewModel.reloadConfiguration()
                        startPhoneBridgeService()
                    },
                    onStopBridge = {
                        stopPhoneBridgeService()
                    },
                    onSwitchToGlassesHud = {
                        isGlassesHudMode = true
                    }
                )
            }
        }
    }

    private fun detectRokidGlassesHardware(): Boolean {
        val fields = listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT,
            Build.HARDWARE,
            Build.DISPLAY
        ).joinToString(" ").lowercase()

        return fields.contains("rokid") ||
            fields.contains("yoda") ||
            fields.contains("sprite") ||
            fields.contains("rg-")
    }

    private fun ensureBluetoothPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            requestBluetoothPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startPhoneBridgeService() {
        try {
            val intent = Intent(this, PhoneBridgeService::class.java).apply {
                action = PhoneBridgeService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopPhoneBridgeService() {
        try {
            val intent = Intent(this, PhoneBridgeService::class.java).apply {
                action = PhoneBridgeService.ACTION_STOP
            }
            startService(intent)
        } catch (_: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleConfigurationIntent(intent)
    }

    private fun handleConfigurationIntent(intent: Intent?) {
        if (intent == null) return
        val url = intent.getStringExtra("ha_url")
        val token = intent.getStringExtra("ha_token")
        val voiceCmd = intent.getStringExtra("voice_cmd")

        if (!url.isNullOrBlank() || !token.isNullOrBlank()) {
            val repo = ConfigRepository(this)
            val current = repo.loadConfig()
            val json = JSONObject().apply {
                put("url", url ?: current.haUrl)
                put("token", token ?: current.accessToken)
            }
            repo.parseAndSaveJsonConfig(json.toString())
            viewModel.reloadConfiguration()
            viewModel.showTemporaryBanner("UPDATED CONFIG VIA ADB INTENT")
            if (!isGlassesHudMode) {
                startPhoneBridgeService()
            }
        }

        if (!voiceCmd.isNullOrBlank()) {
            viewModel.processVoiceCommand(voiceCmd)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val gesture = RokidKeyMapper.mapKeyDown(event.keyCode, event)
            if (gesture != null) {
                if (!isGlassesHudMode) {
                    isGlassesHudMode = true
                }
                viewModel.handleGesture(
                    gesture = gesture,
                    onTriggerVoiceRecognition = { triggerVoiceRecognition() },
                    onOpenWifiSettings = { openWifiSettings() },
                    onExitApp = { finishAndRemoveTask() }
                )
                return true
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (RokidKeyMapper.mapKeyDown(event.keyCode, null) != null) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openWifiSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_WIFI)
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                viewModel.showTemporaryBanner("CONNECT GLASSES WI-FI IN 'HI ROKID' PHONE APP")
            }
        }
    }

    private fun triggerVoiceRecognition() {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPerm) {
            requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        viewModel.startOrStopHaVoiceSession()
    }
}
