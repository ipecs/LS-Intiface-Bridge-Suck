package kr.glora.lsintifacebridge

import android.annotation.SuppressLint
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Handler
import android.os.SystemClock

/** One advertising set, one asynchronous operation at a time, newest desired value wins. */
@SuppressLint("MissingPermission")
class BleTransmitter(
    private val advertiser: BluetoothLeAdvertiser,
    private val handler: Handler,
    private val applied: (Int) -> Unit,
    private val failure: (String) -> Unit,
) {
    var confirmed: Int? = null
        private set
    private var set: AdvertisingSet? = null
    private var inFlight: Int? = null
    private var closed = false
    val isClosed: Boolean get() = closed
    val hasPendingOperation: Boolean get() = inFlight != null
    private var failures = 0
    private var lastAppliedAt = 0L

    private val timeout = Runnable { fatal("Bluetooth no confirmó la operación en 1 s") }
    private val callback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(value: AdvertisingSet?, txPower: Int, status: Int) {
            if (closed) {
                try { advertiser.stopAdvertisingSet(this) } catch (_: Exception) { }
                return
            }
            if (status != ADVERTISE_SUCCESS || value == null) {
                fatal("Inicio BLE falló: $status")
                return
            }
            set = value
            complete(ADVERTISE_SUCCESS)
        }

        override fun onAdvertisingDataSet(value: AdvertisingSet?, status: Int) {
            if (!closed && value == set) complete(status)
        }

        override fun onAdvertisingSetStopped(value: AdvertisingSet?) {
            if (!closed) fatal("La emisión BLE se detuvo inesperadamente")
        }
    }

    fun request(command: Int) {
        if (closed) return
        if (inFlight != null || confirmed == command) return
        val now = SystemClock.elapsedRealtime()
        val isStop = command == VIBRATION[0] || command == SUCTION[0] || command == GLOBAL_STOP
        if (!isStop && set != null && now - lastAppliedAt < 100L) return
        inFlight = command
        handler.postDelayed(timeout, 1000L)
        try {
            val data = AdvertiseData.Builder().addManufacturerData(0xFFF0, body(command)).build()
            val current = set
            if (current != null) {
                current.setAdvertisingData(data)
            } else {
                val parameters = AdvertisingSetParameters.Builder()
                    .setLegacyMode(true)
                    .setConnectable(true)
                    .setScannable(true)
                    .setInterval(AdvertisingSetParameters.INTERVAL_LOW) // 160 slots = 100 ms
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .build()
                advertiser.startAdvertisingSet(parameters, data, null, null, null, callback, handler)
            }
        } catch (e: Exception) {
            fatal("Error BLE: ${e.message}")
        }
    }

    private fun complete(status: Int) {
        handler.removeCallbacks(timeout)
        val command = inFlight ?: return
        inFlight = null
        lastAppliedAt = SystemClock.elapsedRealtime()
        if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            confirmed = command
            failures = 0
            applied(command)
        } else {
            confirmed = null
            failures++
            failure("Actualización BLE falló: $status (intento $failures)")
            if (failures >= 3) fatal("No se pudo confirmar la parada; apagar el juguete físicamente")
        }
        // The service requests its current desired state on its next tick. No historical queue.
    }

    private fun fatal(message: String) {
        if (closed) return
        close()
        failure(message)
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(timeout)
        try { advertiser.stopAdvertisingSet(callback) } catch (_: Exception) { }
        set = null
        inFlight = null
    }

    companion object {
        const val GLOBAL_STOP = 0xE5157D
        val VIBRATION = intArrayOf(0xD5964C, 0xD41F5D, 0xD7846F, 0xD60D7E)
        val SUCTION = intArrayOf(0xA5113F, 0xA4982E, 0xA7031C, 0xA68A0D)
        private val PREFIX = byteArrayOf(0x6D, 0xB6.toByte(), 0x43, 0xCE.toByte(),
            0x97.toByte(), 0xFE.toByte(), 0x42, 0x7C)
        fun body(command: Int): ByteArray = PREFIX + byteArrayOf(
            (command shr 16).toByte(), (command shr 8).toByte(), command.toByte())
    }
}
