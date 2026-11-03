package com.milliesoft.rokid_ha.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Base64
import com.milliesoft.rokid_ha.data.LightEntity
import com.milliesoft.rokid_ha.data.ThermostatEntity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import kotlin.concurrent.thread

/**
 * Runs on the Rokid Glasses to connect to the paired Android Phone's [PhoneBridgeService]
 * over Bluetooth RFCOMM so the glasses never need Wi-Fi turned on.
 */
class BluetoothGlassesClient(
    private val context: Context,
    private val onFullSyncReceived: (List<ThermostatEntity>, List<LightEntity>, String) -> Unit,
    private val onStateUpdateReceived: (ThermostatEntity?, LightEntity?) -> Unit,
    private val onVoiceEventReceived: (
        stage: String,
        transcript: String?,
        answer: String?,
        error: String?,
        audioB64: String?,
        audioExt: String?
    ) -> Unit,
    private val onBridgeDisconnected: () -> Unit
) {

    @Volatile
    private var running = false

    @Volatile
    private var activeSocket: BluetoothSocket? = null

    @Volatile
    private var activeWriter: PrintWriter? = null

    @Volatile
    var connectedPhoneName: String? = null
        private set

    val isConnected: Boolean
        get() = activeSocket?.isConnected == true && activeWriter != null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true

        thread(name = "RokidBtGlassesClientLoop", isDaemon = true) {
            while (running) {
                try {
                    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val adapter: BluetoothAdapter? = btManager?.adapter
                    val bonded = try {
                        adapter?.bondedDevices?.toList().orEmpty()
                    } catch (_: SecurityException) {
                        emptyList()
                    }

                    var connected = false
                    for (device in bonded) {
                        if (!running) break
                        var socket: BluetoothSocket? = null
                        try {
                            socket = device.createInsecureRfcommSocketToServiceRecord(
                                BluetoothBridgeProtocol.BRIDGE_UUID
                            )
                            socket.connect()
                            activeSocket = socket
                            val writer = PrintWriter(
                                OutputStreamWriter(socket.outputStream, Charsets.UTF_8),
                                true
                            )
                            activeWriter = writer
                            val phoneName = try {
                                device.name ?: device.address
                            } catch (_: Exception) {
                                "Android Phone"
                            }
                            connectedPhoneName = phoneName
                            connected = true

                            // Request initial state sync from the phone bridge
                            sendRequestSync()

                            val reader = BufferedReader(
                                InputStreamReader(socket.inputStream, Charsets.UTF_8)
                            )
                            while (running && socket.isConnected) {
                                val line = reader.readLine() ?: break
                                handleIncomingBridgeMessage(line, phoneName)
                            }
                        } catch (_: Exception) {
                        } finally {
                            activeWriter = null
                            activeSocket = null
                            try {
                                socket?.close()
                            } catch (_: Exception) {
                            }
                            if (connected) {
                                connectedPhoneName = null
                                onBridgeDisconnected()
                                connected = false
                            }
                        }
                    }
                } catch (_: Exception) {
                }

                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun handleIncomingBridgeMessage(rawLine: String, phoneName: String) {
        try {
            val json = JSONObject(rawLine)
            when (json.optString("type")) {
                "full_sync" -> {
                    val tArr = json.optJSONArray("thermostats")
                    val lArr = json.optJSONArray("lights")
                    val thermostats = mutableListOf<ThermostatEntity>()
                    val lights = mutableListOf<LightEntity>()

                    if (tArr != null) {
                        for (i in 0 until tArr.length()) {
                            val obj = tArr.optJSONObject(i) ?: continue
                            thermostats.add(BluetoothBridgeProtocol.thermostatFromJson(obj))
                        }
                    }
                    if (lArr != null) {
                        for (i in 0 until lArr.length()) {
                            val obj = lArr.optJSONObject(i) ?: continue
                            lights.add(BluetoothBridgeProtocol.lightFromJson(obj))
                        }
                    }
                    onFullSyncReceived(thermostats, lights, phoneName)
                }

                "state_update" -> {
                    val tObj = json.optJSONObject("thermostat")
                    val lObj = json.optJSONObject("light")
                    val thermo = tObj?.let { BluetoothBridgeProtocol.thermostatFromJson(it) }
                    val light = lObj?.let { BluetoothBridgeProtocol.lightFromJson(it) }
                    onStateUpdateReceived(thermo, light)
                }

                "voice_event" -> {
                    val stage = json.optString("stage", "")
                    val transcript = json.optString("transcript", "").ifBlank { null }
                    val answer = json.optString("answer", "").ifBlank { null }
                    val error = json.optString("error", "").ifBlank { null }
                    val audioB64 = json.optString("audio_b64", "").ifBlank { null }
                    val audioExt = json.optString("audio_ext", "").ifBlank { null }
                    onVoiceEventReceived(stage, transcript, answer, error, audioB64, audioExt)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun sendRequestSync(): Boolean = sendJson(
        JSONObject().apply {
            put("cmd", "request_sync")
        }
    )

    fun sendSetTemp(entityId: String, temp: Double): Boolean = sendJson(
        JSONObject().apply {
            put("cmd", "set_temp")
            put("entity_id", entityId)
            put("temp", temp)
        }
    )

    fun sendSetHvacMode(entityId: String, hvacMode: String): Boolean = sendJson(
        JSONObject().apply {
            put("cmd", "set_hvac_mode")
            put("entity_id", entityId)
            put("hvac_mode", hvacMode)
        }
    )

    fun sendSetLight(entityId: String, turnOn: Boolean, brightnessPct: Int?): Boolean = sendJson(
        JSONObject().apply {
            put("cmd", "set_light")
            put("entity_id", entityId)
            put("turn_on", turnOn)
            if (brightnessPct != null) {
                put("brightness_pct", brightnessPct)
            }
        }
    )

    fun sendVoiceStart(): Boolean = sendJson(
        JSONObject().apply {
            put("cmd", "voice_start")
        }
    )

    fun sendVoiceChunk(pcmBytes: ByteArray, length: Int): Boolean {
        val slice = if (length == pcmBytes.size) pcmBytes else pcmBytes.copyOfRange(0, length)
        val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)
        return sendJson(
            JSONObject().apply {
                put("cmd", "voice_chunk")
                put("b64", b64)
            }
        )
    }

    fun sendVoiceStop(): Boolean = sendJson(
        JSONObject().apply {
            put("cmd", "voice_stop")
        }
    )

    fun sendVoiceText(text: String): Boolean = sendJson(
        JSONObject().apply {
            put("cmd", "voice_text")
            put("text", text)
        }
    )

    fun sendSpeakText(text: String): Boolean = sendJson(
        JSONObject().apply {
            put("cmd", "speak_text")
            put("text", text)
        }
    )

    private fun sendJson(obj: JSONObject): Boolean {
        val writer = activeWriter ?: return false
        return try {
            writer.println(obj.toString())
            !writer.checkError()
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        running = false
        try {
            activeSocket?.close()
        } catch (_: Exception) {
        }
        activeSocket = null
        activeWriter = null
    }
}
