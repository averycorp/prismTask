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
import com.averycorp.prismtask.ui.screens.settings.sections.BrainModeSection
import com.averycorp.prismtask.ui.screens.settings.sections.CustomBrainModeSubSection
import com.averycorp.prismtask.ui.theme.ThemedSubScreenTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainModeScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ndPrefs by viewModel.ndPrefs.collectAsStateWithLifecycle()
    val customModes by viewModel.customBrainModes.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ThemedSubScreenTitle("Brain Mode") },
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
            BrainModeSection(
                ndPrefs = ndPrefs,
                onAdhdModeChange = viewModel::setAdhdMode,
                onCalmModeChange = viewModel::setCalmMode,
                onFocusReleaseModeChange = viewModel::setFocusReleaseMode,
                onGoodEnoughTimersChange = viewModel::setGoodEnoughTimersEnabled,
                onDefaultGoodEnoughMinutesChange = viewModel::setDefaultGoodEnoughMinutes,
                onGoodEnoughEscalationChange = viewModel::setGoodEnoughEscalation,
                onAntiReworkChange = viewModel::setAntiReworkEnabled,
                onSoftWarningChange = viewModel::setSoftWarningEnabled,
                onCoolingOffChange = viewModel::setCoolingOffEnabled,
                onCoolingOffMinutesChange = viewModel::setCoolingOffMinutes,
                onRevisionCounterChange = viewModel::setRevisionCounterEnabled,
                onMaxRevisionsChange = viewModel::setMaxRevisions,
                onShipItCelebrationsChange = viewModel::setShipItCelebrationsEnabled,
                onCelebrationIntensityChange = viewModel::setCelebrationIntensity
            )
            CustomBrainModeSubSection(
                modes = customModes,
                onAdd = viewModel::addCustomBrainMode,
                onRemove = viewModel::removeCustomBrainMode
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
