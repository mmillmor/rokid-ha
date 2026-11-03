package com.milliesoft.rokid_ha.data

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Represents a Home Assistant `climate.*` thermostat entity.
 */
data class ThermostatEntity(
    val entityId: String,
    val friendlyName: String,
    val areaName: String = "Other",
    val hvacMode: String,
    val hvacAction: String?,
    val currentTemperature: Double?,
    val targetTemperature: Double?,
    val minTemp: Double = 7.0,
    val maxTemp: Double = 35.0,
    val targetTempStep: Double = 0.5,
    val unit: String = "°C",
    val hvacModes: List<String> = listOf("off", "heat", "auto")
) {
    val isAvailable: Boolean
        get() = hvacMode != "unavailable" && hvacMode != "unknown"

    /** True when the thermostat is not turned off / unavailable */
    val isHeatingDeviceOn: Boolean
        get() = isAvailable && !hvacMode.equals("off", ignoreCase = true)

    /** True when actively calling for heat */
    val isActivelyHeating: Boolean
        get() = hvacAction.equals("heating", ignoreCase = true) ||
            (hvacAction == null && hvacMode.equals("heat", ignoreCase = true))
}

/**
 * Represents a Home Assistant `light.*` entity with state and 0-100% brightness.
 */
data class LightEntity(
    val entityId: String,
    val friendlyName: String,
    val areaName: String = "Other",
    val isOn: Boolean,
    val brightnessPct: Int,
    val supportsBrightness: Boolean = true,
    val rawState: String = if (isOn) "on" else "off"
) {
    val isAvailable: Boolean
        get() = rawState != "unavailable" && rawState != "unknown"
}

/**
 * Represents a Room (Home Assistant Area) containing thermostats and/or lights.
 */
data class RoomGroup(
    val roomName: String,
    val thermostats: List<ThermostatEntity>,
    val lights: List<LightEntity>
) {
    val totalDevices: Int
        get() = thermostats.size + lights.size

    val lightsOnCount: Int
        get() = lights.count { it.isOn }
}

/**
 * Device category inside a Room.
 */
enum class DeviceType(val label: String) {
    THERMOSTATS("THERMOSTATS"),
    LIGHTS("LIGHTS")
}

/**
 * Hierarchy navigation level: ROOM -> TYPE -> DEVICE.
 */
enum class NavLevel {
    ROOMS,
    TYPES,
    DEVICES
}

/**
 * Unified HUD card item for any level of the Rokid Glasses 1D Left/Right carousel.
 */
sealed interface HudEntityItem {
    val entityId: String
    val friendlyName: String

    /** Overview Card 1: Every heating/thermostat device with current temp, target temp, and ON/OFF status */
    data class HeatingSummary(val thermostats: List<ThermostatEntity>) : HudEntityItem {
        override val entityId: String = "summary.heating"
        override val friendlyName: String = "Heating Summary"
    }

    /** Overview Card 2: Only the lights that are currently ON */
    data class LightsOnSummary(val activeLights: List<LightEntity>) : HudEntityItem {
        override val entityId: String = "summary.lights_on"
        override val friendlyName: String = "Active Lights Summary"
    }

    /** Level 1: Room Card */
    data class RoomCard(val room: RoomGroup) : HudEntityItem {
        override val entityId: String = "room.${room.roomName}"
        override val friendlyName: String = room.roomName
    }

    /** Level 2: Device Type Card inside a Room (Thermostats or Lights) */
    data class TypeCard(
        val room: RoomGroup,
        val deviceType: DeviceType,
        val count: Int,
        val statusSummary: String
    ) : HudEntityItem {
        override val entityId: String = "type.${room.roomName}.${deviceType.name}"
        override val friendlyName: String = "${room.roomName} — ${deviceType.label}"
    }

    /** Level 2 or Level 3: Go Back Up One Hierarchy Level */
    data class BackCard(
        val targetLabel: String
    ) : HudEntityItem {
        override val entityId: String = "nav.back"
        override val friendlyName: String = targetLabel
    }

    /** Level 3: Thermostat Device Card */
    data class Thermostat(val data: ThermostatEntity) : HudEntityItem {
        override val entityId: String = data.entityId
        override val friendlyName: String = data.friendlyName
    }

    /** Level 3: Light Device Card */
    data class Light(val data: LightEntity) : HudEntityItem {
        override val entityId: String = data.entityId
        override val friendlyName: String = data.friendlyName
    }

    /** System Cards at Root Level */
    data object VoiceControl : HudEntityItem {
        override val entityId: String = "system.voice_command"
        override val friendlyName: String = "Voice Command"
    }

    data object SetupAndPair : HudEntityItem {
        override val entityId: String = "system.setup"
        override val friendlyName: String = "Setup & Pair Home Assistant"
    }

    data object ExitApp : HudEntityItem {
        override val entityId: String = "system.exit"
        override val friendlyName: String = "Exit App"
    }
}

/**
 * Filter mode selectable from Setup.
 */
enum class HudTab(val label: String) {
    ALL("ALL"),
    THERMOSTATS("CLIMATE"),
    LIGHTS("LIGHTS")
}

/**
 * Connection status to Home Assistant.
 */
enum class ConnectionState {
    DEMO_MODE,
    CONNECTING,
    CONNECTED_BT,
    CONNECTED_WS,
    CONNECTED_REST,
    ERROR,
    UNCONFIGURED
}

/**
 * Parser utilities for converting Home Assistant state JSON objects into domain models.
 */
object HaStateParser {

    fun parseThermostat(
        json: JSONObject,
        areaMap: Map<String, String> = emptyMap(),
        fallbackUnit: String = "°C"
    ): ThermostatEntity? {
        val entityId = json.optString("entity_id", "")
        if (!entityId.startsWith("climate.")) return null

        val state = json.optString("state", "unknown")
        val attrs = json.optJSONObject("attributes") ?: JSONObject()
        val friendlyName = attrs.optString("friendly_name", entityId.removePrefix("climate."))
        val areaName = resolveAreaName(entityId, friendlyName, areaMap)

        val currentTemp = attrs.optDouble("current_temperature").takeIf { !it.isNaN() }
        val targetTemp = attrs.optDouble("temperature").takeIf { !it.isNaN() }
            ?: attrs.optDouble("target_temp_low").takeIf { !it.isNaN() }

        val minTemp = attrs.optDouble("min_temp", 7.0).let { if (it.isNaN()) 7.0 else it }
        val maxTemp = attrs.optDouble("max_temp", 35.0).let { if (it.isNaN()) 35.0 else it }
        val step = attrs.optDouble("target_temp_step", 0.5).let { if (it.isNaN() || it <= 0.0) 0.5 else it }
        val hvacAction = attrs.optString("hvac_action", "").ifBlank { null }

        val modesArray = attrs.optJSONArray("hvac_modes")
        val modes = mutableListOf<String>()
        if (modesArray != null) {
            for (i in 0 until modesArray.length()) {
                val m = modesArray.optString(i)
                if (m.isNotBlank()) modes.add(m)
            }
        }
        if (modes.isEmpty()) {
            modes.addAll(listOf("off", "heat", "cool", "auto"))
        }

        return ThermostatEntity(
            entityId = entityId,
            friendlyName = friendlyName,
            areaName = areaName,
            hvacMode = state,
            hvacAction = hvacAction,
            currentTemperature = currentTemp,
            targetTemperature = targetTemp,
            minTemp = minTemp,
            maxTemp = maxTemp,
            targetTempStep = step,
            unit = fallbackUnit,
            hvacModes = modes
        )
    }

    fun parseLight(
        json: JSONObject,
        areaMap: Map<String, String> = emptyMap()
    ): LightEntity? {
        val entityId = json.optString("entity_id", "")
        if (!entityId.startsWith("light.")) return null

        val state = json.optString("state", "off")
        val isOn = state.equals("on", ignoreCase = true)
        val attrs = json.optJSONObject("attributes") ?: JSONObject()
        val friendlyName = attrs.optString("friendly_name", entityId.removePrefix("light."))
        val areaName = resolveAreaName(entityId, friendlyName, areaMap)

        val rawBrightness = attrs.optDouble("brightness")
        val brightnessPct = if (!rawBrightness.isNaN()) {
            ((rawBrightness / 255.0) * 100.0).roundToInt().coerceIn(0, 100)
        } else {
            if (isOn) 100 else 0
        }

        val colorModes = attrs.optJSONArray("supported_color_modes")
        var supportsDimming = true
        if (colorModes != null && colorModes.length() == 1) {
            val singleMode = colorModes.optString(0)
            if (singleMode == "onoff") {
                supportsDimming = false
            }
        }

        return LightEntity(
            entityId = entityId,
            friendlyName = friendlyName,
            areaName = areaName,
            isOn = isOn,
            brightnessPct = brightnessPct,
            supportsBrightness = supportsDimming,
            rawState = state
        )
    }

    /**
     * Uses the official Home Assistant Area name if present in [areaMap];
     * otherwise infers a room name from known areas or friendlyName prefix.
     */
    fun resolveAreaName(
        entityId: String,
        friendlyName: String,
        areaMap: Map<String, String>
    ): String {
        val directArea = areaMap[entityId]?.trim()
        if (!directArea.isNullOrBlank() && !directArea.equals("None", ignoreCase = true)) {
            return directArea
        }

        // Check if friendlyName starts with any known area name from areaMap
        val knownAreas = areaMap.values.distinct().sortedByDescending { it.length }
        for (known in knownAreas) {
            if (known.isNotBlank() && friendlyName.startsWith(known, ignoreCase = true)) {
                return known
            }
        }

        // Fallback: infer room from the first 1-2 words before common device suffixes
        val cleaned = friendlyName
            .replace(Regex("""(?i)\b(light|lights|lamp|spots|chandelier|strip|bulb|switch|thermostat|heating|radiator|trv|climate|sensor|ceiling|main|ambient)\b.*"""), "")
            .trim()
            .trim('-', '_', ':')
            .trim()

        if (cleaned.length in 3..28) {
            return cleaned
        }

        return "Other"
    }
}
