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

    @Synchronized
    override fun watch(callback: IClipboardCallback) {
        // This process can read the clipboard for anyone who reaches it; serve Clipway only.
        check(Binder.getCallingUid() == ownerUid) { "caller is not Clipway" }
        val manager = checkNotNull(manager) { "no clipboard service" }
        listener?.let(manager::removePrimaryClipChangedListener)
        listener = ClipboardManager.OnPrimaryClipChangedListener {
            runCatching {
                val clip = manager.primaryClip
                val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                val text = item?.text?.toString()
                // Lengths only: clipboard contents never go to the log.
                Log.i(TAG, "clipboard changed: ${text?.length ?: -1} chars")
                if (clip == null) return@runCatching
                if (text.isNullOrEmpty()) {
                    val uri = item?.uri ?: return@runCatching
                    val mime = clip.description.filterMimeTypes("image/*")?.firstOrNull { it in Wire.IMAGE_TYPES }
                    // Our own authority means the picture came from a Mac a moment ago.
                    if (mime != null && uri.scheme == "content" && uri.authority != OWN_AUTHORITY) {
                        sendImage(callback, uri, mime)
                    }
                    return@runCatching
                }
                if (text.length > Wire.MAX_CLIP_CHARS) return@runCatching
                val sensitive =
                    clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) ?: false
                callback.onClipboardChanged(text, sensitive)
            }.onFailure { Log.w(TAG, "clipboard read failed", it) }
        }.also(manager::addPrimaryClipChangedListener)
        Log.i(TAG, "watching clipboard as uid ${Process.myUid()}")
    }

    /**
     * Reading the clipboard as the shell also grants the shell read access to the copied
     * picture, so the system's own `content` tool can fetch it. The URI is passed as one
     * argument; no shell is involved.
     */
    private fun sendImage(callback: IClipboardCallback, uri: Uri, mime: String) {
        val process = ProcessBuilder("content", "read", "--uri", uri.toString()).start()
        val bytes = process.inputStream.use { it.readNBytes(Wire.MAX_IMAGE_BYTES + 1) }
        process.destroy()
        Log.i(TAG, "image copied: ${bytes.size} bytes")
        if (bytes.isEmpty() || bytes.size > Wire.MAX_IMAGE_BYTES) return
        // Too large for a binder transaction, so it is streamed through a pipe.
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) } }
        }
        callback.onImageCopied(read, mime, bytes.size)
        read.close()
    }

    override fun destroy() {
        exitProcess(0)
    }

    private companion object {
        const val TAG = "ClipwayWatcher"
        const val OWN_AUTHORITY = "dev.mseok.clipway.clips"
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
