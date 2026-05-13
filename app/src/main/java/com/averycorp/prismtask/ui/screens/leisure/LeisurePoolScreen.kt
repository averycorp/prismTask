package com.averycorp.prismtask.ui.screens.leisure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import com.averycorp.prismtask.data.local.entity.LeisureSessionEntity
import com.averycorp.prismtask.data.preferences.LeisureBudgetPreferences
import com.averycorp.prismtask.data.preferences.LeisureCategoryDisplay
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
    var addInitialCategory by remember { mutableStateOf<LeisureCategory?>(null) }
    var editing by remember { mutableStateOf<LeisureActivityEntity?>(null) }
    var checkingOff by remember { mutableStateOf<LeisureActivityEntity?>(null) }
    var pickingCategory by remember { mutableStateOf<LeisureCategory?>(null) }
    var quickLoggingCategory by remember { mutableStateOf<LeisureCategory?>(null) }
    var editingCategory by remember { mutableStateOf<LeisureCategory?>(null) }
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
        val label = state.displayFor(category).label
        coroutineScope.launch {
            snackbarHostState.showSnackbar("Logged $minutes min of $label")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Leisure Minimum", fontWeight = FontWeight.Bold) },
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
        val enabledCategories = remember(state.settings.enabledCategories) {
            LeisureCategory.values().filter { it in state.settings.enabledCategories }
        }
        var manageExpanded by remember { mutableStateOf(false) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                TodayHeroCard(
                    minutesLogged = state.minutesLoggedToday,
                    targetMinutes = state.targetMinutesToday,
                    breakdown = state.minutesByCategoryToday,
                    enabledCategories = enabledCategories,
                    displayFor = state::displayFor
                )
            }

            item { SectionHeader("Quick Log") }
            item {
                CategoryTileGrid(
                    categories = enabledCategories,
                    activitiesByCategory = state.activitiesByCategory,
                    displayFor = state::displayFor,
                    onCategoryClick = { pickingCategory = it }
                )
            }

            item { SectionHeader("Recent Activity") }
            if (sessionDays.isEmpty()) {
                item { EmptyLogHint() }
            } else {
                items(sessionDays, key = { it.date.toString() }) { day ->
                    SessionDayCard(
                        day = day,
                        activitiesById = state.activitiesById,
                        categoryDisplays = state.categoryDisplays,
                        onEditTime = { editingSession = it },
                        onDelete = { deletingSession = it }
                    )
                }
            }

            item {
                ManageSection(
                    expanded = manageExpanded,
                    onToggle = { manageExpanded = !manageExpanded },
                    viewModel = viewModel,
                    snapshot = state.settings,
                    enabledCategories = state.settings.enabledCategories,
                    categoryDisplays = state.categoryDisplays,
                    activities = state.activities,
                    onEditActivity = { editing = it },
                    onCheckOffActivity = { activity -> checkingOff = activity }
                )
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (showAddSheet) {
        val initial = addInitialCategory
            ?: state.settings.enabledCategories.firstOrNull()
            ?: LeisureCategory.PHYSICAL
        AddActivityDialog(
            initialCategory = initial,
            enabledCategories = state.settings.enabledCategories,
            categoryDisplays = state.categoryDisplays,
            onDismiss = {
                showAddSheet = false
                addInitialCategory = null
            },
            onConfirm = { name, category, duration ->
                viewModel.addActivity(name, category, duration)
                showAddSheet = false
                addInitialCategory = null
            }
        )
    }

    editing?.let { activity ->
        EditActivityDialog(
            activity = activity,
            enabledCategories = state.settings.enabledCategories,
            categoryDisplays = state.categoryDisplays,
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

    pickingCategory?.let { category ->
        PickActivityForCategoryDialog(
            display = state.displayFor(category),
            activities = state.activitiesByCategory[category]
                .orEmpty()
                .filter { it.enabled },
            onDismiss = { pickingCategory = null },
            onPickActivity = { activity ->
                pickingCategory = null
                checkingOff = activity
            },
            onLogCategoryOnly = {
                pickingCategory = null
                quickLoggingCategory = category
            },
            onAddActivity = {
                pickingCategory = null
                addInitialCategory = category
                showAddSheet = true
            },
            onEditCategory = {
                pickingCategory = null
                editingCategory = category
            }
        )
    }

    quickLoggingCategory?.let { category ->
        LogCategoryDialog(
            display = state.displayFor(category),
            onDismiss = { quickLoggingCategory = null },
            onConfirm = { minutes ->
                logCategory(category, minutes)
                quickLoggingCategory = null
            }
        )
    }

    editingCategory?.let { category ->
        EditCategoryDialog(
            category = category,
            current = state.displayFor(category),
            onDismiss = { editingCategory = null },
            onSave = { label, emoji ->
                viewModel.setCategoryDisplay(category, label, emoji)
                editingCategory = null
            },
            onReset = {
                viewModel.resetCategoryDisplay(category)
                editingCategory = null
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

private val LeisureAccent = Color(0xFF8B5CF6)

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 0.dp)
    )
}

@Composable
private fun TodayHeroCard(
    minutesLogged: Int,
    targetMinutes: Int,
    breakdown: Map<LeisureCategory, Int>,
    enabledCategories: List<LeisureCategory>,
    displayFor: (LeisureCategory) -> LeisureCategoryDisplay
) {
    val accent = LeisureAccent
    val fractionRaw = if (targetMinutes > 0) minutesLogged.toFloat() / targetMinutes else 0f
    val fraction by animateFloatAsState(
        targetValue = fractionRaw.coerceIn(0f, 1f),
        label = "leisure-progress"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Today",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$minutesLogged",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                        Text(
                            text = " min",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
                if (targetMinutes > 0) {
                    Text(
                        text = "of $targetMinutes min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (targetMinutes > 0) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.16f)
                )
            } else {
                Text(
                    text = "Set a daily minimum below to track progress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val totalBreakdown = enabledCategories.sumOf { breakdown[it] ?: 0 }
            if (totalBreakdown > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    enabledCategories.forEach { category ->
                        val minutes = breakdown[category] ?: 0
                        val display = displayFor(category)
                        BreakdownPill(
                            emoji = display.emoji,
                            minutes = minutes,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakdownPill(
    emoji: String,
    minutes: Int,
    modifier: Modifier = Modifier
) {
    val faded = minutes == 0
    val contentColor = if (faded) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = emoji,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "$minutes",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}

@Composable
private fun CategoryTileGrid(
    categories: List<LeisureCategory>,
    activitiesByCategory: Map<LeisureCategory, List<LeisureActivityEntity>>,
    displayFor: (LeisureCategory) -> LeisureCategoryDisplay,
    onCategoryClick: (LeisureCategory) -> Unit
) {
    if (categories.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        categories.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { category ->
                    CategoryTile(
                        display = displayFor(category),
                        activityCount = activitiesByCategory[category]?.count { it.enabled } ?: 0,
                        onClick = { onCategoryClick(category) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CategoryTile(
    display: LeisureCategoryDisplay,
    activityCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = display.emoji,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Log ${display.label}",
                    tint = LeisureAccent
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = display.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = when (activityCount) {
                    0 -> "No activities"
                    1 -> "1 activity"
                    else -> "$activityCount activities"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    categoryDisplays: Map<LeisureCategory, LeisureCategoryDisplay>,
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
                categoryDisplays = categoryDisplays,
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
    categoryDisplays: Map<LeisureCategory, LeisureCategoryDisplay>,
    onEditTime: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = Color(0xFF8B5CF6)
    val category = LeisureCategory.fromStringOrNull(session.category)
    val display = category?.let { categoryDisplays[it] }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val timeLabel = timeFormat.format(Date(session.loggedAt))
    val label = activity?.name ?: display?.label ?: category?.label ?: "Leisure"

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
                text = display?.emoji ?: category?.emoji ?: "•",
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
    Text(
        text = "Nothing logged yet — tap a category above to start.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    )
}

@Composable
private fun ManageSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    viewModel: LeisurePoolViewModel,
    snapshot: com.averycorp.prismtask.data.preferences.LeisureBudgetSnapshot,
    enabledCategories: Set<LeisureCategory>,
    categoryDisplays: Map<LeisureCategory, LeisureCategoryDisplay>,
    activities: List<LeisureActivityEntity>,
    onEditActivity: (LeisureActivityEntity) -> Unit,
    onCheckOffActivity: (LeisureActivityEntity) -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "manage-chevron"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Manage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${snapshot.dailyTargetMinutes} min/day · ${enabledCategories.size} categories",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    DailyMinimumSubsection(viewModel = viewModel, snapshot = snapshot)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    CategoriesSubsection(
                        enabledCategories = enabledCategories,
                        categoryDisplays = categoryDisplays,
                        onToggle = { category, enabled ->
                            viewModel.setCategoryEnabled(category, enabled)
                        }
                    )
                    if (activities.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        ActivityPoolSubsection(
                            activities = activities,
                            categoryDisplays = categoryDisplays,
                            onCheckOff = onCheckOffActivity,
                            onEdit = onEditActivity,
                            onToggleEnabled = { activity, enabled ->
                                viewModel.setActivityEnabled(activity, enabled)
                            },
                            onDelete = { viewModel.deleteActivity(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubsectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun DailyMinimumSubsection(
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
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        SubsectionLabel("Daily Minimum")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Different Minimum On Weekends",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = weekendOverrideEnabled,
                    onCheckedChange = { enabled ->
                        weekendOverrideEnabled = enabled
                        viewModel.setWeekendTarget(if (enabled) weekendTarget else null)
                    }
                )
            }
            if (weekendOverrideEnabled) {
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
private fun CategoriesSubsection(
    enabledCategories: Set<LeisureCategory>,
    categoryDisplays: Map<LeisureCategory, LeisureCategoryDisplay>,
    onToggle: (LeisureCategory, Boolean) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        SubsectionLabel("Categories")
        LeisureCategory.values().forEach { category ->
            val isEnabled = category in enabledCategories
            val display = categoryDisplays[category]
                ?: LeisureCategoryDisplay(category.emoji, category.label)
            val canDisable = !isEnabled || enabledCategories.size > 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = display.emoji,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = display.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = isEnabled,
                    enabled = canDisable,
                    onCheckedChange = { onToggle(category, it) }
                )
            }
        }
        if (enabledCategories.size <= 1) {
            Text(
                "Keep at least one category enabled.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ActivityPoolSubsection(
    activities: List<LeisureActivityEntity>,
    categoryDisplays: Map<LeisureCategory, LeisureCategoryDisplay>,
    onCheckOff: (LeisureActivityEntity) -> Unit,
    onEdit: (LeisureActivityEntity) -> Unit,
    onToggleEnabled: (LeisureActivityEntity, Boolean) -> Unit,
    onDelete: (LeisureActivityEntity) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        SubsectionLabel("Activity Pool")
        activities.forEach { activity ->
            ActivityRow(
                activity = activity,
                categoryDisplays = categoryDisplays,
                onCheckOff = { onCheckOff(activity) },
                onEdit = { onEdit(activity) },
                onToggleEnabled = { enabled -> onToggleEnabled(activity, enabled) },
                onDelete = { onDelete(activity) }
            )
        }
    }
}

@Composable
private fun ActivityRow(
    activity: LeisureActivityEntity,
    categoryDisplays: Map<LeisureCategory, LeisureCategoryDisplay>,
    onCheckOff: () -> Unit,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val parsedCategory = LeisureCategory.fromStringOrNull(activity.category)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = activity.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (activity.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            val parts = buildList {
                parsedCategory?.let {
                    val d = categoryDisplays[it] ?: LeisureCategoryDisplay(it.emoji, it.label)
                    add("${d.emoji} ${d.label}")
                }
                activity.defaultDurationMinutes?.let { add("$it min") }
                if (!activity.enabled) add("Disabled")
            }
            if (parts.isNotEmpty()) {
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onCheckOff, enabled = activity.enabled) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Log ${activity.name}",
                tint = if (activity.enabled) LeisureAccent else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More Options For ${activity.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text(if (activity.enabled) "Disable" else "Enable") },
                    onClick = {
                        menuOpen = false
                        onToggleEnabled(!activity.enabled)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun AddActivityDialog(
    initialCategory: LeisureCategory,
    enabledCategories: Set<LeisureCategory>,
    categoryDisplays: Map<LeisureCategory, LeisureCategoryDisplay>,
    onDismiss: () -> Unit,
    onConfirm: (String, LeisureCategory, Int?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var category by remember(initialCategory) { mutableStateOf(initialCategory) }
    var durationStr by remember { mutableStateOf("") }
    var categoryMenuOpen by remember { mutableStateOf(false) }

    fun displayOf(c: LeisureCategory): LeisureCategoryDisplay =
        categoryDisplays[c] ?: LeisureCategoryDisplay(c.emoji, c.label)

    val selectableCategories = LeisureCategory.values().filter { it in enabledCategories }
        .ifEmpty { LeisureCategory.values().toList() }

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
                        val d = displayOf(category)
                        Text("${d.emoji} ${d.label}")
                    }
                    DropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { categoryMenuOpen = false }
                    ) {
                        selectableCategories.forEach { c ->
                            val d = displayOf(c)
                            DropdownMenuItem(
                                text = { Text("${d.emoji} ${d.label}") },
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
    enabledCategories: Set<LeisureCategory>,
    categoryDisplays: Map<LeisureCategory, LeisureCategoryDisplay>,
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

    fun displayOf(c: LeisureCategory): LeisureCategoryDisplay =
        categoryDisplays[c] ?: LeisureCategoryDisplay(c.emoji, c.label)

    // Always include the activity's current category so the user can keep it
    // even after that category has been disabled in their settings.
    val selectableCategories = LeisureCategory.values()
        .filter { it in enabledCategories || it == category }
        .ifEmpty { LeisureCategory.values().toList() }

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
                        val d = displayOf(category)
                        Text("${d.emoji} ${d.label}")
                    }
                    DropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { categoryMenuOpen = false }
                    ) {
                        selectableCategories.forEach { c ->
                            val d = displayOf(c)
                            DropdownMenuItem(
                                text = { Text("${d.emoji} ${d.label}") },
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
    display: LeisureCategoryDisplay,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var durationStr by remember(display) { mutableStateOf("30") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log ${display.emoji} ${display.label}") },
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

@Composable
private fun PickActivityForCategoryDialog(
    display: LeisureCategoryDisplay,
    activities: List<LeisureActivityEntity>,
    onDismiss: () -> Unit,
    onPickActivity: (LeisureActivityEntity) -> Unit,
    onLogCategoryOnly: () -> Unit,
    onAddActivity: () -> Unit,
    onEditCategory: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${display.emoji} ${display.label}") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (activities.isEmpty()) {
                    Text(
                        text = "No activities in this category yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Pick An Activity",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    activities.forEach { activity ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickActivity(activity) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = activity.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                activity.defaultDurationMinutes?.let { d ->
                                    Text(
                                        text = "$d min default",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Log ${activity.name}",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        HorizontalDivider()
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onLogCategoryOnly,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Log Without A Specific Activity") }
                TextButton(
                    onClick = onAddActivity,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add Activity To ${display.label}") }
                TextButton(
                    onClick = onEditCategory,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Edit Category…") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun EditCategoryDialog(
    category: LeisureCategory,
    current: LeisureCategoryDisplay,
    onDismiss: () -> Unit,
    onSave: (label: String, emoji: String) -> Unit,
    onReset: () -> Unit
) {
    var label by remember(category) { mutableStateOf(current.label) }
    var emoji by remember(category) { mutableStateOf(current.emoji) }
    val isCustomized = current.label != category.label || current.emoji != category.emoji

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Category") },
        text = {
            Column {
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it },
                    label = { Text("Emoji") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Defaults: ${category.emoji} ${category.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(label, emoji) },
                enabled = label.isNotBlank() && emoji.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (isCustomized) {
                    TextButton(onClick = onReset) { Text("Reset") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
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
