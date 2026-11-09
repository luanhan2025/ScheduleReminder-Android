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
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.scheduler.reminder.core.ReminderOccurrence
import com.scheduler.reminder.core.Task
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One OS alarm per task, automatically scheduling its next occurrence when one fires. */
object ReminderScheduler {
    private const val DEFAULT_CHANNEL = "task_reminders"
    private const val PREFERENCES = "scheduled_reminders"
    private const val KEY_IDS = "known_alarm_ids"
    private const val OCCURRENCE_ID = "occurrence_id"

    fun exactAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    fun notificationsAllowed(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }

    fun makeChannel(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(DEFAULT_CHANNEL, "Nhắc công việc (mặc định)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Âm thanh mặc định của ứng dụng"
                enableVibration(true)
            }
        )
    }

    private fun channelFor(context: Context, task: Task): String {
        if (task.soundId.isBlank()) return DEFAULT_CHANNEL
        if (SoundStore.soundFile(context, task.soundId) == null) return DEFAULT_CHANNEL
        // A new sound creates a new channel because Android notification channel sounds are immutable.
        val channelId = "sound_${task.id}_${task.soundId}"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "${task.title.take(32)} • ${task.soundName.take(30)}", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Nhạc chuông riêng của công việc"
                    enableVibration(true)
                    setSound(SoundStore.soundUri(context, task.soundId),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                }
            )
        }
        return channelId
    }

    @Synchronized
    fun reconcile(context: Context) {
        makeChannel(context)
        val core = CoreHolder.get(context)
        val upcoming = core.listNextOccurrences(System.currentTimeMillis() / 1000L)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()
        val newIds = upcoming.map { "occ:${it.id}" }.toSet()
        // Also cancel legacy alarms produced by v1.0 (IDs without an 'occ:' prefix).
        (previous - newIds).forEach { old ->
            if (old.startsWith("occ:")) {
                alarmManager.cancel(pendingIntent(context, old.removePrefix("occ:")))
            } else {
                alarmManager.cancel(legacyPendingIntent(context, old))
            }
        }
        upcoming.forEach { event ->
            val atMs = TimeUnit.SECONDS.toMillis(event.fireAtUtc)
            val alarm = pendingIntent(context, event.id)
            try {
                if (exactAllowed(context)) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarm)
                else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarm)
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarm)
            }
        }
        prefs.edit().putStringSet(KEY_IDS, newIds).apply()
    }

    private fun pendingIntent(context: Context, occurrenceId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            data = Uri.parse("scheduler://occ/${Uri.encode(occurrenceId)}")
            putExtra(OCCURRENCE_ID, occurrenceId)
        }
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun legacyPendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            data = Uri.parse("scheduler://task/${Uri.encode(taskId)}")
        }
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showNotification(context: Context, task: Task, occurrenceId: String) {
        if (!notificationsAllowed(context)) return
        makeChannel(context)
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = task.notes.ifBlank { "Đã đến giờ nhắc của bạn" }
        val notification = NotificationCompat.Builder(context, channelFor(context, task))
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(task.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(occurrenceId.hashCode(), notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.getStringExtra("occurrence_id") ?: return
        val pending = goAsync()
        thread(name = "reminder-notify") {
            try {
                val core = CoreHolder.get(context)
                val dueTask = core.claimOccurrence(occurrenceId, System.currentTimeMillis() / 1000L)
                if (dueTask != null) ReminderScheduler.showNotification(context, dueTask, occurrenceId)
                ReminderScheduler.reconcile(context.applicationContext)
            } catch (e: Exception) {
                Log.e("ScheduleReminder", "Unable to deliver/rearm reminder", e)
            } finally { pending.finish() }
        }
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "reminder-restore") {
            try { ReminderScheduler.reconcile(context.applicationContext) }
            catch (e: Exception) { Log.e("ScheduleReminder", "Unable to restore reminders", e) }
            finally { pending.finish() }
        }
    }
}