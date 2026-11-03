package com.milliesoft.rokid_ha.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class HomeAssistantClient(
    private val onStatesLoaded: (List<ThermostatEntity>, List<LightEntity>) -> Unit,
    private val onSingleStateChanged: (ThermostatEntity?, LightEntity?) -> Unit,
    private val onConnectionStateChanged: (ConnectionState, String?) -> Unit
) {
    private val okHttpClient: OkHttpClient = buildHttpClient()

    private var webSocket: WebSocket? = null
    private val messageIdCounter = AtomicInteger(1)
    private var getStatesMsgId = -1
    private var activeAssistPipelineMsgId = -1

    private var onAssistSttHandlerReady: ((Int) -> Unit)? = null
    private var onAssistSttText: ((String) -> Unit)? = null
    private var onAssistIntentSpeech: ((String) -> Unit)? = null
    private var onAssistTtsUrl: ((String) -> Unit)? = null
    private var onAssistPipelineError: ((String) -> Unit)? = null

    @Volatile
    private var entityAreaMap: Map<String, String> = emptyMap()

    /**
     * Generates candidate base URLs in case a local `http://...` URL was entered without `:8123`.
     */
    private fun candidateBaseUrls(config: HaConfig): List<String> {
        val raw = config.normalizedBaseUrl()
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            null
        }
        val hasExplicitPort = uri != null && uri.port != -1
        if (hasExplicitPort) return listOf(raw)

        return if (raw.startsWith("http://", ignoreCase = true)) {
            listOf("$raw:8123", raw)
        } else {
            listOf(raw, "$raw:8123")
        }
    }

    private fun toWebSocketUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.startsWith("https://", ignoreCase = true) ->
                "wss://" + base.substring("https://".length) + "/api/websocket"
            base.startsWith("http://", ignoreCase = true) ->
                "ws://" + base.substring("http://".length) + "/api/websocket"
            else -> "ws://$base/api/websocket"
        }
    }

    fun connectWebSocket(config: HaConfig, resolvedBaseUrl: String? = null) {
        disconnectWebSocket()
        if (!config.isConfigured) {
            onConnectionStateChanged(ConnectionState.UNCONFIGURED, "NO TOKEN SET")
            return
        }

        val targetBase = resolvedBaseUrl ?: candidateBaseUrls(config).first()
        val wsUrl = toWebSocketUrl(targetBase)
        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", "RokidHomeAssistant/1.0")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "auth_required" -> {
                            val authMsg = JSONObject().apply {
                                put("type", "auth")
                                put("access_token", config.accessToken.trim())
                            }
                            webSocket.send(authMsg.toString())
                        }

                        "auth_ok" -> {
                            onConnectionStateChanged(
                                ConnectionState.CONNECTED_WS,
                                "CONNECTED LIVE ($targetBase)"
                            )
                            getStatesMsgId = messageIdCounter.getAndIncrement()
                            val getStatesMsg = JSONObject().apply {
                                put("id", getStatesMsgId)
                                put("type", "get_states")
                            }
                            webSocket.send(getStatesMsg.toString())

                            val subMsg = JSONObject().apply {
                                put("id", messageIdCounter.getAndIncrement())
                                put("type", "subscribe_events")
                                put("event_type", "state_changed")
                            }
                            webSocket.send(subMsg.toString())
                        }

                        "auth_invalid" -> {
                            val msg = json.optString("message", "INVALID ACCESS TOKEN")
                            onConnectionStateChanged(ConnectionState.ERROR, "AUTH ERR: $msg")
                            webSocket.close(1000, "Auth invalid")
                        }

                        "result" -> {
                            val id = json.optInt("id", -1)
                            if (id == getStatesMsgId && json.optBoolean("success", false)) {
                                val resultArray = json.optJSONArray("result") ?: JSONArray()
                                parseStatesArray(resultArray)
                            } else if (id == activeAssistPipelineMsgId && !json.optBoolean("success", true)) {
                                val errMsg = json.optJSONObject("error")?.optString("message")
                                    ?: "Enable STT in HA Voice Assistants"
                                onAssistPipelineError?.invoke(errMsg)
                            }
                        }

                        "event" -> {
                            val eventId = json.optInt("id", -1)
                            val eventObj = json.optJSONObject("event") ?: return
                            if (eventId == activeAssistPipelineMsgId) {
                                handleAssistPipelineEvent(eventObj)
                            } else if (eventObj.optString("event_type") == "state_changed") {
                                val dataObj = eventObj.optJSONObject("data") ?: return
                                val newState = dataObj.optJSONObject("new_state") ?: return
                                val entityId = newState.optString("entity_id", "")
                                if (entityId.startsWith("climate.")) {
                                    val thermostat = HaStateParser.parseThermostat(newState, entityAreaMap)
                                    if (thermostat != null) {
                                        onSingleStateChanged(thermostat, null)
                                    }
                                } else if (entityId.startsWith("light.")) {
                                    val light = HaStateParser.parseLight(newState, entityAreaMap)
                                    if (light != null) {
                                        onSingleStateChanged(null, light)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errDetail = formatError(t, response?.code)
                onConnectionStateChanged(ConnectionState.ERROR, errDetail)
            }
        })
    }

    fun disconnectWebSocket() {
        webSocket?.close(1000, "Disconnecting")
        webSocket = null
    }

    /**
     * Starts a Home Assistant Assist voice pipeline (`assist_pipeline/run`) over the active WebSocket.
     * Streams raw 16kHz PCM audio from the Rokid Glasses microphone to Home Assistant STT -> Intent -> TTS.
     */
    fun startAssistVoicePipeline(
        onHandlerReady: (Int) -> Unit,
        onSttResult: (String) -> Unit,
        onIntentResponse: (String) -> Unit,
        onTtsAudioUrl: (String) -> Unit,
        onPipelineError: (String) -> Unit
    ): Boolean {
        val ws = webSocket
        if (ws == null) {
            onPipelineError("NOT CONNECTED TO HA WEBSOCKET")
            return false
        }

        onAssistSttHandlerReady = onHandlerReady
        onAssistSttText = onSttResult
        onAssistIntentSpeech = onIntentResponse
        onAssistTtsUrl = onTtsAudioUrl
        onAssistPipelineError = onPipelineError

        val msgId = messageIdCounter.getAndIncrement()
        activeAssistPipelineMsgId = msgId

        val runMsg = JSONObject().apply {
            put("id", msgId)
            put("type", "assist_pipeline/run")
            put("start_stage", "stt")
            put("end_stage", "tts")
            put("input", JSONObject().apply {
                put("sample_rate", 16000)
            })
        }
        return ws.send(runMsg.toString())
    }

    fun sendAssistAudioChunk(handlerId: Int, pcmBytes: ByteArray, length: Int) {
        val ws = webSocket ?: return
        val framed = ByteArray(1 + length)
        framed[0] = handlerId.toByte()
        System.arraycopy(pcmBytes, 0, framed, 1, length)
        ws.send(ByteString.of(*framed))
    }

    fun finishAssistAudioStream(handlerId: Int) {
        val ws = webSocket ?: return
        ws.send(ByteString.of(handlerId.toByte()))
    }

    private fun handleAssistPipelineEvent(eventObj: JSONObject) {
        val type = eventObj.optString("type")
        val data = eventObj.optJSONObject("data") ?: JSONObject()

        when (type) {
            "run-start" -> {
                val runnerData = data.optJSONObject("runner_data")
                val handlerId = runnerData?.optInt("stt_binary_handler_id", -1) ?: -1
                if (handlerId > 0) {
                    onAssistSttHandlerReady?.invoke(handlerId)
                } else {
                    onAssistPipelineError?.invoke("NO STT ENGINE IN HA ASSIST PIPELINE")
                }
            }

            "stt-end" -> {
                val text = data.optJSONObject("stt_output")?.optString("text", "")?.trim().orEmpty()
                if (text.isNotBlank()) {
                    onAssistSttText?.invoke(text)
                } else {
                    onAssistPipelineError?.invoke("NO SPEECH HEARD")
                }
            }

            "intent-end" -> {
                val speech = data.optJSONObject("intent_output")
                    ?.optJSONObject("response")
                    ?.optJSONObject("speech")
                    ?.optJSONObject("plain")
                    ?.optString("speech", "")
                    ?.trim()
                    .orEmpty()
                if (speech.isNotBlank()) {
                    onAssistIntentSpeech?.invoke(speech)
                }
            }

            "tts-end" -> {
                val relUrl = data.optJSONObject("tts_output")?.optString("url", "")?.trim().orEmpty()
                if (relUrl.isNotBlank()) {
                    val base = activeBaseUrl?.trimEnd('/') ?: ""
                    val fullUrl = if (relUrl.startsWith("http")) relUrl else "$base$relUrl"
                    onAssistTtsUrl?.invoke(fullUrl)
                }
            }

            "error" -> {
                val code = data.optString("code", "ERR")
                val msg = data.optString("message", "HA Assist error")
                onAssistPipelineError?.invoke("$code: $msg")
            }
        }
    }

    private fun parseStatesArray(statesArray: JSONArray) {
        val areas = entityAreaMap
        val thermostats = mutableListOf<ThermostatEntity>()
        val lights = mutableListOf<LightEntity>()
        for (i in 0 until statesArray.length()) {
            val stateObj = statesArray.optJSONObject(i) ?: continue
            val entityId = stateObj.optString("entity_id", "")
            when {
                entityId.startsWith("climate.") -> {
                    HaStateParser.parseThermostat(stateObj, areas)?.let { thermostats.add(it) }
                }
                entityId.startsWith("light.") -> {
                    HaStateParser.parseLight(stateObj, areas)?.let { lights.add(it) }
                }
            }
        }
        onStatesLoaded(
            thermostats.sortedBy { it.friendlyName.lowercase() },
            lights.sortedBy { it.friendlyName.lowercase() }
        )
    }

    /**
     * Queries Home Assistant's `/api/template` endpoint to get the official Area (Room) name
     * for every `climate.*` and `light.*` entity (including areas inherited from parent devices).
     */
    private fun fetchAreaMapSync(baseUrl: String, token: String): Map<String, String> {
        return try {
            val jinja = """
                {% set ns = namespace(items=[]) %}
                {% for s in states.climate | list + states.light | list %}
                  {% set a = area_name(s.entity_id) %}
                  {% if a %}
                    {% set ns.items = ns.items + [(s.entity_id, a)] %}
                  {% endif %}
                {% endfor %}
                {{ dict(ns.items) | tojson }}
            """.trimIndent()
            val payload = JSONObject().apply {
                put("template", jinja)
            }
            val req = Request.Builder()
                .url("$baseUrl/api/template")
                .addHeader("Authorization", "Bearer ${token.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyMap()
                val raw = resp.body?.string()?.trim() ?: return emptyMap()
                val json = JSONObject(raw)
                val map = mutableMapOf<String, String>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = json.optString(k, "").trim()
                    if (v.isNotBlank() && !v.equals("None", ignoreCase = true)) {
                        map[k] = v
                    }
                }
                map
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @Volatile
    private var activeBaseUrl: String? = null

    suspend fun fetchStatesRest(config: HaConfig): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            onConnectionStateChanged(ConnectionState.UNCONFIGURED, "NO TOKEN CONFIGURED")
            return@withContext false
        }

        onConnectionStateChanged(ConnectionState.CONNECTING, "CONNECTING TO ${config.haUrl}...")
        var lastError = "CONNECTION FAILED"

        for (candidateBase in candidateBaseUrls(config)) {
            try {
                val url = "$candidateBase/api/states"
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${config.accessToken.trim()}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "RokidHomeAssistant/1.0")
                    .get()
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        activeBaseUrl = candidateBase
                        val body = resp.body?.string() ?: "[]"
                        val fetchedAreas = fetchAreaMapSync(candidateBase, config.accessToken)
                        if (fetchedAreas.isNotEmpty()) {
                            entityAreaMap = fetchedAreas
                        }
                        parseStatesArray(JSONArray(body))
                        onConnectionStateChanged(
                            ConnectionState.CONNECTED_REST,
                            "CONNECTED TO $candidateBase"
                        )
                        connectWebSocket(config, candidateBase)
                        return@withContext true
                    } else {
                        lastError = when (resp.code) {
                            401, 403 -> "HTTP ${resp.code}: INVALID ACCESS TOKEN"
                            404 -> "HTTP 404 AT $candidateBase/api/states"
                            else -> "HTTP ${resp.code} FROM $candidateBase"
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = formatError(e, null)
            }
        }

        onConnectionStateChanged(ConnectionState.ERROR, lastError)
        false
    }

    suspend fun setThermostatTemperature(
        config: HaConfig,
        entityId: String,
        temperature: Double
    ): Boolean = callService(
        config = config,
        domain = "climate",
        service = "set_temperature",
        payload = JSONObject().apply {
            put("entity_id", entityId)
            put("temperature", temperature)
        }
    )

    suspend fun setThermostatHvacMode(
        config: HaConfig,
        entityId: String,
        hvacMode: String
    ): Boolean = callService(
        config = config,
        domain = "climate",
        service = "set_hvac_mode",
        payload = JSONObject().apply {
            put("entity_id", entityId)
            put("hvac_mode", hvacMode)
        }
    )

    suspend fun setLightState(
        config: HaConfig,
        entityId: String,
        turnOn: Boolean,
        brightnessPct: Int? = null
    ): Boolean {
        val service = if (turnOn) "turn_on" else "turn_off"
        val payload = JSONObject().apply {
            put("entity_id", entityId)
            if (turnOn && brightnessPct != null) {
                put("brightness_pct", brightnessPct.coerceIn(1, 100))
            }
        }
        return callService(config, "light", service, payload)
    }

    suspend fun sendAssistConversation(config: HaConfig, text: String): String? =
        withContext(Dispatchers.IO) {
            if (!config.isConfigured) return@withContext null
            val base = activeBaseUrl ?: candidateBaseUrls(config).first()
            try {
                val url = "$base/api/conversation/process"
                val payload = JSONObject().apply {
                    put("text", text)
                }
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${config.accessToken.trim()}")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val bodyStr = resp.body?.string() ?: return@withContext null
                    val json = JSONObject(bodyStr)
                    json.optJSONObject("response")
                        ?.optJSONObject("speech")
                        ?.optJSONObject("plain")
                        ?.optString("speech")
                }
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun callService(
        config: HaConfig,
        domain: String,
        service: String,
        payload: JSONObject
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext false
        val base = activeBaseUrl ?: candidateBaseUrls(config).first()
        try {
            val url = "$base/api/services/$domain/$service"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${config.accessToken.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun downloadAudioBytes(url: String, token: String? = null): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder().url(url).get()
            if (!token.isNullOrBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer ${token.trim()}")
            }
            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatError(t: Throwable, httpCode: Int?): String {
        if (httpCode != null) {
            return "HTTP $httpCode (${t.javaClass.simpleName})"
        }
        return when (t) {
            is UnknownHostException -> "NO WI-FI OR DNS FAILED (${t.message?.take(28)})"
            else -> "${t.javaClass.simpleName}: ${t.localizedMessage?.take(32) ?: "Check Wi-Fi"}"
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun buildHttpClient(): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)

            try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                })
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
            } catch (_: Exception) {
            }

            return builder.build()
        }
    }
}
