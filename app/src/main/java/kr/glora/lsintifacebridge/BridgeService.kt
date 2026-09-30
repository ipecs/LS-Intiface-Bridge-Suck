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

        // CANAL 1: VIBRACIÓN
        private val CMD_CH1_STOP = byteArrayOf(0xD5.toByte(), 0x96.toByte(), 0x4C.toByte())
        private val CMD_CH1_L1   = byteArrayOf(0xD4.toByte(), 0x1F.toByte(), 0x5D.toByte())
        private val CMD_CH1_L2   = byteArrayOf(0xD7.toByte(), 0x84.toByte(), 0x6F.toByte())
        private val CMD_CH1_L3   = byteArrayOf(0xD6.toByte(), 0x0D.toByte(), 0x7E.toByte())

        // CANAL 2: SUCCIÓN
        private val CMD_CH2_STOP = byteArrayOf(0xA5.toByte(), 0x11.toByte(), 0x3F.toByte())
        private val CMD_CH2_L1   = byteArrayOf(0xA4.toByte(), 0x98.toByte(), 0x2E.toByte())
        private val CMD_CH2_L2   = byteArrayOf(0xA7.toByte(), 0x03.toByte(), 0x1C.toByte())
        private val CMD_CH2_L3   = byteArrayOf(0xA6.toByte(), 0x8A.toByte(), 0x0D.toByte())

        // ================= PARÁMETROS DE SUCCIÓN =================
        private const val SUCTION_MIN_PEAK = 8

        private fun baseSuckMs(level: Int): Long = when (level) {
            3 -> 2000L
            2 -> 1500L
            else -> 1200L
        }

        private const val SUCK_MAX_MS = 6000L
        private const val VENT_MS = 700L
        private const val SUCK_EXTEND_STEP_MS = 300L

        // ================= CADENCIAS INDEPENDIENTES =================
        // Objetivo de cadencia por canal (ms). Menor = más paquetes.
        private const val CH1_TARGET_MS = 70L   // Vibración (imperceptible)
        private const val CH2_TARGET_MS = 40L   // Succión (más paquetes para la bomba)

        // Tick del loop de scheduling
        private const val TICK_MS = 15L
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var webSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentWsStatus: String = "Disconnected"
    private var currentBleStatus: String = "Ready"

    // Vibración
    private var currentVibrationLevel = 0
    private var currentFunscriptInput = 0
    private var lastSentCmd: ByteArray? = null

    private var lastPacketReceivedTime = 0L
    private val VIDEO_PAUSE_TIMEOUT_MS = 1200L

    private var isSuctionEnabled = true

    // Succión: máquina de estados (0 = IDLE, 1 = SUCK, 2 = VENT)
    private var suctionState = 0
    private var suctionTimerStart = 0L
    private var suctionLevel = 0
    private var suctionSuckMs = 1200L
    private var suctionExtendAccum = 0L
    private var lastExtendCheckTime = 0L

    // Scheduler independiente
    private var lastCh1Tx = 0L
    private var lastCh2Tx = 0L

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val loopRunnable = object : Runnable {
        override fun run() {
            manageHardware()
            handler.postDelayed(this, TICK_MS)
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
                transmitBle(CMD_CH1_STOP, force = true)
                transmitBle(CMD_CH2_STOP, force = true)
                sendStatusUpdate(log = "Puente listo (CH1=${CH1_TARGET_MS}ms / CH2=${CH2_TARGET_MS}ms)")
            }
            ACTION_STOP -> {
                stopBridge()
                stopSelf()
            }
            ACTION_TOGGLE_SUCTION -> {
                isSuctionEnabled = !isSuctionEnabled
                if (!isSuctionEnabled) {
                    suctionState = 0
                    suctionLevel = 0
                    transmitBle(CMD_CH2_STOP, force = true)
                }
                startForegroundNotification()
                sendStatusUpdate(log = if (isSuctionEnabled) "Succión: ON" else "Succión: OFF")
            }
            ACTION_TEST_LEVEL -> {
                if (intent.hasExtra(EXTRA_VIBRATION_LEVEL)) {
                    val lvl = intent.getIntExtra(EXTRA_VIBRATION_LEVEL, 0).coerceIn(0, 20)
                    processInput(lvl)
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
            .setContentText(if (isSuctionEnabled) "CH1=${CH1_TARGET_MS}ms / CH2=${CH2_TARGET_MS}ms" else "Solo Vibración")
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
                cmd.startsWith("Vibrate:", ignoreCase = true) ||
                cmd.startsWith("Vibrate1:", ignoreCase = true) -> {
                    val rawValue = (cmd.substringAfter(":").trim().toIntOrNull() ?: 0).coerceIn(0, 20)
                    processInput(rawValue)
                }
                cmd.equals("Stop", ignoreCase = true) -> {
                    currentVibrationLevel = 0
                    currentFunscriptInput = 0
                    suctionState = 0
                    suctionLevel = 0
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

        // ===== VIBRACIÓN (idéntica a tu versión que te gusta) =====
        val newVibeLevel = when {
            rawValue >= 10   -> 3
            rawValue in 5..9 -> 2
            else             -> 0
        }

        if (newVibeLevel != currentVibrationLevel) {
            currentVibrationLevel = newVibeLevel
            applyVibrationHardware(force = true)
            sendStatusUpdate(log = if (newVibeLevel > 0) "Vib: Nivel $newVibeLevel" else "Vib: 0")
        }

        // ===== SUCCIÓN =====
        if (!isSuctionEnabled) return

        if (suctionState == 0) {
            val lvl = levelFromPeak(rawValue)
            if (lvl > 0) {
                startSuctionPulse(now, lvl)
            }
        }
    }

    private fun levelFromPeak(peak: Int): Int = when {
        peak >= SUCTION_MIN_PEAK -> 1
        else -> 0
    }

    private fun startSuctionPulse(now: Long, level: Int) {
        suctionState = 1
        suctionTimerStart = now
        suctionLevel = 1
        suctionSuckMs = baseSuckMs(level)
        suctionExtendAccum = 0L
        lastExtendCheckTime = now
        applySuctionHardware(force = true)
        sendStatusUpdate(log = "Suct: inicio ${suctionSuckMs}ms")
    }

    private fun manageHardware() {
        val now = System.currentTimeMillis()

        // 1. Parada si el vídeo se pausó
        if ((currentVibrationLevel > 0 || suctionLevel > 0 || suctionState != 0) &&
            (now - lastPacketReceivedTime > VIDEO_PAUSE_TIMEOUT_MS)) {
            currentVibrationLevel = 0
            suctionLevel = 0
            suctionState = 0
            currentFunscriptInput = 0
            transmitBle(CMD_CH1_STOP, force = true)
            transmitBle(CMD_CH2_STOP, force = true)
            sendStatusUpdate(log = "Vídeo pausado -> Motores detenidos")
            return
        }

        // 2. Máquina de estados de la succión
        when (suctionState) {
            1 -> { // SUCK
                if (now - lastExtendCheckTime >= SUCK_EXTEND_STEP_MS) {
                    lastExtendCheckTime = now
                    if (currentFunscriptInput >= SUCTION_MIN_PEAK &&
                        suctionExtendAccum + SUCK_EXTEND_STEP_MS <= SUCK_MAX_MS) {
                        suctionSuckMs += SUCK_EXTEND_STEP_MS
                        suctionExtendAccum += SUCK_EXTEND_STEP_MS
                        sendStatusUpdate(log = "Suct: extendido a ${suctionSuckMs}ms")
                    }
                }

                if (now - suctionTimerStart >= suctionSuckMs) {
                    suctionState = 2
                    suctionTimerStart = now
                    suctionLevel = 0
                    sendStatusUpdate(log = "Suct: venteo ${VENT_MS}ms")
                }
            }
            2 -> { // VENT
                if (currentFunscriptInput >= SUCTION_MIN_PEAK && now - suctionTimerStart >= 150L) {
                    startSuctionPulse(now, levelFromPeak(currentFunscriptInput))
                } else if (now - suctionTimerStart >= VENT_MS) {
                    suctionState = 0
                }
            }
        }

        // 3. Scheduler con cadencias independientes
        val ch1Overdue = now - lastCh1Tx
        val ch2Overdue = now - lastCh2Tx

        val wantCh1 = ch1Overdue >= CH1_TARGET_MS
        val wantCh2 = (suctionState != 0) && ch2Overdue >= CH2_TARGET_MS

        if (wantCh2 && wantCh1) {
            // Ambos vencidos: comparamos cuánto de "vencidos" están en proporción
            // a su cadencia objetivo. El más retrasado proporcionalmente va primero.
            if (ch2Overdue * CH1_TARGET_MS >= ch1Overdue * CH2_TARGET_MS) {
                if (suctionState == 1) applySuctionHardware() else transmitBle(CMD_CH2_STOP)
                lastCh2Tx = now
            } else {
                applyVibrationHardware()
                lastCh1Tx = now
            }
        } else if (wantCh2) {
            if (suctionState == 1) applySuctionHardware() else transmitBle(CMD_CH2_STOP)
            lastCh2Tx = now
        } else if (wantCh1) {
            applyVibrationHardware()
            lastCh1Tx = now
        }
    }

    private fun applyVibrationHardware(force: Boolean = false) {
        val cmd = when (currentVibrationLevel) {
            2 -> CMD_CH1_L2
            3 -> CMD_CH1_L3
            else -> CMD_CH1_STOP
        }
        transmitBle(cmd, force)
    }

    private fun applySuctionHardware(force: Boolean = false) {
        val cmd = if (suctionLevel > 0) CMD_CH2_L1 else CMD_CH2_STOP
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
            putExtra(EXTRA_ROTATION_LEVEL, if (suctionState == 1) 1 else 0)
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
