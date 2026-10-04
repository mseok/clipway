package dev.mseok.clipway

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import dev.mseok.clipway.protocol.BridgeCrypto
import dev.mseok.clipway.protocol.Clip
import dev.mseok.clipway.protocol.MacConnection
import dev.mseok.clipway.protocol.PairedMac
import dev.mseok.clipway.protocol.Wire
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

class BridgeApp : Application() {
    val bridge: Bridge by lazy { Bridge(this) }
    val updater: Updater by lazy { Updater(this, bridge.scope) }
    val shizukuInstall: ShizukuInstall by lazy { ShizukuInstall(this, bridge.scope) }

    /** A scanned or received pairing link that waits for the user's confirmation. */
    val pendingPairing = MutableStateFlow<PairingRequest?>(null)
}

/** Result of "연결 테스트" for one Mac; [millis] is the round trip time, or null when it did not answer. */
data class LinkTest(val name: String, val millis: Long?)

/** [external] is true when the link came from outside the app (another app, a web page, the camera). */
data class PairingRequest(val mac: PairedMac, val external: Boolean)

class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    val phoneId: String = prefs.getString("phoneId", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("phoneId", it).apply() }

    fun macs(): List<PairedMac> = runCatching {
        val array = JSONArray(prefs.getString("macs", "[]"))
        List(array.length()) { PairedMac.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveMacs(macs: List<PairedMac>) {
        prefs.edit().putString("macs", JSONArray(macs.map(PairedMac::toJson)).toString()).apply()
    }

    var clipboardEnabled: Boolean
        get() = prefs.getBoolean("clipboardEnabled", true)
        set(value) = prefs.edit().putBoolean("clipboardEnabled", value).apply()

    var otpEnabled: Boolean
        get() = prefs.getBoolean("otpEnabled", true)
        set(value) = prefs.edit().putBoolean("otpEnabled", value).apply()

    var skipSensitive: Boolean
        get() = prefs.getBoolean("skipSensitive", false)
        set(value) = prefs.edit().putBoolean("skipSensitive", value).apply()
}

/**
 * Process-wide core: owns the connections to every paired Mac and moves clipboard
 * text and verification codes across them. [BridgeService] keeps the process alive
 * and feeds it screen and network events.
 */
class Bridge(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = PairingStore(context)
    val watcher = ClipboardWatcher(context, ::onLocalCopy, ::onLocalImage)

    val macs = MutableStateFlow(store.macs())
    val connected = MutableStateFlow<Set<String>>(emptySet())
    val clipboardEnabled = MutableStateFlow(store.clipboardEnabled)
    val otpEnabled = MutableStateFlow(store.otpEnabled)
    /** When on, copies that the source app marked as sensitive (password managers do) stay on the phone. */
    val skipSensitive = MutableStateFlow(store.skipSensitive)

    private val locator = MacLocator(context)
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val links = ConcurrentHashMap<String, Link>()
    private val phoneName: String =
        Settings.Global.getString(context.contentResolver, "device_name") ?: Build.MODEL

    private val clipLock = Any()
    /** Last text copied on this phone since the process started. */
    private var localClip: Clip? = null
    /** When the current clipboard content was copied, on whichever device (last writer wins). */
    private var clipTs = 0L
    /**
     * What the clipboard is known to hold. Change events that report the same text are
     * ignored: they come from our own writes of a Mac clip, from Samsung firing several
     * events per copy, or from another sync tool rewriting the same text.
     */
    private var clipboardText: String? = null
    /** SHA-256 of the picture the clipboard is known to hold, for the same purpose. */
    private var clipboardImage: String? = null

    @Volatile private var interactive = false
    private val pairingInFlight = AtomicBoolean(false)
    private val persistLock = Any()
    private var retryJob: Job? = null
    private var idleJob: Job? = null

    init {
        macs.value.forEach { links[it.id] = Link(it) }
    }

    // region Events from the service

    @Synchronized
    fun setInteractive(value: Boolean) {
        interactive = value
        if (value) {
            idleJob?.cancel()
            locator.start()
            kick()
        } else {
            retryJob?.cancel()
            armIdleClose()
        }
    }

    /**
     * With the screen off nothing needs a live link, so connections are closed after a
     * quiet minute. Also armed when a code or a copy opens a connection while the screen is off.
     */
    @Synchronized
    private fun armIdleClose() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            if (interactive) return@launch
            locator.stop()
            links.values.forEach(Link::close)
        }
    }

    /** Tries to reach every Mac now, then keeps retrying with backoff while the screen is on. */
    @Synchronized
    fun kick() {
        retryJob?.cancel()
        retryJob = scope.launch {
            var attempt = 0
            while (true) {
                connectAll()
                if (!interactive || links.values.all { it.connection != null }) return@launch
                delay(RETRY_DELAYS_MS[attempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)])
                attempt++
            }
        }
    }

    /** The default network changed, so sockets bound to the old one are dead. */
    fun reconnectAll() {
        links.values.forEach(Link::close)
        if (interactive) kick()
    }

    // endregion

    // region Clipboard and verification codes

    /**
     * Called for every clipboard change seen by the Shizuku watcher, and with
     * [manual] set when the user sends text explicitly (tile, share menu).
     */
    fun onLocalCopy(text: String, sensitive: Boolean) {
        val clip = recordLocalClip(text, sensitive, manual = false) ?: return
        if (clipboardEnabled.value) links.values.forEach { it.offer(clip) }
    }

    /**
     * Explicit send from the tile or the share menu. Returns true when a Mac received it.
     * The person asked for this text to be sent, so it goes even when sensitive copies are
     * otherwise held back; it keeps its sensitive mark.
     */
    suspend fun sendNow(text: String, sensitive: Boolean): Boolean {
        val clip = recordLocalClip(text, sensitive, manual = true) ?: return false
        return sendToAll(clip)
    }

    private fun recordLocalClip(text: String, sensitive: Boolean, manual: Boolean): Clip? {
        if (text.isEmpty() || text.length > Wire.MAX_CLIP_CHARS) return null
        synchronized(clipLock) {
            if (!manual) {
                if (text == clipboardText) return null
                clipboardText = text
                clipboardImage = null
            }
            val now = System.currentTimeMillis()
            clipTs = now
            if (!manual && sensitive && skipSensitive.value) {
                // Kept on the phone. The older copy must not be delivered later in its place.
                localClip = null
                return null
            }
            return Clip(text, sensitive, now).also { localClip = it }
        }
    }

    private suspend fun sendToAll(clip: Clip): Boolean = withContext(Dispatchers.IO) {
        val delivered = links.values.map { link ->
            async {
                runCatching { link.ensureConnected()?.sendClip(clip) != null }.getOrDefault(false)
            }
        }.awaitAll().count { it }
        val what = clip.image?.let { "image ${it.bytes.size} bytes" } ?: "${clip.text.length} chars"
        Log.i(TAG, "local copy: $what -> $delivered of ${links.size} Mac(s)")
        delivered > 0
    }

    /** A picture was copied on this phone (reported by the Shizuku watcher). */
    fun onLocalImage(mime: String, bytes: ByteArray, sensitive: Boolean) {
        // The type label comes from whichever app copied; the bytes have to agree with it.
        if (!Wire.looksLike(mime, bytes)) return
        val clip: Clip
        synchronized(clipLock) {
            val digest = sha256(bytes)
            if (digest == clipboardImage) return
            clipboardImage = digest
            clipboardText = null
            val now = System.currentTimeMillis()
            clipTs = now
            if (sensitive && skipSensitive.value) {
                localClip = null
                return
            }
            clip = Clip("", sensitive, now, Clip.Image(mime, bytes)).also { localClip = it }
        }
        if (clipboardEnabled.value) links.values.forEach { it.offer(clip) }
    }

    private fun applyRemoteImage(image: Clip.Image, sensitive: Boolean, reportedTs: Long, offset: Long) {
        val now = System.currentTimeMillis()
        val ts = Wire.translate(reportedTs, offset, now) ?: now
        synchronized(clipLock) {
            if (ts <= clipTs) return
            // Recorded even with sync off, so the Mac does not offer it again at every connect.
            clipTs = ts
            if (!clipboardEnabled.value || !Wire.looksLike(image.mime, image.bytes)) return
            val digest = sha256(image.bytes)
            if (digest == clipboardImage) return
            clipboardImage = digest
            clipboardText = null
        }
        runCatching {
            // One file at a time; other apps reach it only through the clipboard.
            val directory = File(context.cacheDir, "clips").apply { mkdirs() }
            directory.listFiles()?.forEach { it.delete() }
            val file = File(directory, "clip-$ts.${Wire.IMAGE_TYPES.getValue(image.mime)}")
            file.writeBytes(image.bytes)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.clips", file)
            val data = ClipData.newUri(context.contentResolver, "Clipway", uri)
            if (sensitive) {
                data.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(data)
        }.onFailure { Log.w(TAG, "image write failed", it) }
        Log.i(TAG, "image from Mac: ${image.bytes.size} bytes")
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun applyRemoteClip(text: String, sensitive: Boolean, reportedTs: Long, offset: Long) {
        if (text.isEmpty() || text.length > Wire.MAX_CLIP_CHARS) return
        val now = System.currentTimeMillis()
        val ts = Wire.translate(reportedTs, offset, now) ?: now
        synchronized(clipLock) {
            if (ts <= clipTs) return
            // Recorded even with sync off, so the Mac does not offer it again at every connect.
            clipTs = ts
            if (!clipboardEnabled.value) return
            // Already there (another sync tool delivered it first): nothing to write.
            if (text == clipboardText) return
            clipboardText = text
            clipboardImage = null
        }
        val data = ClipData.newPlainText("Clipway", text)
        if (sensitive) {
            data.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        runCatching { clipboard.setPrimaryClip(data) }
            .onFailure { Log.w(TAG, "clipboard write failed", it) }
        Log.i(TAG, "clip from Mac: ${text.length} chars")
    }

    /**
     * Sends a test message to every paired Mac over the same encrypted connection that
     * carries copies and codes. A Mac that receives it shows a banner and answers.
     */
    suspend fun testLinks(): List<LinkTest> = withContext(Dispatchers.IO) {
        links.values.map { async { it.test() } }.awaitAll().sortedBy { it.name }
    }

    /** Returns true when at least one Mac received the code. */
    suspend fun sendOtp(code: String, sender: String): Boolean = withContext(Dispatchers.IO) {
        links.values.map { link ->
            async {
                runCatching { link.ensureConnected()?.sendOtp(code, sender) != null }.getOrDefault(false)
            }
        }.awaitAll().any { it }
    }

    // endregion

    // region Pairing and settings

    /**
     * Connects once with the key from the QR code. Both sides then keep a key derived
     * from that handshake, so the QR code cannot be used again by anyone who saw it.
     */
    suspend fun pair(mac: PairedMac): Boolean = withContext(Dispatchers.IO) {
        // One pairing at a time: two handshakes with the same QR key could leave the two
        // sides with keys from different handshakes.
        if (!pairingInFlight.compareAndSet(false, true)) return@withContext false
        try {
            val link = Link(mac, pairing = true)
            val opened = link.ensureConnected() ?: return@withContext false
            links.put(mac.id, link)?.retire()
            persist()
            // The first record under the new key tells the Mac that the phone has it.
            runCatching { opened.send(JSONObject().put("t", "ping")) }
            true
        } finally {
            pairingInFlight.set(false)
        }
    }

    fun unpair(macId: String) {
        links.remove(macId)?.retire()
        persist()
    }

    /** The check code to compare with the one the Mac shows for this phone. */
    fun pairingCode(mac: PairedMac): String = BridgeCrypto.pairingCode(mac.psk)

    /**
     * True for loopback and for this phone's own addresses. A "Mac" at such an address
     * would be another app on this phone, so they are never dialled.
     */
    fun isOwnAddress(host: String): Boolean = runCatching {
        host.startsWith("127.") || NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }.any { it.hostAddress == host }
    }.getOrDefault(true)

    fun setClipboardEnabled(value: Boolean) {
        store.clipboardEnabled = value
        clipboardEnabled.value = value
    }

    fun setOtpEnabled(value: Boolean) {
        store.otpEnabled = value
        otpEnabled.value = value
    }

    fun setSkipSensitive(value: Boolean) {
        store.skipSensitive = value
        skipSensitive.value = value
    }

    private fun persist() = synchronized(persistLock) {
        val current = links.values.map { it.mac }.sortedBy { it.name }
        store.saveMacs(current)
        macs.value = current
    }

    // endregion

    private suspend fun connectAll() = withContext(Dispatchers.IO) {
        links.values.map { async { it.ensureConnected() } }.awaitAll()
    }

    private inner class Link(@Volatile var mac: PairedMac, @Volatile private var pairing: Boolean = false) {
        private val mutex = Mutex()
        @Volatile var connection: MacConnection? = null
        @Volatile private var retired = false
        private val tests = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
        private val outbox = AtomicReference<Clip?>(null)
        private val draining = AtomicBoolean(false)

        /**
         * Queues a copy for this Mac. Only the newest waiting copy is kept, so a burst of
         * large pictures on a slow link cannot pile up in memory.
         */
        fun offer(clip: Clip) {
            outbox.set(clip)
            if (!draining.compareAndSet(false, true)) return
            scope.launch {
                try {
                    while (true) {
                        val next = outbox.getAndSet(null) ?: break
                        val sent = runCatching { ensureConnected()?.sendClip(next) != null }.getOrDefault(false)
                        val what = next.image?.let { "image ${it.bytes.size} bytes" } ?: "${next.text.length} chars"
                        Log.i(TAG, "local copy: $what -> ${mac.name}: ${if (sent) "sent" else "not sent"}")
                    }
                } finally {
                    draining.set(false)
                    outbox.getAndSet(null)?.let(::offer)
                }
            }
        }

        suspend fun test(): LinkTest {
            val opened = runCatching { ensureConnected() }.getOrNull() ?: return LinkTest(mac.name, null)
            val n = Random.nextLong(Long.MAX_VALUE)
            val answer = CompletableDeferred<Unit>()
            tests[n] = answer
            val started = SystemClock.elapsedRealtime()
            val answered = runCatching { opened.send(JSONObject().put("t", "test").put("n", n)) }.isSuccess &&
                withTimeoutOrNull(TEST_TIMEOUT_MS) { answer.await() } != null
            tests.remove(n)
            return LinkTest(mac.name, if (answered) SystemClock.elapsedRealtime() - started else null)
        }

        suspend fun ensureConnected(): MacConnection? = mutex.withLock {
            connection ?: connect()?.also { opened ->
                if (retired) {
                    opened.close()
                    return@withLock null
                }
                connection = opened
                if (retired) {  // unpaired while the connection was being stored
                    drop(opened, retry = false)
                    return@withLock null
                }
                connected.update { it + mac.id }
                Log.i(TAG, "connected to ${mac.name}")
                scope.launch { readLoop(opened) }
                scope.launch { pingLoop(opened) }
                if (!interactive) armIdleClose()
                // Deliver what was copied here while this Mac was out of reach. Queued rather
                // than sent here, so a large picture does not hold this lock.
                val pending = synchronized(clipLock) { localClip }
                if (clipboardEnabled.value && pending != null && pending.ts > opened.macClipTs &&
                    !(pending.sensitive && skipSensitive.value)
                ) {
                    offer(pending)
                }
            }
        }

        private suspend fun connect(): MacConnection? {
            val ts = synchronized(clipLock) { clipTs }
            if (pairing) {
                // Only the addresses in the QR code are tried: with the QR key in hand,
                // anything that merely announces itself on the Wi-Fi could otherwise take the
                // Mac's place. Bonjour is used only when the code carries no usable address.
                // One address at a time, so the key is spent on exactly one handshake.
                val targets = mac.hosts.ifEmpty { locator.hosts() }.filterNot(::isOwnAddress)
                for (host in targets) {
                    val opened = runCatching {
                        MacConnection.open(host, mac.port, mac.psk, store.phoneId, phoneName, ts)
                    }.getOrNull() ?: continue
                    mac = mac.copy(psk = opened.pairingKey, lastHost = host)
                    pairing = false
                    return opened
                }
                return null
            }
            // Otherwise race every known address; the first completed handshake wins.
            val hosts = (listOfNotNull(mac.lastHost) + mac.hosts + locator.hosts()).distinct().filterNot(::isOwnAddress)
            val winner = CompletableDeferred<Pair<String, MacConnection>?>()
            val attempts = hosts.map { host ->
                scope.launch {
                    val opened = runCatching {
                        MacConnection.open(host, mac.port, mac.psk, store.phoneId, phoneName, ts)
                    }.getOrNull() ?: return@launch
                    if (!winner.complete(host to opened)) opened.close()
                }
            }
            scope.launch {
                attempts.joinAll()
                winner.complete(null)
            }
            // Not cancellable: an abandoned attempt would leave an open connection nobody reads.
            val (host, opened) = withContext(NonCancellable) { winner.await() } ?: return null
            if (host != mac.lastHost) {
                mac = mac.copy(lastHost = host)
                if (links[mac.id] === this) persist()
            }
            return opened
        }

        private fun readLoop(opened: MacConnection) {
            var header: JSONObject? = null  // the picture that is still arriving
            var picture = ByteArray(0)
            var filled = 0
            try {
                while (true) {
                    when (val record = opened.receive()) {
                        is MacConnection.Record.Chunk -> {
                            val announced = header ?: throw IOException("picture data without a header")
                            if (record.bytes.isEmpty() || filled + record.bytes.size > picture.size) {
                                throw IOException("bad picture data")
                            }
                            record.bytes.copyInto(picture, filled)
                            filled += record.bytes.size
                            if (filled == picture.size) {
                                applyRemoteImage(
                                    Clip.Image(announced.getString("mime"), picture),
                                    announced.optBoolean("sensitive"),
                                    announced.optLong("ts"),
                                    opened.clockOffset,
                                )
                                header = null
                                picture = ByteArray(0)
                            }
                        }
                        is MacConnection.Record.Message -> {
                            val message = record.json
                            when (message.optString("t")) {
                                "clip" -> applyRemoteClip(
                                    message.optString("text"),
                                    message.optBoolean("sensitive"),
                                    message.optLong("ts"),
                                    opened.clockOffset,
                                )
                                "image" -> {
                                    val size = message.optInt("size")
                                    if (size !in 1..Wire.MAX_IMAGE_BYTES || message.optString("mime") !in Wire.IMAGE_TYPES) {
                                        throw IOException("bad picture header")
                                    }
                                    header = message
                                    picture = ByteArray(size)
                                    filled = 0
                                }
                                "tested" -> tests.remove(message.optLong("n"))?.complete(Unit)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // Throwable, not Exception: a record a parser chokes on (for example one that
                // exhausts the stack) must end this connection, not the whole app.
                Log.i(TAG, "connection to ${mac.name} ended: ${e.javaClass.simpleName}")
            } finally {
                drop(opened, retry = true)
            }
        }

        private suspend fun pingLoop(opened: MacConnection) {
            val ping = JSONObject().put("t", "ping")
            while (true) {
                delay(PING_INTERVAL_MS)
                if (connection !== opened) return
                if (runCatching { opened.send(ping) }.isFailure) {
                    drop(opened, retry = true)
                    return
                }
            }
        }

        private fun drop(opened: MacConnection, retry: Boolean) {
            opened.close()
            if (connection !== opened) return
            connection = null
            connected.update { it - mac.id }
            if (retry && interactive && !retired) kick()
        }

        fun close() {
            connection?.let { drop(it, retry = false) }
        }

        /** The pairing was removed or replaced; never reconnect. */
        fun retire() {
            retired = true
            close()
        }
    }

    private companion object {
        const val TAG = "Clipway"
        const val PING_INTERVAL_MS = 10_000L
        const val IDLE_CLOSE_MS = 60_000L
        const val TEST_TIMEOUT_MS = 4_000L
        val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000)
    }
}
