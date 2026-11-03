package com.milliesoft.rokid_ha.bt

import com.milliesoft.rokid_ha.data.LightEntity
import com.milliesoft.rokid_ha.data.ThermostatEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object BluetoothBridgeProtocol {

    val BRIDGE_UUID: UUID = UUID.fromString("8f6e5d4c-3b2a-1908-7f6e-5d4c3b2a1908")
    const val SERVICE_NAME = "RokidHomeAssistantBridge"

    fun thermostatToJson(t: ThermostatEntity): JSONObject = JSONObject().apply {
        put("id", t.entityId)
        put("name", t.friendlyName)
        put("area", t.areaName)
        put("mode", t.hvacMode)
        put("action", t.hvacAction ?: "")
        if (t.currentTemperature != null) put("cur", t.currentTemperature)
        if (t.targetTemperature != null) put("tgt", t.targetTemperature)
        put("min", t.minTemp)
        put("max", t.maxTemp)
        put("step", t.targetTempStep)
        put("unit", t.unit)
        put("modes", JSONArray(t.hvacModes))
    }

    fun thermostatFromJson(j: JSONObject): ThermostatEntity {
        val modesArr = j.optJSONArray("modes")
        val modes = mutableListOf<String>()
        if (modesArr != null) {
            for (i in 0 until modesArr.length()) {
                val m = modesArr.optString(i)
                if (m.isNotBlank()) modes.add(m)
            }
        }
        return ThermostatEntity(
            entityId = j.optString("id"),
            friendlyName = j.optString("name"),
            areaName = j.optString("area", "Other"),
            hvacMode = j.optString("mode", "off"),
            hvacAction = j.optString("action", "").ifBlank { null },
            currentTemperature = j.optDouble("cur").takeIf { !it.isNaN() },
            targetTemperature = j.optDouble("tgt").takeIf { !it.isNaN() },
            minTemp = j.optDouble("min", 7.0),
            maxTemp = j.optDouble("max", 35.0),
            targetTempStep = j.optDouble("step", 0.5),
            unit = j.optString("unit", "°C"),
            hvacModes = modes.ifEmpty { listOf("off", "heat", "auto") }
        )
    }

    fun lightToJson(l: LightEntity): JSONObject = JSONObject().apply {
        put("id", l.entityId)
        put("name", l.friendlyName)
        put("area", l.areaName)
        put("on", l.isOn)
        put("pct", l.brightnessPct)
        put("dim", l.supportsBrightness)
        put("raw", l.rawState)
    }

    fun lightFromJson(j: JSONObject): LightEntity = LightEntity(
        entityId = j.optString("id"),
        friendlyName = j.optString("name"),
        areaName = j.optString("area", "Other"),
        isOn = j.optBoolean("on", false),
        brightnessPct = j.optInt("pct", 0),
        supportsBrightness = j.optBoolean("dim", true),
        rawState = j.optString("raw", "off")
    )

    fun encodeFullSync(
        thermostats: List<ThermostatEntity>,
        lights: List<LightEntity>
    ): String {
        val root = JSONObject().apply {
            put("type", "full_sync")
            put("thermostats", JSONArray().apply {
                thermostats.forEach { put(thermostatToJson(it)) }
            })
            put("lights", JSONArray().apply {
                lights.forEach { put(lightToJson(it)) }
            })
        }
        return root.toString()
    }

    fun encodeSingleUpdate(
        thermostat: ThermostatEntity?,
        light: LightEntity?
    ): String {
        val root = JSONObject().apply {
            put("type", "state_update")
            if (thermostat != null) put("thermostat", thermostatToJson(thermostat))
            if (light != null) put("light", lightToJson(light))
        }
        return root.toString()
    }

    fun encodeVoiceEvent(
        stage: String,
        transcript: String? = null,
        answer: String? = null,
        error: String? = null,
        audioB64: String? = null,
        audioExt: String? = null
    ): String {
        val root = JSONObject().apply {
            put("type", "voice_event")
            put("stage", stage)
            if (transcript != null) put("transcript", transcript)
            if (answer != null) put("answer", answer)
            if (error != null) put("error", error)
            if (audioB64 != null) put("audio_b64", audioB64)
            if (audioExt != null) put("audio_ext", audioExt)
        }
        return root.toString()
    }
}
