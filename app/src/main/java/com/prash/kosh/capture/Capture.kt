package com.prash.kosh.capture

import kotlinx.coroutines.tasks.await
import android.Manifest
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.prash.kosh.KoshApp
import com.prash.kosh.data.IngestOutcome
import com.prash.kosh.engine.CaptureSource
import com.prash.kosh.engine.RawInput
import com.prash.kosh.engine.TransactionParser
import kotlinx.coroutines.launch

/** New bank SMS, as they arrive. Multi-part messages are joined before parsing. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bySender = parts.groupBy { it.originatingAddress ?: "" }
        val app = KoshApp.get(context)
        val pending = goAsync()
        app.appScope.launch {
            try {
                for ((sender, msgs) in bySender) {
                    val body = msgs.joinToString("") { it.messageBody ?: "" }
                    app.repo.ingest(RawInput(CaptureSource.SMS, sender, body, System.currentTimeMillis()))
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Payment-app and bank-app notifications (GPay, PhonePe, Paytm, CRED, bank apps). */
class PaymentNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg !in TransactionParser.notificationApps) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val body = listOf(title, text).filter { it.isNotBlank() }.joinToString("\n")
        if (body.isBlank()) return
        val app = KoshApp.get(this)
        app.appScope.launch { app.repo.ingest(RawInput(CaptureSource.NOTIFICATION, pkg, body, sbn.postTime)) }
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, PaymentNotificationListener::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }
}

/** Reads the SMS inbox for the last [days] days and runs every message through the engine. */
object SmsInboxImporter {
    data class Summary(val scanned: Int, val counts: Map<IngestOutcome, Int>)

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun import(context: Context, days: Int, onProgress: (Int) -> Unit = {}): Summary {
        val since = System.currentTimeMillis() - days * 86_400_000L
        val repo = KoshApp.get(context).repo
        val counts = HashMap<IngestOutcome, Int>()
        var n = 0
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0) ?: continue
                // Only DLT-registered business headers (XX-ABCDEF); skips personal chats entirely.
                if (!Regex("""^[A-Z]{2}-?[A-Z0-9]{6}(-[A-Z])?$""", RegexOption.IGNORE_CASE).matches(sender)) continue
                val outcome = repo.ingest(RawInput(CaptureSource.SMS, sender, c.getString(1) ?: "", c.getLong(2)))
                counts[outcome] = (counts[outcome] ?: 0) + 1
                if (++n % 25 == 0) onProgress(n)
            }
        }
        return Summary(n, counts)
    }
}

/** "Share" any message text to the app (works without SMS permission). */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) { finish(); return }
        val app = KoshApp.get(this)
        lifecycleScope.launch {
            val outcome = app.repo.ingest(RawInput(CaptureSource.SHARED, "shared", text, System.currentTimeMillis()))
            val msg = when (outcome) {
                IngestOutcome.POSTED -> "Saved to Kosh"
                IngestOutcome.PENDING -> "Saved, marked for a quick check"
                IngestOutcome.NEEDS_REVIEW -> "Added to Review"
                IngestOutcome.SUSPECTED_DUPLICATE -> "Looks like a duplicate; held in Review"
                IngestOutcome.MERGED -> "Already recorded; linked to it"
                IngestOutcome.ALREADY_SEEN -> "Already imported"
                IngestOutcome.IGNORED -> "Not a transaction (ignored)"
            }
            Toast.makeText(this@ShareReceiverActivity, msg, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

fun openNotificationAccessSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** On-device text recognition for screenshots (Google ML Kit, bundled model: works offline). */
object ScreenshotReader {
    suspend fun read(context: android.content.Context, uri: android.net.Uri): String {
        val image = com.google.mlkit.vision.common.InputImage.fromFilePath(context, uri)
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = recognizer.process(image).await()
            // Keep the reading order; join blocks with newlines like an SMS body.
            return text.textBlocks.joinToString("\n") { it.text }.take(2000)
        } finally { recognizer.close() }
    }
}
