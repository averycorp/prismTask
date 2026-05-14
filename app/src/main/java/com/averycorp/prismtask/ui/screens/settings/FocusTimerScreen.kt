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
import com.averycorp.prismtask.ui.screens.settings.sections.TimerSection
import com.averycorp.prismtask.ui.theme.ThemedSubScreenTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusTimerScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val timerWorkSeconds by viewModel.timerWorkDurationSeconds.collectAsStateWithLifecycle()
    val timerBreakSeconds by viewModel.timerBreakDurationSeconds.collectAsStateWithLifecycle()
    val timerLongBreakSeconds by viewModel.timerLongBreakDurationSeconds.collectAsStateWithLifecycle()
    val timerCustomSeconds by viewModel.timerCustomDurationSeconds.collectAsStateWithLifecycle()
    val pomodoroAvailableMinutes by viewModel.pomodoroAvailableMinutes.collectAsStateWithLifecycle()
    val pomodoroFocusPreference by viewModel.pomodoroFocusPreference.collectAsStateWithLifecycle()
    val timerBuzzUntilDismissed by viewModel.timerBuzzUntilDismissed.collectAsStateWithLifecycle()
    val timerOverrideVolume by viewModel.timerOverrideVolume.collectAsStateWithLifecycle()
    val timerAlarmVolumePercent by viewModel.timerAlarmVolumePercent.collectAsStateWithLifecycle()
    val preSessionCoaching by viewModel.pomodoroPreSessionCoachingEnabled.collectAsStateWithLifecycle()
    val breakCoaching by viewModel.pomodoroBreakCoachingEnabled.collectAsStateWithLifecycle()
    val recapCoaching by viewModel.pomodoroRecapCoachingEnabled.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ThemedSubScreenTitle("Focus Timer") },
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
            TimerSection(
                timerWorkSeconds = timerWorkSeconds,
                timerBreakSeconds = timerBreakSeconds,
                timerLongBreakSeconds = timerLongBreakSeconds,
                timerCustomSeconds = timerCustomSeconds,
                pomodoroAvailableMinutes = pomodoroAvailableMinutes,
                pomodoroFocusPreference = pomodoroFocusPreference,
                buzzUntilDismissed = timerBuzzUntilDismissed,
                overrideVolume = timerOverrideVolume,
                alarmVolumePercent = timerAlarmVolumePercent,
                preSessionCoachingEnabled = preSessionCoaching,
                breakCoachingEnabled = breakCoaching,
                recapCoachingEnabled = recapCoaching,
                onTimerWorkMinutesChange = viewModel::setTimerWorkDurationMinutes,
                onTimerBreakMinutesChange = viewModel::setTimerBreakDurationMinutes,
                onTimerLongBreakMinutesChange = viewModel::setTimerLongBreakDurationMinutes,
                onTimerCustomMinutesChange = viewModel::setTimerCustomDurationMinutes,
                onPomodoroAvailableMinutesChange = viewModel::setPomodoroAvailableMinutes,
                onPomodoroFocusPreferenceChange = viewModel::setPomodoroFocusPreference,
                onBuzzUntilDismissedChange = viewModel::setTimerBuzzUntilDismissed,
                onOverrideVolumeChange = viewModel::setTimerOverrideVolume,
                onAlarmVolumePercentChange = viewModel::setTimerAlarmVolumePercent,
                onPreSessionCoachingChange = viewModel::setPomodoroPreSessionCoachingEnabled,
                onBreakCoachingChange = viewModel::setPomodoroBreakCoachingEnabled,
                onRecapCoachingChange = viewModel::setPomodoroRecapCoachingEnabled
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
