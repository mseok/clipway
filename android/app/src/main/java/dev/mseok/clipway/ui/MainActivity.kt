package dev.mseok.clipway.ui

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.mseok.clipway.BridgeApp
import dev.mseok.clipway.BridgeService
import dev.mseok.clipway.ClipboardWatcher
import dev.mseok.clipway.LinkTest
import dev.mseok.clipway.PairingRequest
import dev.mseok.clipway.protocol.PairedMac
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

data class Permissions(val notifications: Boolean, val sms: Boolean, val battery: Boolean)

class MainActivity : ComponentActivity() {
    private val bridge get() = (application as BridgeApp).bridge
    private val permissions = MutableStateFlow(Permissions(false, false, false))
    private val testResults = MutableStateFlow<List<LinkTest>?>(null)
    private val testing = MutableStateFlow(false)
    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshPermissions()
            // A sideloaded app gets no dialog for SMS until restricted settings are allowed.
            if (!granted) openAppSettings()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeService.start(this)
        // No other app may draw over this screen: the pairing dialog must be what it seems.
        window.setHideOverlayWindows(true)
        // Folding the phone recreates the activity with the same intent; handle it only once.
        if (savedInstanceState == null) intent?.dataString?.let { requestPairing(it, external = true) }
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { requestPairing(it, external = true) }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        bridge.watcher.refresh()
        bridge.kick()
    }

    private fun refreshPermissions() {
        fun granted(permission: String) =
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        permissions.value = Permissions(
            notifications = granted(Manifest.permission.POST_NOTIFICATIONS),
            sms = granted(Manifest.permission.RECEIVE_SMS),
            battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun openAppSettings() {
        toast("앱 정보의 ⋮ 메뉴에서 '제한된 설정 허용'을 누른 뒤 권한을 켜 주세요")
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let { requestPairing(it, external = false) } }
            .addOnFailureListener { toast("스캐너를 준비하는 중입니다. 잠시 후 다시 시도하거나 기본 카메라로 QR을 스캔해 주세요") }
    }

    /**
     * Pairing hands this phone's clipboard and verification codes to the other side, and a
     * link can come from any app or web page. Nothing is paired until the user confirms.
     */
    private fun requestPairing(link: String, external: Boolean) {
        val pending = (application as BridgeApp).pendingPairing
        // A request from outside must not change a dialog that is already on screen.
        if (external && pending.value != null) return
        val parsed = PairedMac.fromPairingLink(link) ?: return toast("Clipway QR이 아닙니다")
        // An address of this phone itself would be another app here posing as a Mac.
        val mac = parsed.copy(hosts = parsed.hosts.filterNot(bridge::isOwnAddress))
        pending.value = PairingRequest(mac, external)
    }

    private fun pair(mac: PairedMac) {
        toast("${mac.name}에 연결하는 중")
        lifecycleScope.launch {
            val paired = bridge.pair(mac)
            val code = bridge.macs.value.firstOrNull { it.id == mac.id }?.let(bridge::pairingCode)
            toast(
                if (paired) "${mac.name} 페어링 완료 · 확인 코드 $code (Mac 화면의 숫자와 같아야 합니다)"
                else "Mac에 연결하지 못했습니다. 같은 Wi-Fi이거나 Tailscale이 켜져 있는지 확인해 주세요"
            )
        }
    }

    private fun sendClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (text.isNullOrEmpty()) return toast("클립보드에 텍스트가 없습니다")
        val sensitive = clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) ?: false
        lifecycleScope.launch {
            toast(if (bridge.sendNow(text, sensitive)) "Mac으로 보냈습니다" else "연결된 Mac이 없습니다")
        }
    }

    private fun testConnection() {
        if (bridge.macs.value.isEmpty()) return toast("페어링된 Mac이 없습니다")
        testing.value = true
        lifecycleScope.launch {
            testResults.value = bridge.testLinks()
            testing.value = false
        }
    }

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
        runCatching { startActivity(launch) }.onFailure { toast("Play 스토어에서 Shizuku를 설치해 주세요") }
    }

    @Composable
    private fun Screen() {
        val macs by bridge.macs.collectAsState()
        val connected by bridge.connected.collectAsState()
        val watcher by bridge.watcher.state.collectAsState()
        val granted by permissions.collectAsState()
        val clipboardEnabled by bridge.clipboardEnabled.collectAsState()
        val otpEnabled by bridge.otpEnabled.collectAsState()
        val skipSensitive by bridge.skipSensitive.collectAsState()
        val pendingPairing = (application as BridgeApp).pendingPairing
        val pairing by pendingPairing.collectAsState()
        val results by testResults.collectAsState()
        val busy by testing.collectAsState()

        results?.let { list ->
            AlertDialog(
                onDismissRequest = { testResults.value = null },
                title = { Text("연결 테스트") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { result ->
                            Text(
                                if (result.millis != null) "✓ ${result.name}: 정상 (왕복 ${result.millis}ms)"
                                else "✗ ${result.name}: 응답 없음"
                            )
                        }
                        if (list.any { it.millis != null }) {
                            Text("정상인 Mac의 화면 오른쪽 위에 '폰 연결 테스트' 알림이 떴습니다.")
                        }
                        if (list.any { it.millis == null }) {
                            Text("응답이 없으면 Mac에서 Clipway가 실행 중인지, 같은 Wi-Fi이거나 Tailscale이 켜져 있는지 확인하세요.")
                        }
                        Text(
                            if (watcher == ClipboardWatcher.State.WATCHING) "복사 자동 감지: 켜짐. 폰에서 복사하면 자동으로 전달됩니다."
                            else "복사 자동 감지: 꺼짐. 복사해도 자동으로 넘어가지 않으니 타일이나 공유 메뉴로 보내세요."
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { testResults.value = null }) { Text("확인") }
                },
            )
        }

        pairing?.let { request ->
            val mac = request.mac
            // The name and id in a link are whatever its author wrote. A link from outside
            // the app may add a Mac but never take the place of one that is already paired.
            val replaces = macs.any { it.id == mac.id || it.name == mac.name }
            val blocked = request.external && replaces
            // A request from outside can pop up under a finger that was about to tap
            // something else, so its button only works after a moment.
            var armed by remember(request) { mutableStateOf(!request.external) }
            LaunchedEffect(request) {
                if (request.external) {
                    delay(2500)
                    armed = true
                }
            }
            AlertDialog(
                onDismissRequest = { pendingPairing.value = null },
                title = { Text(if (blocked) "페어링할 수 없습니다" else "이 Mac과 페어링할까요?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(mac.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (mac.hosts.isEmpty()) "주소: 같은 Wi-Fi에서 자동으로 찾습니다"
                            else "주소: " + mac.hosts.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (request.external) {
                            Text("이 요청은 Clipway 밖(다른 앱, 웹 페이지, 카메라)에서 들어왔습니다.")
                        }
                        if (blocked) {
                            Text(
                                "같은 이름이나 ID의 Mac이 이미 페어링되어 있습니다. 바꾸려면 먼저 목록에서 " +
                                    "'해제'한 뒤 이 앱의 'Mac 추가 (QR 스캔)'으로 다시 페어링하세요."
                            )
                        } else {
                            if (replaces) Text("이미 페어링된 Mac입니다. 계속하면 연결 정보가 새것으로 바뀝니다.")
                            Text(
                                "페어링하면 이 폰의 클립보드와 문자 인증번호가 이 Mac으로 전달됩니다. " +
                                    "내 Mac 화면에 방금 띄운 QR이 아니라면 취소하세요. " +
                                    "페어링되면 Mac 화면에도 알림이 뜹니다."
                            )
                        }
                    }
                },
                confirmButton = {
                    if (blocked) {
                        TextButton(onClick = { pendingPairing.value = null }) { Text("닫기") }
                    } else {
                        Button(enabled = armed, onClick = {
                            pendingPairing.value = null
                            pair(mac)
                        }) { Text(if (armed) "페어링" else "잠시만…") }
                    }
                },
                dismissButton = {
                    if (!blocked) TextButton(onClick = { pendingPairing.value = null }) { Text("취소") }
                },
            )
        }

        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Clipway", style = MaterialTheme.typography.headlineSmall)

            Section("Mac") {
                if (macs.isEmpty()) Text("Mac 메뉴바의 Clipway에서 '새 폰 페어링'을 눌러 QR을 띄운 뒤 스캔하세요.")
                macs.forEach { mac ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val color = if (mac.id in connected) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.outline
                        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mac.name)
                            Text(
                                (if (mac.id in connected) "연결됨" else "연결 대기 중") +
                                    " · 확인 코드 " + bridge.pairingCode(mac),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { bridge.unpair(mac.id) }) { Text("해제") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::scanQr) { Text("Mac 추가 (QR 스캔)") }
                    OutlinedButton(onClick = ::sendClipboard) { Text("클립보드 보내기") }
                }
                OutlinedButton(onClick = ::testConnection, enabled = !busy) {
                    Text(if (busy) "테스트하는 중…" else "연결 테스트")
                }
            }

            Section("복사 자동 감지") {
                when (watcher) {
                    ClipboardWatcher.State.WATCHING -> Text("켜짐. 폰에서 복사하면 바로 Mac으로 전달됩니다.")
                    ClipboardWatcher.State.STARTING -> Text("시작하는 중")
                    ClipboardWatcher.State.NO_PERMISSION -> {
                        Text("Shizuku 사용 권한이 필요합니다.")
                        Button(onClick = { bridge.watcher.requestPermission() }) { Text("Shizuku 권한 허용") }
                    }
                    ClipboardWatcher.State.NOT_RUNNING -> {
                        Text("Shizuku가 실행 중이 아닙니다. 폰을 재부팅했다면 Shizuku 앱에서 다시 시작해 주세요. 그동안은 빠른 설정 타일이나 공유 메뉴로 보낼 수 있습니다.")
                        OutlinedButton(onClick = ::openShizuku) { Text("Shizuku 열기") }
                    }
                    ClipboardWatcher.State.FAILED -> {
                        Text("감시를 시작하지 못했습니다.")
                        OutlinedButton(onClick = { bridge.watcher.refresh() }) { Text("다시 시도") }
                    }
                }
            }

            Section("동기화") {
                Toggle("클립보드 동기화", clipboardEnabled, bridge::setClipboardEnabled)
                Toggle("인증번호를 Mac으로 전달", otpEnabled, bridge::setOtpEnabled)
                Toggle("민감한 항목은 보내지 않기", skipSensitive, bridge::setSkipSensitive)
                Text(
                    "비밀번호 관리자처럼 복사한 내용을 '민감함'으로 표시하는 앱의 복사만 걸러집니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("권한") {
                PermissionRow("알림", granted.notifications) {
                    requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                PermissionRow("문자 수신 (인증번호)", granted.sms) {
                    requestPermission.launch(Manifest.permission.RECEIVE_SMS)
                }
                PermissionRow("배터리 제한 없음", granted.battery) {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }

    @Composable
    private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            if (granted) {
                Text("허용됨", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedButton(onClick = onRequest) { Text("허용") }
            }
        }
    }
}
