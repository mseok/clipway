package dev.mseok.clipway

import dev.mseok.clipway.protocol.Clip
import dev.mseok.clipway.protocol.MacConnection
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNotNull
import org.junit.Test

/**
 * Talks to a running Mac app with the real Kotlin client. Each test is skipped unless
 * its environment variable holds a key the Mac knows (base64).
 */
class MacLiveTest {
    private fun open(key: ByteArray) = MacConnection.open("127.0.0.1", 47823, key, "live-test-phone", "Kotlin Test", 0)

    /** CW_TEST_PSK: the key of an existing pairing. */
    @Test
    fun handshakeClipAndPing() {
        val psk = System.getenv("CW_TEST_PSK")
        assumeNotNull(psk)
        val connection = open(Base64.getDecoder().decode(psk))
        connection.sendClip(Clip(System.getenv("CW_TEST_TEXT") ?: "from-kotlin", false, System.currentTimeMillis()))
        connection.send(JSONObject().put("t", "ping"))
        // The Mac may first deliver a clip that was copied there earlier.
        var reply = (connection.receive() as MacConnection.Record.Message).json
        if (reply.getString("t") == "clip") reply = (connection.receive() as MacConnection.Record.Message).json
        assertEquals("pong", reply.getString("t"))
        connection.close()
    }

    /** CW_TEST_QR_PSK: the key of the QR code that is on screen right now. */
    @Test
    fun pairingReplacesTheQrKey() {
        val qr = System.getenv("CW_TEST_QR_PSK")
        assumeNotNull(qr)
        val qrKey = Base64.getDecoder().decode(qr)
        val first = open(qrKey)
        val rotated = first.pairingKey
        first.close()
        Thread.sleep(500)
        // The QR key was spent on that handshake; only the derived key works from now on.
        assertThrows(Exception::class.java) { open(qrKey) }
        open(rotated).close()
    }
}
