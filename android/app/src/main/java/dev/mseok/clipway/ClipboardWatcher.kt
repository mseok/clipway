package dev.mseok.clipway

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku

/** App-side handle on the Shizuku clipboard watcher. */
class ClipboardWatcher(
    private val context: Context,
    private val onCopy: (text: String, sensitive: Boolean) -> Unit,
) {
    enum class State { NOT_RUNNING, NO_PERMISSION, STARTING, WATCHING, FAILED }

    val state = MutableStateFlow(State.NOT_RUNNING)

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ClipboardWatcherService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("clipwatch")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val callback = object : IClipboardCallback.Stub() {
        override fun onClipboardChanged(text: String?, sensitive: Boolean) {
            if (text != null) onCopy(text, sensitive)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            val result = runCatching {
                IClipboardWatcher.Stub.asInterface(checkNotNull(binder)).watch(callback)
            }
            result.onFailure { Log.w(TAG, "watcher start failed", it) }
            state.value = if (result.isSuccess) State.WATCHING else State.FAILED
        }

        override fun onServiceDisconnected(name: ComponentName) {
            state.value = State.NOT_RUNNING
        }
    }

    private var started = false

    /** Registers for Shizuku lifecycle events; binds as soon as Shizuku is up and permitted. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { state.value = State.NOT_RUNNING }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
    }

    @Synchronized
    fun refresh() {
        if (state.value == State.WATCHING || state.value == State.STARTING) return
        when {
            !Shizuku.pingBinder() -> state.value = State.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                state.value = State.NO_PERMISSION
            else -> {
                state.value = State.STARTING
                runCatching { Shizuku.bindUserService(serviceArgs, connection) }.onFailure {
                    Log.w(TAG, "bind failed", it)
                    state.value = State.FAILED
                }
            }
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(1)
    }

    private companion object {
        const val TAG = "Clipway"
    }
}
