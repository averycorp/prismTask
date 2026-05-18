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
import com.averycorp.prismtask.ui.screens.settings.sections.CheckInStreakSection
import com.averycorp.prismtask.ui.screens.settings.sections.ForgivenessStreakSection
import com.averycorp.prismtask.ui.screens.settings.sections.HabitsSection
import com.averycorp.prismtask.ui.screens.settings.sections.StreakPauseSection
import com.averycorp.prismtask.ui.theme.ThemedSubScreenTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitsStreaksScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val streakMaxMissedDays by viewModel.streakMaxMissedDays.collectAsStateWithLifecycle()
    val forgivenessPrefs by viewModel.forgivenessPrefs.collectAsStateWithLifecycle()
    val checkInStreak by viewModel.checkInStreak.collectAsStateWithLifecycle()
    val restDayUi by viewModel.restDayUiSnapshot.collectAsStateWithLifecycle()
    val todaySkipAfterCompleteDays by viewModel.todaySkipAfterCompleteDays.collectAsStateWithLifecycle()
    val todaySkipBeforeScheduleDays by viewModel.todaySkipBeforeScheduleDays.collectAsStateWithLifecycle()
    val skipCapPerWeek by viewModel.skipCapPerWeek.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ThemedSubScreenTitle("Habits & Streaks") },
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
            HabitsSection(
                streakMaxMissedDays = streakMaxMissedDays,
                onStreakMaxMissedDaysChange = viewModel::setStreakMaxMissedDays,
                todaySkipAfterCompleteDays = todaySkipAfterCompleteDays,
                onTodaySkipAfterCompleteDaysChange = viewModel::setTodaySkipAfterCompleteDays,
                todaySkipBeforeScheduleDays = todaySkipBeforeScheduleDays,
                onTodaySkipBeforeScheduleDaysChange = viewModel::setTodaySkipBeforeScheduleDays,
                skipCapPerWeek = skipCapPerWeek,
                onSkipCapPerWeekChange = viewModel::setSkipCapPerWeek
            )

            ForgivenessStreakSection(
                prefs = forgivenessPrefs,
                onPrefsChange = viewModel::setForgivenessPrefs
            )

            StreakPauseSection(
                activeFrom = restDayUi.pauseFrom,
                activeTo = restDayUi.pauseTo,
                onApplyPause = viewModel::applyStreakPause,
                onClearPause = viewModel::clearStreakPause
            )

            CheckInStreakSection(streak = checkInStreak)
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
