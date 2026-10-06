package kr.glora.lsintifacebridge

import org.junit.Assert.*
import org.junit.Test

class LovenseProtocolTest {
    @Test fun fragmentedAndBatchedCommandsHaveNoDroppedSuffixes() {
        val seen = mutableListOf<Pair<String, Int?>>()
        val p = LovenseProtocol { name, value -> seen.add(name to value); "OK;" }
        assertEquals("", p.receive("Vibra"))
        assertEquals("OK;OK;", p.receive("te:12;Rotate:8;\r\n"))
        assertEquals(listOf("vibrate" to 12, "rotate" to 8), seen)
    }

    @Test fun invalidNumbersRemainInvalidAndOversizeBufferIsDiscarded() {
        val p = LovenseProtocol { _, value -> if (value == null) "ERR;" else "OK;" }
        assertEquals("ERR;", p.receive("Vibrate:invalid;"))
        assertEquals("ERR;", p.receive("X".repeat(4097)))
        assertEquals("OK;", p.receive("Rotate:5;"))
    }
}
