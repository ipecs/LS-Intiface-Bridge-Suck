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
    private var followsVibration = true
    private var lastSnapshot = ""
    private val prefs by lazy { getSharedPreferences("bridge_settings", MODE_PRIVATE) }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            controller.tick(now)
            val command = if (controller.phase == ActuationController.Phase.IDLE) {
                BleTransmitter.VIBRATION[controller.vibrationLevel]
            } else if (generalStopRequested) {
                BleTransmitter.GLOBAL_STOP
            } else {
                BleTransmitter.SUCTION[controller.suctionLevel]
            }
            radio?.request(command)
            if ((command == BleTransmitter.SUCTION[0] || command == BleTransmitter.GLOBAL_STOP) && radio?.confirmed == command) {
                controller.stopDataApplied(now)
            }
            if (shuttingDown) {
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
        followsVibration = prefs.getBoolean("follow_vibration", true)
        controller.configure(prefs.getLong("pulse_ms", 700L), prefs.getLong("cooldown_ms", 1000L), now())
        controller.threeLevels = prefs.getBoolean("three_levels", false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        when (intent.action) {
            ACTION_START -> {
                if (shuttingDown) return START_NOT_STICKY
                if (!ensureRunning()) return START_NOT_STICKY
                controller.setSuctionEnabled(false)
                controller.stopAll()
                remoteOutputsPaused = false
                notification()
                connect(intent.getStringExtra(EXTRA_WS_URL).orEmpty())
            }
            ACTION_STOP, ACTION_GLOBAL_STOP -> if (running) {
                shuttingDown = true
                generalStopRequested = generalStopRequested || intent.action == ACTION_GLOBAL_STOP
                shutdownStart = now()
                stopVibrationAppliedAt = null
                disconnect()
                controller.stopAll(restartHold = intent.action == ACTION_GLOBAL_STOP)
                publish(if (generalStopRequested) "Parada general solicitada; liberación del vacío por comprobar"
                    else "Parando ambos canales antes de cerrar la emisión")
            } else stopSelf()
            ACTION_OFF -> if (running) {
                remoteOutputsPaused = true
                controller.setSuctionEnabled(false)
                controller.stopAll()
                notification()
                publish("Off: salidas del script pausadas y succión deshabilitada; Start para reanudar")
            } else stopSelf()
            ACTION_TOGGLE_SUCTION -> {
                if (running && !shuttingDown) {
                    controller.setSuctionEnabled(intent.getBooleanExtra(EXTRA_SUCTION_ENABLED, !controller.suctionEnabled))
                    notification()
                    publish(if (!controller.suctionEnabled) "Succión deshabilitada"
                        else if (remoteOutputsPaused) "Succión habilitada para pruebas locales; Start para reanudar script"
                        else "Succión habilitada: esperando pico nuevo")
                } else if (!running) stopSelf()
            }
            ACTION_CONFIG -> {
                controller.stopAll(ActuationController.StopReason.SETTINGS_CHANGED)
                controller.configure(intent.getLongExtra(EXTRA_PULSE_MS, controller.pulseMs),
                    intent.getLongExtra(EXTRA_COOLDOWN_MS, controller.cooldownMs), now())
                followsVibration = intent.getBooleanExtra(EXTRA_FOLLOW_VIBRATION, followsVibration)
                controller.threeLevels = intent.getBooleanExtra(EXTRA_THREE_LEVELS, controller.threeLevels)
                prefs.edit().putLong("pulse_ms", controller.pulseMs).putLong("cooldown_ms", controller.cooldownMs)
                    .putBoolean("follow_vibration", followsVibration).putBoolean("three_levels", controller.threeLevels).apply()
                publish("Ajustes guardados; ciclo detenido")
                if (!running) stopSelf()
            }
            ACTION_TEST_LEVEL -> {
                if (!shuttingDown && ensureRunning()) {
                    if (intent.hasExtra(EXTRA_VIBRATION_LEVEL)) {
                        controller.vibration(intent.getIntExtra(EXTRA_VIBRATION_LEVEL, 0), now(), 2000L)
                    }
                    if (intent.hasExtra(EXTRA_ROTATION_LEVEL)) {
                        val level = intent.getIntExtra(EXTRA_ROTATION_LEVEL, 0)
                        if (level == 0) controller.stopAll()
                        else if (controller.manualPulse(level, now())) {
                            remoteOutputsPaused = true
                            publish("Prueba de succión aislada del script; Start para reanudar el script después")
                        } else {
                            publish("Prueba no iniciada: habilitar succión y esperar a que termine el descanso")
                        }
                    }
                    publish("Prueba local: vibración limitada a 2 s; succión limitada al ajuste de pulso")
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
            if (command == BleTransmitter.SUCTION[0] || command == BleTransmitter.GLOBAL_STOP) controller.stopDataApplied(now)
            if (shuttingDown && command == BleTransmitter.VIBRATION[0] &&
                controller.phase == ActuationController.Phase.IDLE) stopVibrationAppliedAt = now
            if (command in BleTransmitter.SUCTION.drop(1) &&
                controller.phase != ActuationController.Phase.SUCKING) {
                radio?.request(if (generalStopRequested) BleTransmitter.GLOBAL_STOP else BleTransmitter.SUCTION[0])
            }
            publish(bleStatus)
        }, { message ->
            controller.setSuctionEnabled(false)
            controller.stopAll(ActuationController.StopReason.RADIO_FAILURE)
            bleStatus = "Error BLE"
            notification()
            publish(message)
        })
        controller.stopAll() // Flush the pump stop before accepting activation.
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
                publish("Intiface conectado; perfil Nora para vibración y rotación")
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
                controller.stopAll(ActuationController.StopReason.CONNECTION_LOST)
                ws.close(code, reason)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) = onMain(ws) {
                wsStatus = "Disconnected"; controller.stopAll(ActuationController.StopReason.CONNECTION_LOST); publish("WebSocket cerrado: $reason")
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = onMain(ws) {
                wsStatus = "Error"; controller.stopAll(ActuationController.StopReason.CONNECTION_LOST); publish("Conexión perdida: ${t.message}")
            }
        })
        publish("Conectando a Intiface")
    }

    private fun handleCommand(name: String, value: Int?): String = when (name) {
        "devicetype" -> "C:11:001122334455;"
        "battery" -> "ERR;" // This broadcast protocol exposes no battery telemetry.
        "status" -> "2;"
        "autoswitch", "rotatechange" -> "OK;"
        "vibrate", "vibrate1" -> {
            if (value == null || value !in 0..20) "ERR;" else {
                if (!remoteOutputsPaused) {
                    controller.vibration(value, now())
                    if (followsVibration) controller.suctionInput(value, now(), stopOnZero = false)
                }
                "OK;"
            }
        }
        "rotate", "vibrate2" -> {
            if (value == null || value !in 0..20) "ERR;" else {
                if (!remoteOutputsPaused && !followsVibration) controller.suctionInput(value, now())
                "OK;"
            }
        }
        "stop", "stopdevice", "poweroff" -> { controller.stopAll(); "OK;" }
        else -> "ERR;"
    }

    private fun disconnect() {
        session++
        socket?.close(1000, "Bridge stopped")
        socket = null
        wsStatus = "Disconnected"
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
            .setContentText(if (controller.suctionEnabled) "Succión habilitada" else "Solo vibración")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(action(if (controller.suctionEnabled) "Desactivar succión" else "Habilitar succión", 1, ACTION_TOGGLE_SUCTION))
            .addAction(action("Parar", 0, ACTION_STOP)).setOngoing(true).build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun publish(log: String = "", force: Boolean = false) {
        val snapshot = "$wsStatus|$bleStatus|${controller.vibrationInput}|${controller.suctionLevel}|${controller.phase}|${controller.stopReason}|${controller.suctionEnabled}|$running|$remoteOutputsPaused"
        if (!force && log.isEmpty() && snapshot == lastSnapshot) return
        lastSnapshot = snapshot
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_WS_STATUS, wsStatus).putExtra(EXTRA_BLE_STATUS, bleStatus)
            .putExtra(EXTRA_LEVEL, controller.vibrationLevel)
            .putExtra(EXTRA_VIBRATION_LEVEL, controller.vibrationInput)
            .putExtra(EXTRA_ROTATION_LEVEL, controller.suctionLevel)
            .putExtra(EXTRA_SUCTION_ENABLED, controller.suctionEnabled)
            .putExtra(EXTRA_REMOTE_PAUSED, remoteOutputsPaused)
            .putExtra(EXTRA_PHASE, controller.phase.name).putExtra(EXTRA_RUNNING, running)
            .putExtra(EXTRA_STOP_REASON, when (controller.stopReason) {
                ActuationController.StopReason.NONE -> "—"
                ActuationController.StopReason.PULSE_LIMIT -> "Tiempo de pulso cumplido"
                ActuationController.StopReason.INPUT_TIMEOUT -> "Sin valor nuevo durante 1,2 s"
                ActuationController.StopReason.ZERO_COMMAND -> "Orden Rotate:0"
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
        const val EXTRA_FOLLOW_VIBRATION = "follow_vibration"
        const val EXTRA_THREE_LEVELS = "three_levels"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_STOP_REASON = "stop_reason"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_REMOTE_PAUSED = "remote_paused"
        private const val CHANNEL_ID = "ls_bridge_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
