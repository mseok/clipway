package dev.mseok.clipway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Forwards verification codes from incoming SMS. Works even when the service is not running. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val bridge = (context.applicationContext as BridgeApp).bridge
        if (!bridge.otpEnabled.value) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val code = OtpExtractor.extract(body) ?: return
        val sender = parts.firstOrNull()?.displayOriginatingAddress.orEmpty()

        val pending = goAsync()
        bridge.scope.launch {
            try {
                val delivered = withTimeoutOrNull(SEND_TIMEOUT_MS) { bridge.sendOtp(code, sender) }
                Log.i("Clipway", "verification code delivered: ${delivered == true}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val SEND_TIMEOUT_MS = 8_000L
    }
}

/** Restarts the service after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                runCatching { BridgeService.start(context) }
        }
    }
}
