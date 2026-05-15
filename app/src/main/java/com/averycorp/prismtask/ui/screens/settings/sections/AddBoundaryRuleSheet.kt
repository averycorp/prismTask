package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.domain.model.BoundaryRule
import com.averycorp.prismtask.domain.model.BoundaryRuleType
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.usecase.BoundaryRuleParser
import com.averycorp.prismtask.ui.components.AnalogClockPicker
import com.averycorp.prismtask.ui.components.rememberAnalogClockState
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Bottom sheet for creating or editing a [BoundaryRule]. Replaces the simple
 * NLP-only dialog previously launched from [BoundariesSection].
 *
 * Two tabs:
 *  - "Quick Add" — free-text input routed through [BoundaryRuleParser]. On a
 *    successful parse the form is pre-filled and the sheet switches to the
 *    Manual tab so the user can review before saving. On parse failure the
 *    user is nudged toward the Manual tab.
 *  - "Manual" — a structured form covering every field on [BoundaryRule] with
 *    inline validation (min-3-char name, start != end, at least one day).
 *
 * Passing [existingRule] switches the sheet into edit mode: the form is
 * pre-populated and the save button reads "Update" instead of "Add".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBoundaryRuleSheet(
    existingRule: BoundaryRule? = null,
    onDismiss: () -> Unit,
    onSave: (BoundaryRule) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isEdit = existingRule != null

    // Tab state: start on Manual when editing, Quick Add when adding.
    var selectedTab by remember { mutableStateOf(if (isEdit) 1 else 0) }

    // ----- Manual-tab form state -----
    var name by remember { mutableStateOf(existingRule?.name.orEmpty()) }
    var ruleType by remember {
        mutableStateOf(existingRule?.ruleType ?: BoundaryRuleType.BLOCK_CATEGORY)
    }
    var category by remember {
        mutableStateOf(existingRule?.category ?: LifeCategory.WORK)
    }
    var startTime by remember {
        mutableStateOf(existingRule?.startTime ?: LocalTime.of(19, 0))
    }
    var endTime by remember {
        mutableStateOf(existingRule?.endTime ?: LocalTime.of(22, 0))
    }
    var activeDays by remember {
        mutableStateOf(existingRule?.activeDays ?: BoundaryRule.WEEKDAYS)
    }
    var isEnabled by remember { mutableStateOf(existingRule?.isEnabled ?: true) }

    // ----- Quick-add tab state -----
    var quickAddText by remember { mutableStateOf("") }
    var quickAddError by remember { mutableStateOf<String?>(null) }

    // ----- Time picker dialogs -----
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    // ----- Validation -----
    val nameError = when {
        name.isBlank() -> null // don't show until user types
        name.trim().length < 3 -> "Name must be at least 3 characters"
        else -> null
    }
    val timeError = if (startTime == endTime) "Start and end times must differ" else null
    val daysError = if (activeDays.isEmpty()) "Select at least one day" else null
    val isValid = name.trim().length >= 3 &&
        startTime != endTime &&
        activeDays.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = if (isEdit) "Edit Boundary Rule" else "Add Boundary Rule",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Quick Add") },
                    enabled = !isEdit
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Manual") }
                )
            }

            Spacer(Modifier.height(16.dp))

            if (selectedTab == 0) {
                QuickAddTab(
                    text = quickAddText,
                    onTextChange = {
                        quickAddText = it
                        quickAddError = null
                    },
                    error = quickAddError,
                    onParse = {
                        val parsed = BoundaryRuleParser.parse(quickAddText)
                        if (parsed == null) {
                            quickAddError = "Couldn't understand that. Try the Manual tab."
                        } else {
                            // Pre-fill manual fields and switch tabs.
                            name = parsed.name
                            ruleType = parsed.ruleType
                            category = parsed.category
                            startTime = parsed.startTime
                            endTime = parsed.endTime
                            activeDays = parsed.activeDays
                            isEnabled = parsed.isEnabled
                            quickAddError = null
                            selectedTab = 1
                        }
                    }
                )
            } else {
                ManualEditorTab(
                    name = name,
                    onNameChange = { name = it },
                    nameError = nameError,
                    ruleType = ruleType,
                    onRuleTypeChange = { ruleType = it },
                    category = category,
                    onCategoryChange = { category = it },
                    startTime = startTime,
                    endTime = endTime,
                    onStartTimeClick = { showStartPicker = true },
                    onEndTimeClick = { showEndPicker = true },
                    timeError = timeError,
                    activeDays = activeDays,
                    onActiveDaysChange = { activeDays = it },
                    daysError = daysError,
                    isEnabled = isEnabled,
                    onEnabledChange = { isEnabled = it }
                )

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val rule = BoundaryRule(
                                id = existingRule?.id ?: 0L,
                                name = name.trim(),
                                ruleType = ruleType,
                                category = category,
                                startTime = startTime,
                                endTime = endTime,
                                activeDays = activeDays,
                                isEnabled = isEnabled
                            )
                            onSave(rule)
                        },
                        enabled = isValid
                    ) {
                        Text(if (isEdit) "Update" else "Add")
                    }
                }
            }
        }
    }

    if (showStartPicker) {
        BoundaryTimePickerDialog(
            initial = startTime,
            onDismiss = { showStartPicker = false },
            onConfirm = {
                startTime = it
                showStartPicker = false
            }
        )
    }
    if (showEndPicker) {
        BoundaryTimePickerDialog(
            initial = endTime,
            onDismiss = { showEndPicker = false },
            onConfirm = {
                endTime = it
                showEndPicker = false
            }
        )
    }
}

@Composable
private fun QuickAddTab(
    text: String,
    onTextChange: (String) -> Unit,
    error: String?,
    onParse: () -> Unit
) {
    Column {
        Text(
            "Describe a rule in plain English, e.g. \"No work after 7pm on weekdays\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Rule Description") },
            isError = error != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onParse,
                enabled = text.isNotBlank()
            ) { Text("Parse") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualEditorTab(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    ruleType: BoundaryRuleType,
    onRuleTypeChange: (BoundaryRuleType) -> Unit,
    category: LifeCategory,
    onCategoryChange: (LifeCategory) -> Unit,
    startTime: LocalTime,
    endTime: LocalTime,
    onStartTimeClick: () -> Unit,
    onEndTimeClick: () -> Unit,
    timeError: String?,
    activeDays: Set<DayOfWeek>,
    onActiveDaysChange: (Set<DayOfWeek>) -> Unit,
    daysError: String?,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Column {
        // Name
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Rule Name") },
            placeholder = { Text("e.g. Evening Wind-Down") },
            isError = nameError != null,
            singleLine = true,
            supportingText = nameError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        // Rule type (segmented button)
        FieldLabel("Rule Type")
        val ruleTypes = listOf(
            BoundaryRuleType.BLOCK_CATEGORY to "Block",
            BoundaryRuleType.SUGGEST_CATEGORY to "Suggest",
            BoundaryRuleType.REMIND to "Remind"
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ruleTypes.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = ruleType == value,
                    onClick = { onRuleTypeChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ruleTypes.size)
                ) { Text(label) }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Life category (chip row)
        FieldLabel("Life Category")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LifeCategory.TRACKED.forEach { cat ->
                FilterChip(
                    selected = category == cat,
                    onClick = { onCategoryChange(cat) },
                    label = { Text(LifeCategory.label(cat)) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Time window
        FieldLabel("Time Window")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimeField(
                label = "Start",
                time = startTime,
                onClick = onStartTimeClick,
                modifier = Modifier.weight(1f)
            )
            TimeField(
                label = "End",
                time = endTime,
                onClick = onEndTimeClick,
                modifier = Modifier.weight(1f)
            )
        }
        if (timeError != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = timeError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall
            )
        }

        Spacer(Modifier.height(16.dp))

        // Active days
        FieldLabel("Active Days")
        DayOfWeekChipRow(
            activeDays = activeDays,
            onChange = onActiveDaysChange
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onActiveDaysChange(BoundaryRule.WEEKDAYS) }) { Text("Weekdays") }
            TextButton(onClick = { onActiveDaysChange(BoundaryRule.WEEKEND) }) { Text("Weekends") }
            TextButton(onClick = { onActiveDaysChange(BoundaryRule.ALL_DAYS) }) { Text("Every Day") }
        }
        if (daysError != null) {
            Text(
                text = daysError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall
            )
        }

        Spacer(Modifier.height(16.dp))

        // Enabled toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enabled", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Rule is active when enabled",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun TimeField(
    label: String,
    time: LocalTime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 12.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = BoundaryRule.formatTime(time),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun DayOfWeekChipRow(
    activeDays: Set<DayOfWeek>,
    onChange: (Set<DayOfWeek>) -> Unit
) {
    // Single-letter labels, Monday-first ordering.
    val days = listOf(
        DayOfWeek.MONDAY to "M",
        DayOfWeek.TUESDAY to "T",
        DayOfWeek.WEDNESDAY to "W",
        DayOfWeek.THURSDAY to "T",
        DayOfWeek.FRIDAY to "F",
        DayOfWeek.SATURDAY to "S",
        DayOfWeek.SUNDAY to "S"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        days.forEach { (day, label) ->
            val selected = day in activeDays
            FilterChip(
                selected = selected,
                onClick = {
                    val next = if (selected) activeDays - day else activeDays + day
                    onChange(next)
                },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BoundaryTimePickerDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit
) {
    val clockState = rememberAnalogClockState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = false
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(LocalTime.of(clockState.hour, clockState.minute))
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        text = { AnalogClockPicker(state = clockState) }
    )
}
