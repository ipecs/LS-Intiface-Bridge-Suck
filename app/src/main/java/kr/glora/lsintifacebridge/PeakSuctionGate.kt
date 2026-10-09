package kr.glora.lsintifacebridge

/** Sparse rising accents, not a pressure controller or a queue of script movements. */
class PeakSuctionGate {
    enum class Event { NONE, START, SKIP_BUSY, SKIP_INTERVAL }
    var enabled = false
        private set
    var threshold = 12
        private set
    var minimumIntervalMs = 8000L
        private set
    val rearmAt: Int get() = (threshold - 4).coerceAtLeast(0)
    var accepted = 0
        private set
    var skipped = 0
        private set
    private var armed = false
    private var lastSampleAt: Long? = null
    private var lastStartAt: Long? = null

    fun configure(threshold: Int, intervalMs: Long) {
        this.threshold = threshold.coerceIn(3, 20)
        minimumIntervalMs = intervalMs.coerceIn(6000L, 30000L)
        setEnabled(false)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        armed = false
        lastSampleAt = null
        // Re-enabling cannot bypass the interval after an earlier accepted accent.
    }

    fun observe(input: Int, now: Long, ready: Boolean): Event {
        if (!enabled) return Event.NONE
        val value = input.coerceIn(0, 20)
        val previousSample = lastSampleAt
        if (previousSample == null || now - previousSample > ActuationController.STREAM_TIMEOUT_MS) {
            armed = value <= rearmAt
            lastSampleAt = now
            return Event.NONE
        }
        lastSampleAt = now
        if (value <= rearmAt) {
            armed = true
            return Event.NONE
        }
        if (!armed || value < threshold) return Event.NONE
        armed = false // Consume this crossing even when busy; never replay it later.
        val event = when {
            !ready -> Event.SKIP_BUSY
            lastStartAt?.let { now - it < minimumIntervalMs } == true -> Event.SKIP_INTERVAL
            else -> Event.START
        }
        if (event == Event.START) {
            accepted++
            lastStartAt = now
        } else skipped++
        return event
    }
}
