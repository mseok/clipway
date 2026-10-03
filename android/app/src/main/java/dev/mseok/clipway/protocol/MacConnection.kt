package dev.mseok.clipway.protocol

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import org.json.JSONObject

object Wire {
    const val VERSION = 1
    const val DEFAULT_PORT = 47823
    const val SERVICE_TYPE = "_clipway._tcp"
    const val MAX_FRAME = 2 * 1024 * 1024

    /** Limit for the frames exchanged before the other side has proved it holds the pairing key. */
    const val MAX_HANDSHAKE_FRAME = 1024
    const val MAX_NAME_LENGTH = 64

    /** Text longer than this is not synced; it also keeps binder transactions small. */
    const val MAX_CLIP_CHARS = 200_000

    fun writeFrame(out: OutputStream, payload: ByteArray) {
        val size = payload.size
        val frame = ByteArray(4 + size)
        frame[0] = (size ushr 24).toByte()
        frame[1] = (size ushr 16).toByte()
        frame[2] = (size ushr 8).toByte()
        frame[3] = size.toByte()
        payload.copyInto(frame, 4)
        out.write(frame)
        out.flush()
    }

    fun readFrame(input: DataInputStream, limit: Int = MAX_FRAME): ByteArray {
        val size = input.readInt()
        if (size <= 0 || size > limit) throw IOException("bad frame length $size")
        return ByteArray(size).also(input::readFully)
    }
}

data class Clip(val text: String, val sensitive: Boolean, val ts: Long)

/** An authenticated, encrypted connection to one Mac. Created by [open]. */
class MacConnection private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val sendCipher: FrameCipher,
    private val recvCipher: FrameCipher,
    /** When the Mac's current pasteboard content was copied (0 if unknown). */
    val macClipTs: Long,
    /** The key to store instead of the QR code's key when this handshake was a pairing. */
    val pairingKey: ByteArray,
) {
    private val output = socket.getOutputStream()
    private var lastSentClipTs = 0L

    @Synchronized
    fun send(message: JSONObject) {
        try {
            Wire.writeFrame(output, sendCipher.seal(message.toString().toByteArray()))
        } catch (e: Exception) {
            // The record counter has advanced; the stream cannot be resynchronised.
            close()
            throw e
        }
    }

    /** Sends a clip once; repeated calls with the same or an older clip are ignored. */
    @Synchronized
    fun sendClip(clip: Clip) {
        if (clip.ts <= lastSentClipTs) return
        send(
            JSONObject()
                .put("t", "clip")
                .put("text", clip.text)
                .put("sensitive", clip.sensitive)
                .put("ts", clip.ts)
        )
        lastSentClipTs = clip.ts
    }

    fun sendOtp(code: String, sender: String) {
        send(JSONObject().put("t", "otp").put("code", code).put("sender", sender))
    }

    /** Blocks until the next message; throws when the connection is lost or idle too long. */
    fun receive(): JSONObject = JSONObject(String(recvCipher.open(Wire.readFrame(input))))

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val HANDSHAKE_TIMEOUT_MS = 4000

        /**
         * The phone pings every 10 s and the Mac answers, so 25 silent seconds mean the link
         * is dead. A Mac app that is killed does not always reset its connections.
         */
        private const val IDLE_TIMEOUT_MS = 25_000

        fun open(
            host: String,
            port: Int,
            psk: ByteArray,
            phoneId: String,
            phoneName: String,
            clipTs: Long,
        ): MacConnection {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                val ephemeral = BridgeCrypto.generateKeyPair()
                // The plaintext hello names neither device: this phone also dials addresses that
                // may belong to someone else's network, and must not be trackable there.
                val phoneHello = JSONObject()
                    .put("v", Wire.VERSION)
                    .put("eph", Base64.getEncoder().encodeToString(ephemeral.publicRaw))
                    .toString().toByteArray()
                Wire.writeFrame(output, phoneHello)

                val macHello = Wire.readFrame(input, Wire.MAX_HANDSHAKE_FRAME)
                val macPublic = Base64.getDecoder().decode(JSONObject(String(macHello)).getString("eph"))
                val keys = BridgeCrypto.deriveKeys(psk, ephemeral.private, macPublic, phoneHello, macHello)
                val sendCipher = FrameCipher(keys.phoneToMac)
                val recvCipher = FrameCipher(keys.macToPhone)

                val hello = JSONObject()
                    .put("t", "hello")
                    .put("id", phoneId)
                    .put("name", phoneName.take(Wire.MAX_NAME_LENGTH))
                    .put("ts", clipTs)
                Wire.writeFrame(output, sendCipher.seal(hello.toString().toByteArray()))
                // A Mac that does not hold the pairing key closes here instead of answering.
                val reply = JSONObject(
                    String(recvCipher.open(Wire.readFrame(input, Wire.MAX_HANDSHAKE_FRAME)))
                )
                if (reply.optString("t") != "hello") throw IOException("unexpected reply")

                socket.soTimeout = IDLE_TIMEOUT_MS
                val macClipTs = reply.optLong("ts", 0).coerceAtMost(System.currentTimeMillis())
                return MacConnection(socket, input, sendCipher, recvCipher, macClipTs, keys.pairingKey)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}
