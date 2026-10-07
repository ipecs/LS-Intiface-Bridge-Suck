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
    private var followsVibration by mutableStateOf(true)
    private var secondPulseEnabled by mutableStateOf(false)
    private var triggerAt by mutableIntStateOf(3)
    private var suctionInputValue by mutableIntStateOf(0)
    private var acceptedPeaks by mutableIntStateOf(0)
    private var skippedPeaks by mutableIntStateOf(0)
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
            bridgeRunning = intent.getBooleanExtra(BridgeService.EXTRA_RUNNING, false)
            phase = intent.getStringExtra(BridgeService.EXTRA_PHASE) ?: phase
            stopReason = intent.getStringExtra(BridgeService.EXTRA_STOP_REASON) ?: stopReason
            remotePaused = intent.getBooleanExtra(BridgeService.EXTRA_REMOTE_PAUSED, false)
            suctionInputValue = intent.getIntExtra(BridgeService.EXTRA_SUCTION_INPUT, 0)
            acceptedPeaks = intent.getIntExtra(BridgeService.EXTRA_ACCEPTED_PEAKS, 0)
            skippedPeaks = intent.getIntExtra(BridgeService.EXTRA_SKIPPED_PEAKS, 0)
            intent.getStringExtra(BridgeService.EXTRA_LOG)?.takeIf { it.isNotBlank() }?.let(::appendLog)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wsUrl = preferences().getString(PREF_WS_URL, DEFAULT_WS_URL) ?: DEFAULT_WS_URL
        followsVibration = preferences().getBoolean("follow_vibration", true)
        testRotateLevel = preferences().getInt("suction_command", 2).coerceIn(1, 3)
        triggerAt = preferences().getInt("trigger_at", 3).coerceIn(1, 20)
        secondPulseEnabled = preferences().getBoolean("second_pulse", false) && testRotateLevel == 2
        pulseSeconds = (preferences().getLong("pulse_ms", 2300L) / 1000f)
            .coerceIn(0.7f, if (secondPulseEnabled) 4f else 5f)
        cooldownSeconds = preferences().getLong("cooldown_ms", 1000L) / 1000f
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
                            Switch(checked = followsVibration, onCheckedChange = {
                                followsVibration = it; saveConfig()
                            })
                            Text("Succión desde la señal de vibración")
                        }
                        Text(if (followsVibration) "Un solo script: disparos desde la intensidad recibida."
                            else "Dos controles: Vibrate y Rotate independientes.")
                        Text("Umbral de succión: $triggerAt / 20")
                        Slider(value = triggerAt.toFloat(), onValueChange = { triggerAt = it.roundToInt() },
                            onValueChangeFinished = { saveConfig() }, valueRange = 1f..20f, steps = 18)
                        Text("Disparo en subida: alcanzar el umbral después de subir al menos ${minOf(2, triggerAt)} puntos.")
                        Text("Duración máxima del pulso: %.1f s".format(pulseSeconds))
                        Slider(value = pulseSeconds, onValueChange = { pulseSeconds = it },
                            onValueChangeFinished = { saveConfig() },
                            valueRange = 0.7f..(if (secondPulseEnabled) 4f else 5f),
                            steps = if (secondPulseEnabled) 32 else 42)
                        Text("Pausa entre pulsos y descanso final: %.1f s".format(cooldownSeconds))
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
                        Text("Durante toda la secuencia se omiten los picos nuevos. El segundo pulso es una prueba de liberación; verificar primero fuera del cuerpo.",
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
                            Text("Comando de succión para script y prueba: $testRotateLevel")
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
                            Button(onClick = { testVibration(testVibeLevel) }) {
                                Text("Vib")
                            }
                            Button(onClick = { testRotation(testRotateLevel) }) {
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
                            "STARTING" -> "Esperando inicio"
                            "SUCKING" -> "Pulso principal"
                            "BETWEEN_STOPPING" -> "Parando entre pulsos"
                            "BETWEEN_WAIT" -> "Pausa entre pulsos"
                            "SECOND_STARTING" -> "Esperando segundo pulso"
                            "SECOND_PULSE" -> "Segundo pulso de prueba"
                            "STOPPING" -> "Parando"
                            "COOLDOWN" -> "Descanso"
                            else -> "Listo"
                        }
                        Text("Orden: Vib $vibeLovenseLevel / Succión $rotateLovenseLevel | $phaseLabel")
                        Text("Entrada succión: $suctionInputValue / 20")
                        Text("Disparos: $acceptedPeaks | Picos omitidos mientras estaba ocupado: $skippedPeaks")
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

    private fun testBoth(vibeLevel: Int, rotateLevel: Int) {
        val intent = Intent(this, BridgeService::class.java)
            .setAction(BridgeService.ACTION_TEST_LEVEL)
            .putExtra(BridgeService.EXTRA_VIBRATION_LEVEL, vibeLevel)
            .putExtra(BridgeService.EXTRA_ROTATION_LEVEL, rotateLevel)
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
            .putExtra(BridgeService.EXTRA_FOLLOW_VIBRATION, followsVibration)
            .putExtra(BridgeService.EXTRA_SUCTION_COMMAND, testRotateLevel)
            .putExtra(BridgeService.EXTRA_TRIGGER_AT, triggerAt)
            .putExtra(BridgeService.EXTRA_SECOND_PULSE, secondPulseEnabled))
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
