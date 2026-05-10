package com.averycorp.prismtask.ui.screens.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch

private val presetColors = listOf(
    "#E86F3C",
    "#D4534A",
    "#4A90D9",
    "#7B61C2",
    "#2E9E6E",
    "#E8B84A",
    "#5B8C5A",
    "#8B5CF6",
    "#EC4899",
    "#06B6D4",
    "#F59E0B",
    "#6B7280"
)

private val presetIcons = listOf(
    "\u2B50",
    "\uD83D\uDCAA",
    "\uD83D\uDCDA",
    "\uD83C\uDFC3",
    "\uD83D\uDCA7",
    "\uD83E\uDDD8",
    "\u270D\uFE0F",
    "\uD83C\uDFB5",
    "\uD83D\uDCA4",
    "\uD83E\uDD57",
    "\uD83D\uDC8A",
    "\uD83E\uDDF9",
    "\uD83D\uDCF1",
    "\uD83C\uDFAF",
    "\uD83E\uDDE0",
    "\u2764\uFE0F"
)

private val defaultCategories = listOf(
    "Health",
    "Productivity",
    "Personal",
    "Fitness",
    "Learning",
    "Self-Care",
    "Housework",
    "Hygiene",
    "Nutrition",
    "Mindfulness",
    "Social"
)
private val dayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val dayValues = listOf(2, 3, 4, 5, 6, 7, 1) // Calendar.MONDAY=2 ... SUNDAY=1

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditHabitScreen(
    navController: NavController,
    viewModel: AddEditHabitViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (viewModel.isEditMode) "Edit Habit" else "New Habit",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (viewModel.isEditMode) {
                        IconButton(onClick = {
                            scope.launch {
                                viewModel.deleteHabit()
                                navController.popBackStack()
                            }
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Name
            OutlinedTextField(
                value = viewModel.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Habit Name") },
                isError = viewModel.nameError,
                supportingText = if (viewModel.nameError) {
                    { Text("Name is required") }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Description
            OutlinedTextField(
                value = viewModel.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text("Description (optional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            // Icon & Color
            SectionLabel("Icon")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetIcons.forEach { emoji ->
                    IconOption(
                        emoji = emoji,
                        selected = viewModel.icon == emoji,
                        onClick = { viewModel.onIconChange(emoji) }
                    )
                }
            }

            SectionLabel("Color")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                presetColors.forEach { hex ->
                    ColorCircle(
                        hex = hex,
                        selected = viewModel.color.equals(hex, ignoreCase = true),
                        onClick = { viewModel.onColorChange(hex) }
                    )
                }
            }

            // Category
            SectionLabel("Category")
            val allCategories = remember(viewModel.customCategories) {
                (defaultCategories + viewModel.customCategories).distinct()
            }
            val showCustomCategoryDialog = remember { mutableStateOf(false) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                allCategories.forEach { cat ->
                    FilterChip(
                        selected = viewModel.category == cat,
                        onClick = {
                            viewModel.onCategoryChange(if (viewModel.category == cat) "" else cat)
                        },
                        label = { Text(cat) }
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { showCustomCategoryDialog.value = true },
                    label = { Text("+ Custom") }
                )
            }

            if (showCustomCategoryDialog.value) {
                val customCategoryName = remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showCustomCategoryDialog.value = false },
                    title = { Text("Custom Category") },
                    text = {
                        OutlinedTextField(
                            value = customCategoryName.value,
                            onValueChange = { customCategoryName.value = it },
                            label = { Text("Category Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val name = customCategoryName.value.trim()
                                if (name.isNotEmpty()) {
                                    viewModel.onCategoryChange(name)
                                    showCustomCategoryDialog.value = false
                                }
                            }
                        ) { Text("Add") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCustomCategoryDialog.value = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Frequency
            SectionLabel("Frequency")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val periods = listOf(
                    "daily" to "Daily",
                    "weekly" to "Weekly",
                    "fortnightly" to "Fortnightly",
                    "monthly" to "Monthly",
                    "bimonthly" to "Bimonthly",
                    "quarterly" to "Quarterly"
                )
                periods.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = viewModel.frequencyPeriod == value,
                        onClick = { viewModel.onFrequencyPeriodChange(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = periods.size)
                    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                }
            }

            // Target count
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val targetLabel = when (viewModel.frequencyPeriod) {
                    "daily" -> "Target per day:"
                    "weekly" -> "Target per week:"
                    "fortnightly" -> "Target per fortnight:"
                    "monthly" -> "Target per month:"
                    "bimonthly" -> "Target per 2 months:"
                    "quarterly" -> "Target per quarter:"
                    else -> "Target:"
                }
                val maxTarget = when (viewModel.frequencyPeriod) {
                    "daily" -> 10
                    "weekly" -> 7
                    "fortnightly" -> 14
                    "monthly" -> 30
                    "bimonthly" -> 60
                    "quarterly" -> 90
                    else -> 10
                }
                Text(
                    text = targetLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { viewModel.onTargetFrequencyChange(viewModel.targetFrequency - 1) },
                    enabled = viewModel.targetFrequency > 1
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease")
                }
                Text(
                    text = "${viewModel.targetFrequency}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = { viewModel.onTargetFrequencyChange(viewModel.targetFrequency + 1) },
                    enabled = viewModel.targetFrequency < maxTarget
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Increase")
                }
            }

            // Active days (weekly only)
            if (viewModel.frequencyPeriod == "weekly") {
                SectionLabel("Active Days")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    dayLabels.forEachIndexed { index, label ->
                        val dayValue = dayValues[index]
                        val isActive = dayValue in viewModel.activeDays
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .then(
                                    if (isActive) {
                                        Modifier.background(MaterialTheme.colorScheme.primary)
                                    } else {
                                        Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    }
                                ).clickable { viewModel.onToggleActiveDay(dayValue) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }

            // Reminder
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Daily Reminder",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = viewModel.reminderEnabled,
                    onCheckedChange = viewModel::onReminderEnabledChange
                )
            }

            if (viewModel.reminderEnabled) {
                val showTimePicker = remember { mutableStateOf(false) }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showTimePicker.value = true }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "Reminder time:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = String.format("%02d:%02d", viewModel.reminderHour, viewModel.reminderMinute),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (showTimePicker.value) {
                    val timePickerState = rememberTimePickerState(
                        initialHour = viewModel.reminderHour,
                        initialMinute = viewModel.reminderMinute,
                        is24Hour = false
                    )
                    AlertDialog(
                        onDismissRequest = { showTimePicker.value = false },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.onReminderHourChange(timePickerState.hour)
                                viewModel.onReminderMinuteChange(timePickerState.minute)
                                showTimePicker.value = false
                            }) { Text("OK") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showTimePicker.value = false }) { Text("Cancel") }
                        },
                        text = { TimePicker(state = timePickerState) }
                    )
                }
            }

            // Medication reminder interval
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Repeat reminder after logging",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Reminds you after a set interval",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.medicationReminderEnabled,
                    onCheckedChange = viewModel::onMedicationReminderEnabledChange
                )
            }

            if (viewModel.medicationReminderEnabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Interval:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.onMedicationReminderIntervalChange(viewModel.medicationReminderIntervalIndex - 1) },
                        enabled = viewModel.medicationReminderIntervalIndex > 1
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease")
                    }
                    Text(
                        text = formatMedInterval(viewModel.medicationReminderIntervalIndex),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { viewModel.onMedicationReminderIntervalChange(viewModel.medicationReminderIntervalIndex + 1) },
                        enabled = viewModel.medicationReminderIntervalIndex < 48
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase")
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Times per day:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.onMedicationTimesPerDayChange(viewModel.medicationTimesPerDay - 1) },
                        enabled = viewModel.medicationTimesPerDay > 1
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease")
                    }
                    Text(
                        text = "${viewModel.medicationTimesPerDay}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { viewModel.onMedicationTimesPerDayChange(viewModel.medicationTimesPerDay + 1) },
                        enabled = viewModel.medicationTimesPerDay < 10
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase")
                    }
                }
            }

            // Show streak
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show Streak",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Display streak count on this habit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.showStreak,
                    onCheckedChange = viewModel::onShowStreakChange
                )
            }

            // Create daily to-do
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Create Daily To-Do",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Add this habit as a task on Today on each scheduled day. Completing either marks both done.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.createDailyTask,
                    onCheckedChange = viewModel::onCreateDailyTaskChange
                )
            }

            // Enable logging
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable Logging",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Add notes when completing, view past logs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.hasLogging,
                    onCheckedChange = viewModel::onHasLoggingChange
                )
            }

            // Recurring habit tracking toggles: booking + previous period
            if (viewModel.frequencyPeriod != "daily") {
                val periodNoun = when (viewModel.frequencyPeriod) {
                    "weekly" -> "week"
                    "fortnightly" -> "fortnight"
                    "monthly" -> "month"
                    "bimonthly" -> "2 months"
                    "quarterly" -> "quarter"
                    else -> "period"
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Track Booking",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Show whether this habit is booked this $periodNoun (a task is linked to this habit)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.trackBooking,
                        onCheckedChange = viewModel::onTrackBookingChange
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Track Previous Period",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Show whether this habit was completed last $periodNoun",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.trackPreviousPeriod,
                        onCheckedChange = viewModel::onTrackPreviousPeriodChange
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "This Is a Bookable Activity",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "For things like haircuts, dentist visits, and oil changes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.isBookable,
                        onCheckedChange = viewModel::onIsBookableChange
                    )
                }
            }

            // Reminder delay override
            SectionLabel("Reminder Settings")

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Custom Reminder Delay",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (viewModel.nagSuppressionOverrideEnabled) {
                            "Override global setting for this habit"
                        } else {
                            "Using global setting (${viewModel.globalSuppressionDays} days)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.nagSuppressionOverrideEnabled,
                    onCheckedChange = viewModel::onNagSuppressionOverrideEnabledChange
                )
            }

            if (viewModel.nagSuppressionOverrideEnabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp)
                ) {
                    Checkbox(
                        checked = viewModel.nagSuppressionDisableForHabit,
                        onCheckedChange = viewModel::onNagSuppressionDisableForHabitChange
                    )
                    Text(
                        text = "Disable Suppression for This Habit",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                if (!viewModel.nagSuppressionDisableForHabit) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp)
                    ) {
                        Text(
                            text = "Suppress if scheduled within",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${viewModel.nagSuppressionDaysOverride.coerceAtLeast(1)} days",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = viewModel.nagSuppressionDaysOverride.coerceAtLeast(1).toFloat(),
                        onValueChange = { viewModel.onNagSuppressionDaysOverrideChange(it.toInt()) },
                        valueRange = 1f..30f,
                        steps = 28,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                    )
                }
            }

            SectionLabel("Today Page Visibility")

            // "Skip on Today after recent completion" override
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hide After Completion",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (viewModel.todaySkipAfterCompleteOverrideEnabled) {
                            "Override global setting for this habit"
                        } else if (viewModel.globalSkipAfterCompleteDays > 0) {
                            "Using global setting (${viewModel.globalSkipAfterCompleteDays} days)"
                        } else {
                            "Global setting: disabled"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.todaySkipAfterCompleteOverrideEnabled,
                    onCheckedChange = viewModel::onTodaySkipAfterCompleteOverrideEnabledChange
                )
            }

            if (viewModel.todaySkipAfterCompleteOverrideEnabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp)
                ) {
                    Text(
                        text = "Hide on Today for",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (viewModel.todaySkipAfterCompleteDays == 0) {
                            "Disabled for this habit"
                        } else {
                            "${viewModel.todaySkipAfterCompleteDays} days"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = viewModel.todaySkipAfterCompleteDays.toFloat(),
                    onValueChange = { viewModel.onTodaySkipAfterCompleteDaysChange(it.toInt()) },
                    valueRange = 0f..30f,
                    steps = 29,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )
            }

            // "Skip on Today before next scheduled occurrence" override
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hide Before Next Schedule",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (viewModel.todaySkipBeforeScheduleOverrideEnabled) {
                            "Override global setting for this habit"
                        } else if (viewModel.globalSkipBeforeScheduleDays > 0) {
                            "Using global setting (${viewModel.globalSkipBeforeScheduleDays} days)"
                        } else {
                            "Global setting: disabled"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.todaySkipBeforeScheduleOverrideEnabled,
                    onCheckedChange = viewModel::onTodaySkipBeforeScheduleOverrideEnabledChange
                )
            }

            if (viewModel.todaySkipBeforeScheduleOverrideEnabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp)
                ) {
                    Text(
                        text = "Hide if next occurrence within",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (viewModel.todaySkipBeforeScheduleDays == 0) {
                            "Disabled for this habit"
                        } else {
                            "${viewModel.todaySkipBeforeScheduleDays} days"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = viewModel.todaySkipBeforeScheduleDays.toFloat(),
                    onValueChange = { viewModel.onTodaySkipBeforeScheduleDaysChange(it.toInt()) },
                    valueRange = 0f..30f,
                    steps = 29,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        if (viewModel.saveHabit()) navController.popBackStack()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = if (viewModel.isEditMode) "Update Habit" else "Save Habit",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun ColorCircle(hex: String, selected: Boolean, onClick: () -> Unit) {
    val color = try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        Color.Gray
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier
                }
            ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun formatMedInterval(index: Int): String {
    val halfHours = index
    val hours = halfHours / 2
    val hasHalf = halfHours % 2 != 0
    return when {
        hours == 0 -> "30m"
        !hasHalf -> "${hours}h"
        else -> "$hours.5h"
    }
}

@Composable
private fun IconOption(emoji: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (selected) {
                    Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                }
            ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = 22.sp,
            textAlign = TextAlign.Center
        )
    }
}
