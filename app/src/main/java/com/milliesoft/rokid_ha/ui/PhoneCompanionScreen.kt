package com.milliesoft.rokid_ha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milliesoft.rokid_ha.bt.PhoneBridgeStatus
import com.milliesoft.rokid_ha.data.ConnectionState
import com.milliesoft.rokid_ha.data.HaConfig

@Composable
fun PhoneCompanionScreen(
    initialConfig: HaConfig,
    bridgeStatus: PhoneBridgeStatus,
    onSaveAndStartBridge: (haUrl: String, token: String) -> Unit,
    onStopBridge: () -> Unit,
    onSwitchToGlassesHud: () -> Unit
) {
    var haUrl by remember(initialConfig.haUrl) {
        mutableStateOf(
            initialConfig.haUrl.ifBlank { "https://my.server.com" }
        )
    }
    var accessToken by remember(initialConfig.accessToken) {
        mutableStateOf(initialConfig.accessToken)
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBlack)
            .verticalScroll(scrollState)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, HudGreenBright, RoundedCornerShape(10.dp))
                .padding(14.dp)
        ) {
            Text(
                text = "ROKID GLASSES // ANDROID PHONE BRIDGE",
                color = HudGreenMid,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "HOME ASSISTANT BLUETOOTH RELAY",
                color = HudGreenBright,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Your phone stays connected to Home Assistant and relays all rooms, thermostats, lights, and voice commands to your Rokid Glasses over Bluetooth — no Wi-Fi required on the glasses.",
                color = HudGreenMid,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Live Bridge Status Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudGreenDarkBg, RoundedCornerShape(10.dp))
                .border(1.dp, HudGreenBright, RoundedCornerShape(10.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "LIVE BLUETOOTH BRIDGE STATUS",
                color = HudGreenBright,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            StatusRow(
                label = "BRIDGE SERVICE:",
                value = if (bridgeStatus.isRunning) "● RUNNING IN BACKGROUND" else "○ STOPPED",
                highlight = bridgeStatus.isRunning
            )

            StatusRow(
                label = "ROKID GLASSES (BT):",
                value = bridgeStatus.connectedGlassesName?.let { "● CONNECTED ($it)" }
                    ?: "WAITING FOR GLASSES APP...",
                highlight = bridgeStatus.connectedGlassesName != null
            )

            val haStatusText = when (bridgeStatus.haConnectionState) {
                ConnectionState.CONNECTED_WS -> "● LIVE WEBSOCKET (${bridgeStatus.thermostatCount} THERMO, ${bridgeStatus.lightCount} LIGHTS)"
                ConnectionState.CONNECTED_REST -> "● CONNECTED REST (${bridgeStatus.thermostatCount} THERMO, ${bridgeStatus.lightCount} LIGHTS)"
                ConnectionState.CONNECTING -> "CONNECTING..."
                ConnectionState.ERROR -> "ERROR: ${bridgeStatus.haStatusMessage}"
                else -> bridgeStatus.haStatusMessage
            }
            StatusRow(
                label = "HOME ASSISTANT:",
                value = haStatusText,
                highlight = bridgeStatus.haConnectionState == ConnectionState.CONNECTED_WS ||
                    bridgeStatus.haConnectionState == ConnectionState.CONNECTED_REST
            )

            if (bridgeStatus.lastRelayedCommand != null) {
                StatusRow(
                    label = "LAST COMMAND:",
                    value = bridgeStatus.lastRelayedCommand,
                    highlight = true
                )
            }
        }

        // Home Assistant Configuration Form
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, HudGreenMid, RoundedCornerShape(10.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "HOME ASSISTANT CONNECTION",
                color = HudGreenBright,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "HOME ASSISTANT URL (e.g. https://my.server.com or http://homeassistant.local:8123)",
                color = HudGreenMid,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HudGreenDarkBg, RoundedCornerShape(6.dp))
                    .border(1.dp, HudGreenBright, RoundedCornerShape(6.dp))
                    .padding(12.dp)
            ) {
                BasicTextField(
                    value = haUrl,
                    onValueChange = { haUrl = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = HudGreenBright,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(HudGreenBright),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                text = "LONG-LIVED ACCESS TOKEN (Profile → Security → Long-Lived Access Tokens)",
                color = HudGreenMid,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .background(HudGreenDarkBg, RoundedCornerShape(6.dp))
                    .border(1.dp, HudGreenBright, RoundedCornerShape(6.dp))
                    .padding(12.dp)
            ) {
                BasicTextField(
                    value = accessToken,
                    onValueChange = { accessToken = it },
                    textStyle = TextStyle(
                        color = HudGreenBright,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(HudGreenBright),
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Save & Start Bridge Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HudGreenBright, RoundedCornerShape(8.dp))
                    .clickable {
                        onSaveAndStartBridge(haUrl.trim(), accessToken.trim())
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "SAVE & START BLUETOOTH BRIDGE",
                    color = HudBlack,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            if (bridgeStatus.isRunning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(HudBlack, RoundedCornerShape(8.dp))
                        .border(1.dp, HudGreenMid, RoundedCornerShape(8.dp))
                        .clickable { onStopBridge() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "STOP BACKGROUND BRIDGE SERVICE",
                        color = HudGreenMid,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Switch to Glasses HUD Mode Button (if running on glasses or previewing on phone)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudGreenDarkBg, RoundedCornerShape(8.dp))
                .border(1.dp, HudGreenBright, RoundedCornerShape(8.dp))
                .clickable { onSwitchToGlassesHud() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "SWITCH TO ROKID GLASSES HUD MODE ▶",
                color = HudGreenBright,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    highlight: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = HudGreenMid,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            color = if (highlight) HudGreenBright else Color(0xFF88AA88),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(0.58f)
        )
    }
}
