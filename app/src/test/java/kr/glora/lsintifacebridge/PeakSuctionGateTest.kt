package kr.glora.lsintifacebridge

import org.junit.Assert.*
import org.junit.Test
import kr.glora.lsintifacebridge.PeakSuctionGate.Event

class PeakSuctionGateTest {
    private fun gate() = PeakSuctionGate().apply { setEnabled(true) }

    @Test fun strongCrossingTriggersOnceAndConstantHighDoesNotRepeat() {
        val g = gate()
        assertEquals(Event.NONE, g.observe(8, 0, true))
        assertEquals(Event.NONE, g.observe(11, 100, true))
        assertEquals(Event.START, g.observe(12, 200, true))
        for (time in 300L..12000L step 100) assertEquals(Event.NONE, g.observe(20, time, true))
        assertEquals(1, g.accepted)
    }

    @Test fun enablingOnAnAlreadyHighStreamWaitsForARealValley() {
        val g = gate()
        assertEquals(Event.NONE, g.observe(18, 0, true))
        assertEquals(Event.NONE, g.observe(20, 100, true))
        assertEquals(Event.NONE, g.observe(11, 200, true))
        assertEquals(Event.NONE, g.observe(18, 300, true))
        g.observe(8, 400, true)
        assertEquals(Event.START, g.observe(12, 500, true))
    }

    @Test fun thresholdJitterAndSmallAboveThresholdFallsCannotRetrigger() {
        val g = gate()
        g.observe(0, 0, true)
        assertEquals(Event.START, g.observe(12, 100, true))
        for (time in 200L..10000L step 100) {
            assertEquals(Event.NONE, g.observe(if (time % 200 == 0L) 11 else 13, time, true))
        }
        assertEquals(1, g.accepted)
        assertEquals(0, g.skipped)
    }

    @Test fun busyCrossingIsConsumedAndDoesNotReplayWhenReady() {
        val g = gate()
        g.observe(0, 0, true)
        assertEquals(Event.SKIP_BUSY, g.observe(15, 100, false))
        assertEquals(Event.NONE, g.observe(15, 200, true))
        g.observe(8, 300, true)
        assertEquals(Event.START, g.observe(12, 400, true))
        assertEquals(1, g.skipped)
    }

    @Test fun intervalCrossingIsConsumedAndOnlyANewRiseCanStartLater() {
        val g = gate()
        g.observe(0, 0, true)
        assertEquals(Event.START, g.observe(12, 100, true))
        g.observe(0, 200, true)
        assertEquals(Event.SKIP_INTERVAL, g.observe(12, 300, true))
        for (time in 400L..8100L step 100) assertEquals(Event.NONE, g.observe(20, time, true))
        g.observe(8, 8200, true)
        assertEquals(Event.START, g.observe(12, 8300, true))
    }

    @Test fun exactIntervalBoundaryAllowsAFreshCrossing() {
        val g = gate()
        g.observe(0, 0, true)
        g.observe(12, 100, true)
        for (time in 200L..8000L step 100) g.observe(0, time, true)
        assertEquals(Event.START, g.observe(12, 8100, true))
    }

    @Test fun missingSamplesCannotUseAnOldValleyToStartLate() {
        val g = gate()
        g.observe(0, 0, true)
        assertEquals(Event.NONE, g.observe(20, 1300, true))
        assertEquals(Event.NONE, g.observe(20, 1400, true))
        g.observe(8, 1500, true)
        assertEquals(Event.START, g.observe(12, 1600, true))
    }

    @Test fun disablingAndReenablingDoNotBypassThePreviousStartInterval() {
        val g = gate()
        g.observe(0, 0, true)
        g.observe(12, 100, true)
        g.setEnabled(false)
        assertEquals(Event.NONE, g.observe(0, 200, true))
        g.setEnabled(true)
        g.observe(0, 300, true)
        assertEquals(Event.SKIP_INTERVAL, g.observe(12, 400, true))
    }

    @Test fun changedThresholdRequiresReenableAndUsesItsOwnCrossing() {
        val g = gate()
        g.configure(15, 6000)
        assertFalse(g.enabled)
        g.setEnabled(true)
        g.observe(11, 0, true)
        assertEquals(Event.NONE, g.observe(14, 100, true))
        assertEquals(Event.START, g.observe(15, 200, true))
    }

    @Test fun lowThresholdStillRequiresAValleyAndStrongEnoughRise() {
        val g = gate()
        g.configure(3, 6000)
        assertEquals(0, g.rearmAt)
        g.setEnabled(true)
        g.observe(1, 0, true)
        assertEquals(Event.NONE, g.observe(3, 100, true))
        g.observe(0, 200, true)
        assertEquals(Event.START, g.observe(3, 300, true))
    }
}
