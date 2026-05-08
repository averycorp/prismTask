package com.averycorp.prismtask.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.domain.model.SelfCareRoutines
import com.averycorp.prismtask.ui.theme.ThemedSubScreenTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedTuningScreen(
    navController: NavController,
    viewModel: AdvancedTuningViewModel = hiltViewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { ThemedSubScreenTitle("Advanced Tuning") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Power-user knobs for scoring, scheduling, widgets, and editor caps. " +
                    "Defaults are tuned for most users — change only if you know what you're doing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // ---------- Scoring ----------
            UrgencyBandsGroup(viewModel)
            UrgencyWindowsGroup(viewModel)
            BurnoutWeightsGroup(viewModel)
            ProductivityWeightsGroup(viewModel)
            MoodCorrelationGroup(viewModel)
            SuggestionGroup(viewModel)
            SmartDefaultsGroup(viewModel)
            ExtractorGroup(viewModel)
            LifeCategoryKeywordsGroup(viewModel)

            // ---------- Wellbeing & ND ----------
            RefillUrgencyGroup(viewModel)
            EnergyPomodoroGroup(viewModel)
            GoodEnoughTimerGroup(viewModel)
            MorningCheckInGroup(viewModel)
            SelfCareTierDefaultsGroup(viewModel)

            // ---------- Schedules ----------
            WeeklySummaryGroup(viewModel)
            OverloadCheckGroup(viewModel)
            ReengagementGroup(viewModel)
            BatchUndoGroup(viewModel)
            HabitReminderFallbackGroup(viewModel)
            ApiNetworkGroup(viewModel)

            // ---------- Widgets ----------
            WidgetRefreshGroup(viewModel)
            ProductivityWidgetGroup(viewModel)

            // ---------- Editor ----------
            EditorFieldRowsGroup(viewModel)
            QuickAddRowsGroup(viewModel)
            SearchPreviewGroup(viewModel)

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// =====================================================================
// Reusable building blocks
// =====================================================================

@Composable
private fun ExpandableGroup(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                content()
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun IntSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    valueFormatter: (Int) -> String = { it.toString() },
    onValueChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first - 1).coerceAtLeast(0)
            )
        }
        Text(
            text = valueFormatter(value),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 4.dp)
        )
    }
}

@Composable
private fun FloatSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueFormatter: (Float) -> String = { String.format("%.2f", it) },
    onValueChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range
            )
        }
        Text(
            text = valueFormatter(value),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 4.dp)
        )
    }
}

@Composable
private fun TimeRow(
    label: String,
    hour: Int,
    minute: Int,
    onChange: (Int, Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Hour: $hour", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = hour.toFloat(),
                    onValueChange = { onChange(it.toInt(), minute) },
                    valueRange = 0f..23f,
                    steps = 22
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Minute: $minute", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = minute.toFloat(),
                    onValueChange = { onChange(hour, it.toInt()) },
                    valueRange = 0f..59f,
                    steps = 58
                )
            }
        }
    }
}

@Composable
private fun DayOfWeekRow(
    label: String,
    dayOfWeek: Int,
    onChange: (Int) -> Unit
) {
    val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val next = if (dayOfWeek >= 7) 1 else dayOfWeek + 1
                onChange(next)
            }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = labels.getOrElse(dayOfWeek - 1) { "Sun" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// =====================================================================
// Scoring groups
// =====================================================================

@Composable
private fun UrgencyBandsGroup(vm: AdvancedTuningViewModel) {
    val state by vm.urgencyBands.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Urgency Bands",
        subtitle = "Score thresholds for Critical / High / Medium"
    ) {
        FloatSliderRow("Critical ≥", state.critical, 0.5f..1.0f) {
            vm.setUrgencyBands(state.copy(critical = it))
        }
        FloatSliderRow("High ≥", state.high, 0.2f..0.9f) {
            vm.setUrgencyBands(state.copy(high = it))
        }
        FloatSliderRow("Medium ≥", state.medium, 0.05f..0.6f) {
            vm.setUrgencyBands(state.copy(medium = it))
        }
    }
}

@Composable
private fun UrgencyWindowsGroup(vm: AdvancedTuningViewModel) {
    val state by vm.urgencyWindows.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Urgency Day Windows",
        subtitle = "How fast urgency ramps for overdue / imminent tasks"
    ) {
        IntSliderRow("Overdue ceiling", state.overdueCeilingDays, 1..30, valueFormatter = { "$it d" }) {
            vm.setUrgencyWindows(state.copy(overdueCeilingDays = it))
        }
        IntSliderRow("Imminent window", state.imminentWindowDays, 1..30, valueFormatter = { "$it d" }) {
            vm.setUrgencyWindows(state.copy(imminentWindowDays = it))
        }
    }
}

@Composable
private fun BurnoutWeightsGroup(vm: AdvancedTuningViewModel) {
    val state by vm.burnoutWeights.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Burnout Score Weights",
        subtitle = "Maximum points each component contributes (out of 100)"
    ) {
        IntSliderRow("Work load", state.workMax, 0..100) {
            vm.setBurnoutWeights(state.copy(workMax = it))
        }
        IntSliderRow("Overdue", state.overdueMax, 0..100) {
            vm.setBurnoutWeights(state.copy(overdueMax = it))
        }
        IntSliderRow("Self-care debt", state.selfCareMax, 0..100) {
            vm.setBurnoutWeights(state.copy(selfCareMax = it))
        }
        IntSliderRow("Medication misses", state.medicationMax, 0..100) {
            vm.setBurnoutWeights(state.copy(medicationMax = it))
        }
        IntSliderRow("Streak fatigue", state.streakMax, 0..100) {
            vm.setBurnoutWeights(state.copy(streakMax = it))
        }
        IntSliderRow("Rest deficit", state.restDeficitMax, 0..100) {
            vm.setBurnoutWeights(state.copy(restDeficitMax = it))
        }
        IntSliderRow("Rest deficit days", state.restDeficitDays, 1..14, valueFormatter = { "$it d" }) {
            vm.setBurnoutWeights(state.copy(restDeficitDays = it))
        }
    }
}

@Composable
private fun ProductivityWeightsGroup(vm: AdvancedTuningViewModel) {
    val state by vm.productivityWeights.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Productivity Score Weights",
        subtitle = "Component weights (should sum to 1.0)"
    ) {
        FloatSliderRow("Tasks", state.taskWeight, 0.0f..1.0f) {
            vm.setProductivityWeights(state.copy(taskWeight = it))
        }
        FloatSliderRow("On-time", state.onTimeWeight, 0.0f..1.0f) {
            vm.setProductivityWeights(state.copy(onTimeWeight = it))
        }
        FloatSliderRow("Habits", state.habitWeight, 0.0f..1.0f) {
            vm.setProductivityWeights(state.copy(habitWeight = it))
        }
        FloatSliderRow("Estimation", state.estimationWeight, 0.0f..1.0f) {
            vm.setProductivityWeights(state.copy(estimationWeight = it))
        }
        FloatSliderRow("Trend threshold", state.trendThreshold, 0.0f..10.0f, valueFormatter = { String.format("%.1f", it) }) {
            vm.setProductivityWeights(state.copy(trendThreshold = it))
        }
    }
}

@Composable
private fun MoodCorrelationGroup(vm: AdvancedTuningViewModel) {
    val state by vm.moodCorrelation.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Mood Correlation",
        subtitle = "Sample minimum and label cutoffs"
    ) {
        IntSliderRow("Min observations", state.minObservations, 1..30) {
            vm.setMoodCorrelation(state.copy(minObservations = it))
        }
        FloatSliderRow("Strong threshold", state.strongThreshold, 0.0f..1.0f) {
            vm.setMoodCorrelation(state.copy(strongThreshold = it))
        }
        FloatSliderRow("Moderate threshold", state.moderateThreshold, 0.0f..1.0f) {
            vm.setMoodCorrelation(state.copy(moderateThreshold = it))
        }
    }
}

@Composable
private fun SuggestionGroup(vm: AdvancedTuningViewModel) {
    val state by vm.suggestion.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Smart Suggestions",
        subtitle = "Tag / project suggestion confidence cutoffs"
    ) {
        FloatSliderRow("Tag threshold", state.tagThreshold, 0.0f..1.0f) {
            vm.setSuggestion(state.copy(tagThreshold = it))
        }
        FloatSliderRow("Project threshold", state.projectThreshold, 0.0f..1.0f) {
            vm.setSuggestion(state.copy(projectThreshold = it))
        }
        IntSliderRow("Max results", state.maxResults, 1..10) {
            vm.setSuggestion(state.copy(maxResults = it))
        }
    }
}

@Composable
private fun SmartDefaultsGroup(vm: AdvancedTuningViewModel) {
    val state by vm.smartDefaults.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Smart Defaults",
        subtitle = "Sample size and duration granularity"
    ) {
        IntSliderRow("Min history", state.minHistory, 1..50) {
            vm.setSmartDefaults(state.copy(minHistory = it))
        }
        IntSliderRow("Duration granularity", state.durationGranularityMinutes, 1..60, valueFormatter = { "$it m" }) {
            vm.setSmartDefaults(state.copy(durationGranularityMinutes = it))
        }
    }
}

@Composable
private fun ExtractorGroup(vm: AdvancedTuningViewModel) {
    val state by vm.extractor.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Conversation Extractor",
        subtitle = "Input + title length caps"
    ) {
        IntSliderRow("Max input chars", state.maxInputChars, 500..50_000, valueFormatter = { "$it" }) {
            vm.setExtractor(state.copy(maxInputChars = it))
        }
        IntSliderRow("Max title chars", state.maxTitleChars, 20..500) {
            vm.setExtractor(state.copy(maxTitleChars = it))
        }
    }
}

@Composable
private fun LifeCategoryKeywordsGroup(vm: AdvancedTuningViewModel) {
    val state by vm.lifeCategoryKeywords.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Life Category Keywords",
        subtitle = "Comma-separated extra keywords per category"
    ) {
        Text(
            text = "Appended to the built-in classifier list. One word per slot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = state.work,
            onValueChange = { vm.setLifeCategoryKeywords(state.copy(work = it)) },
            label = { Text("Work") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = state.personal,
            onValueChange = { vm.setLifeCategoryKeywords(state.copy(personal = it)) },
            label = { Text("Personal") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = state.selfCare,
            onValueChange = { vm.setLifeCategoryKeywords(state.copy(selfCare = it)) },
            label = { Text("Self-care") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = state.health,
            onValueChange = { vm.setLifeCategoryKeywords(state.copy(health = it)) },
            label = { Text("Health") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            singleLine = true
        )
    }
}

// =====================================================================
// Wellbeing / ND groups
// =====================================================================

@Composable
private fun RefillUrgencyGroup(vm: AdvancedTuningViewModel) {
    val state by vm.refillUrgency.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Medication Refill Urgency",
        subtitle = "Day thresholds for urgent / upcoming refill labels"
    ) {
        IntSliderRow("Urgent days", state.urgentDays, 0..14, valueFormatter = { "$it d" }) {
            vm.setRefillUrgency(state.copy(urgentDays = it))
        }
        IntSliderRow("Upcoming days", state.upcomingDays, 0..30, valueFormatter = { "$it d" }) {
            vm.setRefillUrgency(state.copy(upcomingDays = it))
        }
    }
}

@Composable
private fun EnergyPomodoroGroup(vm: AdvancedTuningViewModel) {
    val state by vm.energyPomodoro.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Energy-Aware Pomodoro",
        subtitle = "Per-energy-band work / break / long-break minutes"
    ) {
        EnergyBand(
            label = "Very Low",
            work = state.veryLowWork,
            short = state.veryLowBreak,
            long = state.veryLowLong,
            onChange = { w, s, l ->
                vm.setEnergyPomodoro(state.copy(veryLowWork = w, veryLowBreak = s, veryLowLong = l))
            }
        )
        EnergyBand(
            label = "Low",
            work = state.lowWork,
            short = state.lowBreak,
            long = state.lowLong,
            onChange = { w, s, l ->
                vm.setEnergyPomodoro(state.copy(lowWork = w, lowBreak = s, lowLong = l))
            }
        )
        EnergyBand(
            label = "Medium",
            work = state.mediumWork,
            short = state.mediumBreak,
            long = state.mediumLong,
            onChange = { w, s, l ->
                vm.setEnergyPomodoro(state.copy(mediumWork = w, mediumBreak = s, mediumLong = l))
            }
        )
        EnergyBand(
            label = "High",
            work = state.highWork,
            short = state.highBreak,
            long = state.highLong,
            onChange = { w, s, l ->
                vm.setEnergyPomodoro(state.copy(highWork = w, highBreak = s, highLong = l))
            }
        )
        EnergyBand(
            label = "Very High",
            work = state.veryHighWork,
            short = state.veryHighBreak,
            long = state.veryHighLong,
            onChange = { w, s, l ->
                vm.setEnergyPomodoro(state.copy(veryHighWork = w, veryHighBreak = s, veryHighLong = l))
            }
        )
    }
}

@Composable
private fun EnergyBand(
    label: String,
    work: Int,
    short: Int,
    long: Int,
    onChange: (work: Int, short: Int, long: Int) -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp)
    )
    IntSliderRow("Work", work, 5..90, valueFormatter = { "$it m" }) {
        onChange(it, short, long)
    }
    IntSliderRow("Short break", short, 1..30, valueFormatter = { "$it m" }) {
        onChange(work, it, long)
    }
    IntSliderRow("Long break", long, 5..60, valueFormatter = { "$it m" }) {
        onChange(work, short, it)
    }
}

@Composable
private fun GoodEnoughTimerGroup(vm: AdvancedTuningViewModel) {
    val state by vm.goodEnoughTimer.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Good-Enough Timer (ND)",
        subtitle = "Grace, nudge cooldown, dialog cooldown, extension"
    ) {
        IntSliderRow("Grace period", state.gracePeriodMinutes, 0..30, valueFormatter = { "$it m" }) {
            vm.setGoodEnoughTimer(state.copy(gracePeriodMinutes = it))
        }
        IntSliderRow("Nudge cooldown", state.nudgeCooldownMinutes, 1..60, valueFormatter = { "$it m" }) {
            vm.setGoodEnoughTimer(state.copy(nudgeCooldownMinutes = it))
        }
        IntSliderRow("Dialog cooldown", state.dialogCooldownMinutes, 1..60, valueFormatter = { "$it m" }) {
            vm.setGoodEnoughTimer(state.copy(dialogCooldownMinutes = it))
        }
        IntSliderRow("Extension", state.extensionMinutes, 1..60, valueFormatter = { "$it m" }) {
            vm.setGoodEnoughTimer(state.copy(extensionMinutes = it))
        }
    }
}

@Composable
private fun MorningCheckInGroup(vm: AdvancedTuningViewModel) {
    val state by vm.morningCheckIn.collectAsStateWithLifecycle()
    val validation by vm.morningCheckInValidation.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Morning Check-In Prompt",
        subtitle = "Latest hour the morning prompt will trigger"
    ) {
        IntSliderRow("Latest hour", state.latestHour, 0..23, valueFormatter = { "$it:00" }) {
            vm.setMorningCheckIn(state.copy(latestHour = it))
        }
        (validation as? MorningCheckInCutoffValidation.Invalid)?.let { invalid ->
            Text(
                text = invalid.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// =====================================================================
// Schedule groups
// =====================================================================

@Composable
private fun WeeklySummaryGroup(vm: AdvancedTuningViewModel) {
    val state by vm.weeklySummary.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Weekly Summary Schedule",
        subtitle = "Day + clock time for weekly summary, habit summary, review"
    ) {
        DayOfWeekRow("Day of week", state.dayOfWeek) {
            vm.setWeeklySummary(state.copy(dayOfWeek = it))
        }
        TimeRow("Task summary", state.taskSummaryHour, state.taskSummaryMinute) { h, m ->
            vm.setWeeklySummary(state.copy(taskSummaryHour = h, taskSummaryMinute = m))
        }
        TimeRow("Habit summary", state.habitSummaryHour, state.habitSummaryMinute) { h, m ->
            vm.setWeeklySummary(state.copy(habitSummaryHour = h, habitSummaryMinute = m))
        }
        TimeRow("Weekly review", state.reviewHour, state.reviewMinute) { h, m ->
            vm.setWeeklySummary(state.copy(reviewHour = h, reviewMinute = m))
        }
        IntSliderRow("Evening summary hour", state.eveningSummaryHour, 0..23, valueFormatter = { "$it:00" }) {
            vm.setWeeklySummary(state.copy(eveningSummaryHour = it))
        }
        TimeRow("Weekly analytics", state.analyticsSummaryHour, state.analyticsSummaryMinute) { h, m ->
            vm.setWeeklySummary(state.copy(analyticsSummaryHour = h, analyticsSummaryMinute = m))
        }
    }
}

@Composable
private fun OverloadCheckGroup(vm: AdvancedTuningViewModel) {
    val state by vm.overloadCheck.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Overload Check Schedule",
        subtitle = "Daily time the OverloadCheckWorker fires"
    ) {
        TimeRow("Run at", state.hourOfDay, state.minute) { h, m ->
            vm.setOverloadCheck(state.copy(hourOfDay = h, minute = m))
        }
    }
}

@Composable
private fun ReengagementGroup(vm: AdvancedTuningViewModel) {
    val state by vm.reengagement.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Re-engagement Nudges",
        subtitle = "Absence threshold and max nudges"
    ) {
        IntSliderRow("Absence days", state.absenceDays, 1..30, valueFormatter = { "$it d" }) {
            vm.setReengagement(state.copy(absenceDays = it))
        }
        IntSliderRow("Max nudges", state.maxNudges, 1..10) {
            vm.setReengagement(state.copy(maxNudges = it))
        }
    }
}

@Composable
private fun BatchUndoGroup(vm: AdvancedTuningViewModel) {
    val state by vm.batchUndo.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Batch Undo Tail",
        subtitle = "Days a soft-deleted batch lingers before sweep"
    ) {
        IntSliderRow("Tail days", state.tailDays, 1..30, valueFormatter = { "$it d" }) {
            vm.setBatchUndo(state.copy(tailDays = it))
        }
    }
}

@Composable
private fun HabitReminderFallbackGroup(vm: AdvancedTuningViewModel) {
    val state by vm.habitReminderFallback.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Habit Reminder Fallback",
        subtitle = "Time used when a habit's reminder time is malformed"
    ) {
        TimeRow("Fallback time", state.hour, state.minute) { h, m ->
            vm.setHabitReminderFallback(state.copy(hour = h, minute = m))
        }
    }
}

@Composable
private fun ApiNetworkGroup(vm: AdvancedTuningViewModel) {
    val state by vm.apiNetwork.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "API Network",
        subtitle = "Timeout and token-refresh retry attempts"
    ) {
        Text(
            text = "Restart the app for timeout changes to apply.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        IntSliderRow("Timeout", state.timeoutSeconds, 5..120, valueFormatter = { "${it}s" }) {
            vm.setApiNetwork(state.copy(timeoutSeconds = it))
        }
        IntSliderRow("Retry attempts", state.retryAttempts, 0..10) {
            vm.setApiNetwork(state.copy(retryAttempts = it))
        }
    }
}

// =====================================================================
// Widget groups
// =====================================================================

@Composable
private fun WidgetRefreshGroup(vm: AdvancedTuningViewModel) {
    val state by vm.widgetRefresh.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Widget Refresh Cadence",
        subtitle = "How often home-screen widgets refresh in the background"
    ) {
        IntSliderRow("Interval", state.intervalMinutes, 15..240, valueFormatter = { "$it m" }) {
            vm.setWidgetRefresh(state.copy(intervalMinutes = it))
        }
    }
}

@Composable
private fun ProductivityWidgetGroup(vm: AdvancedTuningViewModel) {
    val state by vm.productivityWidget.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Productivity Widget Thresholds",
        subtitle = "Score cutoffs for green / orange / red badge color"
    ) {
        IntSliderRow("Green ≥", state.greenScore, 0..100) {
            vm.setProductivityWidget(state.copy(greenScore = it))
        }
        IntSliderRow("Orange ≥", state.orangeScore, 0..100) {
            vm.setProductivityWidget(state.copy(orangeScore = it))
        }
    }
}

// =====================================================================
// Editor groups
// =====================================================================

@Composable
private fun EditorFieldRowsGroup(vm: AdvancedTuningViewModel) {
    val state by vm.editorFieldRows.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Editor Field Rows",
        subtitle = "Max lines for description and notes fields"
    ) {
        IntSliderRow("Description rows", state.descriptionRows, 1..50) {
            vm.setEditorFieldRows(state.copy(descriptionRows = it))
        }
        IntSliderRow("Notes rows", state.notesRows, 1..50) {
            vm.setEditorFieldRows(state.copy(notesRows = it))
        }
    }
}

@Composable
private fun QuickAddRowsGroup(vm: AdvancedTuningViewModel) {
    val state by vm.quickAddRows.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Quick-Add Rows",
        subtitle = "Max lines for the quick-add / paste-multi-task field"
    ) {
        IntSliderRow("Max lines", state.maxLines, 1..50) {
            vm.setQuickAddRows(state.copy(maxLines = it))
        }
    }
}

@Composable
private fun SearchPreviewGroup(vm: AdvancedTuningViewModel) {
    val state by vm.searchPreview.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Search Preview Lines",
        subtitle = "Description preview line count in search results"
    ) {
        IntSliderRow("Preview lines", state.previewLines, 1..10) {
            vm.setSearchPreview(state.copy(previewLines = it))
        }
    }
}

@Composable
private fun SelfCareTierDefaultsGroup(vm: AdvancedTuningViewModel) {
    val state by vm.selfCareTierDefaults.collectAsStateWithLifecycle()
    ExpandableGroup(
        title = "Self-Care Default Tier",
        subtitle = "Tier shown first each day for each routine"
    ) {
        TierCycleRow(
            label = "Morning",
            routineType = "morning",
            current = state.morning
        ) { vm.setSelfCareTierDefaults(state.copy(morning = it)) }
        TierCycleRow(
            label = "Bedtime",
            routineType = "bedtime",
            current = state.bedtime
        ) { vm.setSelfCareTierDefaults(state.copy(bedtime = it)) }
        TierCycleRow(
            label = "Medication",
            routineType = "medication",
            current = state.medication
        ) { vm.setSelfCareTierDefaults(state.copy(medication = it)) }
        TierCycleRow(
            label = "Housework",
            routineType = "housework",
            current = state.housework
        ) { vm.setSelfCareTierDefaults(state.copy(housework = it)) }
    }
}

@Composable
private fun TierCycleRow(
    label: String,
    routineType: String,
    current: String,
    onChange: (String) -> Unit
) {
    val order = SelfCareRoutines.getTierOrder(routineType)
    val tiers = SelfCareRoutines.getTiers(routineType)
    val safeCurrent = if (current in order) current else order.last()
    val displayLabel = tiers.firstOrNull { it.id == safeCurrent }?.label ?: safeCurrent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val idx = order.indexOf(safeCurrent).coerceAtLeast(0)
                onChange(order[(idx + 1) % order.size])
            }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = displayLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
