package com.averycorp.prismtask.ui.screens.checkin

import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.calendar.CalendarEventInfo
import com.averycorp.prismtask.data.repository.HabitWithStatus
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.usecase.BalanceState
import com.averycorp.prismtask.domain.usecase.BurnoutBand
import com.averycorp.prismtask.domain.usecase.BurnoutResult
import com.averycorp.prismtask.domain.usecase.CheckInStep
import com.averycorp.prismtask.domain.usecase.RefillUrgency
import com.averycorp.prismtask.ui.theme.LifeCategoryColor
import com.averycorp.prismtask.ui.theme.LocalPrismShapes
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Morning Check-In screen (v1.4.0 V4).
 *
 * Guided horizontal pager: mood/energy → top tasks → habits → balance →
 * calendar → confirmation. Each step is its own small composable below.
 * Users can swipe or tap "Next" to advance; the final page persists a
 * CheckInLog row via the ViewModel so the Today screen stops prompting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningCheckInScreen(
    navController: NavController,
    viewModel: MorningCheckInViewModel = hiltViewModel()
) {
    val planState by viewModel.plan.collectAsStateWithLifecycle()
    val completedSteps by viewModel.completedSteps.collectAsStateWithLifecycle()
    val isFinished by viewModel.isFinished.collectAsStateWithLifecycle()

    LaunchedEffect(isFinished) {
        if (isFinished) navController.popBackStack()
    }

    // Add an implicit final "Ready To Go" page.
    val steps = planState.steps
    val pageCount = steps.size + 1
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Morning Check-In", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StepIndicator(
                current = pagerState.currentPage,
                total = pageCount,
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp)
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                if (page < steps.size) {
                    StepContent(
                        step = steps[page],
                        viewModel = viewModel,
                        planState = planState,
                        completedSteps = completedSteps
                    )
                } else {
                    FinalReadyPage(onDone = { viewModel.finalize() })
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    onClick = {
                        if (pagerState.currentPage > 0) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        }
                    },
                    enabled = pagerState.currentPage > 0
                ) { Text("Back") }
                Button(onClick = {
                    if (pagerState.currentPage < pageCount - 1) {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    } else {
                        viewModel.finalize()
                    }
                }) {
                    Text(if (pagerState.currentPage < pageCount - 1) "Next" else "Done")
                    Spacer(modifier = Modifier.size(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (i in 0 until total) {
            val color = if (i <= current) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun StepContent(
    step: CheckInStep,
    viewModel: MorningCheckInViewModel,
    planState: CheckInScreenState,
    completedSteps: Set<CheckInStep>
) {
    when (step) {
        CheckInStep.MOOD_ENERGY -> MoodEnergyStep(
            onSubmit = { mood, energy, notes -> viewModel.logMoodEnergy(mood, energy, notes) }
        )
        CheckInStep.MEDICATIONS -> {
            val meds by viewModel.medications.collectAsStateWithLifecycle()
            MedicationsStep(
                medications = meds,
                onTake = { viewModel.takeMedicationDose(it.refill) },
                onDone = { viewModel.markStepComplete(step) }
            )
        }
        CheckInStep.TOP_TASKS -> TopTasksStep(
            tasks = planState.topTasks,
            onDone = { viewModel.markStepComplete(step) }
        )
        CheckInStep.HABITS -> {
            val habits by viewModel.todayHabits.collectAsStateWithLifecycle()
            HabitsStep(
                habits = habits,
                onToggle = { viewModel.toggleHabit(it) },
                onDone = { viewModel.markStepComplete(step) }
            )
        }
        CheckInStep.BALANCE -> {
            val balance by viewModel.balanceState.collectAsStateWithLifecycle()
            val burnout by viewModel.burnoutResult.collectAsStateWithLifecycle()
            BalanceStep(
                state = balance,
                burnout = burnout,
                onDone = { viewModel.markStepComplete(step) }
            )
        }
        CheckInStep.CALENDAR -> {
            val connected by viewModel.calendarConnected.collectAsStateWithLifecycle()
            val events by viewModel.calendarEvents.collectAsStateWithLifecycle()
            CalendarStep(
                isConnected = connected,
                events = events,
                onDone = { viewModel.markStepComplete(step) }
            )
        }
    }
}

@Composable
private fun MoodEnergyStep(onSubmit: (mood: Int, energy: Int, notes: String?) -> Unit) {
    var mood by remember { mutableIntStateOf(3) }
    var energy by remember { mutableIntStateOf(3) }
    var notes by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("How are you feeling?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Mood", style = MaterialTheme.typography.bodyMedium)
        EmojiRow(
            emojis = listOf("\uD83D\uDE22", "\uD83D\uDE15", "\uD83D\uDE10", "\uD83D\uDE42", "\uD83D\uDE0A"),
            selected = mood - 1,
            onSelect = { mood = it + 1 }
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("Energy", style = MaterialTheme.typography.bodyMedium)
        EmojiRow(
            emojis = listOf("\uD83D\uDD0B", "\uD83D\uDD0B", "\uD83D\uDD0B", "\uD83D\uDD0B", "\u26A1"),
            selected = energy - 1,
            onSelect = { energy = it + 1 }
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Anything affecting your day?") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { onSubmit(mood, energy, notes.takeIf { it.isNotBlank() }) }) {
            Text("Save & Continue")
        }
    }
}

@Composable
private fun EmojiRow(emojis: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        emojis.forEachIndexed { index, emoji ->
            val bg = if (index == selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = { onSelect(index) },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(emoji, fontSize = 28.sp)
                }
            }
        }
    }
}

@Composable
private fun TopTasksStep(
    tasks: List<com.averycorp.prismtask.data.local.entity.TaskEntity>,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            "Top Tasks",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (tasks.isEmpty()) {
            Text(
                "Clear day — focus on self-care.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            tasks.forEach { task ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "P${task.priority}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onDone) { Text("I've Reviewed These") }
    }
}

/**
 * Real Medications step: renders each tracked medication as a card with
 * dosage info and a "Taken" checkbox. Tapping the checkbox records the
 * dose via [MorningCheckInViewModel.takeMedicationDose]. When any
 * medication's refill urgency is URGENT or OUT_OF_STOCK, a warning badge
 * is pinned to the top.
 */
@Composable
private fun MedicationsStep(
    medications: List<MedicationCheckInItem>,
    onTake: (MedicationCheckInItem) -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            "Medications",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (medications.isEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "\uD83D\uDC8A",
                fontSize = 48.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No Medications Tracked",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Add medications in Settings → Medications to see them here each morning.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = onDone,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("Skip") }
            return@Column
        }

        val anyUrgent = medications.any { it.isRefillUrgent }
        if (anyUrgent) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = LifeCategoryColor.HEALTH.copy(alpha = 0.12f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("\u26A0\uFE0F", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "A medication needs a refill soon.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LifeCategoryColor.HEALTH,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = medications,
                key = { it.refill.id }
            ) { item ->
                MedicationCard(item = item, onTake = { onTake(item) })
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            onClick = onDone,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text("Continue") }
    }
}

@Composable
private fun MedicationCard(
    item: MedicationCheckInItem,
    onTake: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.taken) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.refill.medicationName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                val dosage = "${item.refill.pillsPerDose} pill${if (item.refill.pillsPerDose == 1) "" else "s"} " +
                    "\u00D7 ${item.refill.dosesPerDay}/day"
                Text(
                    text = dosage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.isRefillUrgent) {
                    Spacer(modifier = Modifier.height(4.dp))
                    RefillBadge(urgency = item.forecast.urgency)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (item.taken) "Taken" else "Taken?",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Checkbox(checked = item.taken, onCheckedChange = { if (it) onTake() })
            }
        }
    }
}

@Composable
private fun RefillBadge(urgency: RefillUrgency) {
    val (label, color) = when (urgency) {
        RefillUrgency.OUT_OF_STOCK -> "Out Of Stock" to LifeCategoryColor.HEALTH
        RefillUrgency.URGENT -> "Refill Urgent" to LifeCategoryColor.HEALTH
        RefillUrgency.UPCOMING -> "Refill Soon" to Color(0xFFE68A00)
        RefillUrgency.HEALTHY -> "Healthy" to LifeCategoryColor.PERSONAL
    }
    Box(
        modifier = Modifier
            .clip(LocalPrismShapes.current.chip)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

/**
 * Real Habits step: renders each of today's habits as a tappable chip
 * (matching the Today screen style). Tapping toggles completion via
 * [MorningCheckInViewModel.toggleHabit]. Shows "N of M done" at the top,
 * plus a small celebration message when everything is complete.
 */
@Composable
private fun HabitsStep(
    habits: List<HabitWithStatus>,
    onToggle: (HabitWithStatus) -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            "Habits",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (habits.isEmpty()) {
            Text(
                text = "No habits scheduled for today.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = onDone,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("Skip") }
            return@Column
        }

        val done = habits.count { it.isCompletedToday }
        val total = habits.size
        Text(
            text = "$done of $total done",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (done == total) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = LifeCategoryColor.PERSONAL.copy(alpha = 0.15f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("\uD83C\uDF89", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "All Habits Done — Nice Work!",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = LifeCategoryColor.PERSONAL
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = habits,
                key = { it.habit.id }
            ) { habit ->
                HabitRow(habit = habit, onTap = { onToggle(habit) })
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            onClick = onDone,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text("Continue") }
    }
}

@Composable
private fun HabitRow(habit: HabitWithStatus, onTap: () -> Unit) {
    val habitColor = remember(habit.habit.color) {
        try {
            Color(android.graphics.Color.parseColor(habit.habit.color))
        } catch (_: Exception) {
            Color(0xFF4A90D9)
        }
    }
    val isDone = habit.isCompletedToday
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDone) {
                habitColor.copy(alpha = 0.18f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(habitColor.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = habit.habit.icon, fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = habit.habit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                val target = habit.dailyTarget.coerceAtLeast(1)
                val doneCount = habit.completionsToday.coerceAtMost(target)
                val sub = if (target > 1) {
                    "$doneCount/$target today"
                } else if (isDone) {
                    "Done"
                } else {
                    "Not done yet"
                }
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onTap) {
                Text(if (isDone) "Undo" else "Mark Done")
            }
        }
    }
}

/**
 * Real Balance step: renders this week's category ratios as a stacked
 * horizontal bar, plus a burnout band badge and an overload warning when
 * work exceeds the configured target.
 */
@Composable
private fun BalanceStep(
    state: BalanceState,
    burnout: BurnoutResult,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            "Balance Check",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (state.totalTracked == 0) {
            Text(
                text = "Add categories to your tasks to see your balance.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = onDone,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("Got It") }
            return@Column
        }

        BalanceStackedBar(ratios = state.currentRatios)
        Spacer(modifier = Modifier.height(12.dp))

        val summary = buildCategorySummary(state)
        Text(
            text = "Your week so far: $summary",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BurnoutBandBadge(result = burnout)
            if (state.isOverloaded) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Work-dominant week",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = onDone,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text("Got It") }
    }
}

@Composable
private fun BalanceStackedBar(ratios: Map<LifeCategory, Float>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        LifeCategory.TRACKED.forEach { category ->
            val ratio = (ratios[category] ?: 0f).coerceIn(0f, 1f)
            if (ratio > 0f) {
                Box(
                    modifier = Modifier
                        .weight(ratio)
                        .fillMaxSize()
                        .background(LifeCategoryColor.forCategory(category))
                )
            }
        }
    }
}

@Composable
private fun BurnoutBandBadge(result: BurnoutResult) {
    val color = when (result.band) {
        BurnoutBand.BALANCED -> LifeCategoryColor.PERSONAL
        BurnoutBand.MONITOR -> Color(0xFFE6B800)
        BurnoutBand.CAUTION -> Color(0xFFE68A00)
        BurnoutBand.HIGH_RISK -> LifeCategoryColor.HEALTH
    }
    val label = when (result.band) {
        BurnoutBand.BALANCED -> "Balanced"
        BurnoutBand.MONITOR -> "Monitor"
        BurnoutBand.CAUTION -> "Caution"
        BurnoutBand.HIGH_RISK -> "High Risk"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(LocalPrismShapes.current.chip)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

private fun buildCategorySummary(state: BalanceState): String {
    val parts = LifeCategory.TRACKED
        .mapNotNull { cat ->
            val ratio = state.currentRatios[cat] ?: 0f
            if (ratio <= 0f) {
                null
            } else {
                val pct = (ratio * 100f).roundToInt()
                "$pct% ${categoryShortLabel(cat)}"
            }
        }
    return if (parts.isEmpty()) "No tracked tasks yet" else parts.joinToString(", ")
}

private fun categoryShortLabel(category: LifeCategory): String = when (category) {
    LifeCategory.WORK -> "work"
    LifeCategory.PERSONAL -> "personal"
    LifeCategory.SELF_CARE -> "self-care"
    LifeCategory.HEALTH -> "health"
    LifeCategory.UNCATEGORIZED -> "other"
}

/**
 * Real Calendar step: shows today's next 3 events when Google Calendar is
 * connected, an empty-state message when there are no events, or a
 * "Connect Calendar in Settings" hint when the OAuth scope is missing.
 */
@Composable
private fun CalendarStep(
    isConnected: Boolean,
    events: List<CalendarEventInfo>,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            "Calendar Glance",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (!isConnected) {
            Text("\uD83D\uDCC5", fontSize = 48.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Connect Google Calendar in Settings to see today's events here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        } else if (events.isEmpty()) {
            Text("\u2728", fontSize = 40.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No events today — open schedule!",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        } else {
            Text(
                text = "Your next ${events.size} event${if (events.size == 1) "" else "s"}:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                events.forEach { event -> CalendarEventCard(event) }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = onDone,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text("Got It") }
    }
}

@Composable
private fun CalendarEventCard(event: CalendarEventInfo) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val timeLabel = if (event.isAllDay) "All day" else timeFormat.format(Date(event.startMillis))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "$timeLabel \u00B7 ${formatDuration(event.durationMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatDuration(durationMillis: Long): String {
    if (durationMillis <= 0) return "—"
    val totalMinutes = (durationMillis / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

@Composable
private fun FinalReadyPage(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("\u2728", fontSize = 72.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Ready To Go!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "You've checked in with yourself. That's already a win.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onDone) { Text("Start My Day") }
    }
}
