# LS Intiface Bridge

Android 12+ bridge between Intiface's Device WebSocket Server and LoveSpouse/MuSe BLE advertising devices. This revision targets Android 15 and emulates a Lovense Nora with vibration and rotation controls. Remove the old Hush device from Intiface and scan again if its cached capabilities remain.

The bridge uses one persistent legacy advertising set and updates its data through Android's asynchronous callbacks. It does not restart advertising for each script command. Manufacturer ID is `0xFFF0`; the payload prefix is `6DB643CE97FE427C`.

Vibration retains the existing mapping: inputs below 5 stop the motor, 5–9 request level 2, and 10–20 request level 3. Suction starts disabled on every Start. Enable it explicitly after bench verification. It can follow fresh vibration peaks, or use the separate Rotate input. During a suction pulse and its stop hold, suction data has exclusive advertising priority; vibration resumes from the latest input afterward. No historical commands are queued.

Pulse duration is configurable from 0.7 to 5 seconds. An active pulse never extends in response to incoming commands. After Android accepts the channel-2 stop payload, that payload remains active for the configured cooldown, then a fresh low input followed by a fresh peak is required to trigger again. Local suction tests are bounded by the same pulse limit. Local vibration tests last 2 seconds. Remote input inactivity for 1.2 seconds also stops its channel; clients that send only changed values may therefore produce shorter output during long constant segments.

The three candidate suction commands can be tested separately. Automatic three-level mapping is opt-in and experimental. Neither their physical effects on HB2451 nor valve release are established by this repository. BLE callbacks confirm Android accepted the data, not that the toy received it. This connectionless protocol provides no hardware acknowledgement, pressure or battery telemetry. A stop command cannot be described as verified venting, and software timers cannot guarantee physical release if Android, radio or toy firmware fails.

Build and test with JDK 17:

```sh
./gradlew testDebugUnitTest assembleDebug
```

GitHub Actions performs both steps and uploads the debug APK. The package identity is unchanged; a debug APK signed with a different key requires uninstalling the existing app before installation.
