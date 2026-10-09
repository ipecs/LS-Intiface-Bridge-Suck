package kr.glora.lsintifacebridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kr.glora.lsintifacebridge.ui.theme.LSIntifaceBridgeTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var wsUrl by mutableStateOf(DEFAULT_WS_URL)
    private var webSocketStatus by mutableStateOf("disconnected")
    private var bleStatus by mutableStateOf("idle")
    private var currentLevel by mutableIntStateOf(0)
    private var vibeLovenseLevel by mutableIntStateOf(0)
    private var rotateLovenseLevel by mutableIntStateOf(0)
    private var testVibeLevel by mutableIntStateOf(10)
    private var testRotateLevel by mutableIntStateOf(2)
    private var suctionEnabled by mutableStateOf(false)
    private var bridgeRunning by mutableStateOf(false)
    private var phase by mutableStateOf("IDLE")
    private var stopReason by mutableStateOf("—")
    private var remotePaused by mutableStateOf(false)
    private var fullVibrationRange by mutableStateOf(true)
    private var appliedVibrationLevel by mutableIntStateOf(-1)
    private var peaksEnabled by mutableStateOf(false)
    private var releaseVerified by mutableStateOf(false)
    private var manualCompleted by mutableStateOf(false)
    private var acceptedPeaks by mutableIntStateOf(0)
    private var skippedPeaks by mutableIntStateOf(0)
    private var peakThreshold by mutableIntStateOf(12)
    private var peakIntervalSeconds by mutableStateOf(8f)
    private var recoverySeconds by mutableStateOf(2f)
    private var secondPulseEnabled by mutableStateOf(false)
    private var pulseSeconds by mutableStateOf(2.3f)
    private var cooldownSeconds by mutableStateOf(1.0f)
    private var logText by mutableStateOf("")

    private val permissions: Array<String>
        get() = buildList {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                appendLog("Permissions granted")
            } else {
                appendLog("Permissions denied")
            }
        }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BridgeService.ACTION_STATUS) return
            webSocketStatus = intent.getStringExtra(BridgeService.EXTRA_WS_STATUS) ?: webSocketStatus
            bleStatus = intent.getStringExtra(BridgeService.EXTRA_BLE_STATUS) ?: bleStatus
            currentLevel = intent.getIntExtra(BridgeService.EXTRA_LEVEL, currentLevel)
            vibeLovenseLevel = intent.getIntExtra(BridgeService.EXTRA_VIBRATION_LEVEL, vibeLovenseLevel)
            rotateLovenseLevel = intent.getIntExtra(BridgeService.EXTRA_ROTATION_LEVEL, rotateLovenseLevel)
            suctionEnabled = intent.getBooleanExtra(BridgeService.EXTRA_SUCTION_ENABLED, false)
            peaksEnabled = intent.getBooleanExtra(BridgeService.EXTRA_PEAKS_ENABLED, false)
            releaseVerified = intent.getBooleanExtra(BridgeService.EXTRA_RELEASE_VERIFIED, false)
            manualCompleted = intent.getBooleanExtra(BridgeService.EXTRA_MANUAL_COMPLETED, false)
            acceptedPeaks = intent.getIntExtra(BridgeService.EXTRA_ACCEPTED_PEAKS, acceptedPeaks)
            skippedPeaks = intent.getIntExtra(BridgeService.EXTRA_SKIPPED_PEAKS, skippedPeaks)
            bridgeRunning = intent.getBooleanExtra(BridgeService.EXTRA_RUNNING, false)
            phase = intent.getStringExtra(BridgeService.EXTRA_PHASE) ?: phase
            stopReason = intent.getStringExtra(BridgeService.EXTRA_STOP_REASON) ?: stopReason
            remotePaused = intent.getBooleanExtra(BridgeService.EXTRA_REMOTE_PAUSED, false)
            appliedVibrationLevel = intent.getIntExtra(BridgeService.EXTRA_APPLIED_VIBRATION_LEVEL, -1)
            intent.getStringExtra(BridgeService.EXTRA_LOG)?.takeIf { it.isNotBlank() }?.let(::appendLog)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wsUrl = preferences().getString(PREF_WS_URL, DEFAULT_WS_URL) ?: DEFAULT_WS_URL
        fullVibrationRange = preferences().getBoolean("full_vibration_range", true)
        testRotateLevel = preferences().getInt("suction_command", 2).coerceIn(1, 3)
        secondPulseEnabled = preferences().getBoolean("second_pulse", false) && testRotateLevel == 2
        pulseSeconds = (preferences().getLong("pulse_ms", 2300L) / 1000f)
            .coerceIn(0.7f, if (secondPulseEnabled) 4f else 5f)
        cooldownSeconds = (preferences().getLong("cooldown_ms", 1000L) / 1000f).coerceIn(0.7f, 5f)
        peakThreshold = preferences().getInt("peak_threshold", 12).coerceIn(3, 20)
        peakIntervalSeconds = (preferences().getLong("peak_interval_ms", 8000L) / 1000f).coerceIn(6f, 30f)
        recoverySeconds = (preferences().getLong("recovery_ms", 2000L) / 1000f).coerceIn(1f, 5f)
        requestMissingPermissions()

        setContent {
            LSIntifaceBridgeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("LS Intiface Bridge", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = wsUrl,
                            onValueChange = {
                                wsUrl = it
                                preferences().edit().putString(PREF_WS_URL, it).apply()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Intiface Device WebSocket") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { startBridge() }) {
                                Text("Start")
                            }
                            Button(onClick = { stopBridge() }) {
                                Text("Stop")
                            }
                        }

                        Text("La vibración sigue el script. La bomba añade ciclos completos en subidas elegidas.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = fullVibrationRange, onCheckedChange = {
                                fullVibrationRange = it; saveConfig()
                            })
                            Text("Repartir los modos por el rango completo")
                        }
                        Text(if (fullVibrationRange) "0–2: apagado | 3–8: modo 1 | 9–14: modo 2 | 15–20: modo 3"
                            else "Escala anterior: 0–2 apagado | 3–4 modo 1 | 5–9 modo 2 | 10–20 modo 3")

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = suctionEnabled, enabled = bridgeRunning,
                                onCheckedChange = { enabled ->
                                    startService(Intent(this@MainActivity, BridgeService::class.java)
                                        .setAction(BridgeService.ACTION_TOGGLE_SUCTION)
                                        .putExtra(BridgeService.EXTRA_SUCTION_ENABLED, enabled))
                                })
                            Text("Habilitar succión")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = peaksEnabled,
                                enabled = bridgeRunning && (peaksEnabled || (releaseVerified && !remotePaused && phase == "IDLE")),
                                onCheckedChange = { enabled ->
                                    startService(Intent(this@MainActivity, BridgeService::class.java)
                                        .setAction(BridgeService.ACTION_TOGGLE_PEAKS)
                                        .putExtra(BridgeService.EXTRA_PEAKS_ENABLED, enabled))
                                })
                            Text("Succión en subidas del script")
                        }
                        Text("Disparar al subir a $peakThreshold/20; volver a armar al bajar a ${(peakThreshold - 4).coerceAtLeast(0)} o menos.")
                        Slider(value = peakThreshold.toFloat(), onValueChange = { peakThreshold = it.roundToInt() },
                            onValueChangeFinished = { saveConfig() }, valueRange = 3f..20f, steps = 16)
                        Text("Intervalo mínimo entre inicios: %.0f s".format(peakIntervalSeconds))
                        Slider(value = peakIntervalSeconds, onValueChange = { peakIntervalSeconds = it },
                            onValueChangeFinished = { saveConfig() }, valueRange = 6f..30f, steps = 23)
                        Text("La bajada del script no corta el pulso. Las subidas recibidas durante el ciclo, la recuperación o el intervalo mínimo se omiten.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Duración del pulso principal: %.1f s".format(pulseSeconds))
                        Slider(value = pulseSeconds, onValueChange = { pulseSeconds = it },
                            onValueChangeFinished = { saveConfig() },
                            valueRange = 0.7f..(if (secondPulseEnabled) 4f else 5f),
                            steps = if (secondPulseEnabled) 32 else 42)
                        Text("Pausa antes del segundo pulso: %.1f s".format(cooldownSeconds))
                        Slider(value = cooldownSeconds, onValueChange = { cooldownSeconds = it },
                            onValueChangeFinished = { saveConfig() }, valueRange = 0.7f..5f, steps = 42)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = secondPulseEnabled, enabled = testRotateLevel == 2,
                                onCheckedChange = {
                                    secondPulseEnabled = it
                                    if (it) pulseSeconds = pulseSeconds.coerceAtMost(4f)
                                    saveConfig()
                                })
                            Text("Añadir segundo pulso de 1 s (prueba; comando 2)")
                        }
                        Text("Recuperación tras la parada final: %.1f s".format(recoverySeconds))
                        Slider(value = recoverySeconds, onValueChange = { recoverySeconds = it },
                            onValueChangeFinished = { saveConfig() }, valueRange = 1f..5f, steps = 39)
                        Text("Para habilitar picos: comando 2, segundo pulso activado y prueba manual fuera del cuerpo. Al terminar, confirma sólo si dejó salir todo el aire sin crear vacío otra vez.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            startService(Intent(this@MainActivity, BridgeService::class.java)
                                .setAction(BridgeService.ACTION_CONFIRM_RELEASE))
                        }, enabled = bridgeRunning && manualCompleted && phase == "IDLE" && !releaseVerified) {
                            Text("El ciclo soltó todo el aire")
                        }
                        Text(if (releaseVerified) "Liberación confirmada por ti para estos ajustes. Start y activar subidas."
                            else "Repetición por script bloqueada hasta comprobar la liberación.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Una prueba manual pausa el script hasta Start. Cambiar los tiempos de bomba obliga a comprobar la liberación otra vez.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Probar primero sin contacto corporal. La parada de la bomba no confirma liberación del vacío.",
                            style = MaterialTheme.typography.bodySmall)

                        // Vibration Test Control
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Test Vibration: $testVibeLevel (0..20)")
                            Slider(
                                value = testVibeLevel.toFloat(),
                                onValueChange = { testVibeLevel = it.roundToInt() },
                                valueRange = 0f..20f,
                                steps = 19,
                            )
                        }

                        // Rotation Test Control
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Comando para la prueba manual de succión: $testRotateLevel")
                            Slider(
                                value = testRotateLevel.toFloat(),
                                onValueChange = {
                                    testRotateLevel = it.roundToInt()
                                    if (testRotateLevel != 2) secondPulseEnabled = false
                                },
                                onValueChangeFinished = { saveConfig() },
                                valueRange = 1f..3f,
                                steps = 1,
                            )
                        }

                        // Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Button(onClick = { testVibration(testVibeLevel) }, enabled = bridgeRunning && phase == "IDLE") {
                                Text("Vib")
                            }
                            Button(onClick = { testRotation(testRotateLevel) },
                                enabled = bridgeRunning && suctionEnabled && phase == "IDLE") {
                                Text("Succión")
                            }
                            Button(onClick = { stopAll() }) {
                                Text("Off")
                            }
                        }

                        Text("WebSocket: $webSocketStatus")
                        if (remotePaused) Text("Script pausado. Start para reanudar.")
                        Button(onClick = {
                            startService(Intent(this@MainActivity, BridgeService::class.java)
                                .setAction(BridgeService.ACTION_GLOBAL_STOP))
                        }, enabled = bridgeRunning) { Text("Parada general y cerrar puente") }
                        Text("BLE: $bleStatus")
                        val phaseLabel = when (phase) {
                            "MANUAL_PREPARING" -> "Preparando prueba manual"
                            "STARTING" -> "Esperando inicio"
                            "SUCKING" -> "Pulso principal"
                            "BETWEEN_STOPPING" -> "Parando entre pulsos"
                            "BETWEEN_WAIT" -> "Pausa entre pulsos"
                            "SECOND_STARTING" -> "Esperando segundo pulso"
                            "SECOND_PULSE" -> "Segundo pulso de prueba"
                            "STOPPING" -> "Parando"
                            "COOLDOWN" -> "Recuperación; no confirma liberación"
                            else -> "Listo"
                        }
                        Text("Entrada: $vibeLovenseLevel / 20 → modo de vibración $currentLevel / 3")
                        Text("Último modo aceptado por Android: ${if (appliedVibrationLevel < 0) "sin confirmar" else appliedVibrationLevel.toString()}")
                        Text("Succión: $rotateLovenseLevel | $phaseLabel")
                        Text("Ciclos por subida: $acceptedPeaks | Subidas omitidas: $skippedPeaks")
                        Text("Causa de parada: $stopReason")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = logText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(BridgeService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_GET_STATUS))
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun startBridge() {
        if (!hasPermissions()) {
            requestMissingPermissions()
            return
        }
        val intent = Intent(this, BridgeService::class.java)
            .setAction(BridgeService.ACTION_START)
            .putExtra(BridgeService.EXTRA_WS_URL, wsUrl)
        preferences().edit().putString(PREF_WS_URL, wsUrl).apply()
        ContextCompat.startForegroundService(this, intent)
        appendLog("Bridge service starting")
    }

    private fun stopBridge() {
        val intent = Intent(this, BridgeService::class.java)
            .setAction(BridgeService.ACTION_STOP)
        startService(intent)
        appendLog("Bridge service stopping")
    }

    private fun testVibration(level: Int) {
        val intent = Intent(this, BridgeService::class.java)
            .setAction(BridgeService.ACTION_TEST_LEVEL)
            .putExtra(BridgeService.EXTRA_VIBRATION_LEVEL, level)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun testRotation(level: Int) {
        val intent = Intent(this, BridgeService::class.java)
            .setAction(BridgeService.ACTION_TEST_LEVEL)
            .putExtra(BridgeService.EXTRA_ROTATION_LEVEL, level)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopAll() {
        val intent = Intent(this, BridgeService::class.java)
            .setAction(BridgeService.ACTION_OFF)
        startService(intent)
    }

    private fun saveConfig() {
        startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_CONFIG)
            .putExtra(BridgeService.EXTRA_PULSE_MS, (pulseSeconds * 1000).toLong())
            .putExtra(BridgeService.EXTRA_COOLDOWN_MS, (cooldownSeconds * 1000).toLong())
            .putExtra(BridgeService.EXTRA_FULL_VIBRATION_RANGE, fullVibrationRange)
            .putExtra(BridgeService.EXTRA_SUCTION_COMMAND, testRotateLevel)
            .putExtra(BridgeService.EXTRA_SECOND_PULSE, secondPulseEnabled)
            .putExtra(BridgeService.EXTRA_RECOVERY_MS, (recoverySeconds * 1000).toLong())
            .putExtra(BridgeService.EXTRA_PEAK_THRESHOLD, peakThreshold)
            .putExtra(BridgeService.EXTRA_PEAK_INTERVAL_MS, (peakIntervalSeconds * 1000).toLong()))
    }

    private fun requestMissingPermissions() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermissions(): Boolean =
        permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun appendLog(message: String) {
        logText = (logText + message + "\n").lines().takeLast(120).joinToString("\n")
    }

    private fun preferences() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    companion object {
        private const val DEFAULT_WS_URL = "ws://192.168.0.2:54817"
        private const val PREFS_NAME = "bridge_settings"
        private const val PREF_WS_URL = "ws_url"
    }
}
