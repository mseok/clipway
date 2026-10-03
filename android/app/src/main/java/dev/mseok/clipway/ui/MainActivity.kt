package dev.mseok.clipway.ui

import android.Manifest
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
import dev.mseok.clipway.protocol.PairedMac
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

data class Permissions(val notifications: Boolean, val sms: Boolean, val battery: Boolean)

class MainActivity : ComponentActivity() {
    private val bridge get() = (application as BridgeApp).bridge
    private val permissions = MutableStateFlow(Permissions(false, false, false))
    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshPermissions()
            // A sideloaded app gets no dialog for SMS until restricted settings are allowed.
            if (!granted) openAppSettings()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeService.start(this)
        // Folding the phone recreates the activity with the same intent; pair only once.
        if (savedInstanceState == null) intent?.dataString?.let(::pair)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let(::pair)
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
            .addOnSuccessListener { it.rawValue?.let(::pair) }
            .addOnFailureListener { toast("스캐너를 준비하는 중입니다. 잠시 후 다시 시도하거나 기본 카메라로 QR을 스캔해 주세요") }
    }

    private fun pair(link: String) {
        val mac = PairedMac.fromPairingLink(link) ?: return toast("Clipway QR이 아닙니다")
        toast("${mac.name}에 연결하는 중")
        lifecycleScope.launch {
            val paired = bridge.pair(mac)
            toast(if (paired) "${mac.name} 페어링 완료" else "Mac에 연결하지 못했습니다. 같은 Wi-Fi이거나 Tailscale이 켜져 있는지 확인해 주세요")
        }
    }

    private fun sendClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) return toast("클립보드에 텍스트가 없습니다")
        lifecycleScope.launch {
            toast(if (bridge.sendNow(text)) "Mac으로 보냈습니다" else "연결된 Mac이 없습니다")
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
                                if (mac.id in connected) "연결됨" else "연결 대기 중",
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
