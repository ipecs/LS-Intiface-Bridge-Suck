package kr.glora.lsintifacebridge

import org.junit.Assert.*
import org.junit.Test

class ActuationControllerTest {
    private fun ready(): ActuationController = ActuationController().apply {
        setSuctionEnabled(true)
        suctionInput(0, 0)
    }

    @Test fun longHighStreamCannotExtendTheAbsoluteLimitOrRestart() {
        val c = ready()
        c.configure(10000, 700, 0)
        c.suctionInput(20, 0)
        for (time in 100L..5000L step 100) c.suctionInput(20, time)
        assertEquals(ActuationController.Phase.STOPPING, c.phase)
        assertEquals(0, c.suctionLevel)
        c.stopDataApplied(5300)
        c.suctionInput(20, 5999)
        assertEquals(ActuationController.Phase.COOLDOWN, c.phase)
        c.suctionInput(20, 6000)
        assertEquals(ActuationController.Phase.IDLE, c.phase)
        c.suctionInput(20, 8000)
        assertEquals(ActuationController.Phase.IDLE, c.phase)
        c.suctionInput(0, 8001)
        c.suctionInput(20, 8100)
        assertEquals(ActuationController.Phase.SUCKING, c.phase)
    }

    @Test fun cooldownStartsAfterAndroidAcceptsStopAndCannotBeInterrupted() {
        val c = ready()
        c.suctionInput(10, 0)
        c.tick(700)
        c.tick(1500)
        assertEquals(ActuationController.Phase.STOPPING, c.phase)
        c.stopDataApplied(2000)
        c.suctionInput(0, 2150)
        c.suctionInput(20, 2300)
        assertEquals(ActuationController.Phase.COOLDOWN, c.phase)
        c.tick(3000)
        assertEquals(ActuationController.Phase.IDLE, c.phase)
        assertTrue(c.manualPulse(1, 3000))
    }

    @Test fun toggleAndDisconnectCancelSuction() {
        val c = ready()
        c.suctionInput(20, 0)
        c.setSuctionEnabled(false)
        assertEquals(0, c.suctionLevel)
        assertEquals(ActuationController.Phase.STOPPING, c.phase)
        c.stopDataApplied(100)
        c.tick(1100)
        c.setSuctionEnabled(true)
        c.suctionInput(20, 1101)
        assertEquals(ActuationController.Phase.IDLE, c.phase)
        c.suctionInput(0, 1102)
        c.suctionInput(20, 1103)
        c.stopAll()
        assertEquals(0, c.suctionLevel)
        assertEquals(0, c.vibrationLevel)
    }

    @Test fun staleVibrationDoesNotStayAliveThroughSuctionCommands() {
        val c = ready()
        c.vibration(20, 0)
        c.configure(5000, 700, 0)
        c.suctionInput(20, 0)
        c.suctionInput(20, 1000)
        c.tick(1200)
        assertEquals(0, c.vibrationLevel)
        assertEquals(1, c.suctionLevel)
        c.tick(2200)
        assertEquals(ActuationController.Phase.STOPPING, c.phase)
    }

    @Test fun noBacklogAndVibrationResumesTheLatestValue() {
        val c = ready()
        c.vibration(20, 0)
        c.suctionInput(10, 0)
        c.vibration(5, 500)
        c.suctionInput(20, 500)
        c.tick(700)
        c.stopDataApplied(700)
        c.vibration(12, 1500)
        c.tick(1700)
        assertEquals(ActuationController.Phase.IDLE, c.phase)
        assertEquals(3, c.vibrationLevel)
        assertEquals(0, c.suctionLevel)
    }

    @Test fun threeModesAreOptInAndManualTestsAreBounded() {
        val c = ready()
        assertTrue(c.manualPulse(3, 0))
        assertEquals(3, c.suctionLevel)
        c.tick(700)
        assertEquals(0, c.suctionLevel)
        c.stopDataApplied(700)
        c.tick(1700)
        c.suctionInput(0, 1701)
        c.suctionInput(20, 1800)
        assertEquals(1, c.suctionLevel)
        c.stopAll()
    }

    @Test fun lowValuesStopVibrationAndExplicitZeroStopsPump() {
        val c = ready()
        c.vibration(2, 0)
        assertEquals(0, c.vibrationLevel)
        c.suctionInput(20, 0)
        c.suctionInput(0, 100)
        assertEquals(ActuationController.Phase.STOPPING, c.phase)
    }

    @Test fun vibrationStartsAtThreeAndPreservesTheExistingHigherLevels() {
        val c = ready()
        for ((input, expected) in listOf(0 to 0, 2 to 0, 3 to 1, 4 to 1, 5 to 2,
            9 to 2, 10 to 3, 20 to 3)) {
            c.vibration(input, 0)
            assertEquals("input=$input", expected, c.vibrationLevel)
        }
    }

    @Test fun disablingSuctionRequestsStopEvenWhenTheAppBelievesItIsIdle() {
        val c = ready()
        c.vibration(20, 0)
        c.setSuctionEnabled(false)
        assertEquals(ActuationController.Phase.STOPPING, c.phase)
        c.stopDataApplied(10)
        c.tick(1010)
        assertEquals(ActuationController.Phase.IDLE, c.phase)
        assertEquals(3, c.vibrationLevel)
    }

    @Test fun reportedCutDistinguishesInputTimeoutFromPulseLimit() {
        val c = ready()
        c.configure(3000, 700, 0)
        c.suctionInput(20, 0)
        c.tick(1200)
        assertEquals(ActuationController.StopReason.INPUT_TIMEOUT, c.stopReason)
        c.suctionInput(0, 1300)
        assertEquals(ActuationController.StopReason.INPUT_TIMEOUT, c.stopReason)
        c.stopDataApplied(1300)
        c.tick(2000)
        assertTrue(c.manualPulse(1, 2000))
        c.tick(3200)
        assertEquals(ActuationController.Phase.SUCKING, c.phase)
        c.tick(5000)
        assertEquals(ActuationController.StopReason.PULSE_LIMIT, c.stopReason)
    }

    @Test fun freshGeneralStopCannotInheritAnAlmostFinishedCooldown() {
        val c = ready()
        c.stopAll()
        c.stopDataApplied(0)
        c.tick(999)
        c.stopAll(restartHold = true)
        c.tick(1100)
        assertEquals(ActuationController.Phase.STOPPING, c.phase)
        c.stopDataApplied(1100)
        c.tick(2099)
        assertEquals(ActuationController.Phase.COOLDOWN, c.phase)
        c.tick(2100)
        assertEquals(ActuationController.Phase.IDLE, c.phase)
        assertEquals(0, c.suctionLevel)
        assertEquals(0, c.vibrationLevel)
    }

    @Test fun scriptValuesCannotInterruptOrExtendAManualCycle() {
        val c = ready()
        c.configure(4900, 1000, 0)
        assertTrue(c.manualPulse(3, 0))
        c.suctionInput(0, 100)
        c.suctionInput(8, 200)
        c.suctionInput(20, 4000)
        c.tick(4899)
        assertEquals(ActuationController.Phase.SUCKING, c.phase)
        assertEquals(3, c.suctionLevel)
        c.tick(4900)
        assertEquals(ActuationController.Phase.STOPPING, c.phase)
        assertEquals(ActuationController.StopReason.PULSE_LIMIT, c.stopReason)
    }

    @Test fun manualCycleStillObeysExplicitStopAndDisconnect() {
        val c = ready()
        c.configure(4900, 1000, 0)
        assertTrue(c.manualPulse(3, 0))
        c.stopAll(ActuationController.StopReason.CONNECTION_LOST)
        assertEquals(ActuationController.Phase.STOPPING, c.phase)
        assertEquals(0, c.suctionLevel)
        assertEquals(ActuationController.StopReason.CONNECTION_LOST, c.stopReason)
    }
}
