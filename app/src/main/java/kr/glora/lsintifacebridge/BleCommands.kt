package kr.glora.lsintifacebridge

/** Packet values already used by the device; command numbers are not 20 hardware power levels. */
object BleCommands {
    const val GLOBAL_STOP = 0xE5157D
    val VIBRATION = intArrayOf(0xD5964C, 0xD41F5D, 0xD7846F, 0xD60D7E)
    val SUCTION = intArrayOf(0xA5113F, 0xA4982E, 0xA7031C, 0xA68A0D)
    val STOPS = setOf(VIBRATION[0], SUCTION[0], GLOBAL_STOP)
    private val PREFIX = byteArrayOf(0x6D, 0xB6.toByte(), 0x43, 0xCE.toByte(),
        0x97.toByte(), 0xFE.toByte(), 0x42, 0x7C)

    fun body(command: Int): ByteArray = PREFIX + byteArrayOf(
        (command shr 16).toByte(), (command shr 8).toByte(), command.toByte())
}
