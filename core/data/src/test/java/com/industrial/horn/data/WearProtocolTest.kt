package com.industrial.horn.data

import com.industrial.horn.audio.HornType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearProtocolTest {
    @Test
    fun commandsRoundTrip() {
        WearCommand.entries.forEach { command ->
            assertEquals(command, WearProtocol.decodeCommand(WearProtocol.encodeCommand(command)))
        }
        assertNull(WearProtocol.decodeCommand("NOPE".toByteArray()))
    }

    @Test
    fun statusRoundTripsEveryVoice() {
        for (type in HornType.entries) {
            val status = WearStatus(isHonking = true, voice = type)
            assertEquals(status, WearProtocol.decodeStatus(WearProtocol.encodeStatus(status)))
        }
    }

    @Test
    fun malformedStatusIsRejected() {
        assertNull(WearProtocol.decodeStatus("1".toByteArray()))
        assertNull(WearProtocol.decodeStatus("1|NOT_A_VOICE".toByteArray()))
    }
}
