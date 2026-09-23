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
import java.util.concurrent.TimeUnit

class BridgeService : Service() {

    companion object {
        const val ACTION_START = "kr.glora.lsintifacebridge.ACTION_START"
        const val ACTION_STOP = "kr.glora.lsintifacebridge.ACTION_STOP"
        const val ACTION_STATUS = "kr.glora.lsintifacebridge.ACTION_STATUS"
        const val ACTION_TEST_LEVEL = "kr.glora.lsintifacebridge.ACTION_TEST_LEVEL"

        const val EXTRA_WS_URL = "extra_ws_url"
        const val EXTRA_WS_STATUS = "extra_ws_status"
        const val EXTRA_BLE_STATUS = "extra_ble_status"
        const val EXTRA_LEVEL = "extra_level"
        const val EXTRA_VIBRATION_LEVEL = "extra_vibration_level"
        const val EXTRA_ROTATION_LEVEL = "extra_rotation_level"
        const val EXTRA_LOG = "extra_log"

        private const val CHANNEL_ID = "ls_bridge_channel"
        private const val NOTIFICATION_ID = 1001

        // Prefijo BroadLink Fastcon para LoveSpouse / MuSe
        private val PREFIX = byteArrayOf(
            0x6D.toByte(), 0xB6.toByte(), 0x43.toByte(), 0xCE.toByte(),
            0x97.toByte(), 0xFE.toByte(), 0x42.toByte(), 0x7C.toByte()
        )

        // Parada total
        private val CMD_ALL_STOP = byteArrayOf(0xE5.toByte(), 0x15.toByte(), 0x7D.toByte())

        // CANAL 1: VIBRACIÓN (100% continuo, sin ráfagas ni códigos 0xE0B82A)
        private val CMD_CH1_STOP = byteArrayOf(0xD5.toByte(), 0x96.toByte(), 0x4C.toByte())
        private val CMD_CH1_L1   = byteArrayOf(0xD4.toByte(), 0x1F.toByte(), 0x5D.toByte())
        private val CMD_CH1_L2   = byteArrayOf(0xD7.toByte(), 0x84.toByte(), 0x6F.toByte())
        private val CMD_CH1_L3   = byteArrayOf(0xD6.toByte(), 0x0D.toByte(), 0x7E.toByte())

        // CANAL 2: SUCCIÓN / PRESIÓN
        private val CMD_CH2_STOP = byteArrayOf(0xA5.toByte(), 0x11.toByte(), 0x3F.toByte())
        private val CMD_CH2_L1   = byteArrayOf(0xA4.toByte(), 0x98.toByte(), 0x2E.toByte())
        private val CMD_CH2_L2   = byteArrayOf(0xA7.toByte(), 0x03.toByte(), 0x1C.toByte())
        private val CMD_CH2_L3   = byteArrayOf(0xA6.toByte(), 0x8A.toByte(), 0x0D.toByte())
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var webSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentWsStatus: String = "Disconnected"
    private var currentBleStatus: String = "Ready"

    // Niveles de potencia (0 a 20 estilo Lovense)
    private var currentVibrationLevel = 0
    private var currentRotationLevel = 0

    // Temporizador de venteo / seguridad (Anti-Burst Pacer)
    private var isVenting = false
    private var lastCycleSwitchTime = 0L
    private val SUCK_DURATION_MS = 1000L // Máximo 1.0 s de succión por ciclo
    private val VENT_DURATION_MS = 800L  // 0.8 s de apertura de válvula obligatoria

    private var channelToggle = false // Alternar paquetes BLE entre Canal 1 y Canal 2

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    // Bucle BLE cada 50ms para evitar el watchdog de 250ms del hardware
    private val broadcastRunnable = object : Runnable {
        override fun run() {
            sendHardwareCycle()
            handler.postDelayed(this, 50)
        }
    }

    private val bleCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            currentBleStatus = "Advertising Active"
        }

        override fun onStartFailure(errorCode: Int) {
            currentBleStatus = "BLE Error: $errorCode"
            sendStatusUpdate(log = "BLE Advertise failure: $errorCode")
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
                handler.removeCallbacks(broadcastRunnable)
                handler.post(broadcastRunnable)
                sendStatusUpdate(log = "Bridge iniciado hacia $url")
            }
            ACTION_STOP -> {
                stopBridge()
                stopSelf()
            }
            ACTION_TEST_LEVEL -> {
                if (intent.hasExtra(EXTRA_VIBRATION_LEVEL)) {
                    currentVibrationLevel = intent.getIntExtra(EXTRA_VIBRATION_LEVEL, 0).coerceIn(0, 20)
                }
                if (intent.hasExtra(EXTRA_ROTATION_LEVEL)) {
                    currentRotationLevel = intent.getIntExtra(EXTRA_ROTATION_LEVEL, 0).coerceIn(0, 20)
                }
                sendStatusUpdate(log = "Manual: Vibe=$currentVibrationLevel, Suct=$currentRotationLevel")
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

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LS Intiface Bridge")
            .setContentText("Transfiriendo comandos sin ráfagas")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .addAction(Notification.Action.Builder(null, "Stop", stopPending).build())
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun connectWebSocket(url: String) {
        disconnectWebSocket()
        currentWsStatus = "Connecting..."
        sendStatusUpdate(log = "Conectando a Intiface: $url")

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
                sendStatusUpdate(log = "Conectado a Intiface Central")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleLovenseMessage(text)
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

    private fun handleLovenseMessage(text: String) {
        val parts = text.split(";")
        for (part in parts) {
            val cmd = part.trim()
            if (cmd.isEmpty()) continue

            when {
                cmd.equals("DeviceType", ignoreCase = true) -> {
                    // Reportar como Lovense Edge (P:11) para habilitar 2 motores independientes
                    webSocket?.send("P:11:001122334455\r\n")
                }
                cmd.equals("Battery", ignoreCase = true) -> {
                    webSocket?.send("90;\r\n")
                }
                cmd.startsWith("Vibrate:", ignoreCase = true) -> {
                    val value = cmd.substringAfter(":").toIntOrNull() ?: 0
                    currentVibrationLevel = value.coerceIn(0, 20)
                    sendStatusUpdate()
                }
                cmd.startsWith("Vibrate1:", ignoreCase = true) -> {
                    val value = cmd.substringAfter(":").toIntOrNull() ?: 0
                    currentVibrationLevel = value.coerceIn(0, 20)
                    sendStatusUpdate()
                }
                cmd.startsWith("Vibrate2:", ignoreCase = true) -> {
                    val value = cmd.substringAfter(":").toIntOrNull() ?: 0
                    currentRotationLevel = value.coerceIn(0, 20)
                    sendStatusUpdate()
                }
                cmd.startsWith("Rotate:", ignoreCase = true) -> {
                    val value = cmd.substringAfter(":").toIntOrNull() ?: 0
                    currentRotationLevel = value.coerceIn(0, 20)
                    sendStatusUpdate()
                }
                cmd.equals("Stop", ignoreCase = true) -> {
                    currentVibrationLevel = 0
                    currentRotationLevel = 0
                    sendStatusUpdate()
                }
            }
        }
    }

    private fun sendHardwareCycle() {
        val now = System.currentTimeMillis()

        // 1. Comando Canal 1 (Vibración)
        val ch1Cmd = when {
            currentVibrationLevel == 0 -> CMD_CH1_STOP
            currentVibrationLevel in 1..6 -> CMD_CH1_L1
            currentVibrationLevel in 7..13 -> CMD_CH1_L2
            else -> CMD_CH1_L3
        }

        // 2. Comando Canal 2 (Succión con Anti-Burst y Venteo)
        val ch2Cmd: ByteArray = if (currentRotationLevel == 0) {
            isVenting = false
            lastCycleSwitchTime = now
            CMD_CH2_STOP
        } else {
            if (isVenting) {
                if (now - lastCycleSwitchTime >= VENT_DURATION_MS) {
                    isVenting = false
                    lastCycleSwitchTime = now
                }
                CMD_CH2_STOP // Válvula abierta, aire entrando
            } else {
                if (now - lastCycleSwitchTime >= SUCK_DURATION_MS) {
                    isVenting = true
                    lastCycleSwitchTime = now
                    CMD_CH2_STOP
                } else {
                    when {
                        currentRotationLevel in 1..7 -> CMD_CH2_L1
                        currentRotationLevel in 8..14 -> CMD_CH2_L2
                        else -> CMD_CH2_L3
                    }
                }
            }
        }

        // Si ambos están en 0, mantener parada general
        if (currentVibrationLevel == 0 && currentRotationLevel == 0) {
            transmitBle(CMD_ALL_STOP)
            return
        }

        // Alternar la emisión de paquetes en cada ciclo de 50ms
        channelToggle = !channelToggle
        if (channelToggle) {
            transmitBle(ch1Cmd)
        } else {
            transmitBle(ch2Cmd)
        }
    }

    private fun transmitBle(suffix: ByteArray) {
        val adv = advertiser ?: return
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
        } catch (e: Exception) {
            // Manejo de reinicio rápido en el stack BLE
        }
    }

    private fun sendStatusUpdate(log: String? = null) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_WS_STATUS, currentWsStatus)
            putExtra(EXTRA_BLE_STATUS, currentBleStatus)
            putExtra(EXTRA_LEVEL, currentVibrationLevel)
            putExtra(EXTRA_VIBRATION_LEVEL, currentVibrationLevel)
            putExtra(EXTRA_ROTATION_LEVEL, currentRotationLevel)
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
        handler.removeCallbacks(broadcastRunnable)
        disconnectWebSocket()
        transmitBle(CMD_ALL_STOP)
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
