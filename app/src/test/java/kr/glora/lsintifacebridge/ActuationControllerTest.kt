package kr.glora.lsintifacebridge

import org.junit.Assert.*
import org.junit.Test
import kr.glora.lsintifacebridge.ActuationController.Phase
import kr.glora.lsintifacebridge.ActuationController.StopReason

class ActuationControllerTest {
    private fun ready(pulse: Long = 700, cooldown: Long = 700, command: Int = 2,
        second: Boolean = false) = ActuationController().apply {
        configure(pulse, cooldown, 0, command, second)
        setSuctionEnabled(true)
    }

    @Test fun vibrationStartsAtThreeAndPreservesHigherLevels() {
        val c = ready()
        for ((input, expected) in listOf(0 to 0, 2 to 0, 3 to 1, 4 to 1,
            5 to 2, 9 to 2, 10 to 3, 20 to 3)) {
            c.vibration(input, 0)
            assertEquals("input=$input", expected, c.vibrationLevel)
        }
    }

    @Test fun fullVibrationRangeReachesMaximumOnlyFromFifteen() {
        val c = ready().apply { setFullVibrationRange(true) }
        for ((input, expected) in listOf(0 to 0, 2 to 0, 3 to 1, 4 to 1, 5 to 1,
            8 to 1, 9 to 2, 10 to 2, 14 to 2, 15 to 3, 20 to 3)) {
            c.vibration(input, 0)
            assertEquals("input=$input", expected, c.vibrationLevel)
        }
    }

    @Test fun changingVibrationRangeDoesNotRefreshOrResurrectExpiredInput() {
        val c = ready()
        c.vibration(12, 0)
        c.setFullVibrationRange(true)
        assertEquals(2, c.vibrationLevel)
        c.tick(1200)
        assertEquals(0, c.vibrationLevel)
        c.setFullVibrationRange(false)
        assertEquals(0, c.vibrationLevel)
    }

    @Test fun disablingAnIdleManualTestDoesNotInterruptScriptVibration() {
        val c = ready()
        c.vibration(12, 0)
        c.setSuctionEnabled(false, requestIdleStop = false)
        assertEquals(Phase.IDLE, c.phase)
        assertEquals(3, c.vibrationLevel)
    }

    @Test fun sparseStreamDoesNotCutAnAcceptedBoundedPulseAtOnePointTwoSeconds() {
        val c = ready(pulse = 2300)
        assertTrue(c.manualPulse(2, 0))
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
        c.vibration(0, 100)
        c.vibration(8, 200)
        c.vibration(20, 4000)
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
        assertTrue(c.manualPulse(2, 0))
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

    private fun completedManualSequence(): ActuationController {
        val c = ready(second = true)
        c.configure(700, 700, 0, secondPulse = true, recovery = 2000)
        assertTrue(c.manualPulse(2, 0))
        c.pumpDataApplied(2, 0)
        c.tick(700)
        c.stopDataApplied(800)
        c.tick(1500)
        c.pumpDataApplied(2, 1550)
        c.tick(2550)
        c.stopDataApplied(2600)
        assertFalse(c.manualSequenceCompleted)
        c.tick(4599)
        assertEquals(Phase.COOLDOWN, c.phase)
        c.tick(4600)
        assertTrue(c.manualSequenceCompleted)
        return c
    }

    @Test fun automaticPumpRequiresACompletedManualTestAndUserConfirmation() {
        val c = ready(second = true)
        assertFalse(c.automaticPulse(0))
        assertFalse(c.confirmRelease())
        val tested = completedManualSequence()
        assertFalse(tested.releaseVerified)
        assertFalse(tested.automaticPulse(4600))
        assertTrue(tested.confirmRelease())
        assertTrue(tested.automaticPulse(4700))
        assertEquals(2, tested.suctionLevel)
        assertFalse(tested.automaticPulse(4701))
    }

    @Test fun everyPumpTimingChangeInvalidatesThePreviousReleaseObservation() {
        for (setting in 0..4) {
            val c = completedManualSequence()
            assertTrue(c.confirmRelease())
            c.configure(if (setting == 0) 800 else 700, if (setting == 1) 800 else 700, 4600,
                command = if (setting == 2) 1 else 2, secondPulse = setting != 3,
                recovery = if (setting == 4) 3000 else 2000)
            assertFalse("setting=$setting", c.releaseVerified)
            assertFalse(c.automaticPulse(4700))
        }
    }

    @Test fun UnchangedPumpSettingsAndVibrationScalePreserveTheReleaseObservation() {
        val c = completedManualSequence()
        assertTrue(c.confirmRelease())
        c.configure(700, 700, 4600, command = 2, secondPulse = true, recovery = 2000)
        c.setFullVibrationRange(true)
        assertTrue(c.releaseVerified)
    }

    @Test fun scriptFallChangesVibrationWithoutCuttingAnAcceptedAutomaticPulse() {
        val c = completedManualSequence()
        assertTrue(c.confirmRelease())
        assertTrue(c.automaticPulse(5000))
        c.pumpDataApplied(2, 5010)
        c.vibration(20, 5010)
        c.vibration(0, 5100)
        c.tick(5709)
        assertEquals(Phase.SUCKING, c.phase)
        assertEquals(0, c.vibrationLevel)
        assertEquals(2, c.suctionLevel)
        c.tick(5710)
        assertEquals(Phase.BETWEEN_STOPPING, c.phase)
    }

    @Test fun anAbortedManualSequenceCannotBeConfirmedAsRelease() {
        for (stage in listOf(Phase.STARTING, Phase.SUCKING, Phase.BETWEEN_WAIT, Phase.SECOND_PULSE)) {
            val c = atStage(stage)
            c.stopAll()
            c.stopDataApplied(5000)
            c.tick(6000)
            assertFalse("stage=$stage", c.manualSequenceCompleted)
            assertFalse(c.confirmRelease())
        }
    }

    @Test fun aSinglePulseOrDifferentCommandCannotValidateTheTwoPulseSequence() {
        for ((command, second) in listOf(2 to false, 3 to true)) {
            val c = ready(second = second)
            assertTrue(c.manualPulse(command, 0))
            c.pumpDataApplied(command, 0)
            c.tick(700)
            c.stopDataApplied(800)
            c.tick(1500)
            assertFalse(c.manualSequenceCompleted)
            assertFalse(c.confirmRelease())
        }
    }

    @Test fun aNewManualTestInvalidatesConfirmationUntilItsOwnCompletion() {
        val c = completedManualSequence()
        assertTrue(c.confirmRelease())
        assertTrue(c.manualPulse(2, 4700))
        assertFalse(c.releaseVerified)
        assertFalse(c.confirmRelease())
    }

    @Test fun stopWaitsForDataAcceptanceThenHonorsTheLongerRecoveryTime() {
        val c = ready()
        c.configure(700, 700, 0, recovery = 2000)
        assertTrue(c.manualPulse(2, 0))
        c.pumpDataApplied(2, 0)
        c.tick(700)
        c.tick(5000)
        assertEquals(Phase.STOPPING, c.phase)
        c.stopDataApplied(5000)
        c.tick(6999)
        assertEquals(Phase.COOLDOWN, c.phase)
        c.tick(7000)
        assertEquals(Phase.IDLE, c.phase)
    }

    @Test fun repeatedManualStartsCannotExtendAnAlreadyRunningPulse() {
        val c = ready()
        assertTrue(c.manualPulse(2, 0))
        c.pumpDataApplied(2, 0)
        assertFalse(c.manualPulse(2, 699))
        c.tick(700)
        assertEquals(Phase.STOPPING, c.phase)
    }
}
