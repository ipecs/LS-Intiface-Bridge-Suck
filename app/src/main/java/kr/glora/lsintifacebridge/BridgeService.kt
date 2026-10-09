package kr.glora.lsintifacebridge

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import androidx.core.content.ContextCompat
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.TimeUnit

class BridgeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val controller = ActuationController()
    private val accents = PeakSuctionGate()
    private val outputs = ActuationOutputRouter()
    private val client = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    private var radio: BleTransmitter? = null
    private var socket: WebSocket? = null
    private var session = 0
    private var running = false
    private var shuttingDown = false
    private var generalStopRequested = false
    private var remoteOutputsPaused = false
    private var shutdownStart = 0L
    private var stopVibrationAppliedAt: Long? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wsStatus = "Disconnected"
    private var bleStatus = "Idle"
    private var appliedVibrationLevel = -1
    private var pendingManualSuction: Int? = null
    private var lastSnapshot = ""
    private var lastPublishedAt = 0L
    private var lastPeakSkipLogAt = 0L
    private val prefs by lazy { getSharedPreferences("bridge_settings", MODE_PRIVATE) }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            controller.tick(now)
            val manual = pendingManualSuction
            if (manual != null && controller.phase == ActuationController.Phase.IDLE &&
                radio?.confirmed == BleTransmitter.VIBRATION[0] && radio?.hasPendingOperation == false) {
                pendingManualSuction = null
                if (controller.manualPulse(manual, now)) persistRelease()
            }
            val command = requestCurrentCommand()
            if (radio?.confirmed == command && radio?.hasPendingOperation == false) {
                acceptPumpStage(command, now)
            }
            if (shuttingDown) {
                if (stopVibrationAppliedAt == null && controller.phase == ActuationController.Phase.IDLE &&
                    radio?.confirmed == BleTransmitter.VIBRATION[0] && radio?.hasPendingOperation == false) {
                    stopVibrationAppliedAt = now
                }
                val stoppedAt = stopVibrationAppliedAt
                if (stoppedAt != null && now - stoppedAt >= 500L) {
                    finishShutdown()
                    return
                }
                if (now - shutdownStart > 8000L) {
                    publish("Parada BLE no completada; comprobar y apagar el juguete físicamente")
                    finishShutdown()
                    return
                }
            }
            publish()
            handler.postDelayed(this, 25L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        controller.setFullVibrationRange(prefs.getBoolean("full_vibration_range", true))
        controller.configure(prefs.getLong("pulse_ms", 2300L), prefs.getLong("cooldown_ms", 1000L), now(),
            command = prefs.getInt("suction_command", 2), secondPulse = prefs.getBoolean("second_pulse", false),
            recovery = prefs.getLong("recovery_ms", 2000L))
        accents.configure(prefs.getInt("peak_threshold", 12), prefs.getLong("peak_interval_ms", 8000L))
        controller.restoreRelease(prefs.getString("release_profile", null))
        persistRelease()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        when (intent.action) {
            ACTION_START -> {
                if (shuttingDown) return START_NOT_STICKY
                if (!ensureRunning()) return START_NOT_STICKY
                accents.setEnabled(false)
                controller.setSuctionEnabled(false)
                pendingManualSuction = null
                controller.stopAll()
                requestCurrentCommand()
                remoteOutputsPaused = false
                notification()
                connect(intent.getStringExtra(EXTRA_WS_URL).orEmpty())
            }
            ACTION_STOP, ACTION_GLOBAL_STOP -> if (running) {
                accents.setEnabled(false)
                shuttingDown = true
                generalStopRequested = generalStopRequested || intent.action == ACTION_GLOBAL_STOP
                shutdownStart = now()
                stopVibrationAppliedAt = null
                disconnect()
                pendingManualSuction = null
                controller.stopAll(restartHold = intent.action == ACTION_GLOBAL_STOP)
                requestCurrentCommand()
                publish(if (generalStopRequested) "Parada general solicitada; liberación del vacío por comprobar"
                    else "Parando ambos canales antes de cerrar la emisión")
            } else stopSelf()
            ACTION_OFF -> if (running) {
                pendingManualSuction = null
                remoteOutputsPaused = true
                accents.setEnabled(false)
                controller.setSuctionEnabled(false)
                controller.stopAll()
                requestCurrentCommand()
                notification()
                publish("Off: salidas del script pausadas y succión deshabilitada; Start para reanudar")
            } else stopSelf()
            ACTION_TOGGLE_SUCTION -> {
                if (running && !shuttingDown) {
                    controller.setSuctionEnabled(intent.getBooleanExtra(EXTRA_SUCTION_ENABLED, !controller.suctionEnabled),
                        requestIdleStop = false)
                    if (!controller.suctionEnabled) {
                        pendingManualSuction = null
                        accents.setEnabled(false)
                    }
                    requestCurrentCommand()
                    notification()
                    publish(if (!controller.suctionEnabled) "Succión deshabilitada"
                        else if (remoteOutputsPaused) "Succión habilitada para pruebas locales; Start para reanudar script"
                        else "Succión manual habilitada; los picos se activan por separado")
                } else if (!running) stopSelf()
            }
            ACTION_TOGGLE_PEAKS -> {
                val enabled = intent.getBooleanExtra(EXTRA_PEAKS_ENABLED, false)
                if (running && !shuttingDown) {
                    if (enabled && !controller.releaseVerified) {
                        publish(if (controller.releaseProfile == null)
                            "Selecciona comando 2 y activa el segundo pulso; después confirma la liberación que comprobaste"
                            else "Pulsa «Confirmar liberación comprobada» para guardar el resultado de tu prueba", force = true)
                    } else if (enabled && (controller.phase != ActuationController.Phase.IDLE || pendingManualSuction != null)) {
                        publish("Espera a que figure Listo antes de activar subidas", force = true)
                    } else {
                        if (enabled) remoteOutputsPaused = false
                        accents.setEnabled(enabled)
                        controller.setSuctionEnabled(enabled, requestIdleStop = false)
                        requestCurrentCommand()
                        notification()
                        publish(if (enabled) "Picos activados: subida a ${accents.threshold}/20; intervalo mínimo ${accents.minimumIntervalMs / 1000}s"
                            else "Picos desactivados; pasos de bomba pendientes cancelados")
                    }
                } else if (!running) stopSelf()
            }
            ACTION_CONFIRM_RELEASE -> {
                if (running && !shuttingDown && pendingManualSuction == null && controller.confirmRelease()) {
                    persistRelease()
                    publish("Liberación guardada para estos ajustes; ya puedes activar las subidas", force = true)
                } else {
                    publish("Para confirmar: comando 2, segundo pulso activado y estado Listo", force = true)
                    if (!running) stopSelf()
                }
            }
            ACTION_CONFIG -> {
                pendingManualSuction = null
                accents.setEnabled(false)
                controller.tick(now())
                controller.configure(intent.getLongExtra(EXTRA_PULSE_MS, controller.pulseMs),
                    intent.getLongExtra(EXTRA_COOLDOWN_MS, controller.cooldownMs), now(),
                    command = intent.getIntExtra(EXTRA_SUCTION_COMMAND, controller.scriptCommand),
                    secondPulse = intent.getBooleanExtra(EXTRA_SECOND_PULSE, controller.secondPulseEnabled),
                    recovery = intent.getLongExtra(EXTRA_RECOVERY_MS, controller.recoveryMs))
                accents.configure(intent.getIntExtra(EXTRA_PEAK_THRESHOLD, accents.threshold),
                    intent.getLongExtra(EXTRA_PEAK_INTERVAL_MS, accents.minimumIntervalMs))
                controller.setFullVibrationRange(intent.getBooleanExtra(EXTRA_FULL_VIBRATION_RANGE, controller.fullVibrationRange))
                prefs.edit().putLong("pulse_ms", controller.pulseMs).putLong("cooldown_ms", controller.cooldownMs)
                    .putBoolean("full_vibration_range", controller.fullVibrationRange).putInt("suction_command", controller.scriptCommand)
                    .putBoolean("second_pulse", controller.secondPulseEnabled)
                    .putString("release_profile", if (controller.releaseVerified) controller.releaseProfile else null)
                    .putLong("recovery_ms", controller.recoveryMs).putInt("peak_threshold", accents.threshold)
                    .putLong("peak_interval_ms", accents.minimumIntervalMs).apply()
                requestCurrentCommand()
                if (running) notification()
                publish("Ajustes guardados; reactivar picos si corresponde")
                if (!running) stopSelf()
            }
            ACTION_TEST_LEVEL -> {
                if (!shuttingDown && ensureRunning()) {
                    if (intent.hasExtra(EXTRA_VIBRATION_LEVEL)) {
                        if (controller.phase != ActuationController.Phase.IDLE || pendingManualSuction != null) {
                            publish("Prueba de vibración pendiente: esperar a que figure Listo")
                            return START_NOT_STICKY
                        }
                        accents.setEnabled(false)
                        remoteOutputsPaused = true
                        controller.vibration(intent.getIntExtra(EXTRA_VIBRATION_LEVEL, 0), now(), 2000L)
                        requestVibrationNow()
                        publish("Prueba de vibración aislada del script durante 2 s; Start para reanudar")
                    }
                    if (intent.hasExtra(EXTRA_ROTATION_LEVEL)) {
                        val level = intent.getIntExtra(EXTRA_ROTATION_LEVEL, 0)
                        if (level == 0) {
                            pendingManualSuction = null
                            controller.stopAll()
                            requestCurrentCommand()
                        } else if (controller.suctionEnabled && controller.phase == ActuationController.Phase.IDLE &&
                            pendingManualSuction == null && level in 1..3) {
                            accents.setEnabled(false)
                            remoteOutputsPaused = true
                            controller.vibration(0, now())
                            pendingManualSuction = level
                            radio?.request(BleTransmitter.VIBRATION[0])
                            publish("Prueba de succión aislada del script; Start para reanudar el script después")
                        } else {
                            publish("Prueba no iniciada: habilitar succión y esperar a que termine el descanso")
                        }
                    }
                    publish(if (controller.secondPulseEnabled)
                        "Prueba local: pulso principal, pausa, segundo pulso de 1 s y descanso"
                        else "Prueba local: vibración limitada a 2 s; succión limitada al ajuste de pulso")
                }
            }
            ACTION_GET_STATUS -> { publish(force = true); if (!running) stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun ensureRunning(): Boolean {
        if (running && radio?.isClosed == false) return true
        if (running) {
            handler.removeCallbacks(loop)
            if (wakeLock?.isHeld == true) wakeLock?.release()
            radio = null
            running = false
        }
        val permissions = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            publish("Faltan permisos Bluetooth"); stopSelf(); return false
        }
        val advertiser = try {
            getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser
        } catch (e: SecurityException) { null }
        if (advertiser == null) {
            bleStatus = "Bluetooth apagado o sin soporte de emisión"
            publish(bleStatus); stopSelf(); return false
        }
        notification()
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "$packageName:Bridge").apply { acquire() }
        radio = BleTransmitter(advertiser, handler, { command ->
            val now = now()
            controller.tick(now)
            bleStatus = "Android aceptó %06X".format(command)
            val vibrationLevel = BleTransmitter.VIBRATION.indexOf(command)
            if (vibrationLevel >= 0) appliedVibrationLevel = vibrationLevel
            synchronizeOutputs(now)
            acceptPumpStage(command, now)
            if (shuttingDown && command == BleTransmitter.VIBRATION[0] &&
                controller.phase == ActuationController.Phase.IDLE) stopVibrationAppliedAt = now
            requestCurrentCommand()
            publish(bleStatus)
        }, { message ->
            pendingManualSuction = null
            accents.setEnabled(false)
            remoteOutputsPaused = true
            controller.setSuctionEnabled(false)
            controller.stopAll(ActuationController.StopReason.RADIO_FAILURE)
            requestCurrentCommand()
            bleStatus = "Error BLE"
            notification()
            publish(message)
        })
        outputs.reset()
        accents.setEnabled(false)
        controller.stopAll() // Flush the pump stop before accepting activation.
        appliedVibrationLevel = -1
        running = true
        handler.post(loop)
        return true
    }

    private fun connect(url: String) {
        disconnect()
        val request = try {
            Request.Builder().url(url).build().also {
                require(it.url.scheme in listOf("http", "https"))
            }
        } catch (e: Exception) { wsStatus = "URL inválida"; publish(wsStatus); return }
        wsStatus = "Connecting"
        val generation = session
        val parser = LovenseProtocol(::handleCommand)
        socket = client.newWebSocket(request, object : WebSocketListener() {
            private fun onMain(ws: WebSocket, action: () -> Unit) {
                handler.post { if (generation == session && socket === ws && !shuttingDown) action() }
            }
            override fun onOpen(ws: WebSocket, response: Response) = onMain(ws) {
                wsStatus = "Connected"
                ws.send("{\"identifier\":\"LVSDevice\",\"address\":\"001122334455\",\"version\":0}")
                publish("Intiface conectado; perfil Hush con un único control de vibración")
            }
            override fun onMessage(ws: WebSocket, text: String) = onMain(ws) {
                val reply = parser.receive(text)
                if (reply.isNotEmpty()) ws.send(reply)
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) = onMain(ws) {
                val reply = parser.receive(bytes.utf8())
                if (reply.isNotEmpty()) ws.send(reply.encodeUtf8())
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) = onMain(ws) {
                pendingManualSuction = null
                accents.setEnabled(false)
                controller.stopAll(ActuationController.StopReason.CONNECTION_LOST)
                requestCurrentCommand()
                ws.close(code, reason)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) = onMain(ws) {
                pendingManualSuction = null
                wsStatus = "Disconnected"
                accents.setEnabled(false)
                controller.stopAll(ActuationController.StopReason.CONNECTION_LOST)
                requestCurrentCommand()
                publish("WebSocket cerrado: $reason")
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = onMain(ws) {
                pendingManualSuction = null
                wsStatus = "Error"
                accents.setEnabled(false)
                controller.stopAll(ActuationController.StopReason.CONNECTION_LOST)
                requestCurrentCommand()
                publish("Conexión perdida: ${t.message}")
            }
        })
        publish("Conectando a Intiface")
    }

    private fun handleCommand(name: String, value: Int?): String = when (name) {
        "devicetype" -> "Z:11:001122334455;"
        "battery" -> "ERR;" // This broadcast protocol exposes no battery telemetry.
        "status" -> "2;"
        "autoswitch", "rotatechange" -> "OK;"
        "vibrate", "vibrate1" -> {
            if (value == null || value !in 0..20) "ERR;" else {
                if (!remoteOutputsPaused) {
                    val time = now()
                    controller.tick(time)
                    controller.vibration(value, time)
                    val event = accents.observe(value, time, controller.releaseVerified && controller.suctionEnabled &&
                        controller.phase == ActuationController.Phase.IDLE && pendingManualSuction == null)
                    when (event) {
                        PeakSuctionGate.Event.START -> {
                            if (controller.automaticPulse(time)) publish("Subida $value/20: ciclo de succión iniciado")
                        }
                        PeakSuctionGate.Event.SKIP_BUSY, PeakSuctionGate.Event.SKIP_INTERVAL -> {
                            if (time - lastPeakSkipLogAt >= 1000L) {
                                lastPeakSkipLogAt = time
                                publish("Subida $value/20 omitida: ${if (event == PeakSuctionGate.Event.SKIP_BUSY) "ciclo/recuperación en curso" else "intervalo mínimo"}")
                            }
                        }
                        PeakSuctionGate.Event.NONE -> Unit
                    }
                    requestVibrationNow()
                    publish()
                }
                "OK;"
            }
        }
        "rotate", "vibrate2" -> "ERR;" // The script profile exposes only one vibrator.
        "stop", "stopdevice", "poweroff" -> {
            pendingManualSuction = null
            accents.setEnabled(false)
            controller.stopAll()
            requestCurrentCommand()
            "OK;"
        }
        else -> "ERR;"
    }

    private fun requestVibrationNow() {
        if (running && pendingManualSuction == null) requestCurrentCommand()
    }

    private fun synchronizeOutputs(time: Long): Int {
        val pump = if (controller.phase == ActuationController.Phase.IDLE) null
            else if (generalStopRequested) BleTransmitter.GLOBAL_STOP
            else BleTransmitter.SUCTION[controller.suctionLevel]
        return outputs.select(BleTransmitter.VIBRATION[controller.vibrationLevel], pump,
            controller.pumpRevision, time)
    }

    private fun acceptPumpStage(command: Int, time: Long) {
        if (!outputs.applied(command, time)) return
        if (command == BleTransmitter.SUCTION[0] || command == BleTransmitter.GLOBAL_STOP) {
            controller.stopDataApplied(time)
        } else {
            val level = BleTransmitter.SUCTION.indexOf(command)
            if (level > 0) controller.pumpDataApplied(level, time)
        }
    }

    private fun requestCurrentCommand(): Int {
        val command = synchronizeOutputs(now())
        radio?.request(command)
        return command
    }

    private fun disconnect() {
        session++
        socket?.close(1000, "Bridge stopped")
        socket = null
        wsStatus = "Disconnected"
    }

    private fun persistRelease() {
        prefs.edit().putString("release_profile", if (controller.releaseVerified) controller.releaseProfile else null).apply()
    }

    private fun finishShutdown() {
        running = false
        radio?.close()
        radio = null
        bleStatus = "Stopped"
        publish("Emisión cerrada", force = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "LS Bridge", NotificationManager.IMPORTANCE_LOW))
        fun action(name: String, code: Int, intentAction: String): Notification.Action {
            val intent = Intent(this, BridgeService::class.java).setAction(intentAction)
            val pending = PendingIntent.getService(this, code, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action.Builder(null, name, pending).build()
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LS Intiface Bridge")
            .setContentText(if (accents.enabled) "Vibración y acentos de succión en subidas a ${accents.threshold}/20"
                else "Vibración; succión manual ${if (controller.suctionEnabled) "habilitada" else "apagada"}")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(action(if (controller.suctionEnabled) "Desactivar prueba" else "Habilitar prueba manual", 1, ACTION_TOGGLE_SUCTION))
            .addAction(action("Parar", 0, ACTION_STOP)).setOngoing(true).build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun publish(log: String = "", force: Boolean = false) {
        val snapshot = "$wsStatus|$bleStatus|${controller.vibrationInput}|${controller.vibrationLevel}|${controller.suctionLevel}|${controller.phase}|${controller.stopReason}|${controller.suctionEnabled}|$running|$remoteOutputsPaused|$appliedVibrationLevel|$pendingManualSuction|${accents.enabled}|${accents.accepted}|${accents.skipped}|${controller.releaseVerified}|${controller.manualSequenceCompleted}"
        if (!force && log.isEmpty() && snapshot == lastSnapshot) return
        val time = now()
        if (!force && log.isEmpty() && time - lastPublishedAt < 100L) return
        lastPublishedAt = time
        lastSnapshot = snapshot
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_WS_STATUS, wsStatus).putExtra(EXTRA_BLE_STATUS, bleStatus)
            .putExtra(EXTRA_LEVEL, controller.vibrationLevel)
            .putExtra(EXTRA_APPLIED_VIBRATION_LEVEL, appliedVibrationLevel)
            .putExtra(EXTRA_VIBRATION_LEVEL, controller.vibrationInput)
            .putExtra(EXTRA_ROTATION_LEVEL, controller.suctionLevel)
            .putExtra(EXTRA_SUCTION_ENABLED, controller.suctionEnabled)
            .putExtra(EXTRA_PEAKS_ENABLED, accents.enabled)
            .putExtra(EXTRA_RELEASE_VERIFIED, controller.releaseVerified)
            .putExtra(EXTRA_MANUAL_COMPLETED, controller.manualSequenceCompleted)
            .putExtra(EXTRA_ACCEPTED_PEAKS, accents.accepted).putExtra(EXTRA_SKIPPED_PEAKS, accents.skipped)
            .putExtra(EXTRA_REMOTE_PAUSED, remoteOutputsPaused)
            .putExtra(EXTRA_PHASE, if (pendingManualSuction != null) "MANUAL_PREPARING" else controller.phase.name)
            .putExtra(EXTRA_RUNNING, running)
            .putExtra(EXTRA_STOP_REASON, when (controller.stopReason) {
                ActuationController.StopReason.NONE -> "—"
                ActuationController.StopReason.PULSE_LIMIT -> "Tiempo de pulso cumplido"
                ActuationController.StopReason.SEQUENCE_COMPLETE -> "Segundo pulso terminado; recuperación"
                ActuationController.StopReason.DISABLED -> "Succión deshabilitada"
                ActuationController.StopReason.SETTINGS_CHANGED -> "Cambio de ajustes"
                ActuationController.StopReason.MANUAL_STOP -> "Orden de parada"
                ActuationController.StopReason.CONNECTION_LOST -> "Conexión perdida"
                ActuationController.StopReason.RADIO_FAILURE -> "Error Bluetooth"
            })
            .putExtra(EXTRA_LOG, log))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        disconnect()
        radio?.close()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        const val ACTION_START = "kr.glora.lsintifacebridge.ACTION_START"
        const val ACTION_STOP = "kr.glora.lsintifacebridge.ACTION_STOP"
        const val ACTION_STATUS = "kr.glora.lsintifacebridge.ACTION_STATUS"
        const val ACTION_TEST_LEVEL = "kr.glora.lsintifacebridge.ACTION_TEST_LEVEL"
        const val ACTION_TOGGLE_SUCTION = "kr.glora.lsintifacebridge.ACTION_TOGGLE_SUCTION"
        const val ACTION_TOGGLE_PEAKS = "kr.glora.lsintifacebridge.ACTION_TOGGLE_PEAKS"
        const val ACTION_CONFIRM_RELEASE = "kr.glora.lsintifacebridge.ACTION_CONFIRM_RELEASE"
        const val ACTION_CONFIG = "kr.glora.lsintifacebridge.ACTION_CONFIG"
        const val ACTION_OFF = "kr.glora.lsintifacebridge.ACTION_OFF"
        const val ACTION_GLOBAL_STOP = "kr.glora.lsintifacebridge.ACTION_GLOBAL_STOP"
        const val ACTION_GET_STATUS = "kr.glora.lsintifacebridge.ACTION_GET_STATUS"
        const val EXTRA_WS_URL = "extra_ws_url"
        const val EXTRA_WS_STATUS = "extra_ws_status"
        const val EXTRA_BLE_STATUS = "extra_ble_status"
        const val EXTRA_LEVEL = "extra_level"
        const val EXTRA_VIBRATION_LEVEL = "extra_vibration_level"
        const val EXTRA_ROTATION_LEVEL = "extra_rotation_level"
        const val EXTRA_LOG = "extra_log"
        const val EXTRA_SUCTION_ENABLED = "suction_enabled"
        const val EXTRA_PULSE_MS = "pulse_ms"
        const val EXTRA_COOLDOWN_MS = "cooldown_ms"
        const val EXTRA_FULL_VIBRATION_RANGE = "full_vibration_range"
        const val EXTRA_APPLIED_VIBRATION_LEVEL = "applied_vibration_level"
        const val EXTRA_SUCTION_COMMAND = "suction_command"
        const val EXTRA_SECOND_PULSE = "second_pulse"
        const val EXTRA_PEAKS_ENABLED = "peaks_enabled"
        const val EXTRA_PEAK_THRESHOLD = "peak_threshold"
        const val EXTRA_PEAK_INTERVAL_MS = "peak_interval_ms"
        const val EXTRA_RECOVERY_MS = "recovery_ms"
        const val EXTRA_RELEASE_VERIFIED = "release_verified"
        const val EXTRA_MANUAL_COMPLETED = "manual_completed"
        const val EXTRA_ACCEPTED_PEAKS = "accepted_peaks"
        const val EXTRA_SKIPPED_PEAKS = "skipped_peaks"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_STOP_REASON = "stop_reason"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_REMOTE_PAUSED = "remote_paused"
        private const val CHANNEL_ID = "ls_bridge_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
