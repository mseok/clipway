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

    /** A picture travels as an "image" record followed by raw records of this size. */
    const val IMAGE_CHUNK = 256 * 1024
    const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    val IMAGE_TYPES = mapOf(
        "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
        "image/heic" to "heic", "image/heif" to "heif", "image/bmp" to "bmp",
    )

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

/** A copy to send: text, or a picture when [image] is set (then [text] is empty). */
class Clip(val text: String, val sensitive: Boolean, val ts: Long, val image: Image? = null) {
    class Image(val mime: String, val bytes: ByteArray)
}

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
    /**
     * Add this to a timestamp from the Mac to get the time on this phone's clock. The two
     * clocks can be seconds apart, and "newest copy wins" needs them compared fairly.
     */
    val clockOffset: Long,
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
        val image = clip.image
        if (image == null) {
            send(
                JSONObject()
                    .put("t", "clip")
                    .put("text", clip.text)
                    .put("sensitive", clip.sensitive)
                    .put("ts", clip.ts)
            )
        } else {
            // Header and chunks go out under one lock, so no other record can come between them.
            send(
                JSONObject().put("t", "image").put("mime", image.mime)
                    .put("size", image.bytes.size).put("ts", clip.ts)
            )
            try {
                var offset = 0
                while (offset < image.bytes.size) {
                    val end = minOf(offset + Wire.IMAGE_CHUNK, image.bytes.size)
                    Wire.writeFrame(output, sendCipher.seal(image.bytes.copyOfRange(offset, end)))
                    offset = end
                }
            } catch (e: Exception) {
                close()
                throw e
            }
        }
        lastSentClipTs = clip.ts
    }

    /** Reads the raw records that follow an "image" record. */
    fun receiveImage(header: JSONObject): Clip.Image {
        val size = header.optInt("size")
        val mime = header.optString("mime")
        if (size !in 1..Wire.MAX_IMAGE_BYTES || mime !in Wire.IMAGE_TYPES) throw IOException("bad image header")
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val chunk = recvCipher.open(Wire.readFrame(input))
            if (chunk.isEmpty() || offset + chunk.size > size) throw IOException("bad image record")
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        return Clip.Image(mime, bytes)
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
                    .put("now", System.currentTimeMillis())
                Wire.writeFrame(output, sendCipher.seal(hello.toString().toByteArray()))
                // A Mac that does not hold the pairing key closes here instead of answering.
                val reply = JSONObject(
                    String(recvCipher.open(Wire.readFrame(input, Wire.MAX_HANDSHAKE_FRAME)))
                )
                if (reply.optString("t") != "hello") throw IOException("unexpected reply")

                socket.soTimeout = IDLE_TIMEOUT_MS
                val now = System.currentTimeMillis()
                val offset = if (reply.has("now")) now - reply.getLong("now") else 0L
                val reported = reply.optLong("ts", 0)
                val macClipTs = if (reported == 0L) 0L else (reported + offset).coerceAtMost(now)
                return MacConnection(socket, input, sendCipher, recvCipher, macClipTs, keys.pairingKey, offset)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}
