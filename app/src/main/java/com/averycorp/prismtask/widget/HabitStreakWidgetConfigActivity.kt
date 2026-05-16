package com.averycorp.prismtask.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.PrismTaskTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-instance config activity for [HabitStreakWidget]. Lets the user pick
 * which active habits the widget should track, cap the visible count
 * (1..12), toggle the streak-count display, and switch between the default
 * 2-column card layout and a denser single-column row layout.
 *
 * Selection is persisted to [WidgetConfigDataStore.HabitStreakConfig]
 * keyed on `appWidgetId`. Backing out cancels and the widget is removed by
 * the system, mirroring [ProjectWidgetConfigActivity]'s contract.
 *
 * Empty-selection behaviour is "show all active habits, capped to
 * `maxItems`" — see [HabitStreakWidget.applyConfig]. This keeps the widget
 * useful on first placement before the user touches the picker.
 */
@AndroidEntryPoint
class HabitStreakWidgetConfigActivity : ComponentActivity() {

    @Inject
    lateinit var database: PrismTaskDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val habitsState = MutableStateFlow<List<HabitEntity>>(emptyList())
        val initialConfigState = MutableStateFlow<WidgetConfigDataStore.HabitStreakConfig?>(null)
        lifecycleScope.launch {
            habitsState.value = database.habitDao().getActiveHabitsOnce()
            initialConfigState.value =
                WidgetConfigDataStore.snapshotHabitStreakConfig(this@HabitStreakWidgetConfigActivity, appWidgetId)
        }

        setContent {
            PrismTaskTheme {
                HabitStreakPicker(
                    habitsFlow = habitsState,
                    initialConfigFlow = initialConfigState,
                    onCancel = { finish() },
                    onConfirm = { config ->
                        lifecycleScope.launch {
                            WidgetConfigDataStore.setHabitStreakConfig(
                                this@HabitStreakWidgetConfigActivity,
                                appWidgetId,
                                config
                            )
                            runCatching {
                                HabitStreakWidget().updateAll(this@HabitStreakWidgetConfigActivity)
                            }
                            setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            )
                            finish()
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitStreakPicker(
    habitsFlow: StateFlow<List<HabitEntity>>,
    initialConfigFlow: StateFlow<WidgetConfigDataStore.HabitStreakConfig?>,
    onCancel: () -> Unit,
    onConfirm: (WidgetConfigDataStore.HabitStreakConfig) -> Unit
) {
    val prismColors = LocalPrismColors.current
    val habits by habitsFlow.collectAsState()
    val initial by initialConfigFlow.collectAsState()

    // Seed local state from the persisted config the first time it loads.
    // The `LaunchedEffect(initial)` body only re-runs when `initial` itself
    // changes reference — and we only assign a non-null value once in the
    // Activity's lifecycle scope — so the user's local edits are safe from
    // being clobbered by a stale recomposition.
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var maxItems by remember { mutableStateOf(6) }
    var showStreakCount by remember { mutableStateOf(true) }
    var layoutGrid by remember { mutableStateOf(false) }
    LaunchedEffect(initial) {
        val c = initial ?: return@LaunchedEffect
        selectedIds = c.selectedHabitIds.toSet()
        maxItems = c.maxItems
        showStreakCount = c.showStreakCount
        layoutGrid = c.layoutGrid
    }

    Scaffold(
        containerColor = prismColors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Configure Habit Widget",
                        fontWeight = FontWeight.Bold,
                        color = prismColors.onSurface
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = prismColors.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (habits.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "No Active Habits",
                        style = MaterialTheme.typography.titleMedium,
                        color = prismColors.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Open PrismTask And Create A Habit First.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = prismColors.muted
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    item {
                        Text(
                            text = "Pick Habits To Show",
                            style = MaterialTheme.typography.titleSmall,
                            color = prismColors.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Leave All Unchecked To Show The Most Recent Active Habits.",
                            style = MaterialTheme.typography.bodySmall,
                            color = prismColors.muted
                        )
                    }
                    items(habits, key = { it.id }) { habit ->
                        HabitOption(
                            habit = habit,
                            selected = habit.id in selectedIds,
                            onToggle = {
                                selectedIds = if (habit.id in selectedIds) {
                                    selectedIds - habit.id
                                } else {
                                    selectedIds + habit.id
                                }
                            }
                        )
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Max Visible: $maxItems",
                            style = MaterialTheme.typography.titleSmall,
                            color = prismColors.onSurface
                        )
                        Slider(
                            value = maxItems.toFloat(),
                            onValueChange = { maxItems = it.toInt().coerceIn(1, 12) },
                            valueRange = 1f..12f,
                            steps = 10
                        )
                    }
                    item {
                        SettingRow(
                            label = "Show Streak Counts",
                            checked = showStreakCount,
                            onCheckedChange = { showStreakCount = it }
                        )
                    }
                    item {
                        SettingRow(
                            label = "Compact Row Layout",
                            checked = layoutGrid,
                            onCheckedChange = { layoutGrid = it }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        onConfirm(
                            WidgetConfigDataStore.HabitStreakConfig(
                                selectedHabitIds = selectedIds.toList(),
                                showStreakCount = showStreakCount,
                                layoutGrid = layoutGrid,
                                maxItems = maxItems
                            )
                        )
                    },
                    enabled = habits.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun HabitOption(
    habit: HabitEntity,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val prismColors = LocalPrismColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) prismColors.tagSurface else prismColors.surface)
            .clickable(onClick = onToggle)
            .padding(12.dp)
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Text(
            text = habit.icon,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "  ${habit.name}",
            style = MaterialTheme.typography.bodyLarge,
            color = prismColors.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val prismColors = LocalPrismColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = prismColors.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
