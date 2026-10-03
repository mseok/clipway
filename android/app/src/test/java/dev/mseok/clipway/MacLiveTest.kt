package dev.mseok.clipway

import dev.mseok.clipway.protocol.Clip
import dev.mseok.clipway.protocol.MacConnection
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test

/**
 * Talks to a running Mac app with the real Kotlin client. Skipped unless
 * CW_TEST_PSK (base64) and CW_TEST_MAC_ID name a pairing that the Mac knows
 * for the phone id "fake-phone".
 */
class MacLiveTest {
    @Test
    fun handshakeClipAndPing() {
        val psk = System.getenv("CW_TEST_PSK")
        val macId = System.getenv("CW_TEST_MAC_ID")
        assumeNotNull(psk, macId)
        val connection = MacConnection.open(
            "127.0.0.1", 47823, macId, Base64.getDecoder().decode(psk), "fake-phone", "Kotlin Test", 0)
        connection.sendClip(Clip(System.getenv("CW_TEST_TEXT") ?: "from-kotlin", false, System.currentTimeMillis()))
        connection.send(JSONObject().put("t", "ping"))
        var reply = connection.receive()
        // The Mac may first deliver a clip that was copied there earlier.
        if (reply.getString("t") == "clip") reply = connection.receive()
        assertEquals("pong", reply.getString("t"))
        connection.close()
    }
}
