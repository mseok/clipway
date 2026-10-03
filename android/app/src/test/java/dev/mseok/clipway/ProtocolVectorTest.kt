package dev.mseok.clipway

import dev.mseok.clipway.protocol.BridgeCrypto
import dev.mseok.clipway.protocol.FrameCipher
import dev.mseok.clipway.protocol.PairedMac
import java.io.File
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Checks the Kotlin implementation against testvectors/handshake.json (shared with the Mac tests). */
class ProtocolVectorTest {
    private val vectors = JSONObject(File(System.getProperty("clipway.root"), "testvectors/handshake.json").readText())

    private fun bytes(name: String): ByteArray = Base64.getDecoder().decode(vectors.getString(name))

    @Test
    fun bothSidesDeriveTheReferenceKeys() {
        val phone = BridgeCrypto.keyPairFromRaw(bytes("phonePrivate"), bytes("phonePublic"))
        val mac = BridgeCrypto.keyPairFromRaw(bytes("macPrivate"), bytes("macPublic"))
        val phoneSide = BridgeCrypto.deriveKeys(
            bytes("psk"), phone.private, bytes("macPublic"), bytes("phoneHello"), bytes("macHello"))
        val macSide = BridgeCrypto.deriveKeys(
            bytes("psk"), mac.private, bytes("phonePublic"), bytes("phoneHello"), bytes("macHello"))
        for (keys in listOf(phoneSide, macSide)) {
            assertArrayEquals(bytes("phoneToMacKey"), keys.phoneToMac)
            assertArrayEquals(bytes("macToPhoneKey"), keys.macToPhone)
        }
    }

    @Test
    fun generatedKeyPairsAgree() {
        val a = BridgeCrypto.generateKeyPair()
        val b = BridgeCrypto.generateKeyPair()
        assertEquals(32, a.publicRaw.size)
        val hello = "x".toByteArray()
        val left = BridgeCrypto.deriveKeys(bytes("psk"), a.private, b.publicRaw, hello, hello)
        val right = BridgeCrypto.deriveKeys(bytes("psk"), b.private, a.publicRaw, hello, hello)
        assertArrayEquals(left.phoneToMac, right.phoneToMac)
        assertArrayEquals(left.macToPhone, right.macToPhone)
    }

    @Test
    fun recordsMatchTheReferenceCiphertext() {
        val records = vectors.getJSONArray("records")
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            val key = bytes(if (record.getString("direction") == "phoneToMac") "phoneToMacKey" else "macToPhoneKey")
            val counter = record.getLong("counter")
            val plaintext = record.getString("plaintext").toByteArray()
            val sealed = Base64.getDecoder().decode(record.getString("sealed"))
            assertArrayEquals(sealed, FrameCipher(key, counter).seal(plaintext))
            assertArrayEquals(plaintext, FrameCipher(key, counter).open(sealed))
        }
    }

    @Test
    fun tamperedRecordIsRejectedWithoutAdvancingTheCounter() {
        val record = vectors.getJSONArray("records").getJSONObject(0)
        val sealed = Base64.getDecoder().decode(record.getString("sealed"))
        val cipher = FrameCipher(bytes("phoneToMacKey"), record.getLong("counter"))
        val tampered = sealed.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { cipher.open(tampered) }
        assertArrayEquals(record.getString("plaintext").toByteArray(), cipher.open(sealed))
    }

    @Test
    fun pairingLinkRoundTrips() {
        val psk = ByteArray(32) { 0xFB.toByte() }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(psk)
        val link = "clipway://pair?v=1&id=id-1&name=%ED%99%8D%EA%B8%B8%EB%8F%99%E2%80%99s%20Mac%20mini" +
            "&psk=$encoded&port=47823&hosts=192.168.0.2,100.64.0.10"
        val mac = PairedMac.fromPairingLink(link)!!
        assertEquals("id-1", mac.id)
        assertEquals("홍길동’s Mac mini", mac.name)
        assertArrayEquals(psk, mac.psk)
        assertEquals(47823, mac.port)
        assertEquals(listOf("192.168.0.2", "100.64.0.10"), mac.hosts)
        assertEquals(mac, PairedMac.fromJson(mac.toJson()))

        assertNull(PairedMac.fromPairingLink("https://example.com/?v=1"))
        assertNull(PairedMac.fromPairingLink(link.replace("v=1", "v=2")))
        assertNull(PairedMac.fromPairingLink(link.replace(encoded, "AAAA")))
    }
}
