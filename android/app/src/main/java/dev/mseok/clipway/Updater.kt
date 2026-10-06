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

/** An APK to fetch: where it is and what it must turn out to be. */
data class ApkSource(val url: String, val packageName: String, val size: Long, val sha256: String)

/** How far one install has come, as the app shows it. */
sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val percent: Int) : InstallState
    /** The system asks the user to confirm; [confirm] opens its dialog. */
    data class Confirming(val confirm: Intent) : InstallState
    data class Failed(val message: String) : InstallState
}

/**
 * Streams an APK into a system installer session, checking its size and hash on the way,
 * and follows what the installer then reports. [target] tells [InstallReceiver] which
 * installer a report belongs to.
 */
class ApkInstaller(private val context: Context, private val scope: CoroutineScope, private val target: String) {
    val state = MutableStateFlow<InstallState>(InstallState.Idle)

    val busy get() = state.value is InstallState.Downloading || state.value is InstallState.Confirming

    /** [silent] asks the system to skip its dialog, which it grants to an app updating itself. */
    fun start(source: ApkSource, silent: Boolean) {
        if (busy) return
        state.value = InstallState.Downloading(0)
        scope.launch {
            runCatching { downloadAndCommit(source, silent) }.onFailure {
                Log.w(TAG, "$target download failed: ${it.javaClass.simpleName}")
                state.value = InstallState.Failed("내려받지 못했습니다. 인터넷 연결을 확인해 주세요.")
            }
        }
    }

    /** What the system installer reports for the session committed by [start]. */
    fun onStatus(intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                state.value = InstallState.Confirming(confirm)
                // Opens while the app is on screen; otherwise the button in the app opens it.
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> state.value = InstallState.Idle
            else -> {
                Log.w(TAG, "$target install failed: status $status")
                state.value = InstallState.Failed(
                    when (status) {
                        PackageInstaller.STATUS_FAILURE_ABORTED -> "설치를 취소했습니다."
                        PackageInstaller.STATUS_FAILURE_BLOCKED ->
                            "설치가 차단되었습니다. 설정 → 보안 및 개인정보 보호 → '보안 위험 자동 차단'을 잠시 끈 뒤 다시 시도해 주세요."
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "저장 공간이 부족해 설치하지 못했습니다."
                        else -> "설치하지 못했습니다."
                    }
                )
            }
        }
    }

    private fun downloadAndCommit(source: ApkSource, silent: Boolean) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(source.packageName)
            setSize(source.size)
            if (silent) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val connection = open(source.url)
            try {
                check(connection.responseCode == 200) { "http ${connection.responseCode}" }
                connection.inputStream.use { input ->
                    session.openWrite("package.apk", 0, source.size).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= source.size) { "longer than announced" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            state.value = InstallState.Downloading((total * 100 / source.size).toInt())
                        }
                        session.fsync(output)
                    }
                }
            } finally {
                connection.disconnect()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(total == source.size && hash == source.sha256) { "not the announced file" }
            val status = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallReceiver::class.java).putExtra(InstallReceiver.EXTRA_TARGET, target),
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

    companion object {
        private const val TAG = "Clipway"

        fun open(url: String): HttpURLConnection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
    }
}

/**
 * Finds a newer release and hands its APK to the system installer. Looking is automatic;
 * installing starts with a tap in the app. The system only accepts an APK signed with the
 * same key as the installed app, so a forged release cannot be installed this way.
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {
    enum class Check { NEWER, CURRENT, UNREACHABLE }

    /** The newer release on offer, if any. */
    val available = MutableStateFlow<Release?>(null)
    val installer = ApkInstaller(context, scope, TARGET)

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
        if (installer.busy) return Check.NEWER
        val release = fetchManifest() ?: return Check.UNREACHABLE
        if (!Release.isNewer(release.version, BuildConfig.VERSION_NAME)) {
            available.value = null
            return Check.CURRENT
        }
        available.value = release
        announce(release.version)
        return Check.NEWER
    }

    fun install() {
        val release = available.value ?: return
        installer.start(
            ApkSource(
                "${BuildConfig.RELEASES_URL}/download/v${release.version}/${release.file}",
                context.packageName, release.size, release.sha256,
            ),
            silent = true,
        )
    }

    private fun fetchManifest(): Release? = runCatching {
        val connection = ApkInstaller.open("${BuildConfig.RELEASES_URL}/latest/download/release.json")
        try {
            val bytes = if (connection.responseCode != 200) null
            else connection.inputStream.use { it.readNBytes(Release.MAX_MANIFEST_BYTES + 1) }
            bytes?.takeIf { it.size <= Release.MAX_MANIFEST_BYTES }?.let { Release.parse(String(it)) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

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

    companion object {
        const val TARGET = "update"
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 2
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val STALE_MS = 6 * 60 * 60 * 1000L
    }
}

/**
 * Installs Shizuku for users who do not have it, from its own release page. The file is
 * pinned by hash, so what gets installed is exactly the release that was looked at when
 * this was written. scripts/setup-phone.sh pins the same file.
 */
class ShizukuInstall(context: Context, scope: CoroutineScope) {
    val installer = ApkInstaller(context, scope, TARGET)

    fun install() = installer.start(SOURCE, silent = false)

    companion object {
        const val TARGET = "shizuku"
        const val PACKAGE = "moe.shizuku.privileged.api"
        val SOURCE = ApkSource(
            "https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk",
            PACKAGE, 2_571_773, "6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f",
        )
    }
}

/** Receives the system installer's reports. Not exported: only this app's sessions reach it. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BridgeApp
        when (intent.getStringExtra(EXTRA_TARGET)) {
            Updater.TARGET -> app.updater.installer.onStatus(intent)
            ShizukuInstall.TARGET -> app.shizukuInstall.installer.onStatus(intent)
        }
    }

    companion object {
        const val EXTRA_TARGET = "dev.mseok.clipway.target"
    }
}
