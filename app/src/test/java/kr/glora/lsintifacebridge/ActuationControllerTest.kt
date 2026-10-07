package kr.glora.lsintifacebridge

import org.junit.Assert.*
import org.junit.Test
import kr.glora.lsintifacebridge.ActuationController.Phase
import kr.glora.lsintifacebridge.ActuationController.StopReason

class ActuationControllerTest {
    private fun ready(pulse: Long = 700, cooldown: Long = 700, command: Int = 2,
        second: Boolean = false, threshold: Int = 3) = ActuationController().apply {
        configure(pulse, cooldown, 0, command, threshold, second)
        setSuctionEnabled(true)
        suctionInput(0, 0)
    }

    @Test fun vibrationStartsAtThreeAndPreservesHigherLevels() {
        val c = ready()
        for ((input, expected) in listOf(0 to 0, 2 to 0, 3 to 1, 4 to 1,
            5 to 2, 9 to 2, 10 to 3, 20 to 3)) {
            c.vibration(input, 0)
            assertEquals("input=$input", expected, c.vibrationLevel)
        }
    }

    @Test fun riseTriggersAtTheConfiguredThresholdWithTheSelectedCommand() {
        val c = ready(command = 3, threshold = 8)
        c.suctionInput(7, 10)
        assertEquals(Phase.IDLE, c.phase)
        c.suctionInput(8, 20)
        assertEquals(Phase.STARTING, c.phase)
        assertEquals(3, c.suctionLevel)
        assertEquals(1, c.acceptedPeaks)
        c.pumpDataApplied(3, 30)
        assertEquals(Phase.SUCKING, c.phase)
    }

    @Test fun constantHighCannotExtendTheAbsoluteLimitOrRetrigger() {
        val c = ready(pulse = 10000)
        c.suctionInput(20, 0)
        c.pumpDataApplied(2, 0)
        for (time in 100L..5000L step 100) c.suctionInput(20, time)
        assertEquals(Phase.STOPPING, c.phase)
        c.stopDataApplied(5300)
        c.suctionInput(20, 5999)
        assertEquals(Phase.COOLDOWN, c.phase)
        c.suctionInput(20, 6000)
        assertEquals(Phase.IDLE, c.phase)
        c.suctionInput(0, 6001)
        c.suctionInput(10, 6100)
        assertEquals(Phase.STARTING, c.phase)
        assertEquals(2, c.acceptedPeaks)
    }

    @Test fun stopHoldWaitsForAcceptanceAndBusyPeaksAreConsumed() {
        val c = ready()
        c.suctionInput(10, 0)
        c.pumpDataApplied(2, 0)
        c.tick(700)
        c.tick(1500)
        assertEquals(Phase.STOPPING, c.phase)
        c.stopDataApplied(2000)
        c.suctionInput(0, 2150)
        c.suctionInput(20, 2300)
        assertEquals(Phase.COOLDOWN, c.phase)
        assertEquals(1, c.skippedPeaks)
        c.suctionInput(20, 2701)
        assertEquals(Phase.IDLE, c.phase)
        c.suctionInput(18, 2702)
        c.suctionInput(20, 2703)
        assertEquals(Phase.STARTING, c.phase)
    }

    @Test fun fallDuringBusyTimeArmsTheNextFreshRiseEvenAboveFive() {
        val c = ready()
        c.suctionInput(10, 0)
        c.pumpDataApplied(2, 0)
        c.suctionInput(7, 400)
        c.tick(700)
        c.stopDataApplied(700)
        c.tick(1400)
        c.suctionInput(12, 1401)
        assertEquals(Phase.STARTING, c.phase)
        assertEquals(2, c.acceptedPeaks)
    }

    @Test fun idleDoesNotReplayAPeakObservedDuringThePreviousCycle() {
        val c = ready()
        c.suctionInput(10, 0)
        c.pumpDataApplied(2, 0)
        c.suctionInput(7, 100)
        c.suctionInput(12, 200)
        c.tick(700)
        c.stopDataApplied(700)
        c.suctionInput(20, 1500)
        c.tick(2000)
        assertEquals(Phase.IDLE, c.phase)
        assertEquals(1, c.acceptedPeaks)
        assertEquals(1, c.skippedPeaks)
        c.suctionInput(20, 2001)
        assertEquals(Phase.IDLE, c.phase)
    }

    @Test fun sparseStreamDoesNotCutAnAcceptedBoundedPulseAtOnePointTwoSeconds() {
        val c = ready(pulse = 2300)
        c.suctionInput(3, 0)
        c.pumpDataApplied(2, 0)
        c.tick(1200)
        assertEquals(Phase.SUCKING, c.phase)
        c.tick(2299)
        assertEquals(Phase.SUCKING, c.phase)
        c.tick(2300)
        assertEquals(Phase.STOPPING, c.phase)
        assertEquals(StopReason.PULSE_LIMIT, c.stopReason)
    }

    @Test fun pulseDurationStartsAtAcceptanceAndRepeatedCallbackDoesNotExtendIt() {
        val c = ready(pulse = 2300)
        assertTrue(c.manualPulse(2, 0))
        c.tick(120)
        assertEquals(Phase.STARTING, c.phase)
        c.pumpDataApplied(2, 120)
        c.pumpDataApplied(2, 1000)
        c.tick(2300)
        assertEquals(Phase.SUCKING, c.phase)
        c.tick(2420)
        assertEquals(Phase.STOPPING, c.phase)
    }

    @Test fun missingStartAcceptanceStopsAndLateAcceptanceCannotRestart() {
        val c = ready()
        assertTrue(c.manualPulse(2, 0))
        c.tick(2499)
        assertEquals(Phase.STARTING, c.phase)
        c.tick(2500)
        assertEquals(Phase.STOPPING, c.phase)
        assertEquals(StopReason.RADIO_FAILURE, c.stopReason)
        c.pumpDataApplied(2, 2501)
        assertEquals(Phase.STOPPING, c.phase)
        assertEquals(0, c.suctionLevel)
    }

    @Test fun secondPulseIsSerialAndBothStopHoldsStartAfterAcceptance() {
        val c = ready(second = true)
        assertTrue(c.manualPulse(2, 0))
        c.pumpDataApplied(2, 0)
        c.tick(700)
        assertEquals(Phase.BETWEEN_STOPPING, c.phase)
        assertEquals(0, c.suctionLevel)
        c.stopDataApplied(800)
        c.tick(1499)
        assertEquals(Phase.BETWEEN_WAIT, c.phase)
        c.tick(1500)
        assertEquals(Phase.SECOND_STARTING, c.phase)
        c.pumpDataApplied(2, 1650)
        c.stopDataApplied(1700) // An irrelevant old stop cannot cancel this stage.
        c.tick(2649)
        assertEquals(Phase.SECOND_PULSE, c.phase)
        c.tick(2650)
        assertEquals(Phase.STOPPING, c.phase)
        assertEquals(StopReason.SEQUENCE_COMPLETE, c.stopReason)
        c.stopDataApplied(2700)
        c.tick(3399)
        assertEquals(Phase.COOLDOWN, c.phase)
        c.tick(3400)
        assertEquals(Phase.IDLE, c.phase)
    }

    @Test fun secondPulseCannotStartBeforeTheIntermediateStopWasAccepted() {
        val c = ready(second = true)
        assertTrue(c.manualPulse(2, 0))
        c.pumpDataApplied(2, 0)
        c.tick(700)
        c.tick(10000)
        assertEquals(Phase.BETWEEN_STOPPING, c.phase)
        assertEquals(0, c.suctionLevel)
    }

    private fun atStage(target: Phase): ActuationController {
        val c = ready(second = true)
        assertTrue(c.manualPulse(2, 0))
        val steps: List<() -> Unit> = listOf(
            { c.pumpDataApplied(2, 0) }, { c.tick(700) }, { c.stopDataApplied(700) },
            { c.tick(1400) }, { c.pumpDataApplied(2, 1400) })
        for (step in steps) {
            if (c.phase == target) return c
            step()
        }
        assertEquals(target, c.phase)
        return c
    }

    @Test fun explicitStopCancelsEveryStageWithoutAFuturePositiveCommand() {
        for (stage in listOf(Phase.STARTING, Phase.SUCKING, Phase.BETWEEN_STOPPING,
            Phase.BETWEEN_WAIT, Phase.SECOND_STARTING, Phase.SECOND_PULSE)) {
            val c = atStage(stage)
            c.stopAll()
            c.pumpDataApplied(2, 1500)
            c.tick(100000)
            assertEquals("stage=$stage", Phase.STOPPING, c.phase)
            assertFalse(c.pumpActive)
            assertEquals(0, c.suctionLevel)
            c.stopDataApplied(100000)
            c.tick(100700)
            assertEquals(Phase.IDLE, c.phase)
        }
    }

    @Test fun disconnectAndDisableCancelTheExtraStep() {
        val c = atStage(Phase.BETWEEN_WAIT)
        c.stopAll(StopReason.CONNECTION_LOST)
        c.tick(10000)
        assertEquals(0, c.suctionLevel)
        assertEquals(StopReason.CONNECTION_LOST, c.stopReason)
        val d = atStage(Phase.SECOND_PULSE)
        d.setSuctionEnabled(false)
        d.tick(10000)
        assertEquals(0, d.suctionLevel)
        assertEquals(Phase.STOPPING, d.phase)
    }

    @Test fun totalRequestedPumpOnTimeIncludesTheExtraStepAndCommandTwoIsRequired() {
        val c = ready(pulse = 10000, second = true)
        assertEquals(4000L, c.pulseMs)
        assertTrue(c.secondPulseEnabled)
        for (command in listOf(1, 3)) {
            val d = ready(pulse = 10000, command = command, second = true)
            assertFalse(d.secondPulseEnabled)
            assertEquals(5000L, d.pulseMs)
        }
        assertFalse(ActuationController().secondPulseEnabled)
    }

    @Test fun manualCommandThreeDoesNotUseTheCommandTwoExtraStep() {
        val c = ready(second = true)
        assertTrue(c.manualPulse(3, 0))
        c.pumpDataApplied(3, 0)
        c.tick(700)
        assertEquals(Phase.STOPPING, c.phase)
    }

    @Test fun manualCycleIgnoresStreamValuesButObeysItsOwnLimit() {
        val c = ready(pulse = 4900)
        assertTrue(c.manualPulse(3, 0))
        c.pumpDataApplied(3, 0)
        c.suctionInput(0, 100)
        c.suctionInput(8, 200)
        c.suctionInput(20, 4000)
        c.tick(4899)
        assertEquals(Phase.SUCKING, c.phase)
        assertEquals(3, c.suctionLevel)
        c.tick(4900)
        assertEquals(Phase.STOPPING, c.phase)
    }

    @Test fun settingsChangeCancelsAnIntermediateWait() {
        val c = atStage(Phase.BETWEEN_WAIT)
        c.configure(4000, 700, 1000)
        c.tick(10000)
        assertEquals(Phase.STOPPING, c.phase)
        assertEquals(0, c.suctionLevel)
        assertEquals(StopReason.SETTINGS_CHANGED, c.stopReason)
    }

    @Test fun explicitRotateZeroCancelsButDerivedVibrationZeroDoesNotCutThePulse() {
        val c = ready(pulse = 2300)
        c.suctionInput(10, 0)
        c.pumpDataApplied(2, 0)
        c.suctionInput(0, 100, stopOnZero = false)
        assertEquals(Phase.SUCKING, c.phase)
        c.suctionInput(0, 200)
        assertEquals(Phase.STOPPING, c.phase)
        assertEquals(StopReason.ZERO_COMMAND, c.stopReason)
    }

    @Test fun enablingMidHighStreamRequiresAFreshFallAndRise() {
        val c = ActuationController()
        c.setSuctionEnabled(true)
        c.suctionInput(15, 0)
        c.suctionInput(20, 100)
        assertEquals(Phase.IDLE, c.phase)
        c.suctionInput(18, 200)
        c.suctionInput(20, 300)
        assertEquals(Phase.STARTING, c.phase)
    }

    @Test fun onePointJitterDoesNotGenerateAdditionalPeaks() {
        val c = ready()
        c.suctionInput(10, 0)
        c.pumpDataApplied(2, 0)
        c.suctionInput(9, 100)
        c.suctionInput(10, 200)
        c.suctionInput(9, 300)
        c.tick(700)
        c.stopDataApplied(700)
        c.suctionInput(9, 1401)
        c.suctionInput(10, 1402)
        assertEquals(Phase.IDLE, c.phase)
        assertEquals(1, c.acceptedPeaks)
        assertEquals(0, c.skippedPeaks)
        c.suctionInput(8, 1500)
        c.suctionInput(10, 1600)
        assertEquals(Phase.STARTING, c.phase)
    }

    @Test fun freshGeneralStopCannotInheritAnAlmostFinishedCooldown() {
        val c = ready(cooldown = 1000)
        c.stopAll()
        c.stopDataApplied(0)
        c.tick(999)
        c.stopAll(restartHold = true)
        c.tick(1100)
        assertEquals(Phase.STOPPING, c.phase)
        c.stopDataApplied(1100)
        c.tick(2099)
        assertEquals(Phase.COOLDOWN, c.phase)
        c.tick(2100)
        assertEquals(Phase.IDLE, c.phase)
    }

    @Test fun vibrationKeepsItsSeparateExpiryAndResumesTheLatestValue() {
        val c = ready(cooldown = 1000)
        c.vibration(20, 0)
        c.suctionInput(10, 0)
        c.pumpDataApplied(2, 0)
        c.vibration(5, 500)
        c.tick(700)
        c.stopDataApplied(700)
        c.vibration(12, 1500)
        c.tick(1700)
        assertEquals(Phase.IDLE, c.phase)
        assertEquals(3, c.vibrationLevel)
        c.tick(2700)
        assertEquals(0, c.vibrationLevel)
    }
}
