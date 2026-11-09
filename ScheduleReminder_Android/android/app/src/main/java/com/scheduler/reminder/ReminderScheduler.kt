package com.scheduler.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.scheduler.reminder.core.Task
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** OS alarm ownership remains Android-side. Rust owns times and SQLite records. */
object ReminderScheduler {
    const val CHANNEL_ID = "task_reminders"
    private const val PREFERENCES = "scheduled_reminders"
    private const val KEY_IDS = "known_alarm_ids"
    const val TASK_ID = "task_id"

    fun exactAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    fun notificationsAllowed(context: Context): Boolean {
        val allowedBySystem = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .areNotificationsEnabled()
        return allowedBySystem && (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }

    fun makeChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nhắc công việc", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Thông báo khi đến giờ công việc"
                enableVibration(true)
            }
        )
    }

    fun reconcile(context: Context, tasks: List<Task>) {
        makeChannel(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        // Avoid overloading the system: nearest 128 future, incomplete tasks.
        val upcoming = tasks.asSequence()
            .filter { !it.isCompleted && it.dueAtUtc > now / 1000L }
            .sortedBy { it.dueAtUtc }
            .take(128)
            .toList()
        val newIds = upcoming.map { it.id }.toSet()
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()
        (previous - newIds).forEach { id ->
            alarmManager.cancel(pendingIntent(context, id))
        }
        upcoming.forEach { task ->
            val atMs = TimeUnit.SECONDS.toMillis(task.dueAtUtc)
            val alarm = pendingIntent(context, task.id)
            try {
                if (exactAllowed(context)) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarm)
                } else {
                    // No exact-alarm entitlement: deliver best-effort, possibly delayed.
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarm)
                }
            } catch (denied: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarm)
            }
        }
        prefs.edit().putStringSet(KEY_IDS, newIds).apply()
    }

    private fun pendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            data = Uri.parse("scheduler://task/${Uri.encode(id)}")
            putExtra(TASK_ID, id)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun showNotification(context: Context, task: Task) {
        makeChannel(context)
        if (!notificationsAllowed(context)) return
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.scheduler.reminder.R.drawable.ic_app)
            .setContentTitle(task.title)
            .setContentText(task.notes.ifBlank { "Đã đến giờ công việc của bạn" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.notes.ifBlank { "Đã đến giờ công việc của bạn" }))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(task.id.hashCode(), notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ReminderScheduler.TASK_ID) ?: return
        val result = goAsync()
        thread(name = "reminder-notify") {
            try {
                val task = CoreHolder.get(context).listTasks().find { it.id == id }
                if (task != null && !task.isCompleted && task.dueAtUtc <= System.currentTimeMillis() / 1000L) {
                    ReminderScheduler.showNotification(context, task)
                }
            } catch (e: Exception) {
                Log.e("ScheduleReminder", "Unable to deliver reminder", e)
            } finally {
                result.finish()
            }
        }
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        thread(name = "reminder-restore") {
            try {
                ReminderScheduler.reconcile(context.applicationContext, CoreHolder.get(context).listTasks())
            } catch (e: Exception) {
                Log.e("ScheduleReminder", "Unable to restore reminders", e)
            } finally {
                result.finish()
            }
        }
    }
}
