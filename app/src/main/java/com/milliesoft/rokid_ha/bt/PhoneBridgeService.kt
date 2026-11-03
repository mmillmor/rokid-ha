package com.milliesoft.rokid_ha.bt

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import com.milliesoft.rokid_ha.MainActivity
import com.milliesoft.rokid_ha.data.ConfigRepository
import com.milliesoft.rokid_ha.data.ConnectionState
import com.milliesoft.rokid_ha.data.HomeAssistantClient
import com.milliesoft.rokid_ha.data.LightEntity
import com.milliesoft.rokid_ha.data.ThermostatEntity
import com.milliesoft.rokid_ha.input.VoiceActionResult
import com.milliesoft.rokid_ha.input.VoiceCommandParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

data class PhoneBridgeStatus(
    val isRunning: Boolean = false,
    val haConnectionState: ConnectionState = ConnectionState.UNCONFIGURED,
    val haStatusMessage: String = "Idle",
    val connectedGlassesName: String? = null,
    val thermostatCount: Int = 0,
    val lightCount: Int = 0,
    val lastRelayedCommand: String? = null
)

class PhoneBridgeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var configRepo: ConfigRepository

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var acceptLoopActive = false

    private val activeWriters = CopyOnWriteArrayList<PrintWriter>()

    @Volatile
    private var cachedThermostats: List<ThermostatEntity> = emptyList()

    @Volatile
    private var cachedLights: List<LightEntity> = emptyList()

    @Volatile
    private var activeSttHandlerId: Int = -1

    @Volatile
    private var lastSttTranscript: String = ""

    @Volatile
    private var lastFinalAnswer: String = ""

    @Volatile
    private var usedLocalFallbackAnswer: Boolean = false

    @Volatile
    private var haTtsDispatchedForTurn: Boolean = false

    private var phoneTts: TextToSpeech? = null

    @Volatile
    private var phoneTtsReady: Boolean = false

    private val haClient = HomeAssistantClient(
        onStatesLoaded = { thermostats, lights ->
            cachedThermostats = thermostats
            cachedLights = lights
            _bridgeStatus.update {
                it.copy(
                    thermostatCount = thermostats.size,
                    lightCount = lights.size
                )
            }
            broadcastToGlasses(BluetoothBridgeProtocol.encodeFullSync(thermostats, lights))
        },
        onSingleStateChanged = { updatedThermo, updatedLight ->
            if (updatedThermo != null) {
                val exists = cachedThermostats.any { it.entityId == updatedThermo.entityId }
                cachedThermostats = if (exists) {
                    cachedThermostats.map { if (it.entityId == updatedThermo.entityId) updatedThermo else it }
                } else {
                    (cachedThermostats + updatedThermo).sortedBy { it.friendlyName.lowercase() }
                }
            }
            if (updatedLight != null) {
                val exists = cachedLights.any { it.entityId == updatedLight.entityId }
                cachedLights = if (exists) {
                    cachedLights.map { if (it.entityId == updatedLight.entityId) updatedLight else it }
                } else {
                    (cachedLights + updatedLight).sortedBy { it.friendlyName.lowercase() }
                }
            }
            broadcastToGlasses(BluetoothBridgeProtocol.encodeSingleUpdate(updatedThermo, updatedLight))
        },
        onConnectionStateChanged = { state, msg ->
            _bridgeStatus.update {
                it.copy(
                    haConnectionState = state,
                    haStatusMessage = msg ?: state.name
                )
            }
            updateNotification()
        }
    )

    override fun onCreate() {
        super.onCreate()
        configRepo = ConfigRepository(this)
        try {
            phoneTts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    phoneTts?.language = Locale.getDefault()
                    phoneTtsReady = true
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundBridge()
        reloadHomeAssistantConnection()
        startBluetoothServerLoop()
        return START_STICKY
    }

    private fun startForegroundBridge() {
        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
        }
        _bridgeStatus.update { it.copy(isRunning = true) }
    }

    fun reloadHomeAssistantConnection() {
        val cfg = configRepo.loadConfig()
        if (!cfg.isConfigured) {
            _bridgeStatus.update {
                it.copy(
                    haConnectionState = ConnectionState.UNCONFIGURED,
                    haStatusMessage = "Enter Home Assistant URL & Token above"
                )
            }
            return
        }
        serviceScope.launch {
            haClient.fetchStatesRest(cfg)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothServerLoop() {
        if (acceptLoopActive) return
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _bridgeStatus.update {
                it.copy(haStatusMessage = "Bluetooth is off on phone")
            }
            return
        }

        acceptLoopActive = true
        thread(name = "PhoneBtBridgeAcceptLoop", isDaemon = true) {
            while (acceptLoopActive) {
                try {
                    val srv = adapter.listenUsingInsecureRfcommWithServiceRecord(
                        BluetoothBridgeProtocol.SERVICE_NAME,
                        BluetoothBridgeProtocol.BRIDGE_UUID
                    )
                    serverSocket = srv
                    val clientSocket = srv.accept()
                    try {
                        srv.close()
                    } catch (_: Exception) {
                    }
                    if (clientSocket != null) {
                        handleGlassesSocket(clientSocket)
                    }
                } catch (_: Exception) {
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleGlassesSocket(socket: BluetoothSocket) {
        val deviceName = try {
            socket.remoteDevice?.name ?: socket.remoteDevice?.address ?: "Rokid Glasses"
        } catch (_: Exception) {
            "Rokid Glasses"
        }

        _bridgeStatus.update { it.copy(connectedGlassesName = deviceName) }
        updateNotification()

        thread(name = "GlassesBtClientHandler", isDaemon = true) {
            var writer: PrintWriter? = null
            try {
                writer = PrintWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8), true)
                activeWriters.add(writer)

                // Immediately push full state snapshot to the newly connected Rokid Glasses
                if (cachedThermostats.isNotEmpty() || cachedLights.isNotEmpty()) {
                    writer.println(BluetoothBridgeProtocol.encodeFullSync(cachedThermostats, cachedLights))
                } else {
                    val cfg = configRepo.loadConfig()
                    serviceScope.launch {
                        haClient.fetchStatesRest(cfg)
                    }
                }

                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                while (acceptLoopActive && socket.isConnected) {
                    val line = reader.readLine() ?: break
                    handleIncomingGlassesCommand(line, writer)
                }
            } catch (_: Exception) {
            } finally {
                if (writer != null) {
                    activeWriters.remove(writer)
                }
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                if (activeWriters.isEmpty()) {
                    _bridgeStatus.update { it.copy(connectedGlassesName = null) }
                    updateNotification()
                }
            }
        }
    }

    private fun handleIncomingGlassesCommand(rawJsonLine: String, replyWriter: PrintWriter) {
        try {
            val json = JSONObject(rawJsonLine)
            val cmd = json.optString("cmd")
            val cfg = configRepo.loadConfig()

            when (cmd) {
                "request_sync" -> {
                    if (cachedThermostats.isNotEmpty() || cachedLights.isNotEmpty()) {
                        replyWriter.println(
                            BluetoothBridgeProtocol.encodeFullSync(cachedThermostats, cachedLights)
                        )
                    }
                    serviceScope.launch {
                        haClient.fetchStatesRest(cfg)
                    }
                }

                "set_temp" -> {
                    val entityId = json.optString("entity_id")
                    val temp = json.optDouble("temp")
                    _bridgeStatus.update { it.copy(lastRelayedCommand = "$entityId → $temp°") }
                    serviceScope.launch {
                        haClient.setThermostatTemperature(cfg, entityId, temp)
                    }
                }

                "set_hvac_mode" -> {
                    val entityId = json.optString("entity_id")
                    val mode = json.optString("hvac_mode")
                    _bridgeStatus.update { it.copy(lastRelayedCommand = "$entityId → $mode") }
                    serviceScope.launch {
                        haClient.setThermostatHvacMode(cfg, entityId, mode)
                    }
                }

                "set_light" -> {
                    val entityId = json.optString("entity_id")
                    val turnOn = json.optBoolean("turn_on", true)
                    val pct = if (json.has("brightness_pct")) json.optInt("brightness_pct") else null
                    _bridgeStatus.update {
                        it.copy(lastRelayedCommand = "$entityId → ${if (turnOn) "${pct ?: 100}%" else "OFF"}")
                    }
                    serviceScope.launch {
                        haClient.setLightState(cfg, entityId, turnOn, pct)
                    }
                }

                "voice_start" -> {
                    _bridgeStatus.update { it.copy(lastRelayedCommand = "Voice Assistant started") }
                    lastSttTranscript = ""
                    lastFinalAnswer = ""
                    usedLocalFallbackAnswer = false
                    haTtsDispatchedForTurn = false

                    haClient.startAssistVoicePipeline(
                        onHandlerReady = { handlerId ->
                            activeSttHandlerId = handlerId
                            replyWriter.println(
                                BluetoothBridgeProtocol.encodeVoiceEvent(stage = "ready")
                            )
                        },
                        onSttResult = { transcript ->
                            lastSttTranscript = transcript
                            replyWriter.println(
                                BluetoothBridgeProtocol.encodeVoiceEvent(
                                    stage = "stt",
                                    transcript = transcript
                                )
                            )
                        },
                        onIntentResponse = { haSpeech ->
                            val lower = haSpeech.lowercase()
                            val failed = lower.contains("sorry") || lower.contains("not sure") ||
                                lower.contains("couldn't understand") || lower.contains("not aware")

                            val finalAnswer = if (failed && lastSttTranscript.isNotBlank()) {
                                val local = VoiceCommandParser.parse(
                                    lastSttTranscript,
                                    cachedThermostats,
                                    cachedLights,
                                    null
                                )
                                when (local) {
                                    is VoiceActionResult.QueryAnswer -> {
                                        usedLocalFallbackAnswer = true
                                        local.answerText
                                    }
                                    is VoiceActionResult.SetThermostatTemp -> {
                                        usedLocalFallbackAnswer = true
                                        serviceScope.launch {
                                            haClient.setThermostatTemperature(cfg, local.entity.entityId, local.targetTemp)
                                        }
                                        local.summary
                                    }
                                    is VoiceActionResult.SetThermostatMode -> {
                                        usedLocalFallbackAnswer = true
                                        serviceScope.launch {
                                            haClient.setThermostatHvacMode(cfg, local.entity.entityId, local.hvacMode)
                                        }
                                        local.summary
                                    }
                                    is VoiceActionResult.SetLight -> {
                                        usedLocalFallbackAnswer = true
                                        serviceScope.launch {
                                            haClient.setLightState(
                                                cfg,
                                                local.entity.entityId,
                                                local.turnOn,
                                                local.brightnessPct
                                            )
                                        }
                                        local.summary
                                    }
                                    is VoiceActionResult.Unmatched -> haSpeech
                                }
                            } else {
                                haSpeech
                            }

                            lastFinalAnswer = finalAnswer
                            replyWriter.println(
                                BluetoothBridgeProtocol.encodeVoiceEvent(
                                    stage = "intent",
                                    answer = finalAnswer
                                )
                            )

                            // If we used the local fallback answer or if HA doesn't send a TTS URL within 700ms,
                            // synthesize the spoken answer on the phone and send the WAV audio to the glasses!
                            serviceScope.launch {
                                if (!usedLocalFallbackAnswer) {
                                    delay(700)
                                }
                                if (!haTtsDispatchedForTurn || usedLocalFallbackAnswer) {
                                    synthesizeAndSendSpeechToGlasses(finalAnswer, replyWriter)
                                }
                            }
                        },
                        onTtsAudioUrl = { ttsUrl ->
                            if (!usedLocalFallbackAnswer) {
                                haTtsDispatchedForTurn = true
                                serviceScope.launch {
                                    val bytes = haClient.downloadAudioBytes(ttsUrl, cfg.accessToken)
                                    if (bytes != null && bytes.isNotEmpty()) {
                                        val ext = when {
                                            ttsUrl.contains(".wav", ignoreCase = true) -> "wav"
                                            ttsUrl.contains(".flac", ignoreCase = true) -> "flac"
                                            ttsUrl.contains(".ogg", ignoreCase = true) -> "ogg"
                                            else -> "mp3"
                                        }
                                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                        replyWriter.println(
                                            BluetoothBridgeProtocol.encodeVoiceEvent(
                                                stage = "tts_audio",
                                                audioB64 = b64,
                                                audioExt = ext
                                            )
                                        )
                                    } else if (lastFinalAnswer.isNotBlank()) {
                                        synthesizeAndSendSpeechToGlasses(lastFinalAnswer, replyWriter)
                                    }
                                }
                            }
                        },
                        onPipelineError = { err ->
                            replyWriter.println(
                                BluetoothBridgeProtocol.encodeVoiceEvent(
                                    stage = "error",
                                    error = err
                                )
                            )
                        }
                    )
                }

                "voice_chunk" -> {
                    val hId = activeSttHandlerId
                    if (hId > 0) {
                        val b64 = json.optString("b64", "")
                        if (b64.isNotBlank()) {
                            val pcm = Base64.decode(b64, Base64.NO_WRAP)
                            haClient.sendAssistAudioChunk(hId, pcm, pcm.size)
                        }
                    }
                }

                "voice_stop" -> {
                    val hId = activeSttHandlerId
                    if (hId > 0) {
                        haClient.finishAssistAudioStream(hId)
                    }
                }

                "voice_text" -> {
                    val text = json.optString("text", "")
                    if (text.isNotBlank()) {
                        serviceScope.launch {
                            val reply = haClient.sendAssistConversation(cfg, text)
                            val answer = reply ?: run {
                                when (val local = VoiceCommandParser.parse(text, cachedThermostats, cachedLights, null)) {
                                    is VoiceActionResult.QueryAnswer -> local.answerText
                                    is VoiceActionResult.SetThermostatTemp -> local.summary
                                    is VoiceActionResult.SetThermostatMode -> local.summary
                                    is VoiceActionResult.SetLight -> local.summary
                                    is VoiceActionResult.Unmatched -> "Sent to HA: $text"
                                }
                            }
                            replyWriter.println(
                                BluetoothBridgeProtocol.encodeVoiceEvent(
                                    stage = "intent",
                                    transcript = text,
                                    answer = answer
                                )
                            )
                            synthesizeAndSendSpeechToGlasses(answer, replyWriter)
                        }
                    }
                }

                "speak_text" -> {
                    val text = json.optString("text", "")
                    if (text.isNotBlank()) {
                        synthesizeAndSendSpeechToGlasses(text, replyWriter)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun synthesizeAndSendSpeechToGlasses(text: String, replyWriter: PrintWriter) {
        if (text.isBlank()) return
        val engine = phoneTts
        if (engine == null || !phoneTtsReady) {
            return
        }
        try {
            val utteranceId = "rokid_tts_${System.currentTimeMillis()}"
            val outFile = File(cacheDir, "$utteranceId.wav")
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId && outFile.exists() && outFile.length() > 0L) {
                        try {
                            val bytes = outFile.readBytes()
                            outFile.delete()
                            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            replyWriter.println(
                                BluetoothBridgeProtocol.encodeVoiceEvent(
                                    stage = "tts_audio",
                                    audioB64 = b64,
                                    audioExt = "wav"
                                )
                            )
                        } catch (_: Exception) {
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    outFile.delete()
                }
            })
            val res = engine.synthesizeToFile(text, null, outFile, utteranceId)
            if (res != TextToSpeech.SUCCESS) {
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        } catch (_: Exception) {
        }
    }

    private fun broadcastToGlasses(jsonLine: String) {
        for (writer in activeWriters) {
            try {
                writer.println(jsonLine)
            } catch (_: Exception) {
                activeWriters.remove(writer)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rokid Home Assistant Bluetooth Bridge",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val status = _bridgeStatus.value
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val glassesText = status.connectedGlassesName?.let { "Glasses: $it" } ?: "Waiting for Rokid Glasses..."
        val contentText = "$glassesText | HA: ${status.haConnectionState.name}"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Rokid Home Assistant Bridge")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        acceptLoopActive = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        try {
            phoneTts?.shutdown()
        } catch (_: Exception) {
        }
        phoneTts = null
        haClient.disconnectWebSocket()
        serviceScope.cancel()
        _bridgeStatus.update { it.copy(isRunning = false, connectedGlassesName = null) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "rokid_ha_bridge_channel"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_START = "com.milliesoft.rokid_ha.START_BRIDGE"
        const val ACTION_STOP = "com.milliesoft.rokid_ha.STOP_BRIDGE"

        private val _bridgeStatus = MutableStateFlow(PhoneBridgeStatus())
        val bridgeStatus: StateFlow<PhoneBridgeStatus> = _bridgeStatus.asStateFlow()
    }
}
