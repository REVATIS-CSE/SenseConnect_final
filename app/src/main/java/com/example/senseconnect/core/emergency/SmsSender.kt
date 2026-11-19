package com.example.senseconnect.core.emergency

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

sealed interface SmsResult {
    /** Android's radio layer confirmed every part of the message was sent. */
    data object Sent : SmsResult
    data class Failed(val reason: String) : SmsResult
}

/**
 * Sends an SMS directly with [SmsManager] and waits for the system "sent" broadcast for every
 * message part. Success is reported only when Android confirms it — never assumed.
 */
class SmsSender(private val context: Context) {

    fun canSendDirectly(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun send(phone: String, message: String, timeoutMs: Long = 30_000): SmsResult {
        if (!canSendDirectly()) return SmsResult.Failed("SMS permission not granted")
        val manager = smsManager() ?: return SmsResult.Failed("SMS is not available on this device")
        val parts = manager.divideMessage(message)
        val action = "${context.packageName}.SMS_SENT.${requestCounter.incrementAndGet()}"

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var remaining = parts.size
                var failure: String? = null
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (resultCode != Activity.RESULT_OK && failure == null) failure = describe(resultCode)
                        remaining--
                        if (remaining == 0) {
                            runCatching { context.unregisterReceiver(this) }
                            if (cont.isActive) cont.resume(failure?.let { SmsResult.Failed(it) } ?: SmsResult.Sent)
                        }
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

                val sentIntents = ArrayList(parts.indices.map { index ->
                    PendingIntent.getBroadcast(
                        context, index,
                        Intent(action).setPackage(context.packageName),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                })
                try {
                    manager.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
                } catch (e: Exception) {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(SmsResult.Failed(e.message ?: "Could not send SMS"))
                }
            }
        } ?: SmsResult.Failed("No delivery confirmation from Android within ${timeoutMs / 1000} s")
    }

    private fun smsManager(): SmsManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java)
    } else {
        @Suppress("DEPRECATION")
        SmsManager.getDefault()
    }

    private fun describe(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile network service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Mobile radio is off (airplane mode?)"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Message could not be encoded"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Carrier rejected the message"
        else -> "SMS failed (code $code)"
    }

    private companion object {
        val requestCounter = AtomicInteger()
    }
}
