package com.averycorp.prismtask.ui.screens.leisure.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.preferences.CustomLeisureSection
import com.averycorp.prismtask.data.preferences.LeisurePreferences
import com.averycorp.prismtask.data.preferences.LeisureSlotId
import com.averycorp.prismtask.ui.screens.leisure.components.AddActivityDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeisureSettingsScreen(
    navController: NavController,
    viewModel: LeisureSettingsViewModel = hiltViewModel()
) {
    val musicState by viewModel.musicState.collectAsStateWithLifecycle()
    val flexState by viewModel.flexState.collectAsStateWithLifecycle()
    val languageState by viewModel.languageState.collectAsStateWithLifecycle()
    val customSections by viewModel.customSections.collectAsStateWithLifecycle()

    var addDialogSlot by remember { mutableStateOf<LeisureSlotId?>(null) }
    var resetConfirm by remember { mutableStateOf<LeisureSlotId?>(null) }
    var addActivityForCustomSection by remember { mutableStateOf<String?>(null) }
    var removeCustomSectionConfirm by remember { mutableStateOf<CustomLeisureSection?>(null) }
    var showAddSectionDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Customize Leisure") },
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
            Spacer(Modifier.height(8.dp))
            Text(
                "Music, flexible, and language practice are the built-in leisure sections, " +
                    "fully customizable below. Add your own sections for anything else you " +
                    "want to rotate through each day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            SlotEditor(
                state = musicState,
                viewModel = viewModel,
                onRequestAdd = { addDialogSlot = LeisureSlotId.MUSIC },
                onRequestReset = { resetConfirm = LeisureSlotId.MUSIC }
            )

            Spacer(Modifier.height(24.dp))

            SlotEditor(
                state = flexState,
                viewModel = viewModel,
                onRequestAdd = { addDialogSlot = LeisureSlotId.FLEX },
                onRequestReset = { resetConfirm = LeisureSlotId.FLEX }
            )

            Spacer(Modifier.height(24.dp))

            SlotEditor(
                state = languageState,
                viewModel = viewModel,
                onRequestAdd = { addDialogSlot = LeisureSlotId.LANGUAGE },
                onRequestReset = { resetConfirm = LeisureSlotId.LANGUAGE }
            )

            Spacer(Modifier.height(24.dp))

            CustomSectionsBlock(
                sections = customSections,
                viewModel = viewModel,
                onRequestAddActivity = { sectionId -> addActivityForCustomSection = sectionId },
                onRequestRemove = { section -> removeCustomSectionConfirm = section },
                onRequestAddSection = { showAddSectionDialog = true }
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    addDialogSlot?.let { slot ->
        AddActivityDialog(
            category = when (slot) {
                LeisureSlotId.MUSIC -> musicState.config.label
                LeisureSlotId.FLEX -> flexState.config.label
                LeisureSlotId.LANGUAGE -> languageState.config.label
            },
            onDismiss = { addDialogSlot = null },
            onConfirm = { label, icon ->
                viewModel.addCustomActivity(slot, label, icon)
                addDialogSlot = null
            }
        )
    }

    resetConfirm?.let { slot ->
        AlertDialog(
            onDismissRequest = { resetConfirm = null },
            title = { Text("Reset section?") },
            text = { Text("Restore all defaults for this section. Custom activities are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetSlot(slot)
                    resetConfirm = null
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirm = null }) { Text("Cancel") }
            }
        )
    }

    addActivityForCustomSection?.let { sectionId ->
        val section = customSections.firstOrNull { it.id == sectionId }
        AddActivityDialog(
            category = section?.label ?: "Section",
            onDismiss = { addActivityForCustomSection = null },
            onConfirm = { label, icon ->
                viewModel.addCustomSectionActivity(sectionId, label, icon)
                addActivityForCustomSection = null
            }
        )
    }

    if (showAddSectionDialog) {
        AddSectionDialog(
            onDismiss = { showAddSectionDialog = false },
            onConfirm = { label, emoji ->
                viewModel.addCustomSection(label, emoji)
                showAddSectionDialog = false
            }
        )
    }

    removeCustomSectionConfirm?.let { section ->
        AlertDialog(
            onDismissRequest = { removeCustomSectionConfirm = null },
            title = { Text("Remove Section?") },
            text = { Text("\"${section.label}\" and its activities will be deleted. Past logs are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeCustomSection(section.id)
                    removeCustomSectionConfirm = null
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removeCustomSectionConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CustomSectionsBlock(
    sections: List<CustomLeisureSection>,
    viewModel: LeisureSettingsViewModel,
    onRequestAddActivity: (String) -> Unit,
    onRequestRemove: (CustomLeisureSection) -> Unit,
    onRequestAddSection: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Custom Sections",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = onRequestAddSection) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add Section")
            }
        }

        if (sections.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Add sections like Reading, Movement, or Social to round out your daily leisure.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(Modifier.height(8.dp))
            sections.forEachIndexed { index, section ->
                CustomSectionEditor(
                    section = section,
                    viewModel = viewModel,
                    onRequestAddActivity = { onRequestAddActivity(section.id) },
                    onRequestRemove = { onRequestRemove(section) }
                )
                if (index != sections.lastIndex) Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CustomSectionEditor(
    section: CustomLeisureSection,
    viewModel: LeisureSettingsViewModel,
    onRequestAddActivity: () -> Unit,
    onRequestRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        section.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Custom section",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = section.enabled,
                    onCheckedChange = { viewModel.setCustomSectionEnabled(section.id, it) }
                )
            }

            Spacer(Modifier.height(12.dp))

            LabelEmojiRow(
                label = section.label,
                emoji = section.emoji,
                onLabelChange = { viewModel.setCustomSectionLabel(section.id, it) },
                onEmojiChange = { viewModel.setCustomSectionEmoji(section.id, it) }
            )

            Spacer(Modifier.height(12.dp))

            StepperRow(
                title = "Duration",
                value = section.durationMinutes,
                min = LeisurePreferences.MIN_DURATION_MINUTES,
                max = LeisurePreferences.MAX_DURATION_MINUTES,
                step = 5,
                valueLabel = "${section.durationMinutes} min",
                onChange = { viewModel.setCustomSectionDuration(section.id, it) }
            )

            Spacer(Modifier.height(8.dp))

            StepperRow(
                title = "Grid columns",
                value = section.gridColumns,
                min = LeisurePreferences.MIN_GRID_COLUMNS,
                max = LeisurePreferences.MAX_GRID_COLUMNS,
                step = 1,
                valueLabel = "${section.gridColumns}",
                onChange = { viewModel.setCustomSectionColumns(section.id, it) }
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-complete when timer hits duration", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Off lets you finish manually whenever you want.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = section.autoComplete,
                    onCheckedChange = { viewModel.setCustomSectionAutoComplete(section.id, it) }
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Add as Daily Todo", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Spawns a recurring daily task in your task list for this category.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = section.createDailyTask,
                    onCheckedChange = { viewModel.setCustomSectionCreateDailyTask(section.id, it) }
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text(
                "Activities",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            if (section.customActivities.isEmpty()) {
                Text(
                    "No activities yet. Add at least one so this section can be picked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                section.customActivities.forEach { activity ->
                    ActivityRow(
                        icon = activity.icon,
                        label = activity.label,
                        subtitle = "Custom",
                        struck = false,
                        trailing = {
                            IconButton(onClick = {
                                viewModel.removeCustomSectionActivity(section.id, activity.id)
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove ${activity.label}")
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onRequestAddActivity) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add Activity")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRequestRemove) {
                    Text("Remove Section", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSectionDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, emoji: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Leisure Section") },
        text = {
            Column {
                Text(
                    "Create a new section to track activities like reading, movement, or social time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = emoji,
                        onValueChange = { if (it.length <= 2) emoji = it },
                        label = { Text("Icon") },
                        singleLine = true,
                        modifier = Modifier.width(96.dp)
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Section Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), emoji.trim()) }
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SlotEditor(
    state: LeisureSlotEditState,
    viewModel: LeisureSettingsViewModel,
    onRequestAdd: () -> Unit,
    onRequestReset: () -> Unit
) {
    val slot = state.slot
    val config = state.config

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        config.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when (slot) {
                            LeisureSlotId.MUSIC -> "Music / practice"
                            LeisureSlotId.FLEX -> "Flexible leisure"
                            LeisureSlotId.LANGUAGE -> "Language practice"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { viewModel.setEnabled(slot, it) }
                )
            }

            Spacer(Modifier.height(12.dp))

            LabelEmojiRow(
                label = config.label,
                emoji = config.emoji,
                onLabelChange = { viewModel.setLabel(slot, it) },
                onEmojiChange = { viewModel.setEmoji(slot, it) }
            )

            Spacer(Modifier.height(12.dp))

            StepperRow(
                title = "Duration",
                value = config.durationMinutes,
                min = LeisurePreferences.MIN_DURATION_MINUTES,
                max = LeisurePreferences.MAX_DURATION_MINUTES,
                step = 5,
                valueLabel = "${config.durationMinutes} min",
                onChange = { viewModel.setDurationMinutes(slot, it) }
            )

            Spacer(Modifier.height(8.dp))

            StepperRow(
                title = "Grid columns",
                value = config.gridColumns,
                min = LeisurePreferences.MIN_GRID_COLUMNS,
                max = LeisurePreferences.MAX_GRID_COLUMNS,
                step = 1,
                valueLabel = "${config.gridColumns}",
                onChange = { viewModel.setGridColumns(slot, it) }
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-complete when timer hits duration", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Off lets you finish manually whenever you want.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.autoComplete,
                    onCheckedChange = { viewModel.setAutoComplete(slot, it) }
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Add as Daily Todo", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Spawns a recurring daily task in your task list for this category.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.createDailyTask,
                    onCheckedChange = { viewModel.setSlotCreateDailyTask(slot, it) }
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text(
                "Activities",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            state.builtIns.forEach { builtIn ->
                ActivityRow(
                    icon = builtIn.option.icon,
                    label = builtIn.option.label,
                    subtitle = if (builtIn.hidden) "Hidden" else "Built-in",
                    struck = builtIn.hidden,
                    trailing = {
                        Switch(
                            checked = !builtIn.hidden,
                            onCheckedChange = { checked ->
                                viewModel.setBuiltInHidden(slot, builtIn.option.id, !checked)
                            }
                        )
                    }
                )
            }

            config.customActivities.forEach { custom ->
                ActivityRow(
                    icon = custom.icon,
                    label = custom.label,
                    subtitle = "Custom",
                    struck = false,
                    trailing = {
                        IconButton(onClick = { viewModel.removeCustomActivity(slot, custom.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove ${custom.label}")
                        }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onRequestAdd) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add Activity")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRequestReset) {
                    Text("Reset Section")
                }
            }
        }
    }
}

@Composable
private fun LabelEmojiRow(
    label: String,
    emoji: String,
    onLabelChange: (String) -> Unit,
    onEmojiChange: (String) -> Unit
) {
    var localLabel by remember(label) { mutableStateOf(label) }
    var localEmoji by remember(emoji) { mutableStateOf(emoji) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = localEmoji,
            onValueChange = {
                if (it.length <= 2) {
                    localEmoji = it
                    if (it.isNotBlank()) onEmojiChange(it)
                }
            },
            label = { Text("Icon") },
            singleLine = true,
            modifier = Modifier.width(96.dp)
        )
        OutlinedTextField(
            value = localLabel,
            onValueChange = {
                localLabel = it
                if (it.isNotBlank()) onLabelChange(it)
            },
            label = { Text("Section Name") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StepperRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    valueLabel: String,
    onChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val snapped = ((raw / step).toInt() * step).coerceIn(min, max)
                if (snapped != value) onChange(snapped)
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = if (step > 0) ((max - min) / step - 1).coerceAtLeast(0) else 0
        )
    }
}

@Composable
private fun ActivityRow(
    icon: String,
    label: String,
    subtitle: String,
    struck: Boolean,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, modifier = Modifier.width(32.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (struck) TextDecoration.LineThrough else TextDecoration.None,
                color = if (struck) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing()
    }
}
