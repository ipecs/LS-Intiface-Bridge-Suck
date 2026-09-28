package kr.glora.lsintifacebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.TimeUnit

class BridgeService : Service() {

    companion object {
        const val ACTION_START = "kr.glora.lsintifacebridge.ACTION_START"
        const val ACTION_STOP = "kr.glora.lsintifacebridge.ACTION_STOP"
        const val ACTION_STATUS = "kr.glora.lsintifacebridge.ACTION_STATUS"
        const val ACTION_TEST_LEVEL = "kr.glora.lsintifacebridge.ACTION_TEST_LEVEL"
        const val ACTION_TOGGLE_SUCTION = "kr.glora.lsintifacebridge.ACTION_TOGGLE_SUCTION"

        const val EXTRA_WS_URL = "extra_ws_url"
        const val EXTRA_WS_STATUS = "extra_ws_status"
        const val EXTRA_BLE_STATUS = "extra_ble_status"
        const val EXTRA_LEVEL = "extra_level"
        const val EXTRA_VIBRATION_LEVEL = "extra_vibration_level"
        const val EXTRA_ROTATION_LEVEL = "extra_rotation_level"
        const val EXTRA_LOG = "extra_log"

        private const val CHANNEL_ID = "ls_bridge_channel"
        private const val NOTIFICATION_ID = 1001

        private val PREFIX = byteArrayOf(
            0x6D.toByte(), 0xB6.toByte(), 0x43.toByte(), 0xCE.toByte(),
            0x97.toByte(), 0xFE.toByte(), 0x42.toByte(), 0x7C.toByte()
        )

        // CANAL 1: VIBRACIÓN (3 velocidades físicas)
        private val CMD_CH1_STOP = byteArrayOf(0xD5.toByte(), 0x96.toByte(), 0x4C.toByte())
        private val CMD_CH1_L1   = byteArrayOf(0xD4.toByte(), 0x1F.toByte(), 0x5D.toByte())
        private val CMD_CH1_L2   = byteArrayOf(0xD7.toByte(), 0x84.toByte(), 0x6F.toByte())
        private val CMD_CH1_L3   = byteArrayOf(0xD6.toByte(), 0x0D.toByte(), 0x7E.toByte())

        // CANAL 2: SUCCIÓN DINÁMICA (3 velocidades físicas reales)
        private val CMD_CH2_STOP = byteArrayOf(0xA5.toByte(), 0x11.toByte(), 0x3F.toByte())
        private val CMD_CH2_L1   = byteArrayOf(0xA4.toByte(), 0x98.toByte(), 0x2E.toByte()) // Succión suave
        private val CMD_CH2_L2   = byteArrayOf(0xA7.toByte(), 0x03.toByte(), 0x1C.toByte()) // Succión media
        private val CMD_CH2_L3   = byteArrayOf(0xA6.toByte(), 0x8A.toByte(), 0x0D.toByte()) // ¡Succión máxima!
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var webSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentWsStatus: String = "Disconnected"
    private var currentBleStatus: String = "Ready"

    // Estados de hardware independientes (0 = apagado, 1..3 = velocidad)
    private var currentVibrationLevel = 0
    private var currentSuctionLevel = 0
    private var currentFunscriptInput = 0
    private var lastSentCmd: ByteArray? = null

    private var lastPacketReceivedTime = 0L
    private val VIDEO_PAUSE_TIMEOUT_MS = 1200L

    // Toggle y seguridad de succión
    private var isSuctionEnabled = true
    private var suctionStartTime = 0L
    private var suctionLockedUntilZero = false
    private val MAX_SUCTION_TIME_MS = 3000L // 3s continuos máx antes de forzar descompresión

    // Alternancia de radio BLE equilibrada
    private var lastRfCycleTime = 0L
    private val RF_CYCLE_INTERVAL_MS = 80L
    private var channelToggle = false

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val loopRunnable = object : Runnable {
        override fun run() {
            manageHardware()
            handler.postDelayed(this, 30)
        }
    }

    private val bleCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            currentBleStatus = "Advertising Active"
        }
        override fun onStartFailure(errorCode: Int) {
            currentBleStatus = "BLE Error: $errorCode"
            sendStatusUpdate(log = "BLE Error: $errorCode")
        }
    }

    override fun onCreate() {
        super.onCreate()
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        advertiser = btManager?.adapter?.bluetoothLeAdvertiser
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_WS_URL) ?: return START_NOT_STICKY
                startForegroundNotification()
                connectWebSocket(url)
                handler.removeCallbacks(loopRunnable)
                handler.post(loopRunnable)
                transmitBle(CMD_CH2_STOP, force = true)
                transmitBle(CMD_CH1_STOP, force = true)
                sendStatusUpdate(log = "Puente listo (Vib + Succión 1:1)")
            }
            ACTION_STOP -> {
                stopBridge()
                stopSelf()
            }
            ACTION_TOGGLE_SUCTION -> {
                isSuctionEnabled = !isSuctionEnabled
                if (!isSuctionEnabled && currentSuctionLevel > 0) {
                    currentSuctionLevel = 0
                    suctionLockedUntilZero = false
                    transmitBle(CMD_CH2_STOP, force = true)
                }
                startForegroundNotification()
                sendStatusUpdate(log = if (isSuctionEnabled) "Succión: ON" else "Succión: OFF (Solo Vib)")
            }
            ACTION_TEST_LEVEL -> {
                if (intent.hasExtra(EXTRA_VIBRATION_LEVEL)) {
                    val lvl = intent.getIntExtra(EXTRA_VIBRATION_LEVEL, 0).coerceIn(0, 20)
                    processInput(lvl)
                }
                if (intent.hasExtra(EXTRA_ROTATION_LEVEL)) {
                    val rot = intent.getIntExtra(EXTRA_ROTATION_LEVEL, 0)
                    val prev = isSuctionEnabled
                    isSuctionEnabled = (rot > 0)
                    if (prev != isSuctionEnabled) {
                        if (!isSuctionEnabled && currentSuctionLevel > 0) {
                            currentSuctionLevel = 0
                            transmitBle(CMD_CH2_STOP, force = true)
                        }
                        startForegroundNotification()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LS Intiface Bridge Service",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val stopIntent = Intent(this, BridgeService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = Intent(this, BridgeService::class.java).apply { action = ACTION_TOGGLE_SUCTION }
        val togglePending = PendingIntent.getService(
            this, 1, toggleIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val suctionBtnLabel = if (isSuctionEnabled) "Succión: ON" else "Succión: OFF"

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LS Intiface Bridge")
            .setContentText(if (isSuctionEnabled) "Vibración + Succión Dinámica 1:1" else "Modo Solo Vibración")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .addAction(Notification.Action.Builder(null, suctionBtnLabel, togglePending).build())
            .addAction(Notification.Action.Builder(null, "Stop", stopPending).build())
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun connectWebSocket(url: String) {
        disconnectWebSocket()
        currentWsStatus = "Connecting..."
        sendStatusUpdate(log = "Conectando a: $url")

        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            currentWsStatus = "URL Inválida"
            sendStatusUpdate(log = "Error de URL: ${e.message}")
            return
        }

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                currentWsStatus = "Connected"
                sendStatusUpdate(log = "Conectado. Handshake OK")
                ws.send("{\"identifier\": \"LVSDevice\", \"address\": \"001122334455\", \"version\": 0}")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleLovenseMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleLovenseMessage(bytes.utf8())
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                currentWsStatus = "Disconnected"
                sendStatusUpdate(log = "WebSocket cerrado: $reason")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                currentWsStatus = "Error"
                sendStatusUpdate(log = "Fallo de conexión: ${t.message}")
            }
        })
    }

    private fun sendLovenseResponse(msg: String) {
        val byteString = msg.encodeUtf8()
        webSocket?.send(byteString)
    }

    private fun handleLovenseMessage(text: String) {
        val parts = text.split(";")
        for (part in parts) {
            val cmd = part.trim()
            if (cmd.isEmpty()) continue

            when {
                cmd.equals("DeviceType", ignoreCase = true) -> {
                    sendLovenseResponse("P:11:001122334455;\r\n")
                }
                cmd.equals("Battery", ignoreCase = true) -> {
                    sendLovenseResponse("90;\r\n")
                }
                cmd.startsWith("Vibrate:", ignoreCase = true) || cmd.startsWith("Vibrate1:", ignoreCase = true) -> {
                    val rawValue = (cmd.substringAfter(":").trim().toIntOrNull() ?: 0).coerceIn(0, 20)
                    processInput(rawValue)
                }
                cmd.equals("Stop", ignoreCase = true) -> {
                    currentVibrationLevel = 0
                    currentSuctionLevel = 0
                    currentFunscriptInput = 0
                    suctionLockedUntilZero = false
                    transmitBle(CMD_CH1_STOP, force = true)
                    transmitBle(CMD_CH2_STOP, force = true)
                    sendStatusUpdate(log = "Stop")
                }
            }
        }
    }

    private fun processInput(rawValue: Int) {
        val now = System.currentTimeMillis()
        lastPacketReceivedTime = now
        currentFunscriptInput = rawValue

        // 1. ESCALADO DINÁMICO DE VIBRACIÓN (Canal 1):
        currentVibrationLevel = when {
            rawValue in 1..4 -> 1   // Marcha continua suave en valles
            rawValue in 5..9 -> 2   // Media
            rawValue >= 10   -> 3   // Máxima potencia en picos
            else -> 1               // Piso mínimo mientras el vídeo reproduzca
        }

        // 2. ESCALADO DINÁMICO DE SUCCIÓN QUE SIGUE EL FUNSCRIPT (Canal 2):
        if (!isSuctionEnabled) {
            if (currentSuctionLevel > 0) {
                currentSuctionLevel = 0
                transmitBle(CMD_CH2_STOP, force = true)
            }
            return
        }

        if (rawValue > 0) {
            if (!suctionLockedUntilZero) {
                if (currentSuctionLevel == 0) {
                    suctionStartTime = now // Inicio de un nuevo trazo de succión
                }
                // La potencia de vacío cambia en vivo según la altura del Funscript:
                val newLevel = when {
                    rawValue in 1..5  -> 1  // Nivel 1: Succión suave
                    rawValue in 6..12 -> 2  // Nivel 2: Succión media
                    else              -> 3  // Nivel 3: ¡Vacío profundo en picos climáticos!
                }
                if (newLevel != currentSuctionLevel) {
                    currentSuctionLevel = newLevel
                    applySuctionHardware(force = true)
                    sendStatusUpdate(log = "Suct: Nivel $newLevel (Funscript: $rawValue)")
                }
            }
        } else {
            // EN CUANTO EL FUNSCRIPT TOCA CERO:
            suctionLockedUntilZero = false // Se libera el cerrojo de seguridad
            if (currentSuctionLevel > 0) {
                currentSuctionLevel = 0
                transmitBle(CMD_CH2_STOP, force = true) // Válvula abierta, aire entra
                sendStatusUpdate(log = "Suct: 0 (Válvula liberada)")
            }
        }
    }

    private fun manageHardware() {
        val now = System.currentTimeMillis()

        // 1. Pausa o parada total si el vídeo se detiene (más de 1.2s sin paquetes)
        if (currentVibrationLevel > 0 && (now - lastPacketReceivedTime > VIDEO_PAUSE_TIMEOUT_MS)) {
            currentVibrationLevel = 0
            currentSuctionLevel = 0
            currentFunscriptInput = 0
            suctionLockedUntilZero = false
            transmitBle(CMD_CH1_STOP, force = true)
            transmitBle(CMD_CH2_STOP, force = true)
            sendStatusUpdate(log = "Vídeo pausado -> Motores detenidos")
            return
        }

        // 2. Tope de seguridad de 3 segundos continuos sin tocar cero
        if (currentSuctionLevel > 0 && (now - suctionStartTime >= MAX_SUCTION_TIME_MS)) {
            currentSuctionLevel = 0
            suctionLockedUntilZero = true // Bloquea hasta que el script baje a 0 de verdad
            transmitBle(CMD_CH2_STOP, force = true)
            sendStatusUpdate(log = "Seguridad: 3s alcanzados -> Válvula abierta (esperando 0)")
        }

        // 3. Emisión equilibrada de radio BLE para alimentar ambos motores
        if (now - lastRfCycleTime >= RF_CYCLE_INTERVAL_MS) {
            lastRfCycleTime = now

            if (currentSuctionLevel > 0) {
                // Durante la succión activa, alternamos paquetes cada 80ms para refrescar ambos PWM
                channelToggle = !channelToggle
                if (channelToggle) {
                    applyVibrationHardware()
                } else {
                    applySuctionHardware()
                }
            } else {
                // Cuando no hay succión, el 100% de la radio se dedica a la vibración continua
                applyVibrationHardware()
            }
        }
    }

    private fun applyVibrationHardware(force: Boolean = false) {
        val cmd = when (currentVibrationLevel) {
            1 -> CMD_CH1_L1
            2 -> CMD_CH1_L2
            3 -> CMD_CH1_L3
            else -> CMD_CH1_STOP
        }
        transmitBle(cmd, force)
    }

    private fun applySuctionHardware(force: Boolean = false) {
        val cmd = when (currentSuctionLevel) {
            1 -> CMD_CH2_L1
            2 -> CMD_CH2_L2
            3 -> CMD_CH2_L3
            else -> CMD_CH2_STOP
        }
        transmitBle(cmd, force)
    }

    private fun transmitBle(suffix: ByteArray, force: Boolean = false) {
        val adv = advertiser ?: return

        if (!force && lastSentCmd != null && lastSentCmd!!.contentEquals(suffix)) {
            return
        }
        lastSentCmd = suffix.clone()

        val payload = ByteArray(11)
        System.arraycopy(PREFIX, 0, payload, 0, 8)
        System.arraycopy(suffix, 0, payload, 8, 3)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(0xFFF0, payload)
            .build()

        try {
            adv.stopAdvertising(bleCallback)
            adv.startAdvertising(settings, data, bleCallback)
        } catch (ignored: Exception) {}
    }

    private fun sendStatusUpdate(log: String? = null) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_WS_STATUS, currentWsStatus)
            putExtra(EXTRA_BLE_STATUS, currentBleStatus)
            putExtra(EXTRA_LEVEL, currentVibrationLevel)
            putExtra(EXTRA_VIBRATION_LEVEL, currentVibrationLevel)
            putExtra(EXTRA_ROTATION_LEVEL, currentSuctionLevel)
            putExtra(EXTRA_LOG, log ?: "")
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun disconnectWebSocket() {
        try {
            webSocket?.close(1000, "Normal closure")
        } catch (ignored: Exception) {}
        webSocket = null
    }

    private fun stopBridge() {
        handler.removeCallbacks(loopRunnable)
        disconnectWebSocket()
        transmitBle(CMD_CH1_STOP, force = true)
        transmitBle(CMD_CH2_STOP, force = true)
        try {
            advertiser?.stopAdvertising(bleCallback)
        } catch (ignored: Exception) {}
        currentWsStatus = "Disconnected"
        currentBleStatus = "Stopped"
        sendStatusUpdate(log = "Bridge detenido")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBridge()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
