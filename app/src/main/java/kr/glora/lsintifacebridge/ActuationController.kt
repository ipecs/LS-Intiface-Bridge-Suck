package kr.glora.lsintifacebridge

/** Serialized, bounded pulses. Android accepting data is not a toy acknowledgement. */
class ActuationController {
    enum class Phase { IDLE, STARTING, SUCKING, BETWEEN_STOPPING, BETWEEN_WAIT,
        SECOND_STARTING, SECOND_PULSE, STOPPING, COOLDOWN }
    enum class StopReason { NONE, PULSE_LIMIT, SEQUENCE_COMPLETE, DISABLED,
        SETTINGS_CHANGED, MANUAL_STOP, CONNECTION_LOST, RADIO_FAILURE }

    var stopReason = StopReason.NONE
        private set
    var phase = Phase.IDLE
        private set
    var vibrationInput = 0
        private set
    var vibrationLevel = 0
        private set
    var fullVibrationRange = false
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
    var secondPulseEnabled = false
        private set
    var recoveryMs = 0L
        private set
    var releaseVerified = false
        private set
    var manualSequenceCompleted = false
        private set
    var pumpRevision = 0L
        private set

    val releaseProfile: String? get() = if (scriptCommand == 2 && secondPulseEnabled)
        "hb2451-c2-v1:$pulseMs:$cooldownMs:$SECOND_PULSE_MS:$recoveryMs" else null

    val pumpActive: Boolean get() = phase in listOf(Phase.STARTING, Phase.SUCKING,
        Phase.SECOND_STARTING, Phase.SECOND_PULSE)
    private var pumpDeadline = 0L
    private var stopDeadline = 0L
    private var vibrationExpiry = 0L
    private var cycleCommand = 0
    private var cycleHasSecondPulse = false
    private var calibrationPending = false

    fun configure(pulse: Long, cooldown: Long, now: Long, command: Int = scriptCommand,
        secondPulse: Boolean = secondPulseEnabled,
        recovery: Long = recoveryMs) {
        val newCommand = command.coerceIn(1, 3)
        val newSecond = secondPulse && newCommand == 2
        val newPulse = pulse.coerceIn(700L, HARD_MAX_MS - if (newSecond) SECOND_PULSE_MS else 0L)
        val newCooldown = cooldown.coerceIn(700L, 5000L)
        val newRecovery = recovery.coerceIn(0L, 5000L)
        if (newCommand != scriptCommand || newSecond != secondPulseEnabled || newPulse != pulseMs ||
            newCooldown != cooldownMs || newRecovery != recoveryMs) {
            releaseVerified = false
            manualSequenceCompleted = false
            calibrationPending = false
        }
        scriptCommand = newCommand
        secondPulseEnabled = newSecond
        // Total requested pump-on time remains at most 5 seconds, including the extra step.
        pulseMs = newPulse
        cooldownMs = newCooldown
        recoveryMs = newRecovery
        if (phase !in listOf(Phase.IDLE, Phase.STOPPING, Phase.COOLDOWN)) {
            stopSuction(StopReason.SETTINGS_CHANGED)
        }
        if (phase == Phase.COOLDOWN) stopDeadline = maxOf(stopDeadline, now + maxOf(cooldownMs, recoveryMs))
    }

    fun setSuctionEnabled(enabled: Boolean, requestIdleStop: Boolean = true) {
        suctionEnabled = enabled
        if (!enabled) {
            stopSuction(StopReason.DISABLED)
            if (requestIdleStop && phase == Phase.IDLE) phase = Phase.STOPPING
        }
    }

    fun vibration(level: Int, now: Long, lifetimeMs: Long = STREAM_TIMEOUT_MS) {
        vibrationInput = level.coerceIn(0, 20)
        vibrationLevel = mapVibration(vibrationInput)
        vibrationExpiry = now + lifetimeMs
    }

    fun setFullVibrationRange(enabled: Boolean) {
        fullVibrationRange = enabled
        vibrationLevel = mapVibration(vibrationInput)
    }

    private fun mapVibration(value: Int): Int = if (fullVibrationRange) when (value) {
        in 15..20 -> 3
        in 9..14 -> 2
        in 3..8 -> 1
        else -> 0
    } else when (value) {
        in 10..20 -> 3
        in 5..9 -> 2
        in 3..4 -> 1
        else -> 0
    }

    fun manualPulse(level: Int, now: Long): Boolean {
        tick(now)
        if (!suctionEnabled || phase != Phase.IDLE || level !in 1..3) return false
        startCycle(level, now)
        releaseVerified = false
        manualSequenceCompleted = false
        calibrationPending = level == 2 && cycleHasSecondPulse
        return true
    }

    fun confirmRelease(): Boolean {
        // Explicit user observation may come from an earlier session, not just the latest timer.
        if (phase != Phase.IDLE || releaseProfile == null) return false
        releaseVerified = true // The user's observation, not a pressure measurement.
        manualSequenceCompleted = false
        return true
    }

    fun restoreRelease(profile: String?): Boolean {
        releaseVerified = profile != null && profile == releaseProfile
        return releaseVerified
    }

    fun automaticPulse(now: Long): Boolean {
        tick(now)
        if (!suctionEnabled || !releaseVerified || scriptCommand != 2 || !secondPulseEnabled || phase != Phase.IDLE) return false
        startCycle(scriptCommand, now)
        return true
    }

    private fun setPumpTarget(level: Int, force: Boolean = false) {
        if (force || suctionLevel != level) pumpRevision++
        suctionLevel = level
    }

    private fun startCycle(level: Int, now: Long) {
        cycleCommand = level
        cycleHasSecondPulse = secondPulseEnabled && level == 2
        calibrationPending = false
        setPumpTarget(level, force = true)
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

    private fun stopSuction(reason: StopReason) {
        if (phase !in listOf(Phase.STOPPING, Phase.COOLDOWN) || reason == StopReason.DISABLED) {
            stopReason = reason
        }
        if (phase != Phase.IDLE && phase != Phase.COOLDOWN) phase = Phase.STOPPING
        setPumpTarget(0)
        cycleHasSecondPulse = false
        if (reason != StopReason.SEQUENCE_COMPLETE) {
            calibrationPending = false
            manualSequenceCompleted = false
        }
    }

    fun stopAll(reason: StopReason = StopReason.MANUAL_STOP, restartHold: Boolean = false) {
        vibrationInput = 0
        vibrationLevel = 0
        setPumpTarget(0, force = true)
        cycleHasSecondPulse = false
        calibrationPending = false
        manualSequenceCompleted = false
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
                stopDeadline = now + maxOf(cooldownMs, recoveryMs)
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
                    setPumpTarget(0)
                    phase = Phase.BETWEEN_STOPPING
                    stopReason = StopReason.PULSE_LIMIT
                } else stopSuction(StopReason.PULSE_LIMIT)
            }
            Phase.BETWEEN_WAIT -> if (now >= stopDeadline) {
                setPumpTarget(cycleCommand)
                phase = Phase.SECOND_STARTING
                pumpDeadline = now + START_TIMEOUT_MS
            }
            Phase.SECOND_PULSE -> if (now >= pumpDeadline) {
                stopSuction(StopReason.SEQUENCE_COMPLETE)
            }
            Phase.COOLDOWN -> if (now >= stopDeadline) {
                phase = Phase.IDLE
                if (calibrationPending) {
                    manualSequenceCompleted = true
                    calibrationPending = false
                }
            }
            else -> Unit
        }
    }

    companion object {
        const val HARD_MAX_MS = 5000L
        const val SECOND_PULSE_MS = 1000L
        // Allow an existing advertising update (1 s), this update (1 s), and scheduling margin.
        const val START_TIMEOUT_MS = 2500L
        const val STREAM_TIMEOUT_MS = 1200L
    }
}
