# LS Intiface Bridge

Android bridge app for controlling Love Spouse / MuSe style devices from Intiface Central.

The app connects to Intiface Central's Device Websocket Server as a Lovense-compatible websocket device, receives vibration commands, and emits Love Spouse BLE legacy advertising packets from the Android device.

## Requirements

- Android 12 or newer.
- Android device with BLE advertising support.
- Intiface Central running on Windows.
- Windows PC and Android device on the same network.
- Windows firewall allowing Intiface Central's Device Websocket Server port.

## Intiface Central Setup

Keep Intiface Central's normal Buttplug server and Device Websocket Server on separate ports.

Recommended layout:

```text
Intiface BP server:          ws://0.0.0.0:12345
Device Websocket Server:  ws://0.0.0.0:54817
```

In Intiface Central, add a websocket device:

```text
Protocol: lovense
Name:     LVSDevice
```

If the UI does not expose the name field, configure the user device config with a Lovense websocket entry named `LVSDevice`.

```json
{
  "version": {
    "major": 5,
    "minor": 5
  },
  "user_configs": {
    "protocols": {
      "lovense": {
        "communication": [
          {
            "websocket": {
              "name": "LVSDevice"
            }
          }
        ],
        "configurations": []
      }
    },
    "devices": []
  }
}
```

## Android App Usage

1. Build and install the debug APK.
2. Grant Bluetooth/Nearby Devices permission.
3. On Android 13 or newer, grant notification permission for the foreground service.
4. Enter the Device Websocket Server URL, for example:

```text
ws://192.168.0.2:54817
```

5. Press `Start`.
6. Start scanning in the Intiface client.

The app stores the last websocket URL. While running, it uses a foreground notification with a `Stop` action.

The test slider sends local BLE advertising levels without waiting for Intiface commands.

## BLE Payload

The bridge uses Android's Bluetooth LE advertiser with legacy, connectable, scannable advertising.

```text
Manufacturer ID: 0xFFF0
Prefix:          6D B6 43 CE 97 FE 42 7C
```

Lovense levels `0..20` are mapped to the original Love Spouse/MuSe command table from the ESP32 firmware.

## Development

Main source files:

```text
app/src/main/java/kr/glora/lsintifacebridge/MainActivity.kt
app/src/main/java/kr/glora/lsintifacebridge/BridgeService.kt
```

Build:

```bash
./gradlew :app:assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```


# LS-Intiface-Bridge

An Android bridge service designed to interface teledildonic client applications (such as FapTap) and Intiface Central (Buttplug) with LoveSpouse and MuSe BLE hardware via continuous, dual-channel GAP broadcast engineering.

## How It Works

### End-to-End Pipeline
* **Intiface Central to Android Bridge:** Intiface Central exposes a Device WebSocket Server (`ws://<PC_IP>:54817`) implementing Buttplug's WSDM specification. The Android bridge connects to this endpoint, completes the initial device identification handshake, and ingests incoming actuation instructions formatted as raw binary frames.
* **Android Bridge to Hardware:** The application intercepts the Lovense commands, decouples them into distinct physical hardware channels, and broadcasts 11-byte BroadLink Fastcon BLE legacy advertising packets (Company Identifier `0xFFF0`) across advertising channels 37, 38, and 39 to drive the toy's onboard PWM motor controllers.

### Actuation & Channel Architecture
* **Channel 1 (Continuous Vibration):** Ingested values are normalized across three continuous physical power levels (`0xD41F5D`, `0xD7846F`, `0xD60D7E`). The pipeline enforces an uninterrupted baseline floor during active playback, preventing the motor from cutting out or stuttering during Funscript valley transitions, while strictly suppressing firmware-level burst opcodes (`0xE0B82A`).
* **Channel 2 (Reactive Deep Suction):** Vacuum actuation is driven by deep suction pulses (`0xA7031C`) exclusively during script activation peaks. Suction run-time is bounded by a 1.4-second safety ceiling to prevent excessive negative pressure, and immediately de-energizes (`0xA5113F`) the moment the script returns to zero to equalize atmospheric pressure without artificial cooldown delays.
* **Stream Maintenance & Watchdog:** When playback is paused or packet arrival halts for more than 1.2 seconds, the pipeline suspends active channels and broadcasts global standby frames (`0xE5157D`) to cleanly stop all onboard actuators.
