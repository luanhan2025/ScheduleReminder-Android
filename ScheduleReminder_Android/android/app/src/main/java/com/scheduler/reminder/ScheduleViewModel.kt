package com.scheduler.reminder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scheduler.reminder.core.ScheduleManager
import com.scheduler.reminder.core.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ScheduleState(
    val tasks: List<Task> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ScheduleState())
    internal val state = _state.asStateFlow()
    private val mutation = Mutex()

    init { refresh() }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    fun refresh() {
        viewModelScope.launch {
            mutation.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val app = getApplication<Application>()
                        val tasks = CoreHolder.get(app).listTasks()
                        ReminderScheduler.reconcile(app, tasks)
                        _state.value = ScheduleState(tasks, false)
                    } catch (t: Exception) {
                        _state.value = _state.value.copy(loading = false, error = readable(t))
                    } catch (t: LinkageError) {
                        _state.value = _state.value.copy(loading = false, error = readable(t))
                    }
                }
            }
        }
    }

    fun save(
        taskId: String?, title: String, notes: String,
        dueLocal: String, timeZone: String, onSuccess: () -> Unit
    ) {
        change(onSuccess) { manager ->
            if (taskId == null) manager.createTask(title, notes, dueLocal, timeZone)
            else manager.updateTask(taskId, title, notes, dueLocal, timeZone)
        }
    }

    fun toggle(taskId: String, completed: Boolean) {
        change { it.setCompleted(taskId, completed) }
    }

    fun delete(taskId: String) {
        change { it.deleteTask(taskId) }
    }

    private fun change(onSuccess: () -> Unit = {}, block: (ScheduleManager) -> Unit) {
        viewModelScope.launch {
            val done = mutation.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val app = getApplication<Application>()
                        val core = CoreHolder.get(app)
                        block(core)
                        val tasks = core.listTasks()
                        ReminderScheduler.reconcile(app, tasks)
                        _state.value = ScheduleState(tasks, false)
                        true
                    } catch (t: Exception) {
                        _state.value = _state.value.copy(error = readable(t))
                        false
                    } catch (t: LinkageError) {
                        _state.value = _state.value.copy(error = readable(t))
                        false
                    }
                }
            }
            if (done) onSuccess()
        }
    }

    private fun readable(error: Throwable): String =
        error.message ?: "Không thể mở dữ liệu. Hãy kiểm tra bản APK đã có thư viện Rust."
}
