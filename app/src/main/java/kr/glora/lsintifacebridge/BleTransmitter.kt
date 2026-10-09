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
    private val scheduler = AdvertisingCommandScheduler()
    val confirmed: Int? get() = scheduler.confirmed
    private var set: AdvertisingSet? = null
    private var closed = false
    val isClosed: Boolean get() = closed
    val hasPendingOperation: Boolean get() = scheduler.inFlight != null
    private var failures = 0
    private val driveLater = Runnable { drive() }

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
        scheduler.request(command)
        drive()
    }

    private fun drive() {
        handler.removeCallbacks(driveLater)
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        val command = scheduler.next(now)
        if (command == null) {
            scheduler.delayMs(now)?.let { handler.postDelayed(driveLater, it) }
            return
        }
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
        val command = scheduler.complete(status == AdvertisingSetCallback.ADVERTISE_SUCCESS,
            SystemClock.elapsedRealtime()) ?: return
        if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            failures = 0
            applied(command)
        } else {
            failures++
            failure("Actualización BLE falló: $status (intento $failures)")
            if (failures >= 3) fatal("No se pudo confirmar la parada; apagar el juguete físicamente")
        }
        drive() // A waiting decrease/stop is applied without waiting for the service's next tick.
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
        handler.removeCallbacks(driveLater)
        try { advertiser.stopAdvertisingSet(callback) } catch (_: Exception) { }
        set = null
        scheduler.reset()
    }

    companion object {
        const val GLOBAL_STOP = BleCommands.GLOBAL_STOP
        val VIBRATION = BleCommands.VIBRATION
        val SUCTION = BleCommands.SUCTION
        fun body(command: Int): ByteArray = BleCommands.body(command)
    }
}
