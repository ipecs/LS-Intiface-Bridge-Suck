package kr.glora.lsintifacebridge

/** One pending operation. Decreases/stop are immediate; deferred increases keep only the latest value. */
class AdvertisingCommandScheduler {
    var confirmed: Int? = null
        private set
    var inFlight: Int? = null
        private set
    private var desired: Int? = null
    private var lastAppliedAt: Long? = null

    fun request(command: Int) { desired = command }

    fun delayMs(now: Long): Long? {
        val target = desired ?: return null
        if (inFlight != null || target == confirmed) return null
        if (target in BleCommands.STOPS) return 0L
        val oldLevel = confirmed?.let(BleCommands.VIBRATION::indexOf) ?: -1
        val newLevel = BleCommands.VIBRATION.indexOf(target)
        if (oldLevel >= 0 && newLevel >= 0 && newLevel < oldLevel) return 0L
        val appliedAt = lastAppliedAt ?: return 0L
        return (100L - (now - appliedAt)).coerceAtLeast(0L)
    }

    fun next(now: Long): Int? {
        if (delayMs(now) != 0L) return null
        return desired?.also { inFlight = it }
    }

    fun complete(success: Boolean, now: Long): Int? {
        val command = inFlight ?: return null
        inFlight = null
        lastAppliedAt = now
        confirmed = if (success) command else null
        if (!success) desired = null // No positive replay after an operation fails.
        return command
    }

    fun reset() {
        confirmed = null
        inFlight = null
        desired = null
        lastAppliedAt = null
    }
}
