package dev.mseok.clipway

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sends text to the paired Macs without the Shizuku watcher and reports the result in a toast. */
private fun Activity.sendToMac(text: String?) {
    val bridge = (application as BridgeApp).bridge
    val context = applicationContext
    if (text.isNullOrEmpty()) {
        Toast.makeText(context, "보낼 텍스트가 없습니다", Toast.LENGTH_SHORT).show()
    } else {
        bridge.scope.launch {
            val delivered = bridge.sendNow(text)
            withContext(Dispatchers.Main) {
                val message = if (delivered) "Mac으로 보냈습니다" else "연결된 Mac이 없습니다"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    finish()
}

/**
 * Entry in the share menu and the text selection menu. It has to be exported for those
 * menus, so it only ever sends the text it was handed; it never reads the clipboard.
 */
class ShareToMacActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Any app can start this activity; extras that fail to unpack must not crash the app.
        val text = runCatching {
            when (intent.action) {
                Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                else -> null
            }
        }.getOrNull()
        sendToMac(text?.toString())
    }
}

/**
 * Sends the current clipboard; started by the quick settings tile only (not exported).
 * The activity is invisible and exists because only a focused app may read the clipboard.
 */
class SendClipboardActivity : Activity() {
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        sendToMac(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString())
    }
}

class SendTileService : TileService() {
    override fun onClick() {
        val intent = Intent(this, SendClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
