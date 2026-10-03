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
            assertArrayEquals(bytes("pairingKey"), keys.pairingKey)
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

    @Test
    fun pairingLinkCannotPointAtTheInternet() {
        val psk = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        fun link(hosts: String, port: String = "47823", name: String = "Mac", id: String = "id-1") =
            "clipway://pair?v=1&id=$id&name=$name&psk=$psk&port=$port&hosts=$hosts"

        // Public addresses and host names are dropped; private ones stay.
        val mixed = PairedMac.fromPairingLink(link("203.0.113.7,evil.example.com,192.168.0.2,100.64.0.10,10.0.0.5"))!!
        assertEquals(listOf("192.168.0.2", "100.64.0.10", "10.0.0.5"), mixed.hosts)
        assertEquals(emptyList<String>(), PairedMac.fromPairingLink(link("8.8.8.8"))!!.hosts)

        for (host in listOf("192.168.0.2", "10.1.2.3", "172.16.0.1", "172.31.255.255", "100.64.0.1", "100.127.9.9", "169.254.1.1")) {
            assertEquals(host, true, PairedMac.isPrivateIpv4(host))
        }
        for (host in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "192.169.0.1", "1.2.3", "1.2.3.4.5", "256.1.1.1",
            "192.168.0.2:80", "0x7f.0.0.1", "192.168.0.2 ", "", "localhost", "::1", "１９２.168.0.2")) {
            assertEquals(host, false, PairedMac.isPrivateIpv4(host))
        }

        assertNull(PairedMac.fromPairingLink(link("192.168.0.2", port = "0")))
        assertNull(PairedMac.fromPairingLink(link("192.168.0.2", port = "70000")))
        assertNull(PairedMac.fromPairingLink(link("192.168.0.2", port = "22;x")))
        assertNull(PairedMac.fromPairingLink(link("192.168.0.2", id = "")))
        assertNull(PairedMac.fromPairingLink(link("192.168.0.2", id = "a".repeat(65))))
        assertNull(PairedMac.fromPairingLink(link("192.168.0.2", name = "%0A%0D")))
        assertEquals(64, PairedMac.fromPairingLink(link("192.168.0.2", name = "M".repeat(300)))!!.name.length)
        assertEquals("EvilMac", PairedMac.fromPairingLink(link("192.168.0.2", name = "Evil%0AMac"))!!.name)
        assertEquals(8, PairedMac.fromPairingLink(link((1..20).joinToString(",") { "10.0.0.$it" }))!!.hosts.size)
        assertNull(PairedMac.fromPairingLink(link("192.168.0.2") + "&x=" + "a".repeat(3000)))
    }
}
