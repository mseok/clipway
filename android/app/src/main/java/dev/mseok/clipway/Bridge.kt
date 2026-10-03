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
import dev.mseok.clipway.protocol.Clip
import dev.mseok.clipway.protocol.MacConnection
import dev.mseok.clipway.protocol.PairedMac
import dev.mseok.clipway.protocol.Wire
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    val watcher = ClipboardWatcher(context) { text, sensitive -> onLocalCopy(text, sensitive) }

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

    @Volatile private var interactive = false
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
        if (clipboardEnabled.value) scope.launch { sendToAll(clip) }
    }

    /** Explicit send from the tile or the share menu. Returns true when a Mac received it. */
    suspend fun sendNow(text: String): Boolean {
        val clip = recordLocalClip(text, sensitive = false, manual = true) ?: return false
        return sendToAll(clip)
    }

    private fun recordLocalClip(text: String, sensitive: Boolean, manual: Boolean): Clip? {
        if (text.isEmpty() || text.length > Wire.MAX_CLIP_CHARS) return null
        synchronized(clipLock) {
            if (!manual) {
                if (text == clipboardText) return null
                clipboardText = text
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
        Log.i(TAG, "local copy: ${clip.text.length} chars -> $delivered of ${links.size} Mac(s)")
        delivered > 0
    }

    private fun applyRemoteClip(text: String, sensitive: Boolean, reportedTs: Long) {
        if (!clipboardEnabled.value || text.isEmpty() || text.length > Wire.MAX_CLIP_CHARS) return
        // A timestamp from the future would block later copies; cap it at now.
        val ts = reportedTs.coerceAtMost(System.currentTimeMillis())
        synchronized(clipLock) {
            if (ts <= clipTs) return
            clipTs = ts
            // Already there (another sync tool delivered it first): nothing to write.
            if (text == clipboardText) return
            clipboardText = text
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
        val link = Link(mac, pairing = true)
        link.ensureConnected() ?: return@withContext false
        links.put(mac.id, link)?.retire()
        persist()
        true
    }

    fun unpair(macId: String) {
        links.remove(macId)?.retire()
        persist()
    }

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

    private fun persist() {
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
                connected.update { it + mac.id }
                Log.i(TAG, "connected to ${mac.name}")
                scope.launch { readLoop(opened) }
                scope.launch { pingLoop(opened) }
                if (!interactive) armIdleClose()
                // Deliver what was copied here while this Mac was out of reach.
                val pending = synchronized(clipLock) { localClip }
                if (clipboardEnabled.value && pending != null && pending.ts > opened.macClipTs) {
                    runCatching { opened.sendClip(pending) }
                }
            }
        }

        private suspend fun connect(): MacConnection? {
            val hosts = (listOfNotNull(mac.lastHost) + mac.hosts + locator.hosts()).distinct()
            val ts = synchronized(clipLock) { clipTs }
            if (pairing) {
                // One address at a time: the QR code's key must be spent on exactly one
                // handshake, or the two sides could keep keys from different handshakes.
                for (host in hosts) {
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
            try {
                while (true) {
                    val message = opened.receive()
                    when (message.optString("t")) {
                        "clip" -> applyRemoteClip(
                            message.optString("text"),
                            message.optBoolean("sensitive"),
                            message.optLong("ts"),
                        )
                        "tested" -> tests.remove(message.optLong("n"))?.complete(Unit)
                    }
                }
            } catch (e: Exception) {
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
