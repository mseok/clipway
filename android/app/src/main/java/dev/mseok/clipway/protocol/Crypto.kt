package dev.mseok.clipway.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Protocol v1 key agreement. The reference description lives in testvectors/generate.py. */
object BridgeCrypto {
    private val INFO = "clipway-v1".toByteArray()

    // DER headers that wrap a raw 32-byte X25519 key (RFC 8410).
    private val SPKI_PREFIX = hex("302a300506032b656e032100")
    private val PKCS8_PREFIX = hex("302e020100300506032b656e04220420")

    class KeyPair(val private: PrivateKey, val publicRaw: ByteArray)

    class SessionKeys(val phoneToMac: ByteArray, val macToPhone: ByteArray)

    fun generateKeyPair(): KeyPair {
        val pair = xdh { KeyPairGenerator.getInstance(it) }.generateKeyPair()
        return KeyPair(pair.private, pair.public.encoded.takeLast(32).toByteArray())
    }

    fun keyPairFromRaw(privateRaw: ByteArray, publicRaw: ByteArray): KeyPair {
        val private = xdh { KeyFactory.getInstance(it) }
            .generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + privateRaw))
        return KeyPair(private, publicRaw)
    }

    /** Both hello payloads are the exact bytes that went over the wire. */
    fun deriveKeys(
        psk: ByteArray,
        ownPrivate: PrivateKey,
        peerPublicRaw: ByteArray,
        phoneHello: ByteArray,
        macHello: ByteArray,
    ): SessionKeys {
        require(peerPublicRaw.size == 32) { "bad public key length" }
        val peer = xdh { KeyFactory.getInstance(it) }
            .generatePublic(X509EncodedKeySpec(SPKI_PREFIX + peerPublicRaw))
        val shared = xdh { KeyAgreement.getInstance(it) }.run {
            init(ownPrivate)
            doPhase(peer, true)
            generateSecret()
        }
        val transcript = MessageDigest.getInstance("SHA-256").run {
            update(phoneHello)
            digest(macHello)
        }
        val okm = hkdf(ikm = shared, salt = psk, info = INFO + transcript, length = 64)
        return SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64))
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var index = 1
        while (out.size() < length) {
            mac.update(block)
            mac.update(info)
            mac.update(index.toByte())
            block = mac.doFinal()
            out.write(block)
            index++
        }
        return out.toByteArray().copyOf(length)
    }

    // The JDK registers the curve as "X25519", Android's Conscrypt as "XDH".
    private inline fun <T> xdh(get: (String) -> T): T = try {
        get("X25519")
    } catch (_: NoSuchAlgorithmException) {
        get("XDH")
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * AES-256-GCM records with a per-direction counter nonce. The counter only
 * advances on success, so a failed [open] leaves the cipher usable.
 */
class FrameCipher(key: ByteArray, private var counter: Long = 0) {
    private val key = SecretKeySpec(key, "AES")

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, plaintext)

    @Synchronized
    fun open(sealed: ByteArray): ByteArray = run(Cipher.DECRYPT_MODE, sealed)

    private fun run(mode: Int, input: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(128, nonce))
        val output = cipher.doFinal(input)
        counter++
        return output
    }
}
