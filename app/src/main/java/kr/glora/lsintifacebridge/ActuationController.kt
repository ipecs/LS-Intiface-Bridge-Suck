package kr.glora.lsintifacebridge

/** Pure state machine. All times are supplied by a monotonic clock. No RF acknowledgements. */
class ActuationController {
    enum class Phase { IDLE, SUCKING, STOPPING, COOLDOWN }

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

    fun configure(pulse: Long, cooldown: Long, now: Long) {
        pulseMs = pulse.coerceIn(700L, HARD_MAX_MS)
        cooldownMs = cooldown.coerceIn(700L, 5000L)
        // Changing settings cannot lengthen a running pulse or shorten its stop hold.
        if (phase == Phase.SUCKING) stopSuction()
        if (phase == Phase.COOLDOWN) cooldownDeadline = maxOf(cooldownDeadline, now + cooldownMs)
    }

    fun setSuctionEnabled(enabled: Boolean) {
        suctionEnabled = enabled
        armed = false // A fresh low value, then a fresh peak, must follow re-enabling.
        if (!enabled) {
            stopSuction()
            if (phase == Phase.IDLE) phase = Phase.STOPPING
        }
    }

    fun vibration(level: Int, now: Long, lifetimeMs: Long = STREAM_TIMEOUT_MS) {
        vibrationInput = level.coerceIn(0, 20)
        vibrationLevel = when (vibrationInput) {
            in 10..20 -> 3
            in 5..9 -> 2
            else -> 0
        }
        vibrationExpiry = now + lifetimeMs
    }

    fun suctionInput(level: Int, now: Long, stopOnZero: Boolean = true) {
        tick(now)
        val value = level.coerceIn(0, 20)
        suctionExpiry = now + STREAM_TIMEOUT_MS
        if (value == 0 && stopOnZero) {
            if (phase == Phase.IDLE && suctionEnabled) armed = true else stopSuction()
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
        suctionExpiry = pulseDeadline // Local tests have their own bounded lifetime.
        return true
    }

    private fun startPulse(level: Int, now: Long) {
        armed = false
        suctionLevel = level
        phase = Phase.SUCKING
        pulseDeadline = now + pulseMs.coerceAtMost(HARD_MAX_MS)
    }

    private fun stopSuction() {
        if (phase == Phase.SUCKING) phase = Phase.STOPPING
        suctionLevel = 0
        armed = false
    }

    fun stopAll() {
        vibrationInput = 0
        vibrationLevel = 0
        suctionLevel = 0
        armed = false
        if (phase != Phase.COOLDOWN) phase = Phase.STOPPING
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
        if (phase == Phase.SUCKING && (now >= pulseDeadline || now >= suctionExpiry)) {
            stopSuction()
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
