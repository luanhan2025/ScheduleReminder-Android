package com.scheduler.reminder

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scheduler.reminder.core.Task
import com.scheduler.reminder.core.TaskOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Indigo = Color(0xFF4F46E5)
private val Ink = Color(0xFF202449)
private val Calm = Color(0xFFF5F7FE)
private val SoftInk = Color(0xFF68708B)
private val accentPalette = lightColorScheme(
    primary = Indigo, onPrimary = Color.White,
    background = Calm, surface = Color.White,
    onSurface = Ink, onBackground = Ink,
    secondary = Color(0xFF6574A7)
)

@Composable
fun ScheduleReminderUI(vm: ScheduleViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Task?>(null) }
    var completedOnly by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Task?>(null) }
    var notificationsAllowed by remember { mutableStateOf(ReminderScheduler.notificationsAllowed(context)) }
    var exactAllowed by remember { mutableStateOf(ReminderScheduler.exactAllowed(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationsAllowed = ReminderScheduler.notificationsAllowed(context)
        vm.refresh()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = ReminderScheduler.notificationsAllowed(context)
                exactAllowed = ReminderScheduler.exactAllowed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    MaterialTheme(colorScheme = accentPalette) {
        if (editing) {
            EditTaskScreen(
                task = selected,
                onBack = { editing = false },
                onSave = { title, notes, local, zone, options ->
                    vm.save(selected?.id, title, notes, local, zone, options) { editing = false }
                }
            )
        } else {
            Scaffold(
                containerColor = Calm,
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { selected = null; editing = true },
                        containerColor = Indigo, contentColor = Color.White,
                        text = { Text("Thêm công việc", fontWeight = FontWeight.SemiBold) },
                        icon = { Text("+", fontSize = 25.sp) }
                    )
                }
            ) { contentPadding ->
                val now = System.currentTimeMillis() / 1000L
                val displayed = state.tasks.filter { it.isCompleted == completedOnly }
                val remaining = state.tasks.count { !it.isCompleted && it.dueAtUtc >= now }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Text("SCHEDULE REMINDER", color = Indigo, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("Lịch của bạn ✨", style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold, color = Ink)
                        Text("Sắp xếp mỗi ngày thật nhẹ nhàng", color = SoftInk,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Indigo),
                            shape = RoundedCornerShape(26.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                Modifier.background(
                                    Brush.horizontalGradient(listOf(Indigo, Color(0xFF817CF2)))
                                ).padding(23.dp)
                            ) {
                                Text("HÔM NAY VÀ SẮP TỚI", color = Color(0xFFE5E7FF), fontSize = 11.sp,
                                    letterSpacing = 1.4.sp)
                                Spacer(Modifier.height(6.dp))
                                Text("$remaining công việc đang chờ", color = Color.White,
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(2.dp))
                                Text("Nhắc đúng thời điểm • Lưu trên thiết bị", color = Color(0xFFECEBFF),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (!notificationsAllowed) {
                        item {
                            PermissionStrip(
                                message = "Cho phép thông báo để không bỏ lỡ công việc",
                                button = "Bật thông báo",
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        })
                                    }
                                }
                            )
                        }
                    }
                    if (!exactAllowed) {
                        item {
                            PermissionStrip(
                                message = "Bật Báo thức & lời nhắc để nhận thông báo đúng giờ",
                                button = "Cấp quyền",
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        try {
                                            context.startActivity(Intent(
                                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                                Uri.parse("package:${context.packageName}")
                                            ))
                                        } catch (_: Exception) {
                                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:${context.packageName}")))
                                        }
                                    }
                                }
                            )
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(
                                selected = !completedOnly, onClick = { completedOnly = false },
                                label = { Text("Sắp tới") }
                            )
                            FilterChip(
                                selected = completedOnly, onClick = { completedOnly = true },
                                label = { Text("Đã xong") }
                            )
                        }
                    }
                    if (state.loading) {
                        item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Indigo)
                        } }
                    } else if (displayed.isEmpty()) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(if (completedOnly) "✓" else "📅", fontSize = 43.sp)
                                    Spacer(Modifier.height(9.dp))
                                    Text(if (completedOnly) "Chưa có việc đã xong" else "Chưa có công việc nào",
                                        fontWeight = FontWeight.Bold)
                                    Text("Nhấn Thêm công việc để bắt đầu", color = SoftInk,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    } else {
                        items(displayed, key = { it.id }) { task ->
                            TaskCard(
                                task = task,
                                onToggle = { vm.toggle(task.id, !task.isCompleted) },
                                onEdit = { selected = task; editing = true },
                                onDelete = { deleting = task }
                            )
                        }
                    }
                }
            }
        }
        if (state.error != null) {
            AlertDialog(
                onDismissRequest = vm::clearError,
                title = { Text("Có lỗi xảy ra") },
                text = { Text(state.error.orEmpty()) },
                confirmButton = { TextButton(onClick = vm::clearError) { Text("Đóng") } }
            )
        }
        deleting?.let { task ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Xóa công việc?") },
                text = { Text("Bạn có muốn xóa ‘${task.title}’? Lịch nhắc sẽ được hủy.") },
                confirmButton = {
                    TextButton(onClick = { vm.delete(task.id); deleting = null }) { Text("Xóa") }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Hủy") } }
            )
        }
    }
}

@Composable
private fun PermissionStrip(message: String, button: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6DB))
    ) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = Color(0xFF796326), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick) { Text(button, color = Color(0xFF765C0A)) }
        }
    }
}

private val displayDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy '•' HH:mm")
private val isoFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

@Composable
private fun TaskCard(task: Task, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val dueLocal = remember(task.dueAtUtc) {
        LocalDateTime.ofInstant(Instant.ofEpochSecond(task.dueAtUtc), ZoneId.systemDefault())
    }
    val overdue = !task.isCompleted && task.dueAtUtc < System.currentTimeMillis() / 1000L
    Card(shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = task.isCompleted, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f).padding(top = 5.dp)) {
                Text(task.title, color = Ink, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (task.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(task.notes, color = SoftInk, maxLines = 3,
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text("◷  ${dueLocal.format(displayDateFormat)}${if (overdue) "  •  Quá hạn" else ""}",
                    color = if (overdue) Color(0xFFBE4B49) else Indigo,
                    style = MaterialTheme.typography.labelMedium)
                if (task.repeatMode != "once") {
                    val label = if (task.repeatMode == "interval") "Cách ${task.intervalMinutes} phút" else "Khung giờ hằng ngày"
                    Text("↻  $label · ${task.durationDays} ngày · ${task.reminderCount} lần nhắc",
                        color = SoftInk, style = MaterialTheme.typography.bodySmall)
                }
                if (task.soundName.isNotBlank()) {
                    Text("♫  ${task.soundName}", color = SoftInk, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Sửa") }
                    TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("Xóa", color = Color(0xFFBF455A))
                    }
                }
            }
        }
    }
}

@Composable
private fun EditTaskScreen(
    task: Task?, onBack: () -> Unit,
    onSave: (title: String, notes: String, dueLocal: String, zone: String, options: TaskOptions) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember(task?.id) { mutableStateOf(task?.title ?: "") }
    var notes by remember(task?.id) { mutableStateOf(task?.notes ?: "") }
    var dateTime by remember(task?.id) {
        mutableStateOf(task?.let {
            LocalDateTime.ofInstant(Instant.ofEpochSecond(it.dueAtUtc), ZoneId.systemDefault())
        } ?: LocalDateTime.now().plusHours(1).withSecond(0).withNano(0))
    }
    var mode by remember(task?.id) { mutableStateOf(task?.repeatMode ?: "once") }
    var days by remember(task?.id) { mutableIntStateOf(task?.durationDays?.toInt()?.coerceIn(1, 7) ?: 7) }
    var interval by remember(task?.id) {
        mutableIntStateOf(task?.intervalMinutes?.toInt()?.takeIf { it in listOf(30, 60, 120, 180, 240, 360, 720, 1440) } ?: 240)
    }
    var slots by remember(task?.id) {
        mutableStateOf(task?.dailyTimesCsv?.split(',')?.filter { it.isNotBlank() } ?: listOf("08:00", "12:00", "20:00"))
    }
    var soundId by remember(task?.id) { mutableStateOf(task?.soundId ?: "") }
    var soundName by remember(task?.id) { mutableStateOf(task?.soundName ?: "") }
    var audioError by remember(task?.id) { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                try {
                    val picked = withContext(Dispatchers.IO) { SoundStore.importSound(context, uri) }
                    soundId = picked.id
                    soundName = picked.displayName
                    audioError = null
                } catch (e: Exception) {
                    audioError = e.message ?: "Không đọc được âm thanh đã chọn"
                } finally { importing = false }
            }
        }
    }
    val canSave = title.isNotBlank() && !importing && (mode != "slots" || slots.isNotEmpty())
    Scaffold(
        containerColor = Calm,
        bottomBar = {
            Surface(color = Calm) {
                Button(
                    onClick = {
                        onSave(title.trim(), notes, dateTime.format(isoFormat), ZoneId.systemDefault().id,
                            TaskOptions(
                                soundId = soundId,
                                soundName = soundName,
                                repeatMode = mode,
                                durationDays = days.toUInt(),
                                intervalMinutes = if (mode == "interval") interval.toUInt() else 0u,
                                dailyTimesCsv = if (mode == "slots") slots.sorted().joinToString(",") else ""
                            )
                        )
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (task == null) "Lưu lịch nhắc" else "Cập nhật lịch nhắc", fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹  Quay lại") }
            Text(if (task == null) "Thêm công việc" else "Chỉnh sửa công việc",
                fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Ink)
            OutlinedTextField(
                value = title, onValueChange = { if (it.length <= 180) title = it },
                label = { Text("Tên công việc") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
            )
            OutlinedTextField(
                value = notes, onValueChange = { if (it.length <= 4000) notes = it },
                label = { Text("Ghi chú") }, minLines = 2, maxLines = 4,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
            )
            HorizontalDivider(color = Color(0xFFE8E9F4))
            Text("Ngày giờ bắt đầu", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    DatePickerDialog(context, { _, year, month, day ->
                        dateTime = dateTime.withYear(year).withMonth(month + 1).withDayOfMonth(day)
                    }, dateTime.year, dateTime.monthValue - 1, dateTime.dayOfMonth).show()
                }, modifier = Modifier.weight(1f)) {
                    Text(dateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                }
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute ->
                        dateTime = dateTime.withHour(hour).withMinute(minute).withSecond(0)
                    }, dateTime.hour, dateTime.minute, true).show()
                }, modifier = Modifier.weight(1f)) {
                    Text(dateTime.format(DateTimeFormatter.ofPattern("HH:mm")))
                }
            }
            Text("Múi giờ: ${ZoneId.systemDefault().id}. Lời nhắc cố định theo UTC sau khi lưu.",
                style = MaterialTheme.typography.bodySmall, color = SoftInk)

            HorizontalDivider(color = Color(0xFFE8E9F4))
            Text("Lặp thông báo trong tối đa 7 ngày", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text("Chọn cách nhắc", color = SoftInk, style = MaterialTheme.typography.bodySmall)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(
                    "once" to "Chỉ nhắc 1 lần",
                    "slots" to "Theo khung giờ mỗi ngày",
                    "interval" to "Theo khoảng thời gian"
                ).forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = mode == value, onClick = { mode = value })
                        Text(label, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (mode != "once") {
                Text("Thời gian áp dụng", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 7).forEach { d ->
                        FilterChip(selected = days == d, onClick = { days = d },
                            label = { Text("$d ngày") })
                    }
                }
                if (mode == "slots") {
                    Text("Các giờ nhắc trong mỗi ngày (tối đa 12)", fontWeight = FontWeight.SemiBold)
                    Text("Ví dụ: 08:00, 12:00 và 20:00. Có thể chọn nhiều giờ.",
                        style = MaterialTheme.typography.bodySmall, color = SoftInk)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        slots.sorted().forEach { hour ->
                            InputChip(selected = true,
                                onClick = { slots = slots.filterNot { it == hour } },
                                label = { Text("$hour ×") })
                        }
                    }
                    OutlinedButton(onClick = {
                        TimePickerDialog(context, { _, h, m ->
                            val time = "%02d:%02d".format(h, m)
                            if (time !in slots && slots.size < 12) slots = slots + time
                        }, 9, 0, true).show()
                    }, enabled = slots.size < 12) { Text("+ Thêm khung giờ") }
                    if (slots.isEmpty()) Text("Chọn ít nhất một giờ nhắc", color = Color(0xFFBA3A4A))
                } else if (mode == "interval") {
                    Text("Khoảng cách giữa các lần nhắc", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        listOf(30, 60, 120, 180, 240, 360, 720, 1440).forEach { minutes ->
                            val label = if (minutes < 60) "$minutes phút" else "${minutes / 60} giờ"
                            FilterChip(selected = interval == minutes,
                                onClick = { interval = minutes }, label = { Text(label) })
                        }
                    }
                    Text("Ví dụ: 4 giờ/lần trong 7 ngày = 42 lần nhắc.",
                        style = MaterialTheme.typography.bodySmall, color = SoftInk)
                }
            }
            HorizontalDivider(color = Color(0xFFE8E9F4))
            Text("Âm thanh thông báo", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(if (soundName.isBlank()) "Đang dùng âm thanh mặc định" else "Đã chọn: $soundName",
                style = MaterialTheme.typography.bodyMedium, color = SoftInk)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !importing) {
                    Text(if (importing) "Đang nhập…" else "Chọn âm thanh từ máy")
                }
                if (soundId.isNotBlank()) TextButton(onClick = { soundId = ""; soundName = "" }) {
                    Text("Mặc định")
                }
            }
            Text("Hỗ trợ MP3, OGG, WAV, M4A, AAC dưới 8 MB. Mỗi công việc có thể chọn chuông riêng.",
                style = MaterialTheme.typography.bodySmall, color = SoftInk)
            if (audioError != null) Text(audioError.orEmpty(), color = Color(0xFFBA3A4A))
            Spacer(Modifier.height(24.dp))
        }
    }
}