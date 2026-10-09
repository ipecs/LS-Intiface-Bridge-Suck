# LS Intiface Bridge

Android 12+ bridge for LoveSpouse/MuSe HB2451, targeting Android 15. Version 1.6 fixes enabling the suction accents introduced in 1.5: a user-observed release can be recorded from an earlier session, survives a service restart for identical pump settings, and enabling accents resumes script output after a local test. It emulates a single Lovense Hush vibrator (`Z`), following the [official WSDM example](https://buttplug.io/docs/spec-v3/dev-guide/inflating-buttplug/devices/websocket-device-manager/). WebSocket port remains 54817 and handshake identifier remains `LVSDevice`. When upgrading from an older Nora profile, remove that entry and select the new Hush in Intiface and the player.

## Intended pattern and its limits

Vibration follows the received intensity; the pump supplies an occasional complete accent rather than trying to follow every rapid up/down movement. This is a design choice for a slow pump, not a scientifically established optimum or a validated pressure profile for HB2451.

Default accent detection is a rise from 8 or below to at least 12 on the incoming 0–20 scale. At least 8 seconds must separate accepted starts, and the previous cycle and recovery must be complete. A constant high signal and small threshold jitter do not retrigger. Crossings received while busy or before the minimum interval are consumed and counted as skipped; there is no delayed replay. An initial high value when enabling waits for a low value first. After more than 1.2 seconds without a sample, an old low value cannot arm a new high sample.

The app exposes threshold, minimum start interval, main pulse duration, intermediate pause and final recovery. Threshold defaults to 12; selecting 10 may begin earlier in a rising wave, but the bridge has no future script timestamps and cannot predict the actual peak. A falling intensity, including `Vibrate:0`, changes vibration and **does not interrupt an accepted bounded pump cycle**. Stop, Off, disconnect, disabling suction or a settings change cancels pending pump stages.

## Check release before enabling repetition

Automatic suction requires an explicit user confirmation that the command-2 two-pulse sequence releases all air with the current timing settings. The user has now reported that the second pulse releases all air on their unit. The app can record that observation without demanding another in-app manual test. No pressure sensor or verified valve-open opcode is available. Stopping the pump alone is not evidence that vacuum has been released; the user reported that Off and the global stop do not release it on their unit.

Suggested initial manual settings, based on the user's observations rather than a manufacturer pressure limit:

1. Command 2; main pulse 2.3 seconds.
2. Enable the optional second pulse of 1 second.
3. Intermediate pause 1 second; final recovery 2 seconds.
4. Start and wait for Listo, enable suction, then press Succión for an isolated test outside the body.
5. Only if the complete sequence actually releases all air without creating vacuum again, press **Confirmar liberación comprobada** while Listo. A matching test from an earlier session is sufficient; it does not need to be repeated just to unlock the button. If release fails, leave automatic suction disabled.
6. Enable **Succión en subidas del script**. This also resumes script output after a local test, without requiring another Start. On later launches: Start, wait for Listo, then enable accents using the restored observation.

The sequence requests command 2 for 2.3 seconds, pump stop for 1 second, command 2 for 1 second, pump stop, then 2 seconds of recovery. This takes approximately 6.3 seconds plus command acceptance delays. The user reports release with the extra pulse; that is a physical observation on this unit, not identification of a valve-open opcode. Recovery is a timer, not confirmation of zero pressure. Total requested positive pump time is capped at 5 seconds, including the second pulse. Comfort and automatic timing still require physical verification.

Changing command, main duration, intermediate pause, second-pulse selection or recovery invalidates the release observation. Every new manual suction test also clears it. Threshold, minimum interval and vibration-scale changes preserve an observation when pump settings are unchanged, but switch automatic suction off until explicitly re-enabled. A saved signature binds the user's observation to the command-2 sequence and exact pump timing, and is checked on service creation; missing, incompatible or mismatched signatures do not unlock suction. Uninstalling the app deletes it. Completing or aborting a timer never automatically validates release. The user confirmation supplies an observation for this setup; it is not live pressure monitoring or a safety guarantee.

The automatic switch remains tappable while the bridge is running. The service explains missing confirmation/configuration or an unfinished cycle instead of leaving the control silently grey. It will not start a new cycle while a previous pump stage or recovery is active. Recording an observation still requires command 2, the second pulse and Listo. Start/Stop reset accent enabling while retaining the saved observation. UI duration values are rounded to the nearest millisecond when saved to avoid truncation changes accidentally invalidating the signature.

## Radio scheduling and vibration

One persistent legacy advertising set is used. Operations are serialized through Android callbacks. Each new pump stage gets priority and approximately 350 ms of advertising-data hold after acceptance, then the latest vibration can update while the pump's stage remains latched. Changing vibration does not repeatedly retransmit the pump start. Pump stops take priority over a held positive stage. This relies on independent channel latching reported in public MuSe implementations; that behavior still needs physical verification on this HB2451. Very short vibration changes during the pump-data hold can be coalesced.

The vibration scheduler retains only the newest desired value. Stops and lower vibration commands bypass the 100-ms pacing used for increases, after any in-flight operation completes. Android accepting advertising data is not a toy acknowledgement. Pump-stage durations and recovery start after data acceptance; neither proves radio reception or actual pressure response. UI state broadcasts are limited to 10 per second; skipped-peak logs to one per second, while counts still update.

The default vibration scale remains: 0–2 stop, 3–8 command 1, 9–14 command 2, 15–20 command 3. Turning off **Repartir los modos por el rango completo** restores 3–4 command 1, 5–9 command 2, 10–20 command 3. These are three known command values, not 20 verified physical power levels. The user reports that vibration works correctly in 1.5; this release's changes concern enabling and recording suction configuration. Remote vibration expires after 1.2 seconds without a fresh command, so clients that send only changes can stop during long constant segments.

Local vibration tests pause script outputs and last 2 seconds; inputs 3, 10 and 18 check commands 1, 2 and 3 with the default scale. Manual suction tests first wait for vibration-stop acceptance. Start resumes script reception afterward. `Vibrate` and `Vibrate1` drive the single input; `Rotate` and `Vibrate2` return `ERR`. Explicit protocol Stop also disables automatic accents, requiring re-enabling after a pause that sends Stop.

## Protocol and research references

Manufacturer ID is `0xFFF0`; body prefix is `6DB643CE97FE427C`. Existing vibration commands `D5964C/D41F5D/D7846F/D60D7E` and suction commands `A5113F/A4982E/A7031C/A68A0D` are unchanged. Global stop candidate `E5157D` remains available. No new unverified command or valve mechanism was invented.

The [public MuSe controller implementation](https://github.com/Sfrl79/ble-toy-mcp) reports separate command tables and latched behavior, while warning that device tables differ. It is useful evidence for scheduling, not proof of the HB2451's mechanics. A [LoveSpouse/MuSe manual for a different model, ZLV201](https://m.media-amazon.com/images/I/B1ShVBaY1CL.pdf), describes preset suction patterns; it does not identify this model's pressure response or an air-release opcode. The [Lovense Max 2 guide](https://www.lovense.com/guide/max2) distinguishes pump settings from air-release controls on that different device. Its instructions and opcodes are not transferred to HB2451. No verified HB2451-specific optimum for sensation or release timing was found.

## Build and verification

Use JDK 17:

```sh
./gradlew testDebugUnitTest assembleDebug
```

GitHub Actions runs unit tests and builds the debug APK. A different debug signing key may require uninstalling the previous APK, which removes local settings.

Local verification covers sequence timing, user-confirmation gating and restoration, setting mismatches, rising crossings, busy/interval skips, serialized pump stages, cancellation, vibration updates and a combined fast-stream simulation with delayed acceptance. Service/controller/transmitter compile against Android API 35. Full Compose UI/APK compilation is delegated to GitHub Actions. The user's successful release and vibration tests are recorded above; automatic suction activation and timing for this revision still need checking on the tablet/toy.
