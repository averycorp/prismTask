package com.averycorp.prismtask.ui.screens.leisure

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.averycorp.prismtask.domain.model.LeisureCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogPastLeisureSheet(
    onDismiss: () -> Unit,
    viewModel: LeisurePoolViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()

    var selectedActivityId by remember { mutableStateOf<Long?>(null) }
    var freeText by remember { mutableStateOf("") }
    val defaultCategory = state.settings.enabledCategories.firstOrNull()
        ?: LeisureCategory.PHYSICAL
    var category by remember(defaultCategory) { mutableStateOf(defaultCategory) }
    var durationStr by remember { mutableStateOf("30") }
    var pickerOpen by remember { mutableStateOf(false) }
    var categoryMenuOpen by remember { mutableStateOf(false) }

    val selectedActivity = state.activities.firstOrNull { it.id == selectedActivityId }
    val selectableCategories = LeisureCategory.values()
        .filter { it in state.settings.enabledCategories || it == category }
        .ifEmpty { LeisureCategory.values().toList() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Log past leisure",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Box {
                val selectedCategory = selectedActivity
                    ?.let { LeisureCategory.fromStringOrNull(it.category) }
                OutlinedButton(
                    onClick = { pickerOpen = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            selectedActivity != null && selectedCategory != null ->
                                "${selectedCategory.emoji} ${selectedActivity.name}"
                            selectedActivity != null -> selectedActivity.name
                            freeText.isNotBlank() -> "Free text: $freeText"
                            else -> "Pick an activity…"
                        }
                    )
                }
                DropdownMenu(
                    expanded = pickerOpen,
                    onDismissRequest = { pickerOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("✍️ Free text…") },
                        onClick = {
                            selectedActivityId = null
                            pickerOpen = false
                        }
                    )
                    val grouped = state.activities
                        .filter { it.enabled }
                        .groupBy { LeisureCategory.fromStringOrNull(it.category) }
                    LeisureCategory.values()
                        .filter { it in state.settings.enabledCategories }
                        .forEach { cat ->
                        val items = grouped[cat].orEmpty()
                        if (items.isEmpty()) return@forEach
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${cat.emoji} ${cat.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            enabled = false,
                            onClick = {}
                        )
                        items.forEach { activity ->
                            DropdownMenuItem(
                                text = {
                                    val durationSuffix = activity
                                        .defaultDurationMinutes
                                        ?.let { " • $it min" }.orEmpty()
                                    Text("${activity.name}$durationSuffix")
                                },
                                onClick = {
                                    selectedActivityId = activity.id
                                    category = cat
                                    activity.defaultDurationMinutes?.let {
                                        durationStr = it.toString()
                                    }
                                    pickerOpen = false
                                }
                            )
                        }
                    }
                }
            }

            if (selectedActivityId == null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = freeText,
                    onValueChange = { freeText = it },
                    label = { Text("Activity name") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Category", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(4.dp))
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
                    selectableCategories.forEach { c ->
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
                label = { Text("Duration (min)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    val duration = durationStr.toIntOrNull() ?: 0
                    if (duration < 1) return@Button
                    viewModel.logManualSession(
                        activityId = selectedActivityId,
                        freeTextName = if (selectedActivityId == null) freeText else null,
                        category = category,
                        durationMinutes = duration
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
        }
    }
}
