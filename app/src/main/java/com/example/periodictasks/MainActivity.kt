package com.example.periodictasks

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

enum class Recurrence(val title: String, val shortName: String) {
    DAILY("Каждый день", "Ежедневно"),
    WEEKLY("Каждую неделю", "Еженедельно"),
    MONTHLY("Каждый месяц", "Ежемесячно")
}

data class PeriodicTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val recurrence: Recurrence,
    val nextTriggerTimeMillis: Long,
    val isCompletedForCurrentCycle: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("description", description)
        put("recurrence", recurrence.name)
        put("nextTriggerTimeMillis", nextTriggerTimeMillis)
        put("isCompletedForCurrentCycle", isCompletedForCurrentCycle)
    }

    companion object {
        fun fromJson(json: JSONObject): PeriodicTask {
            return PeriodicTask(
                id = json.getString("id"),
                title = json.getString("title"),
                description = json.optString("description", ""),
                recurrence = Recurrence.valueOf(json.getString("recurrence")),
                nextTriggerTimeMillis = json.getLong("nextTriggerTimeMillis"),
                isCompletedForCurrentCycle = json.optBoolean("isCompletedForCurrentCycle", false)
            )
        }
    }
}

class TaskRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("periodic_tasks_prefs", Context.MODE_PRIVATE)

    fun getTasks(): List<PeriodicTask> {
        val raw = prefs.getString("tasks_json", "[]") ?: "[]"
        val array = JSONArray(raw)
        val list = mutableListOf<PeriodicTask>()
        for (i in 0 until array.length()) {
            list.add(PeriodicTask.fromJson(array.getJSONObject(i)))
        }
        return list.sortedBy { it.nextTriggerTimeMillis }
    }

    fun saveTasks(tasks: List<PeriodicTask>) {
        val array = JSONArray()
        tasks.forEach { array.put(it.toJson()) }
        prefs.edit().putString("tasks_json", array.toString()).apply()
    }

    fun saveTask(task: PeriodicTask) {
        val current = getTasks().toMutableList()
        val index = current.indexOfFirst { it.id == task.id }
        if (index != -1) {
            current[index] = task
        } else {
            current.add(task)
        }
        saveTasks(current)
    }

    fun deleteTask(taskId: String) {
        val current = getTasks().filter { it.id != taskId }
        saveTasks(current)
    }

    fun getTaskById(taskId: String): PeriodicTask? {
        return getTasks().find { it.id == taskId }
    }
}

object NotificationConstants {
    const val CHANNEL_ID = "periodic_task_reminders"
    const val CHANNEL_NAME = "Периодические напоминания"
    const val CHANNEL_DESC = "Уведомления о регулярных задачах (счётчики, оплаты, отчёты)"

    const val ACTION_SNOOZE_1_HOUR = "com.example.periodictasks.ACTION_SNOOZE_1_HOUR"
    const val ACTION_SNOOZE_1_DAY = "com.example.periodictasks.ACTION_SNOOZE_1_DAY"
    const val ACTION_MARK_DONE = "com.example.periodictasks.ACTION_MARK_DONE"
    const val ACTION_TRIGGER = "com.example.periodictasks.ACTION_TRIGGER_TASK"

    const val EXTRA_TASK_ID = "extra_task_id"
}

class TaskAlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleTask(task: PeriodicTask) {
        val intent = Intent(context, TaskNotificationReceiver::class.java).apply {
            action = NotificationConstants.ACTION_TRIGGER
            putExtra(NotificationConstants.EXTRA_TASK_ID, task.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    task.nextTriggerTimeMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    task.nextTriggerTimeMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                task.nextTriggerTimeMillis,
                pendingIntent
            )
        }
    }

    fun cancelTask(task: PeriodicTask) {
        val intent = Intent(context, TaskNotificationReceiver::class.java).apply {
            action = NotificationConstants.ACTION_TRIGGER
            putExtra(NotificationConstants.EXTRA_TASK_ID, task.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
    }
}

class TaskNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = TaskRepository(context)
        val scheduler = TaskAlarmScheduler(context)
        val taskId = intent.getStringExtra(NotificationConstants.EXTRA_TASK_ID) ?: return
        val task = repo.getTaskById(taskId) ?: return
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (intent.action) {
            NotificationConstants.ACTION_TRIGGER -> {
                showNotification(context, notificationManager, task)
            }
            NotificationConstants.ACTION_SNOOZE_1_HOUR -> {
                notificationManager.cancel(task.id.hashCode())
                val newTime = System.currentTimeMillis() + (60 * 60 * 1000L)
                val updated = task.copy(nextTriggerTimeMillis = newTime)
                repo.saveTask(updated)
                scheduler.scheduleTask(updated)
            }
            NotificationConstants.ACTION_SNOOZE_1_DAY -> {
                notificationManager.cancel(task.id.hashCode())
                val newTime = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
                val updated = task.copy(nextTriggerTimeMillis = newTime)
                repo.saveTask(updated)
                scheduler.scheduleTask(updated)
            }
            NotificationConstants.ACTION_MARK_DONE -> {
                notificationManager.cancel(task.id.hashCode())
                val nextCalendar = Calendar.getInstance().apply {
                    timeInMillis = task.nextTriggerTimeMillis
                }
                when (task.recurrence) {
                    Recurrence.DAILY -> nextCalendar.add(Calendar.DAY_OF_YEAR, 1)
                    Recurrence.WEEKLY -> nextCalendar.add(Calendar.WEEK_OF_YEAR, 1)
                    Recurrence.MONTHLY -> nextCalendar.add(Calendar.MONTH, 1)
                }
                val updated = task.copy(
                    nextTriggerTimeMillis = nextCalendar.timeInMillis,
                    isCompletedForCurrentCycle = false
                )
                repo.saveTask(updated)
                scheduler.scheduleTask(updated)
            }
        }
    }

    private fun showNotification(
        context: Context,
        notificationManager: NotificationManager,
        task: PeriodicTask
    ) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            task.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snooze1hIntent = Intent(context, TaskNotificationReceiver::class.java).apply {
            action = NotificationConstants.ACTION_SNOOZE_1_HOUR
            putExtra(NotificationConstants.EXTRA_TASK_ID, task.id)
        }
        val snooze1hPending = PendingIntent.getBroadcast(
            context,
            (task.id + "_1h").hashCode(),
            snooze1hIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snooze1dIntent = Intent(context, TaskNotificationReceiver::class.java).apply {
            action = NotificationConstants.ACTION_SNOOZE_1_DAY
            putExtra(NotificationConstants.EXTRA_TASK_ID, task.id)
        }
        val snooze1dPending = PendingIntent.getBroadcast(
            context,
            (task.id + "_1d").hashCode(),
            snooze1dIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val doneIntent = Intent(context, TaskNotificationReceiver::class.java).apply {
            action = NotificationConstants.ACTION_MARK_DONE
            putExtra(NotificationConstants.EXTRA_TASK_ID, task.id)
        }
        val donePending = PendingIntent.getBroadcast(
            context,
            (task.id + "_done").hashCode(),
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, NotificationConstants.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(task.title)
            .setContentText(
                if (task.description.isNotBlank()) task.description
                else "Напоминание: ${task.recurrence.title.lowercase()}"
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .addAction(0, "⏰ Отложить 1 ч", snooze1hPending)
            .addAction(0, "📅 Отложить 1 д", snooze1dPending)
            .addAction(0, "✅ Сделано", donePending)

        notificationManager.notify(task.id.hashCode(), builder.build())
    }
}

private val ExpressivePrimary = Color(0xFF6750A4)
private val ExpressiveOnPrimary = Color(0xFFFFFFFF)
private val ExpressivePrimaryContainer = Color(0xFFEADDFF)
private val ExpressiveOnPrimaryContainer = Color(0xFF21005D)
private val ExpressiveSecondary = Color(0xFF625B71)
private val ExpressiveSecondaryContainer = Color(0xFFE8DEF8)
private val ExpressiveTertiary = Color(0xFF7D5260)
private val ExpressiveTertiaryContainer = Color(0xFFFFD8E4)
private val ExpressiveBackground = Color(0xFFFDF7FF)
private val ExpressiveSurface = Color(0xFFF7F2FA)
private val ExpressiveSurfaceVariant = Color(0xFFE7E0EC)
private val ExpressiveOutline = Color(0xFF79747E)

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp)
)

@Composable
fun Material3ExpressiveTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = ExpressivePrimary,
        onPrimary = ExpressiveOnPrimary,
        primaryContainer = ExpressivePrimaryContainer,
        onPrimaryContainer = ExpressiveOnPrimaryContainer,
        secondary = ExpressiveSecondary,
        secondaryContainer = ExpressiveSecondaryContainer,
        tertiary = ExpressiveTertiary,
        tertiaryContainer = ExpressiveTertiaryContainer,
        background = ExpressiveBackground,
        surface = ExpressiveSurface,
        surfaceVariant = ExpressiveSurfaceVariant,
        outline = ExpressiveOutline
    )

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ExpressiveShapes,
        content = content
    )
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()

        setContent {
            Material3ExpressiveTheme {
                MainAppScreen()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NotificationConstants.CHANNEL_ID,
                NotificationConstants.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = NotificationConstants.CHANNEL_DESC
                enableVibration(true)
            }
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen() {
    val context = LocalContext.current
    val repository = remember { TaskRepository(context) }
    val scheduler = remember { TaskAlarmScheduler(context) }

    var tasks by remember { mutableStateOf(repository.getTasks()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var filterRecurrence by remember { mutableStateOf<Recurrence?>(null) }
    var taskToSnoozeModal by remember { mutableStateOf<PeriodicTask?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun refreshTasks() {
        tasks = repository.getTasks()
    }

    val filteredTasks = remember(tasks, filterRecurrence) {
        if (filterRecurrence == null) tasks
        else tasks.filter { it.recurrence == filterRecurrence }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = {
                    Column {
                        Text(
                            text = "Регулярные дела",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            )
                        )
                        Text(
                            text = "Напоминания с повтором и отсрочкой",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (tasks.isNotEmpty()) {
                                val dummyIntent = Intent(context, TaskNotificationReceiver::class.java).apply {
                                    action = NotificationConstants.ACTION_TRIGGER
                                    putExtra(NotificationConstants.EXTRA_TASK_ID, tasks.first().id)
                                }
                                context.sendBroadcast(dummyIntent)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsActive,
                            contentDescription = "Тест уведомления",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(26.dp),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить")
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "Новое дело",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            FilterChipsRow(
                selected = filterRecurrence,
                onSelect = { filterRecurrence = it }
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredTasks.isEmpty()) {
                EmptyStateView(hasFilters = filterRecurrence != null) {
                    showAddDialog = true
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(filteredTasks, key = { it.id }) { task ->
                        ExpressiveTaskCard(
                            task = task,
                            onCompleteCycle = {
                                val nextCal = Calendar.getInstance().apply {
                                    timeInMillis = task.nextTriggerTimeMillis
                                }
                                when (task.recurrence) {
                                    Recurrence.DAILY -> nextCal.add(Calendar.DAY_OF_YEAR, 1)
                                    Recurrence.WEEKLY -> nextCal.add(Calendar.WEEK_OF_YEAR, 1)
                                    Recurrence.MONTHLY -> nextCal.add(Calendar.MONTH, 1)
                                }
                                val updated = task.copy(nextTriggerTimeMillis = nextCal.timeInMillis)
                                repository.saveTask(updated)
                                scheduler.scheduleTask(updated)
                                refreshTasks()
                            },
                            onSnoozeClick = {
                                taskToSnoozeModal = task
                            },
                            onDelete = {
                                scheduler.cancelTask(task)
                                repository.deleteTask(task.id)
                                refreshTasks()
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddTaskBottomSheet(
            onDismiss = { showAddDialog = false },
            onSave = { newTask ->
                repository.saveTask(newTask)
                scheduler.scheduleTask(newTask)
                refreshTasks()
                showAddDialog = false
            }
        )
    }

    if (taskToSnoozeModal != null) {
        val task = taskToSnoozeModal!!
        SnoozeChoiceSheet(
            taskTitle = task.title,
            onDismiss = { taskToSnoozeModal = null },
            onSnooze1Hour = {
                val newTime = System.currentTimeMillis() + (60 * 60 * 1000L)
                val updated = task.copy(nextTriggerTimeMillis = newTime)
                repository.saveTask(updated)
                scheduler.scheduleTask(updated)
                refreshTasks()
                taskToSnoozeModal = null
            },
            onSnooze1Day = {
                val newTime = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
                val updated = task.copy(nextTriggerTimeMillis = newTime)
                repository.saveTask(updated)
                scheduler.scheduleTask(updated)
                refreshTasks()
                taskToSnoozeModal = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterChipsRow(
    selected: Recurrence?,
    onSelect: (Recurrence?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("Все") },
            shape = RoundedCornerShape(16.dp),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
            )
        )
        Recurrence.values().forEach { rec ->
            FilterChip(
                selected = selected == rec,
                onClick = { onSelect(if (selected == rec) null else rec) },
                label = { Text(rec.shortName) },
                shape = RoundedCornerShape(16.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

@Composable
fun ExpressiveTaskCard(
    task: PeriodicTask,
    onCompleteCycle: () -> Unit,
    onSnoozeClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMMM, HH:mm", Locale("ru")) }
    val formattedDate = remember(task.nextTriggerTimeMillis) {
        dateFormat.format(Date(task.nextTriggerTimeMillis))
    }

    val isOverdue = remember(task.nextTriggerTimeMillis) {
        task.nextTriggerTimeMillis <= System.currentTimeMillis()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        border = if (isOverdue) {
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
        } else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when (task.recurrence) {
                        Recurrence.DAILY -> MaterialTheme.colorScheme.primaryContainer
                        Recurrence.WEEKLY -> MaterialTheme.colorScheme.secondaryContainer
                        Recurrence.MONTHLY -> MaterialTheme.colorScheme.tertiaryContainer
                    }
                ) {
                    Text(
                        text = task.recurrence.title,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = when (task.recurrence) {
                                Recurrence.DAILY -> MaterialTheme.colorScheme.onPrimaryContainer
                                Recurrence.WEEKLY -> MaterialTheme.colorScheme.onSecondaryContainer
                                Recurrence.MONTHLY -> MaterialTheme.colorScheme.onTertiaryContainer
                            }
                        )
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = task.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 19.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (task.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (isOverdue) "Срок наступил! ($formattedDate)" else "Срок: $formattedDate",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (isOverdue) FontWeight.Bold else FontWeight.Medium,
                        color = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onSnoozeClick,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Snooze,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Отложить", fontSize = 13.sp)
                }

                Button(
                    onClick = onCompleteCycle,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Выполнено", fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskBottomSheet(
    onDismiss: () -> Unit,
    onSave: (PeriodicTask) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedRecurrence by remember { mutableStateOf(Recurrence.MONTHLY) }

    val calendar = remember { Calendar.getInstance().apply { add(Calendar.MINUTE, 5) } }
    var selectedDateMillis by remember { mutableStateOf(calendar.timeInMillis) }
    val displayFormat = remember { SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "Новое регулярное задание",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = Modifier.height(18.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Название (например: Передать счётчики)") },
                placeholder = { Text("Показания воды и электричества") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Примечание (необязательно)") },
                placeholder = { Text("Лицевой счет: 1234567, сайт mos.ru") },
                maxLines = 3,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Периодичность повторения",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Recurrence.values().forEach { rec ->
                    val isSelected = selectedRecurrence == rec
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedRecurrence = rec },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = rec.shortName,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Первое срабатывание",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = displayFormat.format(Date(selectedDateMillis)),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    TextButton(
                        onClick = {
                            val cal = Calendar.getInstance().apply {
                                add(Calendar.DAY_OF_YEAR, 1)
                                set(Calendar.HOUR_OF_DAY, 9)
                                set(Calendar.MINUTE, 0)
                            }
                            selectedDateMillis = cal.timeInMillis
                        }
                    ) {
                        Text("Завтра в 9:00")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val task = PeriodicTask(
                            title = title.trim(),
                            description = description.trim(),
                            recurrence = selectedRecurrence,
                            nextTriggerTimeMillis = selectedDateMillis
                        )
                        onSave(task)
                    }
                },
                enabled = title.isNotBlank(),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = "Сохранить и запустить",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeChoiceSheet(
    taskTitle: String,
    onDismiss: () -> Unit,
    onSnooze1Hour: () -> Unit,
    onSnooze1Day: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "Отложить напоминание",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "«$taskTitle»",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSnooze1Hour() },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.HourglassTop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Отложить на 1 час",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Напомнить чуть позже сегодня",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSnooze1Day() },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarToday,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Отложить на 1 день",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Перенести на то же время завтра",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun EmptyStateView(
    hasFilters: Boolean,
    onAddClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.EventRepeat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = if (hasFilters) "Нет дел в этой категории" else "Пока нет регулярных задач",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Добавьте передачу показаний счётчиков, оплату интернета или полив цветов",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 24.dp),
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onAddClick,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Создать первое задание")
            }
        }
    }
}
