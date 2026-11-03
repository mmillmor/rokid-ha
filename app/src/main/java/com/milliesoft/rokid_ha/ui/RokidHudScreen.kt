package com.milliesoft.rokid_ha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milliesoft.rokid_ha.data.ConnectionState
import com.milliesoft.rokid_ha.data.HudEntityItem
import com.milliesoft.rokid_ha.data.LightEntity
import com.milliesoft.rokid_ha.data.NavLevel
import com.milliesoft.rokid_ha.data.RoomGroup
import com.milliesoft.rokid_ha.data.ThermostatEntity
import com.milliesoft.rokid_ha.input.RokidGesture
import com.milliesoft.rokid_ha.qr.QrScannerScreen
import java.util.Locale

// Waveguide Monochrome Green Palette (#000000 is transparent on optical waveguide)
val HudBlack = Color(0xFF000000)
val HudGreenBright = Color(0xFF00FF66)
val HudGreenMid = Color(0xFF00B848)
val HudGreenDim = Color(0xFF005522)
val HudGreenDarkBg = Color(0xFF001F0B)

@Composable
fun RokidHudScreen(
    uiState: HudUiState,
    onGesture: (RokidGesture) -> Unit,
    onAdjustTemp: (ThermostatEntity, Int) -> Unit,
    onSelectHvacMode: (ThermostatEntity, String) -> Unit,
    onToggleLight: (LightEntity) -> Unit,
    onAdjustBrightness: (LightEntity, Int) -> Unit,
    onQrScanned: (String) -> Unit,
    onCancelQr: () -> Unit,
    onTriggerVoice: () -> Unit
) {
    if (uiState.isScanningQr) {
        QrScannerScreen(
            pairingWebUrl = uiState.pairingWebUrl,
            onQrCodeScanned = onQrScanned,
            onCancel = onCancelQr
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBlack)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top Status Bar (Hierarchy Breadcrumb + Item Position + Connection Status)
            HudTopBar(uiState = uiState)

            // 2. Main Center HUD Card (Room, Type, Thermostat, Light, Back, Voice, Setup, or Exit)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                when (val focused = uiState.focusedItem()) {
                    is HudEntityItem.HeatingSummary -> HeatingSummaryHudCard(
                        thermostats = focused.thermostats,
                        uiState = uiState
                    )

                    is HudEntityItem.LightsOnSummary -> LightsOnSummaryHudCard(
                        activeLights = focused.activeLights,
                        uiState = uiState
                    )

                    is HudEntityItem.RoomCard -> RoomHudCard(room = focused.room)

                    is HudEntityItem.TypeCard -> TypeHudCard(typeCard = focused)

                    is HudEntityItem.BackCard -> BackHudCard(targetLabel = focused.targetLabel)

                    is HudEntityItem.Thermostat -> ThermostatHudCard(
                        thermostat = focused.data,
                        editMode = uiState.editMode,
                        onAdjustTemp = { delta -> onAdjustTemp(focused.data, delta) },
                        onSelectMode = { mode -> onSelectHvacMode(focused.data, mode) }
                    )

                    is HudEntityItem.Light -> LightHudCard(
                        light = focused.data,
                        editMode = uiState.editMode,
                        onToggle = { onToggleLight(focused.data) },
                        onAdjustBrightness = { delta -> onAdjustBrightness(focused.data, delta) }
                    )

                    HudEntityItem.VoiceControl -> VoiceControlHudCard(
                        uiState = uiState,
                        onTriggerVoice = onTriggerVoice
                    )

                    HudEntityItem.SetupAndPair -> SetupHudCard(
                        uiState = uiState,
                        onTap = { onGesture(RokidGesture.SINGLE_TAP) }
                    )

                    HudEntityItem.ExitApp -> ExitAppHudCard()
                }
            }

            // 3. Bottom HUD 1D Navigation Bar (Always shows what LEFT/RIGHT and TAP do)
            HudBottomBar(uiState = uiState)
        }
    }
}

@Composable
private fun HudTopBar(uiState: HudUiState) {
    val items = uiState.visibleItems()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, HudGreenDim, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "◀ ${uiState.hierarchyBreadcrumb()} (${uiState.selectedIndex + 1}/${items.size}) ▶",
            color = HudGreenBright,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        val connBadge = when (uiState.connectionState) {
            ConnectionState.CONNECTED_BT -> "● BT LIVE"
            ConnectionState.CONNECTED_WS -> "● HA LIVE"
            ConnectionState.CONNECTED_REST -> "● HA REST"
            ConnectionState.CONNECTING -> "SYNCING.."
            ConnectionState.DEMO_MODE, ConnectionState.UNCONFIGURED -> {
                if (uiState.wifiIpAddress != null) {
                    "WIFI: ${uiState.wifiIpAddress}:8765"
                } else {
                    "WAIT BT/WIFI"
                }
            }
            ConnectionState.ERROR -> {
                if (uiState.wifiIpAddress == null) "WAIT BT/WIFI" else "CONN ERR"
            }
        }
        Text(
            text = connBadge,
            color = HudGreenBright,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun HeatingSummaryHudCard(
    thermostats: List<ThermostatEntity>,
    uiState: HudUiState
) {
    val isScrolling = uiState.editMode == CardEditMode.SUMMARY_LIST
    val onCount = thermostats.count { it.isHeatingDeviceOn }

    val rows = buildList {
        if (isScrolling) {
            add("[ ↩ DONE / EXIT SUMMARY BACK TO CARDS ]")
        }
        thermostats.forEach { t ->
            val cur = formatTemp(t.currentTemperature, t.unit)
            val tgt = formatTemp(t.targetTemperature, t.unit)
            val stateTag = when {
                !t.isHeatingDeviceOn -> "OFF"
                t.isActivelyHeating -> "ON:HEAT"
                else -> "ON:${t.hvacMode.uppercase()}"
            }
            add("${t.friendlyName.uppercase()} | NOW $cur → $tgt [$stateTag]")
        }
    }

    val selectedIdx = if (isScrolling) {
        uiState.summaryCursorIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    } else 0
    val maxVisibleRows = 5
    val windowStart = if (isScrolling) {
        (selectedIdx - 2).coerceAtLeast(0).coerceAtMost((rows.size - maxVisibleRows).coerceAtLeast(0))
    } else 0
    val windowEnd = (windowStart + maxVisibleRows).coerceAtMost(rows.size)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, if (isScrolling) HudGreenBright else HudGreenMid, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "HEATING SUMMARY (${onCount} ON / ${thermostats.size} TOTAL)",
                color = HudGreenBright,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isScrolling) "ROW ${selectedIdx + 1}/${rows.size}" else "TAP TO SCROLL/EDIT",
                color = HudGreenMid,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        if (thermostats.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "NO HEATING DEVICES FOUND",
                    color = HudGreenMid,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (idx in windowStart until windowEnd) {
                    val isFocused = isScrolling && idx == selectedIdx
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isFocused) HudGreenBright else HudGreenDarkBg,
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                1.dp,
                                if (isFocused) HudGreenBright else HudGreenDim,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = rows[idx],
                            color = if (isFocused) HudBlack else HudGreenBright,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Text(
            text = if (isScrolling) {
                "◀/▶: SCROLL LIST | TAP: OPEN THERMOSTAT (OR ROW 1 TO EXIT)"
            } else {
                "TAP CARD TO SCROLL ALL ${thermostats.size} THERMOSTATS & ADJUST"
            },
            color = HudGreenMid,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LightsOnSummaryHudCard(
    activeLights: List<LightEntity>,
    uiState: HudUiState
) {
    val isScrolling = uiState.editMode == CardEditMode.SUMMARY_LIST

    val rows = buildList {
        if (isScrolling) {
            add("[ ↩ DONE / EXIT SUMMARY BACK TO CARDS ]")
        }
        activeLights.forEach { l ->
            add("${l.areaName.uppercase()}: ${l.friendlyName.uppercase()} [ON • ${l.brightnessPct}%]")
        }
    }

    val selectedIdx = if (isScrolling) {
        uiState.summaryCursorIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    } else 0
    val maxVisibleRows = 5
    val windowStart = if (isScrolling) {
        (selectedIdx - 2).coerceAtLeast(0).coerceAtMost((rows.size - maxVisibleRows).coerceAtLeast(0))
    } else 0
    val windowEnd = (windowStart + maxVisibleRows).coerceAtMost(rows.size)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, if (isScrolling) HudGreenBright else HudGreenMid, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LIGHTS ON SUMMARY (${activeLights.size} ON)",
                color = HudGreenBright,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isScrolling) "ROW ${selectedIdx + 1}/${rows.size}" else "TAP TO SCROLL/EDIT",
                color = HudGreenMid,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        if (activeLights.isEmpty() && !isScrolling) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ALL LIGHTS ARE CURRENTLY OFF",
                    color = HudGreenBright,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (idx in windowStart until windowEnd) {
                    val isFocused = isScrolling && idx == selectedIdx
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isFocused) HudGreenBright else HudGreenDarkBg,
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                1.dp,
                                if (isFocused) HudGreenBright else HudGreenDim,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = rows[idx],
                            color = if (isFocused) HudBlack else HudGreenBright,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Text(
            text = if (isScrolling) {
                "◀/▶: SCROLL ON LIGHTS | TAP: CONTROL LIGHT (OR ROW 1 TO EXIT)"
            } else {
                "SHOWING ONLY LIGHTS THAT ARE ON • TAP TO SCROLL/CONTROL"
            },
            color = HudGreenMid,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RoomHudCard(room: RoomGroup) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, HudGreenMid, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "LEVEL 1: ROOM // ${room.totalDevices} DEVICES",
                color = HudGreenMid,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = room.roomName.uppercase(),
                color = HudGreenBright,
                fontSize = 26.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Room Summary Boxes (Thermostats & Lights)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Thermostats Summary Box
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(if (room.thermostats.isNotEmpty()) HudGreenDarkBg else HudBlack, RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        if (room.thermostats.isNotEmpty()) HudGreenBright else HudGreenDim,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            ) {
                Text(
                    text = "THERMOSTATS",
                    color = HudGreenMid,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${room.thermostats.size}",
                    color = HudGreenBright,
                    fontSize = 26.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                val firstThermo = room.thermostats.firstOrNull()
                Text(
                    text = if (firstThermo != null) {
                        "${formatTemp(firstThermo.currentTemperature, firstThermo.unit)} → ${formatTemp(firstThermo.targetTemperature, firstThermo.unit)}"
                    } else {
                        "NONE"
                    },
                    color = HudGreenBright,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Lights Summary Box
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(if (room.lightsOnCount > 0) HudGreenDarkBg else HudBlack, RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        if (room.lights.isNotEmpty()) HudGreenBright else HudGreenDim,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            ) {
                Text(
                    text = "LIGHTS",
                    color = HudGreenMid,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${room.lights.size}",
                    color = HudGreenBright,
                    fontSize = 26.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (room.lights.isNotEmpty()) {
                        "${room.lightsOnCount} ON / ${room.lights.size - room.lightsOnCount} OFF"
                    } else {
                        "NONE"
                    },
                    color = HudGreenBright,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Action Prompt
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudGreenDarkBg, RoundedCornerShape(6.dp))
                .border(1.dp, HudGreenBright, RoundedCornerShape(6.dp))
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "TAP TO ENTER ${room.roomName.uppercase()} ▶",
                color = HudGreenBright,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TypeHudCard(typeCard: HudEntityItem.TypeCard) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, HudGreenBright, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "LEVEL 2: ${typeCard.room.roomName.uppercase()} // SELECT TYPE",
                color = HudGreenMid,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${typeCard.deviceType.label} (${typeCard.count})",
                color = HudGreenBright,
                fontSize = 26.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudGreenDarkBg, RoundedCornerShape(8.dp))
                .border(1.dp, HudGreenBright, RoundedCornerShape(8.dp))
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = typeCard.statusSummary,
                color = HudGreenBright,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudGreenBright, RoundedCornerShape(6.dp))
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "TAP TO VIEW ${typeCard.count} ${typeCard.deviceType.label} ▶",
                color = HudBlack,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BackHudCard(targetLabel: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, HudGreenMid, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "NAVIGATION // GO UP ONE LEVEL",
            color = HudGreenMid,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )

        Box(
            modifier = Modifier
                .background(HudGreenBright, RoundedCornerShape(8.dp))
                .border(2.dp, HudGreenBright, RoundedCornerShape(8.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                text = targetLabel,
                color = HudBlack,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "TAP TO RETURN UP ONE LEVEL",
            color = HudGreenMid,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ThermostatHudCard(
    thermostat: ThermostatEntity,
    editMode: CardEditMode,
    onAdjustTemp: (Int) -> Unit,
    onSelectMode: (String) -> Unit
) {
    val isEditingTemp = editMode == CardEditMode.THERMOSTAT_TEMP
    val isEditingMode = editMode == CardEditMode.THERMOSTAT_MODE
    val borderColor = if (isEditingTemp || isEditingMode) HudGreenBright else HudGreenMid

    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${thermostat.areaName.uppercase()} // ${thermostat.entityId}",
                    color = HudGreenMid,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = thermostat.friendlyName.uppercase(),
                    color = HudGreenBright,
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val actionLabel = (thermostat.hvacAction ?: thermostat.hvacMode).uppercase()
            Box(
                modifier = Modifier
                    .background(
                        if (actionLabel == "HEATING" || actionLabel == "COOLING") HudGreenBright else HudGreenDarkBg,
                        RoundedCornerShape(6.dp)
                    )
                    .border(1.dp, HudGreenBright, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = actionLabel,
                    color = if (actionLabel == "HEATING" || actionLabel == "COOLING") HudBlack else HudGreenBright,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, HudGreenDim, RoundedCornerShape(8.dp))
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "CURRENT TEMP",
                    color = HudGreenMid,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatTemp(thermostat.currentTemperature, thermostat.unit),
                    color = HudGreenBright,
                    fontSize = 32.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier
                    .weight(1.25f)
                    .background(if (isEditingTemp) HudGreenDarkBg else HudBlack, RoundedCornerShape(8.dp))
                    .border(
                        width = if (isEditingTemp) 2.dp else 1.dp,
                        color = if (isEditingTemp) HudGreenBright else HudGreenDim,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isEditingTemp) "◀ STEP 1: TARGET TEMP ▶" else "TARGET TEMP (1ST TAP)",
                    color = if (isEditingTemp) HudGreenBright else HudGreenMid,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isEditingTemp) {
                        "◀ ${formatTemp(thermostat.targetTemperature, thermostat.unit)} ▶"
                    } else {
                        formatTemp(thermostat.targetTemperature, thermostat.unit)
                    },
                    color = HudGreenBright,
                    fontSize = 32.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isEditingMode) HudGreenDarkBg else HudBlack, RoundedCornerShape(6.dp))
                .border(
                    width = if (isEditingMode) 2.dp else 1.dp,
                    color = if (isEditingMode) HudGreenBright else HudGreenDim,
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = if (isEditingMode) "◀ STEP 2: HVAC MODE (LEFT/RIGHT) ▶" else "HVAC MODE (2ND TAP)",
                color = if (isEditingMode) HudGreenBright else HudGreenMid,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                thermostat.hvacModes.forEach { mode ->
                    val isSelected = thermostat.hvacMode.equals(mode, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (isSelected) HudGreenBright else HudBlack,
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) HudGreenBright else HudGreenMid,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.uppercase(),
                            color = if (isSelected) HudBlack else HudGreenBright,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LightHudCard(
    light: LightEntity,
    editMode: CardEditMode,
    onToggle: () -> Unit,
    onAdjustBrightness: (Int) -> Unit
) {
    val isEditingState = editMode == CardEditMode.LIGHT_STATE
    val isEditingDimmer = editMode == CardEditMode.LIGHT_DIMMER
    val borderColor = if (isEditingState || isEditingDimmer) HudGreenBright else HudGreenMid

    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${light.areaName.uppercase()} // ${light.entityId}",
                color = HudGreenMid,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = light.friendlyName.uppercase(),
                color = HudGreenBright,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isEditingState) HudGreenDarkBg else HudBlack, RoundedCornerShape(8.dp))
                .border(
                    width = if (isEditingState) 2.dp else 1.dp,
                    color = if (isEditingState) HudGreenBright else HudGreenDim,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (isEditingState) "◀ STEP 1: POWER (LEFT = OFF | RIGHT = ON) ▶" else "POWER STATE (1ST TAP)",
                color = if (isEditingState) HudGreenBright else HudGreenMid,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (!light.isOn) HudGreenBright else HudBlack, RoundedCornerShape(6.dp))
                        .border(1.dp, HudGreenBright, RoundedCornerShape(6.dp))
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "◀ OFF",
                        color = if (!light.isOn) HudBlack else HudGreenMid,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (light.isOn) HudGreenBright else HudBlack, RoundedCornerShape(6.dp))
                        .border(1.dp, HudGreenBright, RoundedCornerShape(6.dp))
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ON ▶",
                        color = if (light.isOn) HudBlack else HudGreenMid,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isEditingDimmer) HudGreenDarkBg else HudBlack, RoundedCornerShape(8.dp))
                .border(
                    width = if (isEditingDimmer) 2.dp else 1.dp,
                    color = if (isEditingDimmer) HudGreenBright else HudGreenDim,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isEditingDimmer) "◀ STEP 2: DIMMER (LEFT/RIGHT ±10%) ▶" else "BRIGHTNESS (2ND TAP)",
                    color = if (isEditingDimmer) HudGreenBright else HudGreenMid,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${if (light.isOn) light.brightnessPct else 0}%",
                    color = HudGreenBright,
                    fontSize = 22.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            val activeSegments = if (light.isOn) (light.brightnessPct + 5) / 10 else 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (segment in 1..10) {
                    val lit = segment <= activeSegments
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                if (lit) HudGreenBright else HudBlack,
                                RoundedCornerShape(3.dp)
                            )
                            .border(
                                1.dp,
                                if (lit) HudGreenBright else HudGreenDim,
                                RoundedCornerShape(3.dp)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceControlHudCard(
    uiState: HudUiState,
    onTriggerVoice: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, if (uiState.isListeningVoice) HudGreenBright else HudGreenMid, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "VOICE ASSISTANT // HOME ASSISTANT ASSIST PIPELINE",
            color = HudGreenMid,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )

        Box(
            modifier = Modifier
                .background(if (uiState.isListeningVoice) HudGreenBright else HudGreenDarkBg, RoundedCornerShape(8.dp))
                .border(2.dp, HudGreenBright, RoundedCornerShape(8.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(
                text = if (uiState.isListeningVoice) "● SPEAK NOW (TAP WHEN DONE)" else "TAP TO ASK HOME ASSISTANT",
                color = if (uiState.isListeningVoice) HudBlack else HudGreenBright,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudGreenDarkBg, RoundedCornerShape(8.dp))
                .border(1.dp, HudGreenDim, RoundedCornerShape(8.dp))
                .padding(10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "YOU: ${uiState.lastVoiceTranscript?.let { "\"$it\"" } ?: "\"What is the living room temperature?\""}",
                color = HudGreenMid,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "HA: ${uiState.lastVoiceAnswer ?: "Tap above and speak — audio streams directly to Home Assistant STT & TTS."}",
                color = HudGreenBright,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SetupHudCard(
    uiState: HudUiState,
    onTap: () -> Unit
) {
    val isMenuOpen = uiState.editMode == CardEditMode.SETUP_MENU

    if (!isMenuOpen) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .border(2.dp, HudGreenMid, RoundedCornerShape(10.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SYSTEM // SETUP & PAIRING",
                color = HudGreenMid,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            Box(
                modifier = Modifier
                    .background(HudGreenDarkBg, RoundedCornerShape(8.dp))
                    .border(2.dp, HudGreenBright, RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "TAP TO OPEN SETUP MENU",
                    color = HudGreenBright,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (uiState.wifiIpAddress != null) {
                        "WIFI IP: ${uiState.wifiIpAddress} (WEB: ${uiState.pairingWebUrl})"
                    } else {
                        "WIFI STATUS: NOT CONNECTED TO WI-FI!"
                    },
                    color = HudGreenBright,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "HA: ${uiState.lastConnectionMessage}",
                    color = HudGreenMid,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        return
    }

    val wifiStatusLabel = if (uiState.wifiIpAddress != null) {
        "[WIFI] CONNECTED (${uiState.wifiIpAddress}) - TAP FOR SETTINGS"
    } else {
        "[WIFI] NOT CONNECTED! TAP TO CONNECT GLASSES TO WI-FI"
    }

    val modeLabel = when {
        uiState.config.demoMode -> "[MODE] DEMO MODE (TAP TO CONNECT LIVE HA)"
        uiState.connectionState == ConnectionState.ERROR -> "[RETRY] FAILED — TAP TO RETRY (${uiState.config.haUrl})"
        else -> "[MODE] LIVE HA (${uiState.config.haUrl}) - TAP FOR DEMO"
    }

    val rows = listOf(
        "[ ↩ DONE / EXIT SETUP BACK TO ROOMS ]",
        "[CAMERA] SCAN HOME ASSISTANT CONFIG QR CODE",
        wifiStatusLabel,
        modeLabel,
        "[VIEW] CATEGORY FILTER: ${uiState.activeTab.label}",
        "[FILE] RELOAD ha_config.json / RECONNECT",
        "[PIN FILTER] ${if (uiState.config.showPinnedOnly) "SHOWING PINNED ONLY" else "SHOWING ALL ENTITIES"}",
        "[ ✖ QUIT / CLOSE APPLICATION ]"
    )

    val selectedIdx = uiState.settingsIndex.coerceIn(0, rows.lastIndex)
    val windowStart = (selectedIdx - 2).coerceAtLeast(0)
    val windowEnd = (windowStart + 5).coerceAtMost(rows.size)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, HudGreenBright, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "◀ SETUP MENU (LEFT/RIGHT: MOVE | TAP: SELECT) ▶",
            color = HudGreenBright,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (idx in windowStart until windowEnd) {
                val isFocused = idx == selectedIdx
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isFocused) HudGreenBright else HudBlack,
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            1.dp,
                            if (isFocused) HudGreenBright else HudGreenDim,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { onTap() }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = rows[idx],
                        color = if (isFocused) HudBlack else HudGreenBright,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Text(
            text = "STATUS: ${uiState.lastConnectionMessage}",
            color = HudGreenBright,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ExitAppHudCard() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, HudGreenMid, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SYSTEM // EXIT APPLICATION",
            color = HudGreenMid,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )

        Box(
            modifier = Modifier
                .background(HudGreenBright, RoundedCornerShape(8.dp))
                .border(2.dp, HudGreenBright, RoundedCornerShape(8.dp))
                .padding(horizontal = 24.dp, vertical = 14.dp)
        ) {
            Text(
                text = "✖ TAP TO EXIT APP",
                color = HudBlack,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "RETURNS TO ROKID GLASSES HOME SCREEN",
            color = HudGreenMid,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun HudBottomBar(uiState: HudUiState) {
    val hintText = uiState.statusBanner ?: when (uiState.editMode) {
        CardEditMode.NONE -> when (uiState.navLevel) {
            NavLevel.ROOMS -> "◀/▶: BROWSE ROOMS | TAP: OPEN ROOM"
            NavLevel.TYPES -> "◀/▶: THERMOSTATS / LIGHTS / BACK | TAP: SELECT"
            NavLevel.DEVICES -> "◀/▶: BROWSE DEVICES | TAP: ADJUST DEVICE"
        }
        CardEditMode.THERMOSTAT_TEMP -> "◀/▶: TARGET TEMP ±0.5° | TAP: NEXT (HVAC MODE)"
        CardEditMode.THERMOSTAT_MODE -> "◀/▶: CHANGE HVAC MODE | TAP: DONE (LOCK)"
        CardEditMode.LIGHT_STATE -> "◀: TURN OFF | ▶: TURN ON | TAP: NEXT (DIMMER)"
        CardEditMode.LIGHT_DIMMER -> "◀/▶: BRIGHTNESS ±10% | TAP: DONE (LOCK)"
        CardEditMode.SUMMARY_LIST -> "◀/▶: SCROLL SUMMARY | TAP: SELECT DEVICE / EXIT"
        CardEditMode.SETUP_MENU -> "◀/▶: SELECT OPTION | TAP: ACTIVATE"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (uiState.statusBanner != null) HudGreenDarkBg else HudBlack, RoundedCornerShape(4.dp))
            .border(1.dp, HudGreenDim, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = hintText,
            color = if (uiState.statusBanner != null) HudGreenBright else HudGreenMid,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatTemp(temp: Double?, unit: String): String {
    if (temp == null) return "--$unit"
    return String.format(Locale.US, "%.1f%s", temp, unit)
}
