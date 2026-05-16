package com.averycorp.prismtask.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.ui.components.settings.SectionHeader
import com.averycorp.prismtask.ui.components.settings.SettingsToggleRow
import com.averycorp.prismtask.ui.screens.settings.sections.TaskDefaultsSection
import com.averycorp.prismtask.ui.theme.ThemedSubScreenTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDefaultsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val defaultSort by viewModel.defaultSort.collectAsStateWithLifecycle()
    val defaultViewMode by viewModel.defaultViewMode.collectAsStateWithLifecycle()
    val urgencyWeights by viewModel.urgencyWeights.collectAsStateWithLifecycle()
    val firstDayOfWeek by viewModel.firstDayOfWeek.collectAsStateWithLifecycle()
    val dayStartHour by viewModel.dayStartHour.collectAsStateWithLifecycle()
    val dayStartMinute by viewModel.dayStartMinute.collectAsStateWithLifecycle()
    val userTier by viewModel.userTier.collectAsStateWithLifecycle()
    val aiMasterPrefs by viewModel.aiFeaturePrefs.collectAsStateWithLifecycle()
    val perFeatureAiPrefs by viewModel.perFeatureAiPrefs.collectAsStateWithLifecycle()
    val taskDefaults by viewModel.taskDefaultPrefs.collectAsStateWithLifecycle()
    val quickAddPrefs by viewModel.quickAddPrefs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ThemedSubScreenTitle("Global Defaults") },
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
            val isPro = userTier == com.averycorp.prismtask.data.billing.UserTier.PRO
            TaskDefaultsSection(
                defaultSort = defaultSort,
                defaultViewMode = defaultViewMode,
                firstDayOfWeek = firstDayOfWeek,
                dayStartHour = dayStartHour,
                dayStartMinute = dayStartMinute,
                urgencyWeights = urgencyWeights,
                showAiUrgencyToggle = isPro && aiMasterPrefs.enabled,
                aiUrgencyEnabled = perFeatureAiPrefs.urgencyEnabled,
                defaultTaskDurationMinutes = taskDefaults.defaultDuration ?: 30,
                onDefaultSortChange = viewModel::setDefaultSort,
                onDefaultViewModeChange = viewModel::setDefaultViewMode,
                onFirstDayOfWeekChange = viewModel::setFirstDayOfWeek,
                onStartOfDayChange = viewModel::setStartOfDay,
                onUrgencyWeightsChange = viewModel::setUrgencyWeights,
                onAiUrgencyEnabledChange = viewModel::setAiUrgencyFeatureEnabled,
                onDefaultTaskDurationChange = viewModel::setDefaultTaskDuration,
                onResetTaskBehaviorDefaults = viewModel::resetTaskBehaviorDefaults
            )

            SectionHeader("Quick Add")
            SettingsToggleRow(
                title = "Confirm Task Details Before Saving",
                subtitle = "Show a preview of the parsed task — title, date, priority, " +
                    "project, tags — before it's added. Continuous voice mode skips this.",
                checked = quickAddPrefs.showConfirmation,
                onCheckedChange = { enabled ->
                    viewModel.setQuickAddPrefs(quickAddPrefs.copy(showConfirmation = enabled))
                }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
