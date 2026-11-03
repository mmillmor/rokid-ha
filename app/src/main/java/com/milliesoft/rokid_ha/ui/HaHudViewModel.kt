package com.milliesoft.rokid_ha.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.milliesoft.rokid_ha.bt.BluetoothGlassesClient
import com.milliesoft.rokid_ha.data.ConfigRepository
import com.milliesoft.rokid_ha.data.ConnectionState
import com.milliesoft.rokid_ha.data.DeviceType
import com.milliesoft.rokid_ha.data.HaConfig
import com.milliesoft.rokid_ha.data.HomeAssistantClient
import com.milliesoft.rokid_ha.data.HudEntityItem
import com.milliesoft.rokid_ha.data.HudTab
import com.milliesoft.rokid_ha.data.LightEntity
import com.milliesoft.rokid_ha.data.LocalPairingServer
import com.milliesoft.rokid_ha.data.NavLevel
import com.milliesoft.rokid_ha.data.RoomGroup
import com.milliesoft.rokid_ha.data.ThermostatEntity
import com.milliesoft.rokid_ha.input.HaAudioStreamer
import com.milliesoft.rokid_ha.input.RokidGesture
import com.milliesoft.rokid_ha.input.VoiceActionResult
import com.milliesoft.rokid_ha.input.VoiceCommandParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.util.Locale
import kotlin.math.roundToInt

enum class CardEditMode {
    /** Left/Right swipes browse across cards in the current hierarchy level */
    NONE,

    /** Step 1 on Thermostat: Left/Right adjusts Target Temperature (±0.5°). Tap -> THERMOSTAT_MODE */
    THERMOSTAT_TEMP,

    /** Step 2 on Thermostat: Left/Right cycles HVAC Mode (OFF/HEAT/AUTO/COOL). Tap -> NONE */
    THERMOSTAT_MODE,

    /** Step 1 on Light: Left = Turn OFF, Right = Turn ON. Tap -> LIGHT_DIMMER */
    LIGHT_STATE,

    /** Step 2 on Light: Left = Dim -10%, Right = Brighten +10%. Tap -> NONE */
    LIGHT_DIMMER,

    /** Inside Heating Summary or Active Lights Summary: Left/Right scrolls rows; Tap selects device (Row 0 exits) */
    SUMMARY_LIST,

    /** Inside Setup Card: Left/Right moves through Setup options; Tap activates option (Row 0 exits) */
    SETUP_MENU
}

data class HudUiState(
    val config: HaConfig = HaConfig(),
    val connectionState: ConnectionState = ConnectionState.DEMO_MODE,
    val statusBanner: String? = null,
    val lastConnectionMessage: String = "Not connected yet",
    val activeTab: HudTab = HudTab.ALL,
    val thermostats: List<ThermostatEntity> = emptyList(),
    val lights: List<LightEntity> = emptyList(),
    val navLevel: NavLevel = NavLevel.ROOMS,
    val selectedRoomName: String? = null,
    val selectedDeviceType: DeviceType? = null,
    val selectedIndex: Int = 0,
    val savedRoomIndex: Int = 0,
    val savedTypeIndex: Int = 0,
    val summaryCursorIndex: Int = 0,
    val settingsIndex: Int = 0,
    val editMode: CardEditMode = CardEditMode.NONE,
    val isScanningQr: Boolean = false,
    val isListeningVoice: Boolean = false,
    val lastVoiceTranscript: String? = null,
    val lastVoiceAnswer: String? = null,
    val pairingWebUrl: String? = null,
    val wifiIpAddress: String? = null
) {
    /**
     * Groups all filtered thermostats and lights by Room (`areaName`).
     */
    fun roomGroups(): List<RoomGroup> {
        val filterSet = config.pinnedEntities
        val useFilter = config.showPinnedOnly && filterSet.isNotEmpty()

        val filteredThermostats = if (activeTab == HudTab.LIGHTS) {
            emptyList()
        } else {
            thermostats.filter { !useFilter || filterSet.contains(it.entityId) }
        }

        val filteredLights = if (activeTab == HudTab.THERMOSTATS) {
            emptyList()
        } else {
            lights.filter { !useFilter || filterSet.contains(it.entityId) }
        }

        val allRoomNames = (filteredThermostats.map { it.areaName } + filteredLights.map { it.areaName })
            .distinct()
            .sortedWith(
                compareBy<String> { it.equals("Other", ignoreCase = true) }
                    .thenBy { it.lowercase() }
            )

        return allRoomNames.mapNotNull { roomName ->
            val rThermostats = filteredThermostats.filter { it.areaName == roomName }
            val rLights = filteredLights.filter { it.areaName == roomName }
            if (rThermostats.isEmpty() && rLights.isEmpty()) {
                null
            } else {
                RoomGroup(
                    roomName = roomName,
                    thermostats = rThermostats,
                    lights = rLights
                )
            }
        }
    }

    /**
     * Returns the 1D Left/Right carousel items for the current hierarchy level:
     * - [NavLevel.ROOMS]: Room Cards + Voice + Setup + Exit App
     * - [NavLevel.TYPES]: Thermostats Type Card + Lights Type Card + Back to Rooms Card
     * - [NavLevel.DEVICES]: Device Cards in that Room/Type + Back to Room Card
     */
    fun visibleItems(): List<HudEntityItem> {
        val rooms = roomGroups()

        return when (navLevel) {
            NavLevel.ROOMS -> {
                val filterSet = config.pinnedEntities
                val useFilter = config.showPinnedOnly && filterSet.isNotEmpty()
                val allThermostats = thermostats
                    .filter { !useFilter || filterSet.contains(it.entityId) }
                    .sortedWith(compareBy({ it.areaName.lowercase() }, { it.friendlyName.lowercase() }))
                val onLights = lights
                    .filter { it.isOn && (!useFilter || filterSet.contains(it.entityId)) }
                    .sortedWith(compareBy({ it.areaName.lowercase() }, { it.friendlyName.lowercase() }))

                listOf(
                    HudEntityItem.HeatingSummary(allThermostats),
                    HudEntityItem.LightsOnSummary(onLights)
                ) + rooms.map { HudEntityItem.RoomCard(it) } + listOf(
                    HudEntityItem.VoiceControl,
                    HudEntityItem.SetupAndPair,
                    HudEntityItem.ExitApp
                )
            }

            NavLevel.TYPES -> {
                val room = rooms.firstOrNull { it.roomName == selectedRoomName }
                    ?: return listOf(HudEntityItem.BackCard("↩ BACK TO ROOMS"))

                buildList {
                    if (room.thermostats.isNotEmpty()) {
                        val firstT = room.thermostats.first()
                        val cur = firstT.currentTemperature?.let { String.format(Locale.US, "%.1f%s", it, firstT.unit) } ?: "--"
                        val tgt = firstT.targetTemperature?.let { String.format(Locale.US, "%.1f%s", it, firstT.unit) } ?: "--"
                        add(
                            HudEntityItem.TypeCard(
                                room = room,
                                deviceType = DeviceType.THERMOSTATS,
                                count = room.thermostats.size,
                                statusSummary = "NOW $cur → TARGET $tgt (${firstT.hvacMode.uppercase()})"
                            )
                        )
                    }
                    if (room.lights.isNotEmpty()) {
                        add(
                            HudEntityItem.TypeCard(
                                room = room,
                                deviceType = DeviceType.LIGHTS,
                                count = room.lights.size,
                                statusSummary = "${room.lightsOnCount} ON / ${room.lights.size - room.lightsOnCount} OFF"
                            )
                        )
                    }
                    add(HudEntityItem.BackCard("↩ BACK TO ROOMS"))
                }
            }

            NavLevel.DEVICES -> {
                val room = rooms.firstOrNull { it.roomName == selectedRoomName }
                    ?: return listOf(HudEntityItem.BackCard("↩ BACK TO ROOMS"))

                buildList {
                    when (selectedDeviceType) {
                        DeviceType.THERMOSTATS -> {
                            room.thermostats.forEach { add(HudEntityItem.Thermostat(it)) }
                        }
                        DeviceType.LIGHTS -> {
                            room.lights.forEach { add(HudEntityItem.Light(it)) }
                        }
                        null -> {
                            room.thermostats.forEach { add(HudEntityItem.Thermostat(it)) }
                            room.lights.forEach { add(HudEntityItem.Light(it)) }
                        }
                    }
                    add(HudEntityItem.BackCard("↩ BACK TO ${room.roomName.uppercase()}"))
                }
            }
        }
    }

    fun focusedItem(): HudEntityItem {
        val items = visibleItems()
        return items[selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))]
    }

    fun hierarchyBreadcrumb(): String {
        return when (navLevel) {
            NavLevel.ROOMS -> "ROOMS"
            NavLevel.TYPES -> selectedRoomName?.uppercase() ?: "ROOM"
            NavLevel.DEVICES -> "${selectedRoomName?.uppercase() ?: "ROOM"} > ${selectedDeviceType?.label ?: "DEVICES"}"
        }
    }
}

class HaHudViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val configRepo = ConfigRepository(application)
    private val _uiState = MutableStateFlow(HudUiState())
    val uiState: StateFlow<HudUiState> = _uiState.asStateFlow()

    private var debounceJob: Job? = null
    private var bannerClearJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val audioStreamer = HaAudioStreamer(application)

    @Volatile
    private var currentSttHandlerId: Int = -1

    private var lastSwipeTimestampMs: Long = 0L
    private var lastTapTimestampMs: Long = 0L

    private val pairingServer = LocalPairingServer(
        port = 8765,
        currentConfigProvider = { _uiState.value.config },
        onConfigReceived = { rawJson ->
            viewModelScope.launch {
                onQrCodeScanned(rawJson)
            }
        }
    )

    private val haClient = HomeAssistantClient(
        onStatesLoaded = { thermostats, lights ->
            _uiState.update { state ->
                val nextState = state.copy(
                    thermostats = thermostats,
                    lights = lights
                )
                val maxIdx = (nextState.visibleItems().size - 1).coerceAtLeast(0)
                nextState.copy(selectedIndex = state.selectedIndex.coerceIn(0, maxIdx))
            }
        },
        onSingleStateChanged = { updatedThermostat, updatedLight ->
            _uiState.update { state ->
                val newThermostats = if (updatedThermostat != null) {
                    val exists = state.thermostats.any { it.entityId == updatedThermostat.entityId }
                    if (exists) {
                        state.thermostats.map {
                            if (it.entityId == updatedThermostat.entityId) updatedThermostat else it
                        }
                    } else {
                        (state.thermostats + updatedThermostat).sortedBy { it.friendlyName.lowercase() }
                    }
                } else state.thermostats

                val newLights = if (updatedLight != null) {
                    val exists = state.lights.any { it.entityId == updatedLight.entityId }
                    if (exists) {
                        state.lights.map {
                            if (it.entityId == updatedLight.entityId) updatedLight else it
                        }
                    } else {
                        (state.lights + updatedLight).sortedBy { it.friendlyName.lowercase() }
                    }
                } else state.lights

                state.copy(thermostats = newThermostats, lights = newLights)
            }
        },
        onConnectionStateChanged = { connState, message ->
            if (!btClient.isConnected) {
                _uiState.update {
                    it.copy(
                        connectionState = connState,
                        lastConnectionMessage = message ?: it.lastConnectionMessage
                    )
                }
                if (message != null) showTemporaryBanner(message)
            }
        }
    )

    private val btClient: BluetoothGlassesClient = BluetoothGlassesClient(
        context = appContext,
        onFullSyncReceived = { thermostats, lights, phoneName ->
            viewModelScope.launch {
                _uiState.update { state ->
                    val wasSetup = state.editMode == CardEditMode.SETUP_MENU && !state.config.isConfigured
                    val nextState = state.copy(
                        connectionState = ConnectionState.CONNECTED_BT,
                        lastConnectionMessage = "BT BRIDGE: $phoneName (${thermostats.size + lights.size} ENTITIES)",
                        thermostats = thermostats,
                        lights = lights,
                        navLevel = if (wasSetup) NavLevel.ROOMS else state.navLevel,
                        editMode = if (wasSetup) CardEditMode.NONE else state.editMode,
                        selectedIndex = if (wasSetup) 0 else state.selectedIndex
                    )
                    val maxIdx = (nextState.visibleItems().size - 1).coerceAtLeast(0)
                    nextState.copy(selectedIndex = nextState.selectedIndex.coerceIn(0, maxIdx))
                }
                showTemporaryBanner("CONNECTED VIA PHONE BLUETOOTH ($phoneName)")
            }
        },
        onStateUpdateReceived = { updatedThermostat, updatedLight ->
            viewModelScope.launch {
                _uiState.update { state ->
                    val newThermostats = if (updatedThermostat != null) {
                        val exists = state.thermostats.any { it.entityId == updatedThermostat.entityId }
                        if (exists) {
                            state.thermostats.map {
                                if (it.entityId == updatedThermostat.entityId) updatedThermostat else it
                            }
                        } else {
                            (state.thermostats + updatedThermostat).sortedBy { it.friendlyName.lowercase() }
                        }
                    } else state.thermostats

                    val newLights = if (updatedLight != null) {
                        val exists = state.lights.any { it.entityId == updatedLight.entityId }
                        if (exists) {
                            state.lights.map {
                                if (it.entityId == updatedLight.entityId) updatedLight else it
                            }
                        } else {
                            (state.lights + updatedLight).sortedBy { it.friendlyName.lowercase() }
                        }
                    } else state.lights

                    state.copy(
                        connectionState = ConnectionState.CONNECTED_BT,
                        thermostats = newThermostats,
                        lights = newLights
                    )
                }
            }
        },
        onVoiceEventReceived = { stage, transcript, answer, error, audioB64, audioExt ->
            viewModelScope.launch {
                when (stage) {
                    "ready" -> {
                        showTemporaryBanner("SPEAK NOW (RELAYING VIA PHONE BT)")
                    }
                    "stt" -> {
                        if (transcript != null) {
                            _uiState.update {
                                it.copy(
                                    isListeningVoice = false,
                                    lastVoiceTranscript = transcript
                                )
                            }
                            showTemporaryBanner("YOU: \"$transcript\"")
                        }
                    }
                    "intent" -> {
                        if (transcript != null) {
                            _uiState.update { it.copy(lastVoiceTranscript = transcript) }
                        }
                        if (answer != null) {
                            _uiState.update {
                                it.copy(
                                    isListeningVoice = false,
                                    lastVoiceAnswer = answer
                                )
                            }
                            showTemporaryBanner("HA: $answer")
                            audioStreamer.playTtsUrlOrSpeak(null, answer)
                        }
                    }
                    "tts_audio" -> {
                        if (!audioB64.isNullOrBlank()) {
                            try {
                                val bytes = Base64.decode(audioB64, Base64.NO_WRAP)
                                audioStreamer.playAudioBytes(
                                    bytes,
                                    audioExt ?: "mp3",
                                    _uiState.value.lastVoiceAnswer
                                )
                            } catch (_: Exception) {
                            }
                        }
                    }
                    "error" -> {
                        val errText = error ?: "Voice error"
                        _uiState.update {
                            it.copy(
                                isListeningVoice = false,
                                lastVoiceAnswer = errText
                            )
                        }
                        showTemporaryBanner(errText)
                    }
                }
            }
        },
        onBridgeDisconnected = {
            viewModelScope.launch {
                showTemporaryBanner("PHONE BT DISCONNECTED — RECONNECTING...")
                reloadConfiguration()
            }
        }
    )

    init {
        btClient.start()
        acquireWifiAndNetwork()
        pairingServer.start()
        reloadConfiguration()

        viewModelScope.launch {
            while (isActive) {
                val previousIp = _uiState.value.wifiIpAddress
                refreshLocalIp()
                val currentIp = _uiState.value.wifiIpAddress
                val state = _uiState.value
                if (previousIp == null && currentIp != null &&
                    !state.config.demoMode && state.config.isConfigured &&
                    state.connectionState == ConnectionState.ERROR
                ) {
                    haClient.fetchStatesRest(state.config)
                }
                delay(3000)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun acquireWifiAndNetwork() {
        try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                if (!wifiManager.isWifiEnabled) {
                    try {
                        wifiManager.isWifiEnabled = true
                    } catch (_: Exception) {
                    }
                }
                if (wifiLock == null) {
                    wifiLock = wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "RokidHA:WifiLock"
                    ).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            }

            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                cm.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cm.bindProcessToNetwork(network)
                        viewModelScope.launch {
                            refreshLocalIp()
                            val cfg = _uiState.value.config
                            if (!cfg.demoMode && cfg.isConfigured) {
                                haClient.fetchStatesRest(cfg)
                            }
                        }
                    }
                })
            }
        } catch (_: Exception) {
        }
    }

    private fun refreshLocalIp() {
        var ip = LocalPairingServer.getLocalWifiIpAddress()
        if (ip == null) {
            try {
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNet = cm?.activeNetwork
                val linkProps = if (activeNet != null) cm.getLinkProperties(activeNet) else null
                ip = linkProps?.linkAddresses
                    ?.mapNotNull { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
            } catch (_: Exception) {
            }
        }
        val url = if (ip != null) "http://$ip:${pairingServer.port}" else null
        _uiState.update { it.copy(wifiIpAddress = ip, pairingWebUrl = url) }
    }

    fun reloadConfiguration() {
        btClient.start()
        if (btClient.isConnected) {
            btClient.sendRequestSync()
            return
        }
        refreshLocalIp()
        val cfg = configRepo.loadConfig()
        if (cfg.demoMode || !cfg.isConfigured) {
            haClient.disconnectWebSocket()
            val demoThermostats = ConfigRepository.createDemoThermostats()
            val demoLights = ConfigRepository.createDemoLights()
            _uiState.update { state ->
                val baseState = state.copy(
                    config = cfg,
                    connectionState = if (cfg.demoMode) ConnectionState.DEMO_MODE else ConnectionState.UNCONFIGURED,
                    thermostats = demoThermostats,
                    lights = demoLights,
                    navLevel = NavLevel.ROOMS,
                    selectedRoomName = null,
                    selectedDeviceType = null
                )
                val setupIdx = baseState.visibleItems().indexOfFirst { it is HudEntityItem.SetupAndPair }.coerceAtLeast(0)
                baseState.copy(
                    selectedIndex = if (!cfg.isConfigured) setupIdx else 0,
                    editMode = if (!cfg.isConfigured) CardEditMode.SETUP_MENU else CardEditMode.NONE,
                    settingsIndex = 1
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    config = cfg,
                    connectionState = ConnectionState.CONNECTING,
                    lastConnectionMessage = "CONNECTING TO ${cfg.haUrl}...",
                    navLevel = NavLevel.ROOMS,
                    selectedRoomName = null,
                    selectedDeviceType = null,
                    selectedIndex = 0,
                    editMode = CardEditMode.NONE
                )
            }
            viewModelScope.launch {
                val ok = haClient.fetchStatesRest(cfg)
                if (!ok && !btClient.isConnected && _uiState.value.thermostats.isEmpty() && _uiState.value.lights.isEmpty()) {
                    val demoThermostats = ConfigRepository.createDemoThermostats()
                    val demoLights = ConfigRepository.createDemoLights()
                    _uiState.update { state ->
                        val baseState = state.copy(
                            thermostats = demoThermostats,
                            lights = demoLights,
                            navLevel = NavLevel.ROOMS
                        )
                        val setupIdx = baseState.visibleItems().indexOfFirst { it is HudEntityItem.SetupAndPair }.coerceAtLeast(0)
                        baseState.copy(
                            selectedIndex = setupIdx,
                            editMode = CardEditMode.SETUP_MENU,
                            settingsIndex = 2
                        )
                    }
                }
            }
        }
    }

    fun handleGesture(
        gesture: RokidGesture,
        onTriggerVoiceRecognition: () -> Unit,
        onOpenWifiSettings: () -> Unit,
        onExitApp: () -> Unit
    ) {
        val now = SystemClock.uptimeMillis()
        val state = _uiState.value

        if (gesture == RokidGesture.SWIPE_FORWARD || gesture == RokidGesture.SWIPE_BACKWARD) {
            if (now - lastSwipeTimestampMs < 240L) {
                return
            }
            lastSwipeTimestampMs = now
        } else if (gesture == RokidGesture.SINGLE_TAP) {
            if (now - lastTapTimestampMs < 240L) {
                return
            }
            lastTapTimestampMs = now
        }

        if (state.isScanningQr) {
            if (gesture == RokidGesture.SINGLE_TAP || gesture == RokidGesture.DOUBLE_TAP_BACK) {
                _uiState.update { it.copy(isScanningQr = false) }
            }
            return
        }

        when (gesture) {
            RokidGesture.VOICE_TRIGGER -> {
                onTriggerVoiceRecognition()
            }

            RokidGesture.DOUBLE_TAP_BACK -> {
                handleNavigateUpOrExit(onExitApp)
            }

            RokidGesture.SINGLE_TAP -> {
                handleSingleTap(onTriggerVoiceRecognition, onOpenWifiSettings, onExitApp)
            }

            RokidGesture.SWIPE_FORWARD -> {
                handleLeftRight(delta = +1)
            }

            RokidGesture.SWIPE_BACKWARD -> {
                handleLeftRight(delta = -1)
            }
        }
    }

    private fun handleNavigateUpOrExit(onExitApp: () -> Unit) {
        val state = _uiState.value
        if (state.editMode != CardEditMode.NONE) {
            _uiState.update { it.copy(editMode = CardEditMode.NONE) }
            showTemporaryBanner("CARD LOCKED")
            return
        }

        when (state.navLevel) {
            NavLevel.DEVICES -> {
                _uiState.update {
                    it.copy(
                        navLevel = NavLevel.TYPES,
                        selectedDeviceType = null,
                        selectedIndex = state.savedTypeIndex
                    )
                }
            }
            NavLevel.TYPES -> {
                _uiState.update {
                    it.copy(
                        navLevel = NavLevel.ROOMS,
                        selectedRoomName = null,
                        selectedDeviceType = null,
                        selectedIndex = state.savedRoomIndex
                    )
                }
            }
            NavLevel.ROOMS -> {
                onExitApp()
            }
        }
    }

    fun cycleCategoryFilter() {
        val state = _uiState.value
        val tabs = HudTab.entries
        val nextTab = tabs[(state.activeTab.ordinal + 1) % tabs.size]
        _uiState.update {
            it.copy(
                activeTab = nextTab,
                navLevel = NavLevel.ROOMS,
                selectedRoomName = null,
                selectedDeviceType = null,
                selectedIndex = 0
            )
        }
        showTemporaryBanner("VIEW FILTER: ${nextTab.label}")
    }

    private fun handleSingleTap(
        onTriggerVoiceRecognition: () -> Unit,
        onOpenWifiSettings: () -> Unit,
        onExitApp: () -> Unit
    ) {
        val state = _uiState.value

        if (state.editMode == CardEditMode.SETUP_MENU) {
            handleSetupOptionTap(onOpenWifiSettings, onExitApp)
            return
        }

        if (state.editMode == CardEditMode.SUMMARY_LIST) {
            handleSummaryListTap()
            return
        }

        when (val focused = state.focusedItem()) {
            is HudEntityItem.HeatingSummary -> {
                _uiState.update {
                    it.copy(
                        editMode = CardEditMode.SUMMARY_LIST,
                        summaryCursorIndex = 0
                    )
                }
                showTemporaryBanner("◀/▶: SCROLL THERMOSTATS | TAP: OPEN DEVICE")
            }

            is HudEntityItem.LightsOnSummary -> {
                _uiState.update {
                    it.copy(
                        editMode = CardEditMode.SUMMARY_LIST,
                        summaryCursorIndex = 0
                    )
                }
                showTemporaryBanner("◀/▶: SCROLL ON LIGHTS | TAP: OPEN LIGHT")
            }

            is HudEntityItem.RoomCard -> {
                // Enter Room -> Level 2 (TYPES)
                _uiState.update {
                    it.copy(
                        navLevel = NavLevel.TYPES,
                        selectedRoomName = focused.room.roomName,
                        savedRoomIndex = state.selectedIndex,
                        selectedIndex = 0,
                        editMode = CardEditMode.NONE
                    )
                }
            }

            is HudEntityItem.TypeCard -> {
                // Enter Type -> Level 3 (DEVICES)
                _uiState.update {
                    it.copy(
                        navLevel = NavLevel.DEVICES,
                        selectedDeviceType = focused.deviceType,
                        savedTypeIndex = state.selectedIndex,
                        selectedIndex = 0,
                        editMode = CardEditMode.NONE
                    )
                }
            }

            is HudEntityItem.BackCard -> {
                handleNavigateUpOrExit(onExitApp)
            }

            is HudEntityItem.Thermostat -> {
                val nextMode = when (state.editMode) {
                    CardEditMode.NONE -> CardEditMode.THERMOSTAT_TEMP
                    CardEditMode.THERMOSTAT_TEMP -> CardEditMode.THERMOSTAT_MODE
                    else -> CardEditMode.NONE
                }
                _uiState.update { it.copy(editMode = nextMode) }
                val msg = when (nextMode) {
                    CardEditMode.THERMOSTAT_TEMP -> "STEP 1/2: LEFT/RIGHT = TEMP | TAP = MODE"
                    CardEditMode.THERMOSTAT_MODE -> "STEP 2/2: LEFT/RIGHT = MODE | TAP = DONE"
                    else -> "CARD LOCKED (LEFT/RIGHT = NEXT DEVICE)"
                }
                showTemporaryBanner(msg)
            }

            is HudEntityItem.Light -> {
                val nextMode = when (state.editMode) {
                    CardEditMode.NONE -> CardEditMode.LIGHT_STATE
                    CardEditMode.LIGHT_STATE -> {
                        if (focused.data.supportsBrightness) CardEditMode.LIGHT_DIMMER else CardEditMode.NONE
                    }
                    else -> CardEditMode.NONE
                }
                _uiState.update { it.copy(editMode = nextMode) }
                val msg = when (nextMode) {
                    CardEditMode.LIGHT_STATE -> "STEP 1/2: LEFT = OFF, RIGHT = ON | TAP = DIMMER"
                    CardEditMode.LIGHT_DIMMER -> "STEP 2/2: LEFT/RIGHT = DIM ±10% | TAP = DONE"
                    else -> "CARD LOCKED (LEFT/RIGHT = NEXT DEVICE)"
                }
                showTemporaryBanner(msg)
            }

            HudEntityItem.VoiceControl -> {
                onTriggerVoiceRecognition()
            }

            HudEntityItem.SetupAndPair -> {
                _uiState.update {
                    it.copy(
                        editMode = CardEditMode.SETUP_MENU,
                        settingsIndex = 0
                    )
                }
                showTemporaryBanner("LEFT/RIGHT: SELECT OPTION | TAP: CONFIRM")
            }

            HudEntityItem.ExitApp -> {
                onExitApp()
            }
        }
    }

    private fun handleLeftRight(delta: Int) {
        val state = _uiState.value

        when (state.editMode) {
            CardEditMode.NONE -> {
                val items = state.visibleItems()
                if (items.isNotEmpty()) {
                    val nextIdx = (state.selectedIndex + delta).mod(items.size)
                    _uiState.update { it.copy(selectedIndex = nextIdx) }
                }
            }

            CardEditMode.THERMOSTAT_TEMP -> {
                val focused = state.focusedItem()
                if (focused is HudEntityItem.Thermostat) {
                    adjustThermostatTemp(focused.data, delta)
                }
            }

            CardEditMode.THERMOSTAT_MODE -> {
                val focused = state.focusedItem()
                if (focused is HudEntityItem.Thermostat) {
                    cycleThermostatMode(focused.data, delta)
                }
            }

            CardEditMode.LIGHT_STATE -> {
                val focused = state.focusedItem()
                if (focused is HudEntityItem.Light) {
                    val turnOn = delta > 0
                    val nextBrightness = if (turnOn) {
                        if (focused.data.brightnessPct > 0) focused.data.brightnessPct else 100
                    } else 0
                    updateLightState(focused.data.entityId, turnOn, nextBrightness)
                    showTemporaryBanner("${focused.data.friendlyName.uppercase()} -> ${if (turnOn) "ON" else "OFF"}")
                }
            }

            CardEditMode.LIGHT_DIMMER -> {
                val focused = state.focusedItem()
                if (focused is HudEntityItem.Light) {
                    adjustLightBrightness(focused.data, delta * 10)
                }
            }

            CardEditMode.SUMMARY_LIST -> {
                val focused = state.focusedItem()
                val rowCount = when (focused) {
                    is HudEntityItem.HeatingSummary -> 1 + focused.thermostats.size
                    is HudEntityItem.LightsOnSummary -> 1 + focused.activeLights.size
                    else -> 1
                }
                val nextIdx = (state.summaryCursorIndex + delta).mod(rowCount.coerceAtLeast(1))
                _uiState.update { it.copy(summaryCursorIndex = nextIdx) }
            }

            CardEditMode.SETUP_MENU -> {
                val totalSetupRows = 8
                val nextIdx = (state.settingsIndex + delta).mod(totalSetupRows)
                _uiState.update { it.copy(settingsIndex = nextIdx) }
            }
        }
    }

    fun adjustThermostatTemp(thermostat: ThermostatEntity, stepCount: Int) {
        val currentTarget = thermostat.targetTemperature ?: thermostat.currentTemperature ?: 20.0
        val step = if (thermostat.targetTempStep > 0) thermostat.targetTempStep else 0.5
        val rawNext = currentTarget + (stepCount * step)
        val roundedNext = ((rawNext * 2.0).roundToInt() / 2.0)
            .coerceIn(thermostat.minTemp, thermostat.maxTemp)

        setThermostatTargetTemperature(thermostat.entityId, roundedNext)
    }

    fun setThermostatTargetTemperature(entityId: String, targetTemp: Double) {
        _uiState.update { state ->
            state.copy(
                thermostats = state.thermostats.map {
                    if (it.entityId == entityId) it.copy(targetTemperature = targetTemp) else it
                }
            )
        }

        if (btClient.isConnected) {
            debounceJob?.cancel()
            debounceJob = viewModelScope.launch {
                delay(300)
                btClient.sendSetTemp(entityId, targetTemp)
            }
            return
        }

        val cfg = _uiState.value.config
        if (!cfg.demoMode && cfg.isConfigured) {
            debounceJob?.cancel()
            debounceJob = viewModelScope.launch {
                delay(350)
                haClient.setThermostatTemperature(cfg, entityId, targetTemp)
            }
        }
    }

    fun cycleThermostatMode(thermostat: ThermostatEntity, delta: Int) {
        val modes = thermostat.hvacModes.ifEmpty { listOf("off", "heat", "auto") }
        val currentIdx = modes.indexOf(thermostat.hvacMode).let { if (it == -1) 0 else it }
        val nextMode = modes[(currentIdx + delta).mod(modes.size)]
        setThermostatMode(thermostat.entityId, nextMode)
    }

    fun setThermostatMode(entityId: String, hvacMode: String) {
        _uiState.update { state ->
            state.copy(
                thermostats = state.thermostats.map {
                    if (it.entityId == entityId) {
                        val action = when (hvacMode) {
                            "off" -> "off"
                            "heat" -> "heating"
                            "cool" -> "cooling"
                            else -> "idle"
                        }
                        it.copy(hvacMode = hvacMode, hvacAction = action)
                    } else it
                }
            )
        }
        showTemporaryBanner("MODE -> ${hvacMode.uppercase()}")

        if (btClient.isConnected) {
            btClient.sendSetHvacMode(entityId, hvacMode)
            return
        }

        val cfg = _uiState.value.config
        if (!cfg.demoMode && cfg.isConfigured) {
            viewModelScope.launch {
                haClient.setThermostatHvacMode(cfg, entityId, hvacMode)
            }
        }
    }

    fun toggleLight(light: LightEntity) {
        val nextOn = !light.isOn
        val nextBrightness = if (nextOn && light.brightnessPct == 0) 100 else if (!nextOn) 0 else light.brightnessPct
        updateLightState(light.entityId, nextOn, nextBrightness)
    }

    fun adjustLightBrightness(light: LightEntity, deltaPct: Int) {
        val base = if (light.isOn) light.brightnessPct else 0
        val nextPct = (base + deltaPct).coerceIn(0, 100)
        val nextOn = nextPct > 0
        updateLightState(light.entityId, nextOn, nextPct)
    }

    private fun updateLightState(entityId: String, isOn: Boolean, brightnessPct: Int) {
        _uiState.update { state ->
            state.copy(
                lights = state.lights.map {
                    if (it.entityId == entityId) {
                        it.copy(isOn = isOn, brightnessPct = brightnessPct)
                    } else it
                }
            )
        }

        if (btClient.isConnected) {
            debounceJob?.cancel()
            debounceJob = viewModelScope.launch {
                delay(200)
                btClient.sendSetLight(entityId, isOn, if (isOn) brightnessPct else null)
            }
            return
        }

        val cfg = _uiState.value.config
        if (!cfg.demoMode && cfg.isConfigured) {
            debounceJob?.cancel()
            debounceJob = viewModelScope.launch {
                delay(250)
                haClient.setLightState(
                    config = cfg,
                    entityId = entityId,
                    turnOn = isOn,
                    brightnessPct = if (isOn) brightnessPct else null
                )
            }
        }
    }

    private fun handleSummaryListTap() {
        val state = _uiState.value
        val cursor = state.summaryCursorIndex
        if (cursor == 0) {
            _uiState.update { it.copy(editMode = CardEditMode.NONE) }
            showTemporaryBanner("EXITED SUMMARY LIST")
            return
        }

        when (val focused = state.focusedItem()) {
            is HudEntityItem.HeatingSummary -> {
                val targetThermo = focused.thermostats.getOrNull(cursor - 1) ?: return
                val roomThermos = state.roomGroups()
                    .firstOrNull { it.roomName == targetThermo.areaName }
                    ?.thermostats.orEmpty()
                val deviceIdx = roomThermos.indexOfFirst { it.entityId == targetThermo.entityId }.coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        navLevel = NavLevel.DEVICES,
                        selectedRoomName = targetThermo.areaName,
                        selectedDeviceType = DeviceType.THERMOSTATS,
                        savedRoomIndex = state.selectedIndex,
                        savedTypeIndex = 0,
                        selectedIndex = deviceIdx,
                        editMode = CardEditMode.THERMOSTAT_TEMP
                    )
                }
                showTemporaryBanner("STEP 1/2: LEFT/RIGHT = TEMP | TAP = MODE")
            }

            is HudEntityItem.LightsOnSummary -> {
                val targetLight = focused.activeLights.getOrNull(cursor - 1) ?: return
                val roomLights = state.roomGroups()
                    .firstOrNull { it.roomName == targetLight.areaName }
                    ?.lights.orEmpty()
                val deviceIdx = roomLights.indexOfFirst { it.entityId == targetLight.entityId }.coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        navLevel = NavLevel.DEVICES,
                        selectedRoomName = targetLight.areaName,
                        selectedDeviceType = DeviceType.LIGHTS,
                        savedRoomIndex = state.selectedIndex,
                        savedTypeIndex = 0,
                        selectedIndex = deviceIdx,
                        editMode = CardEditMode.LIGHT_STATE
                    )
                }
                showTemporaryBanner("STEP 1/2: LEFT = OFF, RIGHT = ON | TAP = DIMMER")
            }

            else -> {
                _uiState.update { it.copy(editMode = CardEditMode.NONE) }
            }
        }
    }

    private fun handleSetupOptionTap(
        onOpenWifiSettings: () -> Unit,
        onExitApp: () -> Unit
    ) {
        val state = _uiState.value

        when (state.settingsIndex) {
            0 -> {
                _uiState.update { it.copy(editMode = CardEditMode.NONE, selectedIndex = 0) }
                showTemporaryBanner("EXITED SETUP")
            }
            1 -> {
                _uiState.update { it.copy(isScanningQr = true) }
            }
            2 -> {
                acquireWifiAndNetwork()
                refreshLocalIp()
                onOpenWifiSettings()
            }
            3 -> {
                if (state.config.demoMode) {
                    val updated = state.config.copy(demoMode = false)
                    configRepo.saveConfig(updated)
                    reloadConfiguration()
                } else if (state.connectionState == ConnectionState.ERROR) {
                    reloadConfiguration()
                } else {
                    val updated = state.config.copy(demoMode = true)
                    configRepo.saveConfig(updated)
                    reloadConfiguration()
                    showTemporaryBanner("DEMO MODE ENABLED")
                }
            }
            4 -> {
                cycleCategoryFilter()
            }
            5 -> {
                val externalFile = configRepo.getExternalConfigFile()
                if (externalFile.exists()) {
                    val parsed = configRepo.parseAndSaveJsonConfig(externalFile.readText())
                    if (parsed != null) {
                        reloadConfiguration()
                        showTemporaryBanner("LOADED ha_config.json")
                    } else {
                        showTemporaryBanner("INVALID ha_config.json")
                    }
                } else {
                    reloadConfiguration()
                    showTemporaryBanner("RETRYING CONNECTION...")
                }
            }
            6 -> {
                val nextPinnedOnly = !state.config.showPinnedOnly
                val updated = state.config.copy(showPinnedOnly = nextPinnedOnly)
                configRepo.saveConfig(updated)
                _uiState.update { it.copy(config = updated) }
                showTemporaryBanner(if (nextPinnedOnly) "SHOWING PINNED ONLY" else "SHOWING ALL ENTITIES")
            }
            7 -> {
                onExitApp()
            }
        }
    }

    fun onQrCodeScanned(rawQrText: String) {
        val parsed = configRepo.parseAndSaveJsonConfig(rawQrText)
        _uiState.update {
            it.copy(
                isScanningQr = false,
                editMode = CardEditMode.NONE,
                navLevel = NavLevel.ROOMS,
                selectedIndex = 0
            )
        }
        if (parsed != null) {
            showTemporaryBanner("SCANNED: ${parsed.haUrl}")
            reloadConfiguration()
        } else {
            showTemporaryBanner("INVALID QR CONFIG JSON")
        }
    }

    fun cancelQrScan() {
        _uiState.update { it.copy(isScanningQr = false) }
    }

    fun setVoiceListeningState(listening: Boolean) {
        _uiState.update { it.copy(isListeningVoice = listening) }
    }

    /**
     * Streams microphone audio directly to Home Assistant's WebSocket `assist_pipeline/run`
     * (either over the Phone Bluetooth Bridge or direct Wi-Fi — no on-device Google ASR service required!).
     */
    fun startOrStopHaVoiceSession() {
        if (audioStreamer.isCurrentlyRecording) {
            val hId = currentSttHandlerId
            audioStreamer.stopRecordingAndFinish {
                if (btClient.isConnected) {
                    btClient.sendVoiceStop()
                } else if (hId > 0) {
                    haClient.finishAssistAudioStream(hId)
                }
            }
            _uiState.update { it.copy(isListeningVoice = false) }
            showTemporaryBanner("PROCESSING VOICE IN HOME ASSISTANT...")
            return
        }

        if (btClient.isConnected) {
            _uiState.update {
                it.copy(
                    isListeningVoice = true,
                    lastVoiceAnswer = "Streaming voice via Phone Bluetooth..."
                )
            }
            showTemporaryBanner("SPEAK NOW (RELAYING VIA PHONE BT)")
            btClient.sendVoiceStart()
            audioStreamer.startStreamingToHomeAssistant(
                onAudioChunk = { pcmBytes, len ->
                    btClient.sendVoiceChunk(pcmBytes, len)
                },
                onSpeechFinished = {
                    btClient.sendVoiceStop()
                    viewModelScope.launch {
                        _uiState.update { it.copy(isListeningVoice = false) }
                        showTemporaryBanner("WAITING FOR HA RESPONSE...")
                    }
                },
                onError = { errMsg ->
                    viewModelScope.launch {
                        _uiState.update {
                            it.copy(
                                isListeningVoice = false,
                                lastVoiceAnswer = errMsg
                            )
                        }
                        showTemporaryBanner(errMsg)
                    }
                }
            )
            return
        }

        val state = _uiState.value
        if (state.config.demoMode || !state.config.isConfigured) {
            showTemporaryBanner("CONNECT PHONE BLUETOOTH BRIDGE OR WI-FI FIRST")
            return
        }

        _uiState.update {
            it.copy(
                isListeningVoice = true,
                lastVoiceAnswer = "Connecting to HA Assist..."
            )
        }
        showTemporaryBanner("STARTING HA VOICE PIPELINE...")

        val started = haClient.startAssistVoicePipeline(
            onHandlerReady = { handlerId ->
                currentSttHandlerId = handlerId
                viewModelScope.launch {
                    showTemporaryBanner("SPEAK NOW (TAP AGAIN OR PAUSE WHEN DONE)")
                }
                audioStreamer.startStreamingToHomeAssistant(
                    onAudioChunk = { pcmBytes, len ->
                        haClient.sendAssistAudioChunk(handlerId, pcmBytes, len)
                    },
                    onSpeechFinished = {
                        haClient.finishAssistAudioStream(handlerId)
                        viewModelScope.launch {
                            _uiState.update { it.copy(isListeningVoice = false) }
                            showTemporaryBanner("WAITING FOR HA RESPONSE...")
                        }
                    },
                    onError = { errMsg ->
                        viewModelScope.launch {
                            _uiState.update {
                                it.copy(
                                    isListeningVoice = false,
                                    lastVoiceAnswer = errMsg
                                )
                            }
                            showTemporaryBanner(errMsg)
                        }
                    }
                )
            },
            onSttResult = { transcript ->
                viewModelScope.launch {
                    _uiState.update {
                        it.copy(
                            isListeningVoice = false,
                            lastVoiceTranscript = transcript
                        )
                    }
                    showTemporaryBanner("YOU: \"$transcript\"")
                }
            },
            onIntentResponse = { haSpeech ->
                viewModelScope.launch {
                    val lower = haSpeech.lowercase()
                    val haDidNotUnderstand = lower.contains("sorry") ||
                        lower.contains("not sure") ||
                        lower.contains("couldn't understand") ||
                        lower.contains("noigg") ||
                        lower.contains("not aware")

                    val transcript = _uiState.value.lastVoiceTranscript.orEmpty()
                    if (haDidNotUnderstand && transcript.isNotBlank()) {
                        processVoiceCommand(transcript, fallbackHaSpeech = haSpeech)
                    } else {
                        _uiState.update { it.copy(lastVoiceAnswer = haSpeech) }
                        showTemporaryBanner("HA: $haSpeech")
                        audioStreamer.playTtsUrlOrSpeak(null, haSpeech)
                    }
                }
            },
            onTtsAudioUrl = { ttsUrl ->
                viewModelScope.launch {
                    val bytes = haClient.downloadAudioBytes(ttsUrl, _uiState.value.config.accessToken)
                    if (bytes != null && bytes.isNotEmpty()) {
                        val ext = when {
                            ttsUrl.contains(".wav", ignoreCase = true) -> "wav"
                            ttsUrl.contains(".flac", ignoreCase = true) -> "flac"
                            ttsUrl.contains(".ogg", ignoreCase = true) -> "ogg"
                            else -> "mp3"
                        }
                        audioStreamer.playAudioBytes(bytes, ext, _uiState.value.lastVoiceAnswer)
                    } else {
                        audioStreamer.playTtsUrlOrSpeak(ttsUrl, _uiState.value.lastVoiceAnswer)
                    }
                }
            },
            onPipelineError = { err ->
                viewModelScope.launch {
                    _uiState.update {
                        it.copy(
                            isListeningVoice = false,
                            lastVoiceAnswer = err
                        )
                    }
                    showTemporaryBanner(err)
                }
            }
        )

        if (!started) {
            _uiState.update {
                it.copy(
                    isListeningVoice = false,
                    lastVoiceAnswer = "WebSocket not connected — reconnecting..."
                )
            }
            haClient.connectWebSocket(state.config)
        }
    }

    fun processVoiceCommand(spokenText: String, fallbackHaSpeech: String? = null) {
        _uiState.update {
            it.copy(
                isListeningVoice = false,
                lastVoiceTranscript = spokenText
            )
        }

        val state = _uiState.value
        val focusedId = state.focusedItem().entityId
        val result = VoiceCommandParser.parse(
            spokenText = spokenText,
            thermostats = state.thermostats,
            lights = state.lights,
            focusedEntityId = focusedId
        )

        when (result) {
            is VoiceActionResult.QueryAnswer -> {
                _uiState.update { it.copy(lastVoiceAnswer = result.answerText) }
                showTemporaryBanner(result.answerText)
                speakReply(result.answerText)
            }
            is VoiceActionResult.SetThermostatTemp -> {
                setThermostatTargetTemperature(result.entity.entityId, result.targetTemp)
                _uiState.update { it.copy(lastVoiceAnswer = result.summary) }
                showTemporaryBanner("VOICE: ${result.summary}")
                speakReply(result.summary)
            }
            is VoiceActionResult.SetThermostatMode -> {
                setThermostatMode(result.entity.entityId, result.hvacMode)
                _uiState.update { it.copy(lastVoiceAnswer = result.summary) }
                showTemporaryBanner("VOICE: ${result.summary}")
                speakReply(result.summary)
            }
            is VoiceActionResult.SetLight -> {
                updateLightState(
                    entityId = result.entity.entityId,
                    isOn = result.turnOn,
                    brightnessPct = result.brightnessPct ?: if (result.turnOn) 100 else 0
                )
                _uiState.update { it.copy(lastVoiceAnswer = result.summary) }
                showTemporaryBanner("VOICE: ${result.summary}")
                speakReply(result.summary)
            }
            is VoiceActionResult.Unmatched -> {
                if (fallbackHaSpeech != null) {
                    _uiState.update { it.copy(lastVoiceAnswer = fallbackHaSpeech) }
                    showTemporaryBanner("HA: $fallbackHaSpeech")
                    speakReply(fallbackHaSpeech)
                    return
                }
                if (btClient.isConnected) {
                    btClient.sendVoiceText(spokenText)
                    return
                }
                val cfg = state.config
                if (!cfg.demoMode && cfg.isConfigured) {
                    viewModelScope.launch {
                        val reply = haClient.sendAssistConversation(cfg, spokenText)
                        val ans = reply ?: "Sent to HA Assist: \"$spokenText\""
                        _uiState.update { it.copy(lastVoiceAnswer = ans) }
                        showTemporaryBanner(ans)
                        if (reply != null) speakReply(reply)
                        haClient.fetchStatesRest(cfg)
                    }
                } else {
                    val msg = "UNRECOGNIZED: \"$spokenText\""
                    _uiState.update { it.copy(lastVoiceAnswer = msg) }
                    showTemporaryBanner(msg)
                }
            }
        }
    }

    private fun speakReply(text: String) {
        if (btClient.isConnected) {
            btClient.sendSpeakText(text)
        } else {
            audioStreamer.playTtsUrlOrSpeak(null, text)
        }
    }

    fun showTemporaryBanner(message: String) {
        bannerClearJob?.cancel()
        _uiState.update { it.copy(statusBanner = message) }
        bannerClearJob = viewModelScope.launch {
            delay(4000)
            _uiState.update { it.copy(statusBanner = null) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            wifiLock?.release()
        } catch (_: Exception) {
        }
        btClient.stop()
        audioStreamer.release()
        pairingServer.stop()
        haClient.disconnectWebSocket()
    }
}
