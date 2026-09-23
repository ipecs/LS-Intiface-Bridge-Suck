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

        // CANAL 1: VIBRACIÓN (Puro continuo sin saltos)
        private val CMD_CH1_STOP = byteArrayOf(0xD5.toByte(), 0x96.toByte(), 0x4C.toByte())
        private val CMD_CH1_L1   = byteArrayOf(0xD4.toByte(), 0x1F.toByte(), 0x5D.toByte())
        private val CMD_CH1_L2   = byteArrayOf(0xD7.toByte(), 0x84.toByte(), 0x6F.toByte())
        private val CMD_CH1_L3   = byteArrayOf(0xD6.toByte(), 0x0D.toByte(), 0x7E.toByte())

        // CANAL 2: SUCCIÓN
        private val CMD_CH2_STOP = byteArrayOf(0xA5.toByte(), 0x11.toByte(), 0x3F.toByte())
        private val CMD_CH2_L1   = byteArrayOf(0xA4.toByte(), 0x98.toByte(), 0x2E.toByte())
    }

    private enum class SuctionState {
        IDLE,             // Esperando a que el script envíe una señal
        SUCKING,          // Succión activa (bloquea ráfagas intermedias)
        LOCKOUT_VENTING   // Válvula abierta soltando aire (bloquea nuevas señales)
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var webSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentWsStatus: String = "Disconnected"
    private var currentBleStatus: String = "Ready"

    private var currentVibrationLevel = 0
    private var currentFunscriptInput = 0 // Guarda el valor exacto actual del Funscript
    private var lastSentCmd: ByteArray? = null

    // Inercia de vibración continua
    private var lastNonZeroTime = 0L
    private val ZERO_HOLD_MS = 250L

    // Cerrojo y temporizadores de succión
    private var suctionState = SuctionState.IDLE
    private var suctionTimerStart = 0L
    private val SUCK_PULSE_MS = 700L       // 0.7 segundos de succión máxima por pulso
    private val LOCKOUT_VENT_MS = 700L     // 0.7 segundos de despresurización obligatoria

    private var suctionToggle = false

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    // Bucle gestor de estados (cada 40ms)
    private val loopRunnable = object : Runnable {
        override fun run() {
            manageStateTransitions()
            handler.postDelayed(this, 40)
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
                applyVibrationHardware(force = true)
                sendStatusUpdate(log = "Puente activo: Vib continua + Succión protegida")
            }
            ACTION_STOP -> {
                stopBridge()
                stopSelf()
            }
            ACTION_TEST_LEVEL -> {
                if (intent.hasExtra(EXTRA_VIBRATION_LEVEL)) {
                    val lvl = intent.getIntExtra(EXTRA_VIBRATION_LEVEL, 0).coerceIn(0, 20)
                    currentVibrationLevel = lvl
                    currentFunscriptInput = lvl
                    if (lvl > 0) lastNonZeroTime = System.currentTimeMillis()
                }
                if (intent.hasExtra(EXTRA_ROTATION_LEVEL)) {
                    val rot = intent.getIntExtra(EXTRA_ROTATION_LEVEL, 0)
                    if (rot > 0 && suctionState == SuctionState.IDLE) {
                        startSuctionCycle(System.currentTimeMillis())
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

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LS Intiface Bridge")
            .setContentText("Vibración + Succión con Cerrojo Anti-Dolor")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
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
                    val now = System.currentTimeMillis()
                    currentFunscriptInput = rawValue

                    // 1. Manejo de Vibración (Inercia suave continua)
                    if (rawValue > 0) {
                        lastNonZeroTime = now
                        currentVibrationLevel = rawValue
                    } else {
                        if (now - lastNonZeroTime > ZERO_HOLD_MS) {
                            currentVibrationLevel = 0
                        }
                    }

                    // 2. Disparador de Succión:
                    // SOLO si la máquina está libre (IDLE) se acepta el inicio de succión.
                    // Si está en SUCKING o LOCKOUT_VENTING, las señales rápidas se ignoran/bloquean.
                    if (suctionState == SuctionState.IDLE && rawValue > 0) {
                        startSuctionCycle(now)
                    }
                }
                cmd.equals("Stop", ignoreCase = true) -> {
                    currentVibrationLevel = 0
                    currentFunscriptInput = 0
                    suctionState = SuctionState.IDLE
                    transmitBle(CMD_CH1_STOP)
                    transmitBle(CMD_CH2_STOP)
                    sendStatusUpdate(log = "Stop")
                }
            }
        }
    }

    private fun startSuctionCycle(now: Long) {
        suctionState = SuctionState.SUCKING
        suctionTimerStart = now
        transmitBle(CMD_CH2_L1, force = true)
        sendStatusUpdate(log = "Succión: Pulso activo (700ms)")
    }

    private fun manageStateTransitions() {
        val now = System.currentTimeMillis()

        // 1. Apagar vibración únicamente si el vídeo lleva más de 250ms detenido
        if (currentVibrationLevel > 0 && (now - lastNonZeroTime > ZERO_HOLD_MS)) {
            currentVibrationLevel = 0
        }

        // 2. Máquina de estados de Succión
        when (suctionState) {
            SuctionState.SUCKING -> {
                // Al terminar los 700ms de succión -> Pasa a VENTEO OBLIGATORIO
                if (now - suctionTimerStart >= SUCK_PULSE_MS) {
                    suctionState = SuctionState.LOCKOUT_VENTING
                    suctionTimerStart = now
                    transmitBle(CMD_CH2_STOP, force = true) // Válvula abierta, aire entra
                    sendStatusUpdate(log = "Succión: Venteo y bloqueo (700ms)")
                }
            }
            SuctionState.LOCKOUT_VENTING -> {
                // Al terminar los 700ms de venteo -> Cerrojo liberado
                if (now - suctionTimerStart >= LOCKOUT_VENT_MS) {
                    // Resumen dinámico: si en este milisegundo exacto el script sigue arriba, dispara otra
                    if (currentFunscriptInput > 0) {
                        startSuctionCycle(now)
                    } else {
                        suctionState = SuctionState.IDLE
                    }
                }
            }
            SuctionState.IDLE -> {
                if (currentFunscriptInput > 0) {
                    startSuctionCycle(now)
                }
            }
        }

        // 3. Emisión de paquetes de radio
        if (suctionState == SuctionState.SUCKING) {
            // Durante la succión activa alternamos rápidamente para alimentar ambos motores
            suctionToggle = !suctionToggle
            if (suctionToggle) {
                applyVibrationHardware()
            } else {
                transmitBle(CMD_CH2_L1)
            }
        } else {
            // Cuando la succión está en reposo o despresurizando, el 100% de la radio es para vibración
            applyVibrationHardware()
        }
    }

    private fun applyVibrationHardware(force: Boolean = false) {
        val cmd = when {
            currentVibrationLevel == 0 -> CMD_CH1_STOP
            currentVibrationLevel in 1..6 -> CMD_CH1_L1
            currentVibrationLevel in 7..13 -> CMD_CH1_L2
            else -> CMD_CH1_L3
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
            putExtra(EXTRA_ROTATION_LEVEL, if (suctionState == SuctionState.SUCKING) 1 else 0)
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
