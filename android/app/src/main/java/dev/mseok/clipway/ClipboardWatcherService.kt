package dev.mseok.clipway

import android.content.AttributionSource
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Binder
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import dev.mseok.clipway.protocol.Wire
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
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                // Lengths only: clipboard contents never go to the log.
                Log.i(TAG, "clipboard changed: ${text?.length ?: -1} chars")
                if (clip == null || text.isNullOrEmpty() || text.length > Wire.MAX_CLIP_CHARS) return@runCatching
                val sensitive =
                    clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) ?: false
                callback.onClipboardChanged(text, sensitive)
            }.onFailure { Log.w(TAG, "clipboard read failed", it) }
        }.also(manager::addPrimaryClipChangedListener)
        Log.i(TAG, "watching clipboard as uid ${Process.myUid()}")
    }

    override fun destroy() {
        exitProcess(0)
    }

    private companion object {
        const val TAG = "ClipwayWatcher"
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
