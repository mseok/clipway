package dev.mseok.clipway.protocol

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    /**
     * A picture travels as an "image" record followed by chunk records: a zero byte, then
     * up to this many bytes. JSON records start with "{", so the two cannot be confused
     * and other records (a ping, a verification code) may come between the chunks.
     */
    const val IMAGE_CHUNK = 256 * 1024

    /** 2100-01-01 in milliseconds; anything later is not a real timestamp. */
    const val MAX_TIMESTAMP = 4_102_444_800_000L

    /** What to add to the Mac's timestamps to get this phone's time (0 if its clock is unusable). */
    fun clockOffset(peerNow: Long, localNow: Long): Long =
        if (peerNow in 1..MAX_TIMESTAMP && localNow in 1..MAX_TIMESTAMP) localNow - peerNow else 0L

    /** A timestamp from the Mac on this phone's clock, never later than now; null if unusable. */
    fun translate(ts: Long, offset: Long, now: Long): Long? =
        if (ts in 1..MAX_TIMESTAMP && offset in -MAX_TIMESTAMP..MAX_TIMESTAMP) (ts + offset).coerceIn(1, now) else null

    /** True when the bytes start like a file of the declared type. */
    fun looksLike(mime: String, bytes: ByteArray): Boolean {
        fun at(offset: Int, text: String) =
            bytes.size >= offset + text.length && text.indices.all { bytes[offset + it] == text[it].code.toByte() }
        return when (mime) {
            "image/png" -> at(1, "PNG") && bytes[0] == 0x89.toByte()
            "image/jpeg" -> bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            "image/gif" -> at(0, "GIF8")
            "image/webp" -> at(0, "RIFF") && at(8, "WEBP")
            "image/bmp" -> at(0, "BM")
            "image/heic", "image/heif" -> at(4, "ftyp")
            else -> false
        }
    }
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
    private val clipOrder = Any()

    sealed interface Record {
        class Message(val json: JSONObject) : Record
        /** Part of the picture announced by the last "image" message. */
        class Chunk(val bytes: ByteArray) : Record
    }

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
    fun sendClip(clip: Clip) = synchronized(clipOrder) {
        if (clip.ts <= lastSentClipTs) return@synchronized
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
            // Each chunk takes the write lock on its own: a ping or a verification code can
            // go out between chunks instead of waiting for the whole picture.
            send(
                JSONObject().put("t", "image").put("mime", image.mime).put("size", image.bytes.size)
                    .put("sensitive", clip.sensitive).put("ts", clip.ts)
            )
            var offset = 0
            while (offset < image.bytes.size) {
                val end = minOf(offset + Wire.IMAGE_CHUNK, image.bytes.size)
                sendChunk(image.bytes, offset, end)
                offset = end
            }
        }
        lastSentClipTs = clip.ts
    }

    @Synchronized
    private fun sendChunk(bytes: ByteArray, from: Int, to: Int) {
        try {
            val record = ByteArray(1 + to - from)
            bytes.copyInto(record, 1, from, to)
            Wire.writeFrame(output, sendCipher.seal(record))
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    fun sendOtp(code: String, sender: String) {
        send(JSONObject().put("t", "otp").put("code", code).put("sender", sender))
    }

    /** Blocks until the next record; throws when the connection is lost or idle too long. */
    fun receive(): Record {
        val plaintext = recvCipher.open(Wire.readFrame(input))
        return if (plaintext.isNotEmpty() && plaintext[0] == 0.toByte()) {
            Record.Chunk(plaintext.copyOfRange(1, plaintext.size))
        } else {
            Record.Message(JSONObject(String(plaintext)))
        }
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val HANDSHAKE_TIMEOUT_MS = 4000

        /** The read timeout is per read; this bounds the whole handshake against a slow trickle. */
        private const val HANDSHAKE_DEADLINE_MS = 8000L
        private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "clipway-deadline").apply { isDaemon = true } }

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
            val deadline = watchdog.schedule({ runCatching { socket.close() } }, HANDSHAKE_DEADLINE_MS, TimeUnit.MILLISECONDS)
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

                if (!deadline.cancel(false)) throw IOException("handshake took too long")
                socket.soTimeout = IDLE_TIMEOUT_MS
                val now = System.currentTimeMillis()
                val offset = Wire.clockOffset(reply.optLong("now", 0), now)
                val macClipTs = Wire.translate(reply.optLong("ts", 0), offset, now) ?: 0L
                return MacConnection(socket, input, sendCipher, recvCipher, macClipTs, keys.pairingKey, offset)
            } catch (e: Exception) {
                deadline.cancel(false)
                runCatching { socket.close() }
                throw e
            }
        }
    }
}
