package com.milliesoft.rokid_ha.input

import com.milliesoft.rokid_ha.data.LightEntity
import com.milliesoft.rokid_ha.data.ThermostatEntity
import java.util.Locale
import kotlin.math.roundToInt

sealed interface VoiceActionResult {
    data class QueryAnswer(
        val answerText: String
    ) : VoiceActionResult

    data class SetThermostatTemp(
        val entity: ThermostatEntity,
        val targetTemp: Double,
        val summary: String
    ) : VoiceActionResult

    data class SetThermostatMode(
        val entity: ThermostatEntity,
        val hvacMode: String,
        val summary: String
    ) : VoiceActionResult

    data class SetLight(
        val entity: LightEntity,
        val turnOn: Boolean,
        val brightnessPct: Int?,
        val summary: String
    ) : VoiceActionResult

    data class Unmatched(val rawText: String) : VoiceActionResult
}

object VoiceCommandParser {

    private val numberRegex = Regex("""(\d+(?:\.\d+)?)""")

    /**
     * Parses natural-language voice questions and commands as a local fallback/complement
     * to Home Assistant's Assist pipeline.
     */
    fun parse(
        spokenText: String,
        thermostats: List<ThermostatEntity>,
        lights: List<LightEntity>,
        focusedEntityId: String?
    ): VoiceActionResult {
        val text = spokenText.lowercase().trim()
        if (text.isBlank()) return VoiceActionResult.Unmatched(spokenText)

        val matchedThermostat = findBestMatch(
            text = text,
            items = thermostats,
            nameSelector = { "${it.areaName} ${it.friendlyName}" },
            idSelector = { it.entityId },
            focusedEntityId = focusedEntityId?.takeIf { it.startsWith("climate.") }
        )

        val matchedLight = findBestMatch(
            text = text,
            items = lights,
            nameSelector = { "${it.areaName} ${it.friendlyName}" },
            idSelector = { it.entityId },
            focusedEntityId = focusedEntityId?.takeIf { it.startsWith("light.") }
        )

        val isQuestion = text.startsWith("what") || text.startsWith("how") ||
            text.startsWith("which") || text.startsWith("is ") || text.startsWith("are ") ||
            text.contains("what is") || text.contains("what's")

        // 0. Answer questions about temperature or active lights
        if (isQuestion) {
            if (text.contains("temp") || text.contains("warm") || text.contains("cold") ||
                text.contains("heat") || text.contains("thermostat") ||
                (matchedThermostat != null && !text.contains("light"))
            ) {
                val target = matchedThermostat ?: thermostats.firstOrNull()
                if (target != null) {
                    val cur = target.currentTemperature?.let { String.format(Locale.US, "%.1f%s", it, target.unit) } ?: "unknown"
                    val tgt = target.targetTemperature?.let { String.format(Locale.US, "%.1f%s", it, target.unit) } ?: "--"
                    return VoiceActionResult.QueryAnswer(
                        "${target.friendlyName}: Current $cur, Target $tgt (${target.hvacMode.uppercase()})"
                    )
                }
            }

            if (text.contains("light") || text.contains("on")) {
                val onLights = lights.filter { it.isOn }
                return if (onLights.isEmpty()) {
                    VoiceActionResult.QueryAnswer("All lights are currently OFF.")
                } else {
                    val names = onLights.take(4).joinToString(", ") { "${it.friendlyName} (${it.brightnessPct}%)" }
                    val more = if (onLights.size > 4) " +${onLights.size - 4} more" else ""
                    VoiceActionResult.QueryAnswer("${onLights.size} lights ON: $names$more")
                }
            }
        }

        val numberMatch = numberRegex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val mentionsPercentOrDim = text.contains("percent") || text.contains("%") ||
            text.contains("dim") || text.contains("bright")
        val mentionsTempOrDegrees = text.contains("degree") || text.contains("temp") ||
            text.contains("heat") || text.contains("cool") || text.contains("thermostat")

        // 1. Check for HVAC Mode changes on a thermostat
        if (matchedThermostat != null && (mentionsTempOrDegrees || matchedLight == null)) {
            val requestedMode = when {
                text.contains("heat_cool") || text.contains("heat cool") -> "heat_cool"
                text.contains("mode to auto") || text.contains("to auto") -> "auto"
                text.contains("mode to cool") || (text.contains("cool") && numberMatch == null) -> "cool"
                text.contains("mode to heat") || (text.contains("heat") && numberMatch == null && !text.contains("off")) -> "heat"
                (text.contains("turn off") || text.contains("mode to off")) &&
                    (text.contains("heat") || text.contains("thermostat") || text.contains("climate") || matchedLight == null) -> "off"
                else -> null
            }

            if (requestedMode != null) {
                return VoiceActionResult.SetThermostatMode(
                    entity = matchedThermostat,
                    hvacMode = requestedMode,
                    summary = "${matchedThermostat.friendlyName} -> ${requestedMode.uppercase()}"
                )
            }

            // 2. Check for Thermostat Temperature adjustment
            if (numberMatch != null && !mentionsPercentOrDim && numberMatch in 5.0..40.0) {
                val clamped = numberMatch.coerceIn(matchedThermostat.minTemp, matchedThermostat.maxTemp)
                return VoiceActionResult.SetThermostatTemp(
                    entity = matchedThermostat,
                    targetTemp = clamped,
                    summary = "${matchedThermostat.friendlyName} -> $clamped${matchedThermostat.unit}"
                )
            }
        }

        // 3. Check for Light Dimming / Brightness or On/Off
        if (matchedLight != null) {
            if (text.contains("turn off") || text.endsWith(" off")) {
                return VoiceActionResult.SetLight(
                    entity = matchedLight,
                    turnOn = false,
                    brightnessPct = null,
                    summary = "${matchedLight.friendlyName} -> OFF"
                )
            }

            if (numberMatch != null && (mentionsPercentOrDim || numberMatch in 1.0..100.0)) {
                val pct = numberMatch.roundToInt().coerceIn(1, 100)
                return VoiceActionResult.SetLight(
                    entity = matchedLight,
                    turnOn = true,
                    brightnessPct = pct,
                    summary = "${matchedLight.friendlyName} -> $pct%"
                )
            }

            if (text.contains("turn on") || text.endsWith(" on") || text.contains("toggle")) {
                val nextState = if (text.contains("toggle")) !matchedLight.isOn else true
                return VoiceActionResult.SetLight(
                    entity = matchedLight,
                    turnOn = nextState,
                    brightnessPct = if (nextState && matchedLight.brightnessPct == 0) 100 else null,
                    summary = "${matchedLight.friendlyName} -> ${if (nextState) "ON" else "OFF"}"
                )
            }
        }

        return VoiceActionResult.Unmatched(spokenText)
    }

    private fun <T> findBestMatch(
        text: String,
        items: List<T>,
        nameSelector: (T) -> String,
        idSelector: (T) -> String,
        focusedEntityId: String?
    ): T? {
        if (items.isEmpty()) return null

        var bestItem: T? = null
        var bestScore = 0

        for (item in items) {
            val friendly = nameSelector(item).lowercase()
            val shortId = idSelector(item).substringAfter('.').replace('_', ' ').lowercase()

            if (text.contains(friendly)) {
                val score = friendly.length + 100
                if (score > bestScore) {
                    bestScore = score
                    bestItem = item
                }
            } else {
                val words = (friendly.split(" ") + shortId.split(" "))
                    .map { it.trim() }
                    .filter { it.length >= 3 }
                    .distinct()
                val matchedWords = words.count { text.contains(it) }
                if (matchedWords > 0 && matchedWords * 10 > bestScore) {
                    bestScore = matchedWords * 10
                    bestItem = item
                }
            }
        }

        if (bestItem != null) return bestItem

        if (focusedEntityId != null) {
            return items.firstOrNull { idSelector(it) == focusedEntityId }
        }

        return null
    }
}
