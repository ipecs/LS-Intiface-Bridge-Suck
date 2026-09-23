package kr.glora.lsintifacebridge

import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import android.os.ParcelUuid
import okhttp3.*
import java.util.UUID

class BridgeService : Service() {

    private var advertiser: BluetoothLeAdvertiser? = null
    private var webSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())

    // Prefijo LoveSpouse / MuSe (BroadLink Fastcon)
    private val PREFIX = byteArrayOf(
        0x6D.toByte(), 0xB6.toByte(), 0x43.toByte(), 0xCE.toByte(),
        0x97.toByte(), 0xFE.toByte(), 0x42.toByte(), 0x7C.toByte()
    )

    // Opcodes Canal 1 (VIBRACIÓN) - 100% Continuos, SIN bursts
    private val CMD_CH1_STOP = byteArrayOf(0xD5.toByte(), 0x96.toByte(), 0x4C.toByte())
    private val CMD_CH1_L1   = byteArrayOf(0xD4.toByte(), 0x1F.toByte(), 0x5D.toByte())
    private val CMD_CH1_L2   = byteArrayOf(0xD7.toByte(), 0x84.toByte(), 0x6F.toByte())
    private val CMD_CH1_L3   = byteArrayOf(0xD6.toByte(), 0x0D.toByte(), 0x7E.toByte())

    // Opcodes Canal 2 (SUCCIÓN)
    private val CMD_CH2_STOP = byteArrayOf(0xA5.toByte(), 0x11.toByte(), 0x3F.toByte())
    private val CMD_CH2_L1   = byteArrayOf(0xA4.toByte(), 0x98.toByte(), 0x2E.toByte())

    // Estado de los actuadores
    private var currentVibeLevel = 0 // 0..20 desde Intiface
    private var enableSuction = false // Ponlo en true si deseas succión rítmica

    // Variables del motor de venteo Anti-Burst (Canal 2)
    private var isVenting = false
    private var lastCycleTime = 0L
    private val SUCK_DURATION_MS = 1000L // 1.0 s de succión max
    private val VENT_DURATION_MS = 800L  // 0.8 s de despresurización obligatoria

    // Loop de refresco BLE (emite cada 60ms para evitar el watchdog de 250ms del juguete)
    private val broadcastRunnable = object : Runnable {
        override fun run() {
            updateHardwareState()
            handler.postDelayed(this, 60)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        advertiser = btManager.adapter?.bluetoothLeAdvertiser
        handler.post(broadcastRunnable)
    }

    private fun updateHardwareState() {
        val now = System.currentTimeMillis()

        // 1. Resolver el comando de Vibración (Canal 1) según el nivel de Intiface (0 a 20)
        val vibeCmd = when {
            currentVibeLevel == 0 -> CMD_CH1_STOP
            currentVibeLevel in 1..6 -> CMD_CH1_L1
            currentVibeLevel in 7..13 -> CMD_CH1_L2
            else -> CMD_CH1_L3
        }

        // 2. Resolver el comando de Succión (Canal 2) con control de escape de aire
        var suctionCmd = CMD_CH2_STOP
        if (enableSuction && currentVibeLevel > 0) {
            if (isVenting) {
                suctionCmd = CMD_CH2_STOP // Válvula abierta, aire entrando
                if (now - lastCycleTime >= VENT_DURATION_MS) {
                    isVenting = false
                    lastCycleTime = now
                }
            } else {
                suctionCmd = CMD_CH2_L1 // Succión suave
                if (now - lastCycleTime >= SUCK_DURATION_MS) {
                    isVenting = true
                    lastCycleTime = now
                }
            }
        } else {
            isVenting = false
            suctionCmd = CMD_CH2_STOP
        }

        // Emitir alternadamente o priorizar vibración
        sendBlePacket(vibeCmd)
        if (enableSuction) {
            sendBlePacket(suctionCmd)
        }
    }

    private fun sendBlePacket(cmdSuffix: ByteArray) {
        val payload = ByteArray(11)
        System.arraycopy(PREFIX, 0, payload, 0, 8)
        System.arraycopy(cmdSuffix, 0, payload, 8, 3)

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
            advertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {})
        } catch (e: Exception) {
            // Manejar fallos silenciosos de stack BLE
        }
    }

    // Procesa las órdenes que envía Intiface Central (Protocolo Lovense por Websocket)
    fun handleLovenseCommand(text: String) {
        // Ejemplo de Intiface: "Vibrate:15;" o "Vibrate1:10;"
        if (text.startsWith("Vibrate:")) {
            val levelStr = text.substringAfter("Vibrate:").substringBefore(";")
            currentVibeLevel = levelStr.toIntOrNull() ?: 0
        } else if (text.startsWith("Vibrate1:")) {
            // Canal 1 independiente
            val levelStr = text.substringAfter("Vibrate1:").substringBefore(";")
            currentVibeLevel = levelStr.toIntOrNull() ?: 0
        } else if (text.startsWith("Vibrate2:")) {
            // Canal 2 independiente (si usas script de 2 motores)
            val levelStr = text.substringAfter("Vibrate2:").substringBefore(";")
            val suctionLevel = levelStr.toIntOrNull() ?: 0
            enableSuction = suctionLevel > 0
        } else if (text.startsWith("DeviceType;")) {
            // Responder como Lovense Edge para que Intiface active 2 motores independientes
            webSocket?.send("P:11:001122334455\r\n")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(broadcastRunnable)
        sendBlePacket(CMD_CH1_STOP)
        sendBlePacket(CMD_CH2_STOP)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
