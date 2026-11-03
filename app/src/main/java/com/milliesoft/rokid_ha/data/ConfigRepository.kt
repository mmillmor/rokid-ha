package com.milliesoft.rokid_ha.data

import android.content.Context
import com.milliesoft.rokid_ha.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HaConfig(
    val haUrl: String = BuildConfig.DEFAULT_HA_URL,
    val accessToken: String = BuildConfig.DEFAULT_HA_TOKEN,
    val pinnedEntities: Set<String> = emptySet(),
    val showPinnedOnly: Boolean = false,
    val demoMode: Boolean = true
) {
    val isConfigured: Boolean
        get() = haUrl.isNotBlank() && accessToken.isNotBlank()

    fun normalizedBaseUrl(): String = haUrl.trim().trimEnd('/')

    fun webSocketUrl(): String {
        val base = normalizedBaseUrl()
        return when {
            base.startsWith("https://", ignoreCase = true) ->
                "wss://" + base.substring("https://".length) + "/api/websocket"
            base.startsWith("http://", ignoreCase = true) ->
                "ws://" + base.substring("http://".length) + "/api/websocket"
            else -> "ws://$base/api/websocket"
        }
    }
}

class ConfigRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("rokid_ha_prefs", Context.MODE_PRIVATE)

    /**
     * External app-specific config file path that can be pushed via ADB without runtime permissions:
     * `adb push ha_config.json /sdcard/Android/data/com.milliesoft.rokid_ha/files/ha_config.json`
     */
    fun getExternalConfigFile(): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "ha_config.json")
    }

    fun loadConfig(): HaConfig {
        // First check if a newer external ha_config.json file was pushed via ADB
        val externalFile = getExternalConfigFile()
        if (externalFile.exists()) {
            val lastImported = prefs.getLong("external_file_mtime", 0L)
            if (externalFile.lastModified() > lastImported) {
                parseAndSaveJsonConfig(externalFile.readText())?.let {
                    prefs.edit().putLong("external_file_mtime", externalFile.lastModified()).apply()
                    return it
                }
            }
        }

        val url = prefs.getString("ha_url", BuildConfig.DEFAULT_HA_URL) ?: BuildConfig.DEFAULT_HA_URL
        val token = prefs.getString("ha_token", BuildConfig.DEFAULT_HA_TOKEN) ?: BuildConfig.DEFAULT_HA_TOKEN
        val pinned = prefs.getStringSet("pinned_entities", emptySet()) ?: emptySet()
        val showPinnedOnly = prefs.getBoolean("show_pinned_only", false)
        val hasToken = token.isNotBlank()
        val demoMode = prefs.getBoolean("demo_mode", !hasToken)

        return HaConfig(
            haUrl = url,
            accessToken = token,
            pinnedEntities = pinned,
            showPinnedOnly = showPinnedOnly,
            demoMode = demoMode
        )
    }

    fun saveConfig(config: HaConfig) {
        prefs.edit()
            .putString("ha_url", config.haUrl.trim())
            .putString("ha_token", config.accessToken.trim())
            .putStringSet("pinned_entities", config.pinnedEntities)
            .putBoolean("show_pinned_only", config.showPinnedOnly)
            .putBoolean("demo_mode", config.demoMode)
            .apply()
    }

    /**
     * Parses a JSON payload from either a QR code or `ha_config.json` file.
     * Accepted keys:
     * - `url` or `ha_url`: Home Assistant URL (e.g. "http://192.168.1.50:8123")
     * - `token` or `access_token`: Long-Lived Access Token
     * - `entities` or `pinned_entities`: Optional array of entity IDs to pin
     * - `show_pinned_only`: Optional boolean
     */
    fun parseAndSaveJsonConfig(rawJson: String): HaConfig? {
        return try {
            val json = JSONObject(rawJson.trim())
            val current = loadConfig()
            val url = json.optString("url", json.optString("ha_url", json.optString("u", current.haUrl))).trim()
            val token = json.optString("token", json.optString("access_token", json.optString("t", current.accessToken))).trim()

            val entitiesArray: JSONArray? = json.optJSONArray("entities")
                ?: json.optJSONArray("pinned_entities")
                ?: json.optJSONArray("e")
            val pinned = if (entitiesArray != null) {
                buildSet {
                    for (i in 0 until entitiesArray.length()) {
                        val id = entitiesArray.optString(i).trim()
                        if (id.isNotBlank()) add(id)
                    }
                }
            } else {
                current.pinnedEntities
            }

            val showPinnedOnly = if (json.has("show_pinned_only")) {
                json.optBoolean("show_pinned_only", pinned.isNotEmpty())
            } else {
                pinned.isNotEmpty()
            }

            val updated = HaConfig(
                haUrl = url.ifBlank { current.haUrl },
                accessToken = token,
                pinnedEntities = pinned,
                showPinnedOnly = showPinnedOnly,
                demoMode = token.isBlank()
            )
            saveConfig(updated)
            updated
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun createDemoThermostats(): List<ThermostatEntity> = listOf(
            ThermostatEntity(
                entityId = "climate.living_room",
                friendlyName = "Living Room",
                areaName = "Living Room",
                hvacMode = "heat",
                hvacAction = "heating",
                currentTemperature = 19.5,
                targetTemperature = 21.0,
                minTemp = 10.0,
                maxTemp = 30.0,
                targetTempStep = 0.5,
                unit = "°C",
                hvacModes = listOf("off", "heat", "auto", "cool")
            ),
            ThermostatEntity(
                entityId = "climate.master_bedroom",
                friendlyName = "Master Bedroom",
                areaName = "Master Bedroom",
                hvacMode = "auto",
                hvacAction = "idle",
                currentTemperature = 18.5,
                targetTemperature = 18.5,
                minTemp = 10.0,
                maxTemp = 28.0,
                targetTempStep = 0.5,
                unit = "°C",
                hvacModes = listOf("off", "heat", "auto")
            ),
            ThermostatEntity(
                entityId = "climate.home_office",
                friendlyName = "Home Office",
                areaName = "Home Office",
                hvacMode = "off",
                hvacAction = "off",
                currentTemperature = 17.0,
                targetTemperature = 20.0,
                minTemp = 10.0,
                maxTemp = 30.0,
                targetTempStep = 0.5,
                unit = "°C",
                hvacModes = listOf("off", "heat", "auto")
            )
        )

        fun createDemoLights(): List<LightEntity> = listOf(
            LightEntity(
                entityId = "light.living_room_main",
                friendlyName = "Living Room Main",
                areaName = "Living Room",
                isOn = true,
                brightnessPct = 80,
                supportsBrightness = true
            ),
            LightEntity(
                entityId = "light.kitchen_island",
                friendlyName = "Kitchen Island",
                areaName = "Kitchen",
                isOn = true,
                brightnessPct = 100,
                supportsBrightness = true
            ),
            LightEntity(
                entityId = "light.bedroom_ambient",
                friendlyName = "Bedroom Ambient",
                areaName = "Master Bedroom",
                isOn = true,
                brightnessPct = 35,
                supportsBrightness = true
            ),
            LightEntity(
                entityId = "light.hallway_spots",
                friendlyName = "Hallway Spots",
                areaName = "Hallway",
                isOn = false,
                brightnessPct = 0,
                supportsBrightness = true
            )
        )
    }
}
