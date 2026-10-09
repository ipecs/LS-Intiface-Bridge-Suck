package kr.glora.lsintifacebridge

import org.junit.Assert.*
import org.junit.Test

class ActuationIntegrationTest {
    @Test fun fastScriptUpdatesKeepOneBoundedTwoPulseCycleAndNeverReplayBusyCrossings() {
        val c = ActuationController().apply {
            configure(2300, 1000, 0, secondPulse = true, recovery = 2000)
            setFullVibrationRange(true)
            setSuctionEnabled(true)
        }
        // Completing timers is insufficient: the separate user confirmation is required.
        assertTrue(c.manualPulse(2, 0))
        c.pumpDataApplied(2, 0)
        c.tick(2300)
        c.stopDataApplied(2300)
        c.tick(3300)
        c.pumpDataApplied(2, 3300)
        c.tick(4300)
        c.stopDataApplied(4300)
        c.tick(6300)
        assertTrue(c.confirmRelease())

        val gate = PeakSuctionGate().apply { setEnabled(true) }
        val router = ActuationOutputRouter()
        val radio = AdvertisingCommandScheduler()
        val sent = mutableListOf<Pair<Long, Int>>()
        val vibrationDuringMainPulse = mutableListOf<Int>()
        var completionAt: Long? = null

        fun select(now: Long): Int {
            val pump = if (c.phase == ActuationController.Phase.IDLE) null else BleCommands.SUCTION[c.suctionLevel]
            return router.select(BleCommands.VIBRATION[c.vibrationLevel], pump, c.pumpRevision, now)
        }
        fun accept(command: Int, now: Long) {
            select(now)
            if (router.applied(command, now)) {
                if (command == BleCommands.SUCTION[0]) c.stopDataApplied(now)
                else c.pumpDataApplied(BleCommands.SUCTION.indexOf(command), now)
            }
        }

        for (now in 7000L..18000L step 25) {
            c.tick(now)
            if (completionAt?.let { now >= it } == true) {
                val command = radio.complete(true, now)!!
                completionAt = null
                accept(command, now)
            }
            // Repeated deep valleys and strong rises while busy; then a constant high tail.
            val input = if (now < 7100) 3 else if (now >= 13400) 18
                else if ((now - 7100) / 200 % 2 == 0L) 18 else 3
            c.vibration(input, now)
            if (gate.observe(input, now, c.phase == ActuationController.Phase.IDLE) == PeakSuctionGate.Event.START) {
                assertTrue(c.automaticPulse(now))
            }
            val command = select(now)
            radio.request(command)
            if (radio.confirmed == command && radio.inFlight == null) accept(command, now)
            radio.next(now)?.let {
                sent += now to it
                if (c.phase == ActuationController.Phase.SUCKING && it in BleCommands.VIBRATION) {
                    vibrationDuringMainPulse += it
                }
                completionAt = now + 50 // Delayed asynchronous acceptance.
            }
        }
        assertEquals(listOf(BleCommands.SUCTION[2], BleCommands.SUCTION[0],
            BleCommands.SUCTION[2], BleCommands.SUCTION[0]), sent.map { it.second }.filter { it in BleCommands.SUCTION })
        assertEquals(1, gate.accepted)
        assertTrue(gate.skipped > 0)
        assertEquals(ActuationController.Phase.IDLE, c.phase)
        assertTrue(vibrationDuringMainPulse.contains(BleCommands.VIBRATION[1]))
        assertTrue(vibrationDuringMainPulse.contains(BleCommands.VIBRATION[3]))
        assertEquals(BleCommands.VIBRATION[3], radio.confirmed)
    }
}
