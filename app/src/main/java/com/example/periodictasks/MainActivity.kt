package com.example.periodictasks

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

// --- МОДЕЛИ ДАННЫХ ---
enum class RecurrenceType(val title: String) {
    DAILY("Ежедневно"),
    WEEKLY("Еженедельно"),
    MONTHLY("Число месяца"),
    YEARLY("Ежегодно"),
    EVENT("Важное событие")
}

data class PeriodicTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val type: RecurrenceType,
    val dayOfWeek: Int = 1, // 1=Пн, 7=Вс
    val dayOfMonth: Int = 1, // 1-31
    val monthOfYear: Int = 1, // 1-12
    val hour: Int = 9,
    val minute: Int = 0,
    val notifyMonthBefore: Boolean = false,
    val notifyTwoWeeksBefore: Boolean = false,
    val notifyWeekBefore: Boolean = false
)

// --- ХРАНИЛИЩЕ (ViewModel) ---
class TaskViewModel : ViewModel() {
    private val _tasks = MutableStateFlow<List<PeriodicTask>>(emptyList())
    val tasks: StateFlow<List<PeriodicTask>> = _tasks.asStateFlow()

    fun addTask(task: PeriodicTask, context: Context) {
        _tasks.value = _tasks.value + task
        scheduleAlarms(context, task)
    }

    fun completeTask(taskId: String, context: Context) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        // Перепланируем на следующий период
        scheduleAlarms(context, task)
    }
}

// --- ЛОГИКА БУДИЛЬНИКОВ ---
fun scheduleAlarms(context: Context, task: Task) {
    val am = context.getSystemService(AlarmManager::class.java)
    val intent = Intent(context, AlarmReceiver::class.java).apply {
        putExtra("TASK_ID", task.id)
        putExtra("TASK_TITLE", task.title)
        putExtra("TASK_DESC", task.description)
    }

    val now = LocalDateTime.now()
    var target = now.withHour(task.hour).withMinute(task.minute).withSecond(0).withNano(0)

    when (task.type) {
        RecurrenceType.DAILY -> {
            if (target.isBefore(now)) target = target.plusDays(1)
        }
        RecurrenceType.WEEKLY -> {
            while (target.dayOfWeek.value != task.dayOfWeek || target.isBefore(now)) {
                target = target.plusDays(1)
            }
        }
        RecurrenceType.MONTHLY -> {
            val maxDays = target.month.length(target.toLocalDate().isLeapYear)
            target = target.withDayOfMonth(task.dayOfMonth.coerceAtMost(maxDays))
            if (target.isBefore(now)) {
                target = target.plusMonths(1)
                target = target.withDayOfMonth(task.dayOfMonth.coerceAtMost(target.month.length(target.toLocalDate().isLeapYear)))
            }
        }
        RecurrenceType.YEARLY, RecurrenceType.EVENT -> {
            val maxDays = java.time.Month.of(task.monthOfYear).length(target.toLocalDate().isLeapYear)
            target = target.withMonth(task.monthOfYear).withDayOfMonth(task.dayOfMonth.coerceAtMost(maxDays))
            if (target.isBefore(now)) {
                target = target.plusYears(1)
                val newMax = java.time.Month.of(task.monthOfYear).length(target.toLocalDate().isLeapYear)
                target = target.withDayOfMonth(task.dayOfMonth.coerceAtMost(newMax))
            }
        }
    }

    // 1. Основной будильник (В саму дату)
    val piMain = PendingIntent.getBroadcast(
        context, task.id.hashCode(),
        intent.apply { putExtra("IS_PRE", false) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target.atZone(ZoneId.systemDefault()).toEpochSecond() * 1000, piMain)

    // 2. Заблаговременные будильники (Только для Событий)
    if (task.type == RecurrenceType.EVENT) {
        val preNotifs = listOf(
            Triple(task.notifyMonthBefore, target.minusMonths(1), "Скоро событие (через месяц): "),
            Triple(task.notifyTwoWeeksBefore, target.minusWeeks(2), "Скоро событие (через 2 недели): "),
            Triple(task.notifyWeekBefore, target.minusWeeks(1), "Скоро событие (через неделю): ")
        )
        
        preNotifs.forEachIndexed { index, (isEnabled, preTarget, prefix) ->
            val piPre = PendingIntent.getBroadcast(
                context, task.id.hashCode() + index + 1,
                intent.apply { 
                    putExtra("IS_PRE", true)
                    putExtra("PRE_PREFIX", prefix)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            if (isEnabled && preTarget.isAfter(now)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTarget.atZone(ZoneId.systemDefault()).toEpochSecond() * 1000, piPre)
            } else {
                am.cancel(piPre)
            }
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("TASK_ID") ?: return
        val title = intent.getStringExtra("TASK_TITLE") ?: "Напоминание"
        val desc = intent.getStringExtra("TASK_DESC") ?: ""
        val isPre = intent.getBooleanExtra("IS_PRE", false)
        val prePrefix = intent.getStringExtra("PRE_PREFIX") ?: ""
        val action = intent.action

        val nm = NotificationManagerCompat.from(context)

        // Обработка кнопок из уведомления
        if (action != null) {
            when (action) {
                "DONE" -> {
                    nm.cancel(taskId.hashCode())
                    if (!isPre) {
                        // Если это основное событие, имитируем запуск перепланирования
                        val fakeTask = PeriodicTask(id = taskId, title = "", description = "", type = RecurrenceType.DAILY)
                        // В реальном приложении здесь нужен запрос к БД по taskId
                    }
                    return
                }
                "SNOOZE_1H" -> {
                    val am = context.getSystemService(AlarmManager::class.java)
                    val pi = PendingIntent.getBroadcast(context, taskId.hashCode() + 99, intent.apply { action = null }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3600_000L, pi)
                    nm.cancel(taskId.hashCode())
                    return
                }
            }
        }

        // Создание уведомления
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("tasks", "Задачи", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }

        val doneIntent = PendingIntent.getBroadcast(context, taskId.hashCode(), Intent(context, AlarmReceiver::class.java).apply { 
            this.action = "DONE"
            putExtra("TASK_ID", taskId)
            putExtra("IS_PRE", isPre)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val snoozeIntent = PendingIntent.getBroadcast(context, taskId.hashCode() + 100, Intent(context, AlarmReceiver::class.java).apply { 
            this.action = "SNOOZE_1H"
            putExtra("TASK_ID", taskId)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val displayTitle = if (isPre) "$prePrefix$title" else title

        val builder = NotificationCompat.Builder(context, "tasks")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(displayTitle)
            .setContentText(desc)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "✅ Сделано", doneIntent)
            .addAction(0, "⏰ Отложить 1ч", snoozeIntent)

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            nm.notify(taskId.hashCode(), builder.build())
        }
    }
}

// --- UI СЛОЙ ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(colorScheme = dynamicLightColorScheme(LocalContext.current)) {
                PeriodicTasksApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodicTasksApp(viewModel: TaskViewModel = viewModel()) {
    var showAddSheet by remember { mutableStateOf(false) }
    val tasks by viewModel.tasks.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Мои Задачи", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            LargeFloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(28.dp)
            ) {
                Icon(Icons.Filled.Add, "Добавить", modifier = Modifier.size(36.dp))
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tasks) { task ->
                TaskCard(task)
            }
        }

        if (showAddSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAddSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            ) {
                AddWizard(
                    onSave = { newTask ->
                        viewModel.addTask(newTask, context)
                        showAddSheet = false
                    },
                    onCancel = { showAddSheet = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWizard(onSave: (PeriodicTask) -> Unit, onCancel: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    
    // Состояния полей
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(RecurrenceType.DAILY) }
    var dayOfMonth by remember { mutableStateOf(1) }
    var selectedDateMillis by remember { mutableStateOf<Long?>(null) }
    
    val timePickerState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
    
    var notifyMonth by remember { mutableStateOf(false) }
    var notifyTwoWeeks by remember { mutableStateOf(false) }
    var notifyWeek by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).navigationBarsPadding()) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { slideInHorizontally(tween(300)) { it } togetherWith slideOutHorizontally(tween(300)) { -it } }
        ) { currentStep ->
            when (currentStep) {
                0 -> {
                    // ШАГ 1: Основное
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Создать напоминание", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                        OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Описание (опционально)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                        
                        Text("Повторять:", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RecurrenceType.values().forEach { recType ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                        .background(if (type == recType) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { type = recType }.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(recType.title, fontWeight = if (type == recType) FontWeight.Bold else FontWeight.Normal)
                                    Spacer(modifier = Modifier.weight(1f))
                                    if (type == recType) Icon(Icons.Default.Check, null)
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // ШАГ 2: Дата
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("Выберите дату", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        when (type) {
                            RecurrenceType.MONTHLY -> {
                                Text("Каждое число месяца:")
                                LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.height(250.dp)) {
                                    items((1..31).toList()) { day ->
                                        Box(
                                            modifier = Modifier.padding(4.dp).aspectRatio(1f).clip(CircleShape)
                                                .background(if (dayOfMonth == day) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                                .clickable { dayOfMonth = day },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(day.toString(), color = if (dayOfMonth == day) Color.White else MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                            }
                            RecurrenceType.YEARLY, RecurrenceType.EVENT -> {
                                val dateState = rememberDatePickerState()
                                selectedDateMillis = dateState.selectedDateMillis
                                DatePicker(state = dateState, title = null, headline = null, showModeToggle = false)
                            }
                            else -> Text("Для этого типа дата не требуется", modifier = Modifier.padding(32.dp))
                        }
                    }
                }
                2 -> {
                    // ШАГ 3: Время
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("Выберите время", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start).padding(bottom = 24.dp))
                        TimePicker(state = timePickerState)
                    }
                }
                3 -> {
                    // ШАГ 4: Заблаговременные уведомления (Только Событие)
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Заранее напомнить?", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = notifyMonth, onCheckedChange = { notifyMonth = it })
                            Text("За 1 месяц")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = notifyTwoWeeks, onCheckedChange = { notifyTwoWeeks = it })
                            Text("За 2 недели")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = notifyWeek, onCheckedChange = { notifyWeek = it })
                            Text("За 1 неделю")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { if (step > 0) step-- else onCancel() }) {
                Text(if (step == 0) "Отмена" else "Назад", fontSize = 16.sp)
            }
            
            val isLastStep = (type != RecurrenceType.EVENT && step == 2) || (type == RecurrenceType.EVENT && step == 3)
            
            Button(
                onClick = {
                    if (type == RecurrenceType.DAILY && step == 0) step = 2 // Пропускаем выбор даты для Ежедневного
                    else if (!isLastStep) step++
                    else {
                        // ФИНАЛ: Сохранение
                        var finalMonth = 1
                        var finalDay = 1
                        if (selectedDateMillis != null) {
                            val dt = Instant.ofEpochMilli(selectedDateMillis!!).atZone(ZoneId.of("UTC")).toLocalDate()
                            finalMonth = dt.monthValue
                            finalDay = dt.dayOfMonth
                        }
                        
                        onSave(
                            PeriodicTask(
                                title = title.ifEmpty { "Без названия" },
                                description = desc,
                                type = type,
                                dayOfMonth = if (type == RecurrenceType.MONTHLY) dayOfMonth else finalDay,
                                monthOfYear = finalMonth,
                                hour = timePickerState.hour,
                                minute = timePickerState.minute,
                                notifyMonthBefore = notifyMonth,
                                notifyTwoWeeksBefore = notifyTwoWeeks,
                                notifyWeekBefore = notifyWeek
                            )
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                enabled = title.isNotBlank() || step > 0
            ) {
                Text(if (isLastStep) "Сохранить" else "Далее", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
fun TaskCard(task: PeriodicTask) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (task.description.isNotBlank()) {
                    Text(task.description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${task.type.title} • ${String.format("%02d:%02d", task.hour, task.minute)}",
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
