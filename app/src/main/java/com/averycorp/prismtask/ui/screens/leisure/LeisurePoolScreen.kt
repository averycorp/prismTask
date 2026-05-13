package com.averycorp.prismtask.ui.screens.leisure

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import com.averycorp.prismtask.data.local.entity.LeisureSessionEntity
import com.averycorp.prismtask.data.preferences.LeisureBudgetPreferences
import com.averycorp.prismtask.domain.model.LeisureCategory
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeisurePoolScreen(
    navController: NavController,
    viewModel: LeisurePoolViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showAddSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LeisureActivityEntity?>(null) }
    var checkingOff by remember { mutableStateOf<LeisureActivityEntity?>(null) }
    var loggingCategory by remember { mutableStateOf<LeisureCategory?>(null) }
    var editingSession by remember { mutableStateOf<LeisureSessionEntity?>(null) }
    var deletingSession by remember { mutableStateOf<LeisureSessionEntity?>(null) }

    fun logCheckOff(activity: LeisureActivityEntity, minutes: Int) {
        viewModel.checkOffActivity(activity, minutes)
        coroutineScope.launch {
            snackbarHostState.showSnackbar("Logged $minutes min of ${activity.name}")
        }
    }

    fun logCategory(category: LeisureCategory, minutes: Int) {
        viewModel.logCategorySession(category, minutes)
        coroutineScope.launch {
            snackbarHostState.showSnackbar("Logged $minutes min of ${category.label}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Leisure Budget", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (navController.previousBackStackEntry != null) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Activity")
            }
        }
    ) { padding ->
        val sessionDays = remember(state.recentSessions) { groupSessionsByDay(state.recentSessions) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TodayProgressCard(
                    minutesLogged = state.minutesLoggedToday,
                    targetMinutes = state.targetMinutesToday
                )
            }
            item {
                Text(
                    "Log By Category",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(LeisureCategory.values().toList()) { category ->
                CategoryLogCard(
                    category = category,
                    activityCount = state.activitiesByCategory[category]?.size ?: 0,
                    onClick = { loggingCategory = category }
                )
            }
            item {
                Text(
                    "Log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (sessionDays.isEmpty()) {
                item { EmptyLogHint() }
            } else {
                items(sessionDays, key = { it.date.toString() }) { day ->
                    SessionDayCard(
                        day = day,
                        activitiesById = state.activitiesById,
                        onEditTime = { editingSession = it },
                        onDelete = { deletingSession = it }
                    )
                }
            }
            item { BudgetTargetCard(viewModel = viewModel, snapshot = state.settings) }
            item { RefreshLimitCard(viewModel = viewModel, snapshot = state.settings) }
            if (state.activities.isNotEmpty()) {
                item {
                    Text(
                        "Activity Pool",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(state.activities, key = { it.id }) { activity ->
                    ActivityRow(
                        activity = activity,
                        onCheckOff = {
                            val defaultDuration = activity.defaultDurationMinutes
                            if (defaultDuration != null && defaultDuration > 0) {
                                logCheckOff(activity, defaultDuration)
                            } else {
                                checkingOff = activity
                            }
                        },
                        onEdit = { editing = activity },
                        onToggleEnabled = { enabled ->
                            viewModel.setActivityEnabled(activity, enabled)
                        },
                        onDelete = { viewModel.deleteActivity(activity) }
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (showAddSheet) {
        AddActivityDialog(
            onDismiss = { showAddSheet = false },
            onConfirm = { name, category, duration ->
                viewModel.addActivity(name, category, duration)
                showAddSheet = false
            }
        )
    }

    editing?.let { activity ->
        EditActivityDialog(
            activity = activity,
            onDismiss = { editing = null },
            onConfirm = { name, category, duration ->
                viewModel.updateActivity(activity, name, category, duration)
                editing = null
            }
        )
    }

    checkingOff?.let { activity ->
        CheckOffDurationDialog(
            activity = activity,
            onDismiss = { checkingOff = null },
            onConfirm = { minutes ->
                logCheckOff(activity, minutes)
                checkingOff = null
            }
        )
    }

    loggingCategory?.let { category ->
        LogCategoryDialog(
            category = category,
            onDismiss = { loggingCategory = null },
            onConfirm = { minutes ->
                logCategory(category, minutes)
                loggingCategory = null
            }
        )
    }

    editingSession?.let { session ->
        EditSessionTimeDialog(
            session = session,
            onDismiss = { editingSession = null },
            onConfirm = { newLoggedAt ->
                viewModel.updateSessionTime(session.id, newLoggedAt)
                editingSession = null
            }
        )
    }

    deletingSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deletingSession = null },
            title = { Text("Delete Entry?") },
            text = {
                Text("This removes ${session.durationMinutes} min from your leisure log.")
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteSession(session.id)
                    deletingSession = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingSession = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TodayProgressCard(minutesLogged: Int, targetMinutes: Int) {
    val accent = Color(0xFF8B5CF6)
    val fraction = if (targetMinutes > 0) {
        (minutesLogged.toFloat() / targetMinutes).coerceIn(0f, 1f)
    } else {
        0f
    }
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Today",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (targetMinutes > 0) {
                        "$minutesLogged / $targetMinutes min"
                    } else {
                        "$minutesLogged min"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = accent
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (targetMinutes > 0) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = accent
                )
            } else {
                Text(
                    text = "Set a daily target below to track progress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CategoryLogCard(
    category: LeisureCategory,
    activityCount: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = category.emoji,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (activityCount) {
                        0 -> "Tap to log"
                        1 -> "1 activity • tap to log"
                        else -> "$activityCount activities • tap to log"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Log ${category.label}",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private data class SessionDay(
    val date: LocalDate,
    val sessions: List<LeisureSessionEntity>
) {
    val totalMinutes: Int get() = sessions.sumOf { it.durationMinutes }
}

private fun groupSessionsByDay(sessions: List<LeisureSessionEntity>): List<SessionDay> {
    val zone = ZoneId.systemDefault()
    return sessions
        .groupBy { Instant.ofEpochMilli(it.loggedAt).atZone(zone).toLocalDate() }
        .map { (date, list) -> SessionDay(date, list.sortedByDescending { it.loggedAt }) }
        .sortedByDescending { it.date }
}

@Composable
private fun SessionDayCard(
    day: SessionDay,
    activitiesById: Map<Long, LeisureActivityEntity>,
    onEditTime: (LeisureSessionEntity) -> Unit,
    onDelete: (LeisureSessionEntity) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault()) }
    val dayMillis = day.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val dateLabel = dateFormat.format(Date(dayMillis))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${day.totalMinutes} min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${day.sessions.size} logged",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        day.sessions.forEach { session ->
            Spacer(modifier = Modifier.height(8.dp))
            SessionRow(
                session = session,
                activity = session.activityId?.let { activitiesById[it] },
                onEditTime = { onEditTime(session) },
                onDelete = { onDelete(session) }
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: LeisureSessionEntity,
    activity: LeisureActivityEntity?,
    onEditTime: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = Color(0xFF8B5CF6)
    val category = LeisureCategory.fromStringOrNull(session.category)
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val timeLabel = timeFormat.format(Date(session.loggedAt))
    val label = activity?.name ?: category?.label ?: "Leisure"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f))
                .border(1.dp, accent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = category?.emoji ?: "•",
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$timeLabel • ${session.durationMinutes} min",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onEditTime) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = "Edit Time",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete Entry",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun EmptyLogHint() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Nothing Logged Yet",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Tap a category card above to log your first leisure session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BudgetTargetCard(
    viewModel: LeisurePoolViewModel,
    snapshot: com.averycorp.prismtask.data.preferences.LeisureBudgetSnapshot
) {
    var dailyTarget by remember(snapshot) { mutableIntStateOf(snapshot.dailyTargetMinutes) }
    var weekendOverrideEnabled by remember(snapshot) {
        mutableStateOf(snapshot.weekendTargetMinutes != null)
    }
    var weekendTarget by remember(snapshot) {
        mutableIntStateOf(snapshot.weekendTargetMinutes ?: snapshot.dailyTargetMinutes)
    }
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Daily Target", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$dailyTarget minutes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = dailyTarget.toFloat(),
                onValueChange = { dailyTarget = it.toInt() },
                onValueChangeFinished = { viewModel.setDailyTarget(dailyTarget) },
                valueRange = 0f..LeisureBudgetPreferences.MAX_TARGET.toFloat() / 4f,
                steps = 0
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Different Target On Weekends", modifier = Modifier.weight(1f))
                Switch(
                    checked = weekendOverrideEnabled,
                    onCheckedChange = { enabled ->
                        weekendOverrideEnabled = enabled
                        viewModel.setWeekendTarget(if (enabled) weekendTarget else null)
                    }
                )
            }
            if (weekendOverrideEnabled) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Weekend: $weekendTarget minutes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = weekendTarget.toFloat(),
                    onValueChange = { weekendTarget = it.toInt() },
                    onValueChangeFinished = { viewModel.setWeekendTarget(weekendTarget) },
                    valueRange = 0f..LeisureBudgetPreferences.MAX_TARGET.toFloat() / 4f,
                    steps = 0
                )
            }
        }
    }
}

@Composable
private fun RefreshLimitCard(
    viewModel: LeisurePoolViewModel,
    snapshot: com.averycorp.prismtask.data.preferences.LeisureBudgetSnapshot
) {
    var refresh by remember(snapshot) { mutableIntStateOf(snapshot.refreshLimit) }
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Refresh Limit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Refreshes per day to surface a new suggestion. The limit is the feature; it isn't a paywall.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("$refresh refreshes per day", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = refresh.toFloat(),
                onValueChange = { refresh = it.toInt() },
                onValueChangeFinished = { viewModel.setRefreshLimit(refresh) },
                valueRange = 0f..LeisureBudgetPreferences.MAX_REFRESH.toFloat(),
                steps = LeisureBudgetPreferences.MAX_REFRESH - 1
            )
        }
    }
}

@Composable
private fun ActivityRow(
    activity: LeisureActivityEntity,
    onCheckOff: () -> Unit,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(activity.name, style = MaterialTheme.typography.bodyLarge)
                val parsedCategory = LeisureCategory.fromStringOrNull(activity.category)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (parsedCategory != null) {
                        AssistChip(
                            onClick = onEdit,
                            label = {
                                Text("${parsedCategory.emoji} ${parsedCategory.label}")
                            },
                            colors = AssistChipDefaults.assistChipColors()
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    activity.defaultDurationMinutes?.let { duration ->
                        Text(
                            "$duration min default",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            IconButton(
                onClick = onCheckOff,
                enabled = activity.enabled
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Check Off As Done",
                    tint = if (activity.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit Activity")
            }
            Switch(
                checked = activity.enabled,
                onCheckedChange = onToggleEnabled
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Activity")
            }
        }
    }
}

@Composable
private fun AddActivityDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, LeisureCategory, Int?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(LeisureCategory.PHYSICAL) }
    var durationStr by remember { mutableStateOf("") }
    var categoryMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Activity") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box {
                    OutlinedButton(
                        onClick = { categoryMenuOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${category.emoji} ${category.label}")
                    }
                    DropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { categoryMenuOpen = false }
                    ) {
                        LeisureCategory.values().forEach { c ->
                            DropdownMenuItem(
                                text = { Text("${c.emoji} ${c.label}") },
                                onClick = {
                                    category = c
                                    categoryMenuOpen = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = durationStr,
                    onValueChange = { durationStr = it.filter(Char::isDigit) },
                    label = { Text("Default Duration (min, optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(name, category, durationStr.toIntOrNull())
                }
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EditActivityDialog(
    activity: LeisureActivityEntity,
    onDismiss: () -> Unit,
    onConfirm: (String, LeisureCategory, Int?) -> Unit
) {
    var name by remember(activity.id) { mutableStateOf(activity.name) }
    var category by remember(activity.id) {
        mutableStateOf(
            LeisureCategory.fromStringOrNull(activity.category) ?: LeisureCategory.PHYSICAL
        )
    }
    var durationStr by remember(activity.id) {
        mutableStateOf(activity.defaultDurationMinutes?.toString().orEmpty())
    }
    var categoryMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Activity") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box {
                    OutlinedButton(
                        onClick = { categoryMenuOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${category.emoji} ${category.label}")
                    }
                    DropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { categoryMenuOpen = false }
                    ) {
                        LeisureCategory.values().forEach { c ->
                            DropdownMenuItem(
                                text = { Text("${c.emoji} ${c.label}") },
                                onClick = {
                                    category = c
                                    categoryMenuOpen = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = durationStr,
                    onValueChange = { durationStr = it.filter(Char::isDigit) },
                    label = { Text("Default Duration (min, optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(name, category, durationStr.toIntOrNull())
                },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CheckOffDurationDialog(
    activity: LeisureActivityEntity,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var durationStr by remember(activity.id) {
        mutableStateOf(activity.defaultDurationMinutes?.toString() ?: "30")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log ${activity.name}") },
        text = {
            Column {
                Text(
                    "How long did you spend on this?",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = durationStr,
                    onValueChange = { durationStr = it.filter(Char::isDigit) },
                    label = { Text("Duration (min)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val minutes = durationStr.toIntOrNull() ?: 0
                    if (minutes >= 1) onConfirm(minutes)
                },
                enabled = (durationStr.toIntOrNull() ?: 0) >= 1
            ) { Text("Log") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun LogCategoryDialog(
    category: LeisureCategory,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var durationStr by remember(category) { mutableStateOf("30") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log ${category.emoji} ${category.label}") },
        text = {
            Column {
                Text(
                    "How long did you spend?",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = durationStr,
                    onValueChange = { durationStr = it.filter(Char::isDigit) },
                    label = { Text("Duration (min)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val minutes = durationStr.toIntOrNull() ?: 0
                    if (minutes >= 1) onConfirm(minutes)
                },
                enabled = (durationStr.toIntOrNull() ?: 0) >= 1
            ) { Text("Log") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSessionTimeDialog(
    session: LeisureSessionEntity,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val initialDateTime = remember(session.id) {
        Instant.ofEpochMilli(session.loggedAt).atZone(zone).toLocalDateTime()
    }
    var pickedDate by remember(session.id) {
        mutableStateOf(initialDateTime.toLocalDate())
    }
    var pickedHour by remember(session.id) { mutableIntStateOf(initialDateTime.hour) }
    var pickedMinute by remember(session.id) { mutableIntStateOf(initialDateTime.minute) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val dateLabel = dateFormat.format(
        Date(pickedDate.atStartOfDay(zone).toInstant().toEpochMilli())
    )
    val timeLabel = timeFormat.format(
        Date(
            LocalDateTime.of(pickedDate, java.time.LocalTime.of(pickedHour, pickedMinute))
                .atZone(zone).toInstant().toEpochMilli()
        )
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Time") },
        text = {
            Column {
                Text("Date", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(dateLabel) }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Time", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(timeLabel) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val newMillis = LocalDateTime.of(
                    pickedDate,
                    java.time.LocalTime.of(pickedHour, pickedMinute)
                ).atZone(zone).toInstant().toEpochMilli()
                onConfirm(newMillis)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = pickedDate.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        pickedDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = pickedHour,
            initialMinute = pickedMinute
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select Time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    pickedHour = timePickerState.hour
                    pickedMinute = timePickerState.minute
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }
}
