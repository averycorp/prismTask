package com.averycorp.prismtask.ui.screens.leisure

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import com.averycorp.prismtask.data.preferences.LeisureBudgetPreferences
import com.averycorp.prismtask.domain.model.LeisureCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeisurePoolScreen(
    navController: NavController,
    viewModel: LeisurePoolViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Leisure Budget", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add activity")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { BudgetTargetCard(viewModel = viewModel, snapshot = state.settings) }
            item { CategoryToggleCard(viewModel = viewModel, snapshot = state.settings) }
            item { RefreshLimitCard(viewModel = viewModel, snapshot = state.settings) }
            item {
                Text(
                    "Activity Pool",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            LeisureCategory.values().forEach { category ->
                val activitiesForCategory = state.activitiesByCategory[category].orEmpty()
                item {
                    CategorySectionHeader(category, activitiesForCategory.size)
                }
                items(activitiesForCategory, key = { it.id }) { activity ->
                    ActivityRow(activity = activity, viewModel = viewModel)
                }
            }
            if (state.activities.isEmpty()) {
                item {
                    EmptyPoolHint()
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
                Text("Different target on weekends", modifier = Modifier.weight(1f))
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
private fun CategoryToggleCard(
    viewModel: LeisurePoolViewModel,
    snapshot: com.averycorp.prismtask.data.preferences.LeisureBudgetSnapshot
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Categories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Pick which categories count toward your budget.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LeisureCategory.values().forEach { category ->
                    val selected = category in snapshot.enabledCategories
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = if (selected) {
                                snapshot.enabledCategories - category
                            } else {
                                snapshot.enabledCategories + category
                            }
                            if (next.isNotEmpty()) viewModel.setEnabledCategories(next)
                        },
                        label = { Text("${category.emoji} ${category.label}") }
                    )
                }
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
private fun CategorySectionHeader(category: LeisureCategory, count: Int) {
    Row(
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${category.emoji} ${category.label}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "($count)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActivityRow(
    activity: LeisureActivityEntity,
    viewModel: LeisurePoolViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(activity.name, style = MaterialTheme.typography.bodyLarge)
                activity.defaultDurationMinutes?.let { duration ->
                    Text(
                        "$duration min default",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = activity.enabled,
                onCheckedChange = { viewModel.setActivityEnabled(activity, it) }
            )
            IconButton(onClick = { viewModel.deleteActivity(activity) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete activity")
            }
        }
    }
}

@Composable
private fun EmptyPoolHint() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "No activities yet",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Tap + to add your first leisure activity. Try a quick walk, " +
                    "calling a friend, or 20 minutes of piano.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
