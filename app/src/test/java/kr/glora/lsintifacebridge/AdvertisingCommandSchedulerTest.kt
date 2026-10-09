package kr.glora.lsintifacebridge

import org.junit.Assert.*
import org.junit.Test

class AdvertisingCommandSchedulerTest {
    private val v = BleCommands.VIBRATION
    private fun applied(mode: Int, at: Long = 0) = AdvertisingCommandScheduler().apply {
        request(v[mode])
        assertEquals(v[mode], next(at))
        complete(true, at)
    }

    @Test fun briefLowPositiveValleyIsNotDiscardedByTheHundredMillisecondGate() {
        val s = applied(3)
        s.request(v[1])
        assertEquals(0L, s.delayMs(25))
        assertEquals(v[1], s.next(25))
        s.complete(true, 30)
        assertEquals(v[1], s.confirmed)
    }

    @Test fun everyVibrationDecreaseAndEveryStopIsImmediate() {
        for (target in listOf(v[2], v[1], v[0], BleCommands.SUCTION[0], BleCommands.GLOBAL_STOP)) {
            val s = applied(3)
            s.request(target)
            assertEquals(target, s.next(1))
        }
    }

    @Test fun deferredIncreaseIsCancelledWhenTheLatestValueReturnsToLow() {
        val s = applied(1)
        s.request(v[3])
        assertEquals(75L, s.delayMs(25))
        assertNull(s.next(25))
        s.request(v[1])
        assertNull(s.delayMs(30))
        assertNull(s.next(100))
        assertEquals(v[1], s.confirmed)
    }

    @Test fun completionUsesLatestRequestWithoutReplayingIntermediateValues() {
        val s = AdvertisingCommandScheduler()
        s.request(v[3])
        assertEquals(v[3], s.next(0))
        s.request(v[2])
        s.request(v[1])
        assertNull(s.next(1))
        assertEquals(v[3], s.complete(true, 10))
        assertEquals(v[1], s.next(10))
    }

    @Test fun stopArrivingDuringAnOperationWinsAfterItsCallback() {
        val s = AdvertisingCommandScheduler()
        s.request(v[3])
        assertEquals(v[3], s.next(0))
        s.request(v[2])
        s.request(v[0])
        assertNull(s.next(1))
        s.complete(true, 5)
        assertEquals(v[0], s.next(5))
    }

    @Test fun failedOperationDoesNotAutomaticallyReplayAPositiveRequest() {
        val s = AdvertisingCommandScheduler()
        s.request(v[2])
        assertEquals(v[2], s.next(0))
        s.request(v[3])
        s.complete(false, 10)
        assertNull(s.next(10000))
        s.request(v[0])
        assertEquals(v[0], s.next(10001))
    }

    @Test fun duplicateRequestsAndCallbacksDoNotExtendTheNextUpdateDeadline() {
        val s = applied(1)
        for (time in 1L..90L) {
            s.request(v[1])
            assertNull(s.next(time))
            assertNull(s.complete(true, time))
        }
        s.request(v[2])
        assertEquals(10L, s.delayMs(90))
        assertEquals(v[2], s.next(100))
    }

    @Test fun resetDropsPendingAndDeferredCommands() {
        val s = applied(1)
        s.request(v[3])
        assertNull(s.next(50))
        s.reset()
        assertNull(s.next(1000))
        s.request(v[2])
        assertEquals(v[2], s.next(1001))
        s.reset()
        assertNull(s.complete(true, 1002))
        assertNull(s.next(1003))
    }

    @Test fun actualInputWaveformChangesOutputWithoutALateMaximumReplay() {
        val c = ActuationController().apply { setFullVibrationRange(true) }
        val s = AdvertisingCommandScheduler()
        fun request(input: Int, time: Long): Int? {
            c.vibration(input, time)
            s.request(v[c.vibrationLevel])
            return s.next(time)
        }
        assertEquals(v[3], request(20, 0))
        s.complete(true, 0)
        assertEquals(v[1], request(3, 25))
        s.complete(true, 25)
        assertNull(request(20, 50))
        assertNull(request(3, 75))
        assertNull(s.next(125))
        assertEquals(v[2], request(10, 150))
        s.complete(true, 150)
        assertEquals(v[0], request(0, 175))
    }

    @Test fun extractedPacketTableRetainsTheKnownManufacturerBody() {
        val expected = byteArrayOf(0x6D, 0xB6.toByte(), 0x43, 0xCE.toByte(), 0x97.toByte(),
            0xFE.toByte(), 0x42, 0x7C, 0xD7.toByte(), 0x84.toByte(), 0x6F)
        assertArrayEquals(expected, BleCommands.body(v[2]))
    }
}
