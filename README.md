# Rokid Glasses — Home Assistant HUD & Bluetooth Bridge (`rokid-ha`)

A smart-home HUD application for the Kickstarter **Rokid Glasses** (running **YodaOS-Sprite** / Android 12 on Qualcomm AR1 Gen 1 with a monochrome green waveguide micro-LED display) paired with an **Android Phone Bluetooth Bridge**.

The same APK (`com.milliesoft.rokid_ha`) installs on **both** your Android phone and your Rokid Glasses:
- **On your Android Phone:** Runs a persistent background Bluetooth RFCOMM bridge service (`PhoneBridgeService`) that stays connected to your Home Assistant server over Wi-Fi / 5G so your Rokid Glasses never need Wi-Fi turned on.
- **On your Rokid Glasses:** Displays a monochrome green waveguide HUD designed for **1D temple touchpad navigation** (Left/Right swipes + Single Tap) and streams voice queries & actions to Home Assistant Assist with spoken TTS audio replies through the glasses speakers.

---

## Features

1. **Android Phone ↔ Rokid Glasses Bluetooth Bridge (No Glasses Wi-Fi Needed)**
   - Because YodaOS-Sprite aggressively powers down Wi-Fi on the glasses to conserve battery and limit temple heat, the phone companion mode relays all Home Assistant states, live updates, device controls, and voice assistant audio over Bluetooth RFCOMM (`UUID: 8f6e5d4c-3b2a-1908-7f6e-5d4c3b2a1908`).
2. **Waveguide-Optimized Monochrome Green HUD & Line-Art Launcher Icon**
   - Uses `#000000` black backgrounds (transparent on optical waveguides) with high-contrast green (`#00FF66`) typography and a minimalist monochrome line-drawing launcher icon.
3. **Summary Screens (Top-Level Quick Views)**
   - **Heating Summary Card:** Lists every thermostat/climate device with Current Temperature, Target Temperature, and whether it is `ON` (`HEAT` / `AUTO`) or `OFF`. Tap any row to jump straight to that thermostat.
   - **Lights On Summary Card:** Lists **only** the lights that are currently `ON` with their Room name and Brightness `%`. Tap any row to jump straight to that light.
4. **3-Level Room Hierarchy (`ROOM → TYPE → DEVICE`)**
   - Automatically queries Home Assistant's Area registry (`/api/template` with `area_name(entity_id)`) to group hundreds of entities cleanly by **Room**, then **Type** (`THERMOSTATS` / `LIGHTS`), then **Device**.
5. **Pure Left/Right + Single-Tap Temple Touchpad Controls**
   - Every screen, device adjustment, summary list, and exit action works using only **Forward/Backward Swipes (Left/Right)** and **Single Tap**:
     - **Thermostats (`climate.*`):**
       - `1st Tap` → **Step 1 (Target Temp):** Swipe Left/Right to adjust target temperature (`±0.5°`).
       - `2nd Tap` → **Step 2 (HVAC Mode):** Swipe Left/Right to cycle `OFF`, `HEAT`, `AUTO`, `COOL`.
       - `3rd Tap` → **Done (Lock Card).**
     - **Lights (`light.*`):**
       - `1st Tap` → **Step 1 (Power State):** Swipe Left for `OFF`, Swipe Right for `ON`.
       - `2nd Tap` → **Step 2 (Dimmer):** Swipe Left/Right to dim or brighten (`±10%`).
       - `3rd Tap` → **Done (Lock Card).**
6. **Home Assistant Voice Assistant (Q&A + Actions + Spoken TTS Replies)**
   - Streams 16kHz 16-bit mono PCM audio from the glasses microphone (`AudioRecord`) over Bluetooth (or Wi-Fi) into Home Assistant's WebSocket `assist_pipeline/run` (STT → Conversation Agent → TTS) and plays the spoken response through the glasses' built-in temple speakers.

---

## Building & Installing

### 1. Build the APK
```powershell
.\gradlew.bat assembleDebug
```

### 2. Install on Your Android Phone & Rokid Glasses
Install the built APK on **both** your Android phone and your Rokid Glasses (paired via Bluetooth):
```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

1. **On your Android Phone:**
   - Open **Home Assistant** (`com.milliesoft.rokid_ha`). It automatically opens in **Phone Companion Mode**.
   - Enter your **Home Assistant URL** (e.g., `https://my.server.com` or `http://homeassistant.local:8123`) and your **Long-Lived Access Token** (*Home Assistant Profile → Security → Long-Lived Access Tokens*).
   - Tap **SAVE & START BLUETOOTH BRIDGE**.
2. **On your Rokid Glasses:**
   - Open **Home Assistant** from the glasses launcher and accept the Bluetooth permission prompt.
   - The HUD automatically connects to your phone over Bluetooth (`● BT LIVE` in the top-right corner) and loads all rooms, thermostats, and lights.
