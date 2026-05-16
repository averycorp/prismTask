package com.averycorp.prismtask.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.PrismTaskTheme
import kotlinx.coroutines.launch

/**
 * One-off config activity shown by the launcher when the user drops a
 * [TodayWidget] onto their home screen, or taps "Reconfigure" on an
 * existing instance (`android:widgetFeatures="reconfigurable"` in
 * `today_widget_info.xml`).
 *
 * Exposes the [WidgetConfigDataStore.TodayConfig] knobs the widget body
 * actually consumes — max task rows + the four boolean section toggles —
 * so users can hide the progress bar, task list, habit roll-up, or overdue
 * badge per-instance without affecting other placed widgets.
 *
 * Setting `RESULT_OK` back with the `appWidgetId` extra is required by the
 * `AppWidgetManager` contract; on Cancel we leave the default `RESULT_CANCELED`
 * which causes the launcher to drop the placement.
 */
class TodayWidgetConfigActivity : ComponentActivity() {

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

        setContent {
            PrismTaskTheme {
                TodayConfigEditor(
                    loadInitial = {
                        WidgetConfigDataStore.snapshotTodayConfig(this@TodayWidgetConfigActivity, appWidgetId)
                    },
                    onCancel = { finish() },
                    onConfirm = { config ->
                        lifecycleScope.launch {
                            WidgetConfigDataStore.setTodayConfig(
                                this@TodayWidgetConfigActivity,
                                appWidgetId,
                                config
                            )
                            runCatching { TodayWidget().updateAll(this@TodayWidgetConfigActivity) }

                            setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(
                                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                                    appWidgetId
                                )
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
private fun TodayConfigEditor(
    loadInitial: suspend () -> WidgetConfigDataStore.TodayConfig,
    onCancel: () -> Unit,
    onConfirm: (WidgetConfigDataStore.TodayConfig) -> Unit
) {
    val prismColors = LocalPrismColors.current
    var showProgress by remember { mutableStateOf(true) }
    var showTaskList by remember { mutableStateOf(true) }
    var showHabitSummary by remember { mutableStateOf(true) }
    var showOverdueBadge by remember { mutableStateOf(true) }
    var maxTasks by remember { mutableIntStateOf(8) }

    LaunchedEffect(Unit) {
        val initial = runCatching { loadInitial() }.getOrDefault(WidgetConfigDataStore.TodayConfig())
        showProgress = initial.showProgress
        showTaskList = initial.showTaskList
        showHabitSummary = initial.showHabitSummary
        showOverdueBadge = initial.showOverdueBadge
        maxTasks = initial.maxTasks
    }

    Scaffold(
        containerColor = prismColors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Configure Today Widget",
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
                .padding(PaddingValues(horizontal = 20.dp, vertical = 16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Max Tasks: $maxTasks",
                style = MaterialTheme.typography.bodyLarge,
                color = prismColors.onSurface
            )
            Slider(
                value = maxTasks.toFloat(),
                onValueChange = { maxTasks = it.toInt().coerceIn(1, 20) },
                valueRange = 1f..20f,
                steps = 18
            )

            ToggleRow(
                label = "Show Progress Bar",
                checked = showProgress,
                onChange = { showProgress = it }
            )
            ToggleRow(
                label = "Show Task List",
                checked = showTaskList,
                onChange = { showTaskList = it }
            )
            ToggleRow(
                label = "Show Habit Summary",
                checked = showHabitSummary,
                onChange = { showHabitSummary = it }
            )
            ToggleRow(
                label = "Show Overdue Badge",
                checked = showOverdueBadge,
                onChange = { showOverdueBadge = it }
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        onConfirm(
                            WidgetConfigDataStore.TodayConfig(
                                showProgress = showProgress,
                                showTaskList = showTaskList,
                                showHabitSummary = showHabitSummary,
                                showOverdueBadge = showOverdueBadge,
                                maxTasks = maxTasks
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val prismColors = LocalPrismColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = prismColors.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange
        )
    }
}
