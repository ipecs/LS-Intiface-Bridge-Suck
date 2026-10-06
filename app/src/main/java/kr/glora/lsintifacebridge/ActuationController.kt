package kr.glora.lsintifacebridge

/** Pure state machine. All times are supplied by a monotonic clock. No RF acknowledgements. */
class ActuationController {
    enum class Phase { IDLE, SUCKING, STOPPING, COOLDOWN }
    enum class StopReason { NONE, PULSE_LIMIT, INPUT_TIMEOUT, ZERO_COMMAND, DISABLED,
        SETTINGS_CHANGED, MANUAL_STOP, CONNECTION_LOST, RADIO_FAILURE }

    var stopReason = StopReason.NONE
        private set

    var phase = Phase.IDLE
        private set
    var vibrationInput = 0
        private set
    var vibrationLevel = 0
        private set
    var suctionLevel = 0
        private set
    var suctionEnabled = false
        private set
    var pulseMs = 700L
        private set
    var cooldownMs = 1000L
        private set
    var threeLevels = false
    private var armed = true
    private var pulseDeadline = 0L
    private var cooldownDeadline = 0L
    private var vibrationExpiry = 0L
    private var suctionExpiry = 0L
    private var manualPulseActive = false

    fun configure(pulse: Long, cooldown: Long, now: Long) {
        pulseMs = pulse.coerceIn(700L, HARD_MAX_MS)
        cooldownMs = cooldown.coerceIn(700L, 5000L)
        // Changing settings cannot lengthen a running pulse or shorten its stop hold.
        if (phase == Phase.SUCKING) stopSuction(StopReason.SETTINGS_CHANGED)
        if (phase == Phase.COOLDOWN) cooldownDeadline = maxOf(cooldownDeadline, now + cooldownMs)
    }

    fun setSuctionEnabled(enabled: Boolean) {
        suctionEnabled = enabled
        armed = false // A fresh low value, then a fresh peak, must follow re-enabling.
        if (!enabled) {
            stopSuction(StopReason.DISABLED)
            if (phase == Phase.IDLE) phase = Phase.STOPPING
        }
    }

    fun vibration(level: Int, now: Long, lifetimeMs: Long = STREAM_TIMEOUT_MS) {
        vibrationInput = level.coerceIn(0, 20)
        vibrationLevel = when (vibrationInput) {
            in 10..20 -> 3
            in 5..9 -> 2
            in 3..4 -> 1
            else -> 0
        }
        vibrationExpiry = now + lifetimeMs
    }

    fun suctionInput(level: Int, now: Long, stopOnZero: Boolean = true) {
        tick(now)
        // A local test owns its duration. Stream values cannot interrupt or extend it.
        if (manualPulseActive) return
        val value = level.coerceIn(0, 20)
        suctionExpiry = now + STREAM_TIMEOUT_MS
        if (value == 0 && stopOnZero) {
            if (phase == Phase.IDLE && suctionEnabled) armed = true else stopSuction(StopReason.ZERO_COMMAND)
            return
        }
        if (!suctionEnabled || phase != Phase.IDLE) return
        if (value < REARM_BELOW) armed = true
        if (armed && value >= TRIGGER_AT) {
            val mode = if (!threeLevels) 1 else when (value) {
                in 16..20 -> 3
                in 12..15 -> 2
                else -> 1
            }
            startPulse(mode, now)
        }
    }

    fun manualPulse(level: Int, now: Long): Boolean {
        tick(now)
        if (!suctionEnabled || phase != Phase.IDLE || level !in 1..3) return false
        startPulse(level, now)
        manualPulseActive = true
        suctionExpiry = pulseDeadline // Local tests have their own bounded lifetime.
        return true
    }

    private fun startPulse(level: Int, now: Long) {
        manualPulseActive = false
        armed = false
        suctionLevel = level
        phase = Phase.SUCKING
        stopReason = StopReason.NONE
        pulseDeadline = now + pulseMs.coerceAtMost(HARD_MAX_MS)
    }

    private fun stopSuction(reason: StopReason) {
        if (phase == Phase.SUCKING || (phase == Phase.IDLE && reason == StopReason.DISABLED)) {
            stopReason = reason
        }
        if (phase == Phase.SUCKING) phase = Phase.STOPPING
        suctionLevel = 0
        armed = false
        manualPulseActive = false
    }

    fun stopAll(reason: StopReason = StopReason.MANUAL_STOP, restartHold: Boolean = false) {
        vibrationInput = 0
        vibrationLevel = 0
        suctionLevel = 0
        armed = false
        manualPulseActive = false
        stopReason = reason
        if (restartHold || phase != Phase.COOLDOWN) phase = Phase.STOPPING
    }

    /** Android accepted CH2 STOP data. This does not prove the toy received it or vented. */
    fun stopDataApplied(now: Long) {
        if (phase == Phase.STOPPING) {
            phase = Phase.COOLDOWN
            cooldownDeadline = now + cooldownMs
        }
    }

    fun tick(now: Long) {
        if (now >= vibrationExpiry) {
            vibrationInput = 0
            vibrationLevel = 0
        }
        if (phase == Phase.SUCKING) {
            if (now >= pulseDeadline) stopSuction(StopReason.PULSE_LIMIT)
            else if (now >= suctionExpiry) stopSuction(StopReason.INPUT_TIMEOUT)
        }
        if (phase == Phase.COOLDOWN && now >= cooldownDeadline) phase = Phase.IDLE
    }

    companion object {
        const val HARD_MAX_MS = 5000L
        const val STREAM_TIMEOUT_MS = 1200L
        const val TRIGGER_AT = 8
        const val REARM_BELOW = 5
    }
}
