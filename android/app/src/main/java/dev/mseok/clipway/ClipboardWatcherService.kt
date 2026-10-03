package dev.mseok.clipway

import android.content.AttributionSource
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import dev.mseok.clipway.protocol.Wire
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs in a separate process that Shizuku starts with shell privileges (uid 2000).
 * The shell is allowed to read the clipboard in the background, which a normal
 * app has not been able to do since Android 10.
 */
class ClipboardWatcherService @Keep constructor(context: Context) : IClipboardWatcher.Stub() {
    private val manager =
        ShellContext(context).getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val ownerUid = context.applicationInfo.uid
    /** Pictures are fetched here, so a slow or stuck source never delays the listener. */
    private val pictures = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()

    @Synchronized
    override fun watch(callback: IClipboardCallback) {
        // This process can read the clipboard for anyone who reaches it; serve Clipway only.
        check(Binder.getCallingUid() == ownerUid) { "caller is not Clipway" }
        val manager = checkNotNull(manager) { "no clipboard service" }
        listener?.let(manager::removePrimaryClipChangedListener)
        listener = ClipboardManager.OnPrimaryClipChangedListener {
            runCatching {
                val current = generation.incrementAndGet()
                val clip = manager.primaryClip
                val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                val text = item?.text?.toString()
                // Lengths only: clipboard contents never go to the log.
                Log.i(TAG, "clipboard changed: ${text?.length ?: -1} chars")
                if (clip == null) return@runCatching
                val sensitive =
                    clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) ?: false
                if (text.isNullOrEmpty()) {
                    val uri = item?.uri ?: return@runCatching
                    val mime = clip.description.filterMimeTypes("image/*")?.firstOrNull { it in Wire.IMAGE_TYPES }
                    // Our own authority means the picture came from a Mac a moment ago.
                    if (mime != null && uri.scheme == "content" && uri.authority != OWN_AUTHORITY) {
                        pictures.execute {
                            runCatching { sendImage(callback, uri, mime, sensitive, current) }
                                .onFailure { Log.w(TAG, "picture read failed", it) }
                        }
                    }
                    return@runCatching
                }
                if (text.length > Wire.MAX_CLIP_CHARS) return@runCatching
                callback.onClipboardChanged(text, sensitive)
            }.onFailure { Log.w(TAG, "clipboard read failed", it) }
        }.also(manager::addPrimaryClipChangedListener)
        Log.i(TAG, "watching clipboard as uid ${Process.myUid()}")
    }

    /**
     * Fetches the copied picture with the system's own `content` tool, which runs as the
     * shell like this process. The URI is passed as one argument; no shell is involved.
     *
     * The URI was chosen by whichever app copied, so the source is not trusted: a provider
     * that never finishes is cut off after a few seconds, and the result is dropped if
     * something else has been copied in the meantime. The bytes only ever go to this
     * app, which sends them to the paired Macs, never back to the app that copied.
     */
    private fun sendImage(callback: IClipboardCallback, uri: Uri, mime: String, sensitive: Boolean, current: Int) {
        val process = ProcessBuilder("content", "read", "--uri", uri.toString()).start()
        process.errorStream.close()
        val result = AtomicReference<ByteArray?>()
        val reader = thread {
            result.set(runCatching { process.inputStream.use { it.readNBytes(Wire.MAX_IMAGE_BYTES + 1) } }.getOrNull())
        }
        reader.join(READ_TIMEOUT_MS)
        process.destroyForcibly()  // also unblocks the reader if the source never finished
        val bytes = result.get()
        Log.i(TAG, "image copied: ${bytes?.size ?: -1} bytes")
        if (bytes == null || bytes.isEmpty() || bytes.size > Wire.MAX_IMAGE_BYTES) return
        if (generation.get() != current) return
        // Too large for a binder transaction, so it is streamed through a pipe.
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) } }
        }
        callback.onImageCopied(read, mime, bytes.size, sensitive)
        read.close()
    }

    override fun destroy() {
        exitProcess(0)
    }

    private companion object {
        const val TAG = "ClipwayWatcher"
        const val OWN_AUTHORITY = "dev.mseok.clipway.clips"
        const val READ_TIMEOUT_MS = 8_000L
    }
}

/**
 * Makes framework managers identify as the shell package, so the clipboard service
 * sees a caller whose package matches its uid. Port of scrcpy's FakeContext.
 */
private class ShellContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName() = PACKAGE

    override fun getOpPackageName() = PACKAGE

    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(Process.SHELL_UID).setPackageName(PACKAGE).build()

    override fun getDeviceId() = 0

    override fun getApplicationContext(): Context = this

    override fun getSystemService(name: String): Any? {
        val service = super.getSystemService(name) ?: return null
        // The managers keep the context they were created with, so point them here.
        // "semclipboard" is the Samsung service the stock ClipboardManager delegates to.
        if (name == CLIPBOARD_SERVICE || name == "semclipboard" || name == ACTIVITY_SERVICE) {
            service.javaClass.getDeclaredField("mContext").apply { isAccessible = true }.set(service, this)
        }
        return service
    }

    private companion object {
        const val PACKAGE = "com.android.shell"
    }
}
