package com.scheduler.reminder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val viewModel: ScheduleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderScheduler.makeChannel(applicationContext)
        setContent { ScheduleReminderUI(viewModel) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh() // Also reschedules if an exact-alarm permission just changed.
    }
}
