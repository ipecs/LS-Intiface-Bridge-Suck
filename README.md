# LS Intiface Bridge

Android 12+ bridge between Intiface's Device WebSocket Server and LoveSpouse/MuSe BLE advertising devices. This revision targets Android 15 and emulates a Lovense Nora with vibration and rotation controls. Remove the old Hush device from Intiface and scan again if its cached capabilities remain.

The bridge uses one persistent legacy advertising set and updates its data through Android's asynchronous callbacks. It does not restart advertising for each script command. Manufacturer ID is `0xFFF0`; the payload prefix is `6DB643CE97FE427C`.

Vibration starts at input 3: inputs 0–2 stop the motor, 3–4 request level 1, 5–9 request level 2, and 10–20 request level 3. Suction starts disabled on every Start. Enable it explicitly after bench verification. It can follow fresh vibration peaks, or use the separate Rotate input. During a suction pulse and its stop hold, suction data has exclusive advertising priority; vibration resumes from the latest input afterward. No historical commands are queued.

Pulse duration is configurable from 0.7 to 5 seconds. An active pulse never extends in response to incoming commands. After Android accepts the channel-2 stop payload, that payload remains active for the configured cooldown, then a fresh low input followed by a fresh peak is required to trigger again. Local suction tests are bounded by the same pulse limit. Local vibration tests last 2 seconds. Remote input inactivity for 1.2 seconds also stops its channel; clients that send only changed values may therefore produce shorter output during long constant segments.

The three suction commands can be tested separately. User observations on one HB2451 suggest different internal patterns: command 1 starts a second suction within a 2.3-second window; command 2 produces two suctions within 4.9 seconds; command 3 produces one complete suction within 4.9 seconds but leaves the vacuum held. These observations do not establish exact cycle periods. Do not interpret the commands as confirmed power levels or enable repeated script suction before release is verified. Automatic three-command mapping remains opt-in and experimental.

Version 1.2 shows why a pulse ended: pulse limit, 1.2-second remote-input timeout, explicit zero, settings change, disconnect, or stop. A local suction test pauses script outputs until Start and retains its selected command and duration despite incoming script values; explicit Stop or disconnection still cancels it. Off disables suction and pauses script outputs until Start; incoming peaks cannot immediately reactivate the pump.

The separate diagnostic button “Parada general y cerrar puente” disconnects Intiface, advertises the LoveSpouse global stop candidate `E5157D` for a fresh full configured stop hold, sends vibration stop, and closes the bridge. It is a stop candidate, not a verified HB2451 valve-open command. Its public protocol reference is [pylovespouse OFF()](https://github.com/RevenantFreddy/pylovespouse/blob/main/lovespouse.py). If this test also leaves the vacuum held, capture the official app's working release command before implementing automatic suction/release cycles.

BLE callbacks confirm Android accepted the data, not that the toy received it. This connectionless protocol provides no hardware acknowledgement, pressure or battery telemetry. A stop command cannot be described as verified venting, and software timers cannot guarantee physical release if Android, radio or toy firmware fails.

Build and test with JDK 17:

```sh
./gradlew testDebugUnitTest assembleDebug
```

GitHub Actions performs both steps and uploads the debug APK. The package identity is unchanged; a debug APK signed with a different key requires uninstalling the existing app before installation.
