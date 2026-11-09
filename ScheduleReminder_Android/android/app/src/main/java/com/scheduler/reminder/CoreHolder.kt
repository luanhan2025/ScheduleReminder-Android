package com.scheduler.reminder

import android.content.Context
import com.scheduler.reminder.core.ScheduleManager

/** Only one Rust-backed SQLite connection per Android process. */
object CoreHolder {
    @Volatile private var instance: ScheduleManager? = null

    fun get(context: Context): ScheduleManager = instance ?: synchronized(this) {
        instance ?: ScheduleManager(
            context.applicationContext.getDatabasePath("schedule_reminder.sqlite3").apply {
                parentFile?.mkdirs()
            }.absolutePath
        ).also { instance = it }
    }
}
