package dev.mseok.clipway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.SystemClock
import android.util.Log
import dev.mseok.clipway.ui.MainActivity
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The Android part of a release's `release.json`. */
data class Release(val version: String, val file: String, val sha256: String, val size: Long) {
    companion object {
        const val MAX_MANIFEST_BYTES = 16 * 1024
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        private val FILE = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}")
        private val HASH = Regex("[0-9a-f]{64}")

        /** Null unless [json] is a well-formed manifest. */
        fun parse(json: String): Release? = runCatching {
            val root = JSONObject(json)
            val apk = root.getJSONObject("android")
            Release(root.getString("version"), apk.getString("file"), apk.getString("sha256"), apk.getLong("size"))
        }.getOrNull()?.takeIf {
            parseVersion(it.version) != null && FILE.matches(it.file) && HASH.matches(it.sha256) &&
                it.size in 1..MAX_APK_BYTES
        }

        /** "0.1.0" as numbers without trailing zeros, or null for anything else. */
        fun parseVersion(text: String): List<Int>? {
            val fields = text.split(".")
            if (fields.size !in 1..4 || fields.any { !it.matches(Regex("[0-9]{1,6}")) }) return null
            return fields.map(String::toInt).dropLastWhile { it == 0 }
        }

        fun isNewer(offered: String, current: String): Boolean {
            val a = parseVersion(offered) ?: return false
            val b = parseVersion(current) ?: return false
            for (index in 0 until maxOf(a.size, b.size)) {
                val difference = a.getOrElse(index) { 0 } - b.getOrElse(index) { 0 }
                if (difference != 0) return difference > 0
            }
            return false
        }
    }
}

/**
 * Finds a newer release and hands its APK to the system installer. Looking is automatic;
 * installing starts with a tap in the app. The system only accepts an APK signed with the
 * same key as the installed app, so a forged release cannot be installed this way.
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        /** The system asks the user to confirm; [confirm] opens its dialog. */
        data class Confirming(val release: Release, val confirm: Intent) : State
        data class Failed(val message: String) : State
    }

    enum class Check { NEWER, CURRENT, UNREACHABLE }

    val state = MutableStateFlow<State>(State.Idle)

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private var schedule: Job? = null
    @Volatile private var lastCheck = 0L

    /** Checks now and then once a day, for as long as the process lives. */
    @Synchronized
    fun start() {
        if (schedule != null) return
        schedule = scope.launch {
            while (true) {
                checkForUpdate()
                delay(DAY_MS)
            }
        }
    }

    /** For when the app comes to the front: the daily timer stalls while the phone sleeps. */
    fun checkIfStale() {
        if (SystemClock.elapsedRealtime() - lastCheck > STALE_MS) scope.launch { checkForUpdate() }
    }

    /** Asks the release server; blocks, so call it off the main thread. */
    fun checkForUpdate(): Check {
        lastCheck = SystemClock.elapsedRealtime()
        val current = state.value
        if (current is State.Downloading || current is State.Confirming) return Check.NEWER
        val release = fetchManifest() ?: return Check.UNREACHABLE
        if (!Release.isNewer(release.version, BuildConfig.VERSION_NAME)) {
            state.value = State.Idle
            return Check.CURRENT
        }
        state.value = State.Available(release)
        announce(release.version)
        return Check.NEWER
    }

    fun install() {
        val release = (state.value as? State.Available)?.release ?: return
        state.value = State.Downloading(release, 0)
        scope.launch {
            runCatching { downloadAndCommit(release) }.onFailure {
                Log.w(TAG, "update download failed: ${it.javaClass.simpleName}")
                state.value = State.Failed("업데이트를 내려받지 못했습니다. 인터넷 연결을 확인해 주세요.")
            }
        }
    }

    /** What the system installer reports for a session committed by [install]. */
    fun onInstallerStatus(intent: Intent) {
        val release = when (val current = state.value) {
            is State.Downloading -> current.release
            is State.Confirming -> current.release
            else -> null
        }
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (release != null) state.value = State.Confirming(release, confirm)
                // Opens while the app is on screen; otherwise the button in the app opens it.
                runCatching { context.startActivity(confirm) }
            }
            // Normally never seen: the update replaces this process.
            PackageInstaller.STATUS_SUCCESS -> state.value = State.Idle
            else -> {
                Log.w(TAG, "update install failed: status $status")
                state.value = State.Failed(
                    when (status) {
                        PackageInstaller.STATUS_FAILURE_ABORTED -> "설치를 취소했습니다."
                        PackageInstaller.STATUS_FAILURE_BLOCKED ->
                            "설치가 차단되었습니다. 설정 → 보안 및 개인정보 보호 → '보안 위험 자동 차단'을 잠시 끈 뒤 다시 시도해 주세요."
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "저장 공간이 부족해 설치하지 못했습니다."
                        else -> "업데이트를 설치하지 못했습니다."
                    }
                )
            }
        }
    }

    private fun fetchManifest(): Release? = runCatching {
        val connection = open("${BuildConfig.RELEASES_URL}/latest/download/release.json")
        try {
            val bytes = if (connection.responseCode != 200) null
            else connection.inputStream.use { it.readNBytes(Release.MAX_MANIFEST_BYTES + 1) }
            bytes?.takeIf { it.size <= Release.MAX_MANIFEST_BYTES }?.let { Release.parse(String(it)) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Streams the APK into an installer session, checking its size and hash on the way. */
    private fun downloadAndCommit(release: Release) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(release.size)
            // Once an update has gone through this app, later ones need no system dialog.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val connection = open("${BuildConfig.RELEASES_URL}/download/v${release.version}/${release.file}")
            try {
                check(connection.responseCode == 200) { "http ${connection.responseCode}" }
                connection.inputStream.use { input ->
                    session.openWrite("clipway.apk", 0, release.size).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= release.size) { "longer than announced" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            state.value = State.Downloading(release, (total * 100 / release.size).toInt())
                        }
                        session.fsync(output)
                    }
                }
            } finally {
                connection.disconnect()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(total == release.size && hash == release.sha256) { "does not match the manifest" }
            val status = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }

    /** One notification per version: the app is rarely opened once it is set up. */
    private fun announce(version: String) {
        if (prefs.getString("announced", null) == version) return
        prefs.edit().putString("announced", version).apply()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "업데이트", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_bridge)
                .setContentTitle("Clipway $version 업데이트")
                .setContentText("눌러서 설치할 수 있습니다.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val TAG = "Clipway"
        const val CHANNEL = "updates"
        const val NOTIFICATION_ID = 2
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val STALE_MS = 6 * 60 * 60 * 1000L
    }
}

/** Receives the installer's progress for an update session. Only this app can send to it. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as BridgeApp).updater.onInstallerStatus(intent)
    }
}
