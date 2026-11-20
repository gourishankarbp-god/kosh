package com.prash.kosh.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prash.kosh.KoshApp
import com.prash.kosh.MainActivity
import com.prash.kosh.R
import com.prash.kosh.data.Notifier
import com.prash.kosh.data.upcoming
import com.prash.kosh.engine.Money
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** Budget alerts and payment reminders as Android notifications (only if you allowed them). */
class Notifications(private val context: Context) : Notifier {

    companion object {
        const val BUDGET = "budget_alerts"
        const val REMINDERS = "payment_reminders"

        fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(BUDGET, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When a category reaches 80% and 100% of its budget" })
            nm.createNotificationChannel(NotificationChannel(REMINDERS, "Payment reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Recurring payments, SIPs, LIC premiums and RD instalments that are due" })
        }

        fun post(context: Context, channel: String, id: Int, title: String, text: String) {
            if (!allowed(context)) return
            createChannels(context)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build()
            try { NotificationManagerCompat.from(context).notify(id, n) } catch (e: SecurityException) { /* permission revoked */ }
        }
    }

    override fun budgetAlert(categoryName: String, percent: Int, spentPaise: Long, availablePaise: Long) {
        val title = if (percent >= 100) "$categoryName budget used up" else "$categoryName at $percent% of budget"
        val text = "Spent ${Money.format(spentPaise, showPaise = false)} of ${Money.format(availablePaise, showPaise = false)} this month."
        post(context, BUDGET, ("budget-$categoryName").hashCode(), title, text)
    }
}

/** Runs each morning: reminds about payments due today or within each item's reminder window. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = KoshApp.get(applicationContext)
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        val windows = app.db.recurring().active().associate { "r${it.id}" to it.remindDaysBefore.toLong() }
        app.repo.upcoming(today, horizonDays = 7).forEach { d ->
            val daysLeft = ChronoUnit.DAYS.between(today, d.dueDate)
            val window = windows[d.key] ?: 2L // SIP / LIC / RD: two days ahead
            if (daysLeft > window) return@forEach
            if (app.settings.remindedOnce("${d.key}@${d.dueDate}@${if (daysLeft < 0) "overdue" else "due"}")) return@forEach
            val whenText = when {
                daysLeft < 0 -> "was due ${d.dueDate.dayOfMonth} ${d.dueDate.month.name.lowercase().replaceFirstChar { it.uppercase() }}"
                daysLeft == 0L -> "is due today"
                daysLeft == 1L -> "is due tomorrow"
                else -> "is due in $daysLeft days"
            }
            Notifications.post(applicationContext, Notifications.REMINDERS, d.key.hashCode(),
                "${d.name} $whenText", "${Money.format(d.amountPaise, showPaise = false)}. Open Kosh › Plan › Upcoming to mark it paid.")
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"))
            var first = now.toLocalDate().atTime(8, 30)
            if (!first.isAfter(now)) first = first.plusDays(1)
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, first).toMinutes(), TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("payment-reminders", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
