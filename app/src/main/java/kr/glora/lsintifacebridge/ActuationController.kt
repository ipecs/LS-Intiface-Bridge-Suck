package kr.glora.lsintifacebridge

/** Serialized, bounded pulses. Android accepting data is not a toy acknowledgement. */
class ActuationController {
    enum class Phase { IDLE, STARTING, SUCKING, BETWEEN_STOPPING, BETWEEN_WAIT,
        SECOND_STARTING, SECOND_PULSE, STOPPING, COOLDOWN }
    enum class StopReason { NONE, PULSE_LIMIT, SEQUENCE_COMPLETE, ZERO_COMMAND, DISABLED,
        SETTINGS_CHANGED, MANUAL_STOP, CONNECTION_LOST, RADIO_FAILURE }

    var stopReason = StopReason.NONE
        private set
    var phase = Phase.IDLE
        private set
    var vibrationInput = 0
        private set
    var vibrationLevel = 0
        private set
    var suctionInputValue = 0
        private set
    var suctionLevel = 0
        private set
    var suctionEnabled = false
        private set
    var pulseMs = 2300L
        private set
    var cooldownMs = 1000L
        private set
    var scriptCommand = 2
        private set
    var triggerAt = 3
        private set
    var secondPulseEnabled = false
        private set
    var acceptedPeaks = 0
        private set
    var skippedPeaks = 0
        private set

    val pumpActive: Boolean get() = phase in listOf(Phase.STARTING, Phase.SUCKING,
        Phase.SECOND_STARTING, Phase.SECOND_PULSE)
    private var pumpDeadline = 0L
    private var stopDeadline = 0L
    private var vibrationExpiry = 0L
    private var manualCycle = false
    private var cycleCommand = 0
    private var cycleHasSecondPulse = false
    private var peakSeeded = false
    private var risingArmed = false
    private var high = 0
    private var low = 0

    fun configure(pulse: Long, cooldown: Long, now: Long, command: Int = scriptCommand,
        threshold: Int = triggerAt, secondPulse: Boolean = secondPulseEnabled) {
        scriptCommand = command.coerceIn(1, 3)
        secondPulseEnabled = secondPulse && scriptCommand == 2
        // Total requested pump-on time remains at most 5 seconds, including the extra step.
        pulseMs = pulse.coerceIn(700L, HARD_MAX_MS - if (secondPulseEnabled) SECOND_PULSE_MS else 0L)
        cooldownMs = cooldown.coerceIn(700L, 5000L)
        triggerAt = threshold.coerceIn(1, 20)
        resetPeakDetector()
        if (phase !in listOf(Phase.IDLE, Phase.STOPPING, Phase.COOLDOWN)) {
            stopSuction(StopReason.SETTINGS_CHANGED)
        }
        if (phase == Phase.COOLDOWN) stopDeadline = maxOf(stopDeadline, now + cooldownMs)
    }

    fun setSuctionEnabled(enabled: Boolean) {
        suctionEnabled = enabled
        resetPeakDetector()
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
        val value = level.coerceIn(0, 20)
        suctionInputValue = value
        if (manualCycle || !suctionEnabled) return
        val newPeak = observePeak(value)
        if (value == 0 && stopOnZero && phase !in listOf(Phase.IDLE, Phase.COOLDOWN, Phase.STOPPING)) {
            stopSuction(StopReason.ZERO_COMMAND)
            return
        }
        if (!newPeak) return
        if (phase == Phase.IDLE) {
            acceptedPeaks++
            startCycle(scriptCommand, now)
        } else {
            // Consume busy peaks immediately. There is no delayed replay or command backlog.
            skippedPeaks++
        }
    }

    /** Detect a fresh rise, including when its preceding fall happened during a busy cycle. */
    private fun observePeak(value: Int): Boolean {
        if (!peakSeeded) {
            peakSeeded = true
            high = value
            low = value
            risingArmed = value < triggerAt
            return false
        }
        if (!risingArmed) {
            high = maxOf(high, value)
            if (value < triggerAt || high - value >= PEAK_DELTA) {
                risingArmed = true
                low = value
            }
            return false
        }
        low = minOf(low, value)
        val riseNeeded = minOf(PEAK_DELTA, triggerAt)
        if (value >= triggerAt && value - low >= riseNeeded) {
            risingArmed = false
            high = value
            return true
        }
        return false
    }

    private fun resetPeakDetector() {
        peakSeeded = false
        risingArmed = false
        suctionInputValue = 0
    }

    fun manualPulse(level: Int, now: Long): Boolean {
        tick(now)
        if (!suctionEnabled || phase != Phase.IDLE || level !in 1..3) return false
        startCycle(level, now)
        manualCycle = true
        return true
    }

    private fun startCycle(level: Int, now: Long) {
        manualCycle = false
        cycleCommand = level
        cycleHasSecondPulse = secondPulseEnabled && level == 2
        suctionLevel = level
        phase = Phase.STARTING
        stopReason = StopReason.NONE
        pumpDeadline = now + START_TIMEOUT_MS
    }

    /** Begin the selected duration only after Android accepts this stage's command. */
    fun pumpDataApplied(level: Int, now: Long) {
        if (level != cycleCommand) return
        when (phase) {
            Phase.STARTING -> {
                phase = Phase.SUCKING
                pumpDeadline = now + pulseMs
            }
            Phase.SECOND_STARTING -> {
                phase = Phase.SECOND_PULSE
                pumpDeadline = now + SECOND_PULSE_MS
            }
            else -> Unit // Late/repeated callbacks cannot restart a timer or another cycle.
        }
    }

    private fun stopSuction(reason: StopReason, resetDetector: Boolean = true) {
        if (phase !in listOf(Phase.STOPPING, Phase.COOLDOWN) || reason == StopReason.DISABLED) {
            stopReason = reason
        }
        if (phase != Phase.IDLE && phase != Phase.COOLDOWN) phase = Phase.STOPPING
        suctionLevel = 0
        manualCycle = false
        cycleHasSecondPulse = false
        if (resetDetector) resetPeakDetector()
    }

    fun stopAll(reason: StopReason = StopReason.MANUAL_STOP, restartHold: Boolean = false) {
        vibrationInput = 0
        vibrationLevel = 0
        suctionLevel = 0
        manualCycle = false
        cycleHasSecondPulse = false
        resetPeakDetector()
        stopReason = reason
        if (restartHold || phase != Phase.COOLDOWN) phase = Phase.STOPPING
    }

    /** A stop hold starts only after Android accepts stop data, never before. */
    fun stopDataApplied(now: Long) {
        when (phase) {
            Phase.BETWEEN_STOPPING -> {
                phase = Phase.BETWEEN_WAIT
                stopDeadline = now + cooldownMs
            }
            Phase.STOPPING -> {
                phase = Phase.COOLDOWN
                stopDeadline = now + cooldownMs
            }
            else -> Unit
        }
    }

    fun tick(now: Long) {
        if (now >= vibrationExpiry) {
            vibrationInput = 0
            vibrationLevel = 0
        }
        when (phase) {
            Phase.STARTING, Phase.SECOND_STARTING -> if (now >= pumpDeadline) {
                stopSuction(StopReason.RADIO_FAILURE)
            }
            Phase.SUCKING -> if (now >= pumpDeadline) {
                if (cycleHasSecondPulse) {
                    suctionLevel = 0
                    phase = Phase.BETWEEN_STOPPING
                    stopReason = StopReason.PULSE_LIMIT
                } else stopSuction(StopReason.PULSE_LIMIT, resetDetector = false)
            }
            Phase.BETWEEN_WAIT -> if (now >= stopDeadline) {
                suctionLevel = cycleCommand
                phase = Phase.SECOND_STARTING
                pumpDeadline = now + START_TIMEOUT_MS
            }
            Phase.SECOND_PULSE -> if (now >= pumpDeadline) {
                stopSuction(StopReason.SEQUENCE_COMPLETE, resetDetector = false)
            }
            Phase.COOLDOWN -> if (now >= stopDeadline) phase = Phase.IDLE
            else -> Unit
        }
    }

    companion object {
        const val HARD_MAX_MS = 5000L
        const val SECOND_PULSE_MS = 1000L
        // Allow an existing advertising update (1 s), this update (1 s), and scheduling margin.
        const val START_TIMEOUT_MS = 2500L
        const val STREAM_TIMEOUT_MS = 1200L
        const val PEAK_DELTA = 2
    }
}
