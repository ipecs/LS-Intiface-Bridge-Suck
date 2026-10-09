package kr.glora.lsintifacebridge

/** Send each pump stage once, hold its data briefly, then let latched vibration update. */
class ActuationOutputRouter {
    private var target: Int? = null
    private var revision = -1L
    private var accepted = false
    private var heldCommand: Int? = null
    private var holdUntil = 0L

    fun select(vibration: Int, pump: Int?, pumpRevision: Long, now: Long): Int {
        if (target != pump || revision != pumpRevision) {
            target = pump
            revision = pumpRevision
            accepted = false
        }
        // A new stop overrides a still-held positive command immediately.
        if (pump != null && !accepted) return pump
        val held = heldCommand
        if (held != null && now < holdUntil) return held
        return vibration
    }

    /** Returns true only for a new acknowledgement of the current pump stage. */
    fun applied(command: Int, now: Long): Boolean {
        if (target != command || accepted) return false
        accepted = true
        heldCommand = command
        holdUntil = now + PUMP_DATA_HOLD_MS
        return true
    }

    fun reset() {
        target = null
        revision = -1L
        accepted = false
        heldCommand = null
        holdUntil = 0L
    }

    companion object {
        // Several 100-ms advertising opportunities; no physical reception guarantee.
        const val PUMP_DATA_HOLD_MS = 350L
    }
}
