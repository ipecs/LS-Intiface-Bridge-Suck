package kr.glora.lsintifacebridge

import org.junit.Assert.*
import org.junit.Test

class ActuationOutputRouterTest {
    private val v = BleCommands.VIBRATION
    private val p = BleCommands.SUCTION

    @Test fun vibrationOnlyRetainsTheImmediateLatestValue() {
        val r = ActuationOutputRouter()
        assertEquals(v[3], r.select(v[3], null, 0, 0))
        assertEquals(v[1], r.select(v[1], null, 0, 25))
        assertEquals(v[0], r.select(v[0], null, 0, 50))
    }

    @Test fun pumpStartHoldsBrieflyThenLetsVibrationFallWhilePumpRemainsLatched() {
        val r = ActuationOutputRouter()
        assertEquals(p[2], r.select(v[3], p[2], 1, 0))
        assertTrue(r.applied(p[2], 50))
        assertEquals(p[2], r.select(v[1], p[2], 1, 399))
        assertEquals(v[1], r.select(v[1], p[2], 1, 400))
        assertFalse(r.applied(v[1], 420))
        assertEquals(v[0], r.select(v[0], p[2], 1, 425))
        assertEquals(v[3], r.select(v[3], p[2], 1, 800))
    }

    @Test fun pumpStopOverridesAHeldPositiveStageBeforeItsHoldEnds() {
        val r = ActuationOutputRouter()
        r.select(v[3], p[2], 1, 0)
        r.applied(p[2], 50)
        assertEquals(p[0], r.select(v[0], p[0], 2, 60))
        assertFalse(r.applied(p[2], 70))
        assertEquals(p[0], r.select(v[0], p[0], 2, 80))
        assertTrue(r.applied(p[0], 90))
        assertEquals(v[0], r.select(v[0], p[0], 2, 440))
    }

    @Test fun repeatedCallbacksCannotExtendThePumpDataHold() {
        val r = ActuationOutputRouter()
        r.select(v[3], p[2], 1, 0)
        assertTrue(r.applied(p[2], 50))
        assertFalse(r.applied(p[2], 399))
        assertEquals(v[1], r.select(v[1], p[2], 1, 400))
    }

    @Test fun aNewStageMustBeAcceptedEvenWhenItsCommandMatchesAnEarlierStage() {
        val r = ActuationOutputRouter()
        r.select(v[0], p[0], 1, 0)
        r.applied(p[0], 0)
        assertEquals(v[3], r.select(v[3], p[0], 1, 400))
        assertEquals(p[0], r.select(v[0], p[0], 2, 401))
        assertTrue(r.applied(p[0], 401))
        assertEquals(p[0], r.select(v[0], p[0], 2, 500))
    }

    @Test fun serialTwoPulseStagesDoNotReplayPumpWhenVibrationUpdates() {
        val r = ActuationOutputRouter()
        for ((index, command) in listOf(p[2], p[0], p[2], p[0]).withIndex()) {
            val time = index * 1000L
            val revision = index + 1L
            assertEquals(command, r.select(v[3], command, revision, time))
            assertTrue(r.applied(command, time + 25))
            for (offset in 375L..900L step 25) {
                val vibration = v[(offset / 25L % 4L).toInt()]
                assertEquals(vibration, r.select(vibration, command, revision, time + offset))
                assertFalse(r.applied(vibration, time + offset))
            }
        }
    }

    @Test fun generalStopAlsoCancelsAHeldPumpAndThenFlushesVibrationStop() {
        val r = ActuationOutputRouter()
        r.select(v[3], p[2], 1, 0)
        r.applied(p[2], 0)
        assertEquals(BleCommands.GLOBAL_STOP, r.select(v[0], BleCommands.GLOBAL_STOP, 2, 25))
        r.applied(BleCommands.GLOBAL_STOP, 50)
        assertEquals(v[0], r.select(v[0], null, 2, 400))
    }

    @Test fun resetCannotReplayAHeldPositiveCommand() {
        val r = ActuationOutputRouter()
        r.select(v[3], p[2], 1, 0)
        r.applied(p[2], 50)
        r.reset()
        assertEquals(v[0], r.select(v[0], null, 0, 60))
        assertEquals(p[0], r.select(v[0], p[0], 1, 70))
    }
}
