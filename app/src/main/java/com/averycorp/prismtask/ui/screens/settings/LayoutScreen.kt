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
import com.averycorp.prismtask.ui.screens.settings.sections.DashboardSection
import com.averycorp.prismtask.ui.screens.settings.sections.NavigationSection
import com.averycorp.prismtask.ui.screens.settings.sections.SwipeActionsSection
import com.averycorp.prismtask.ui.theme.ThemedSubScreenTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val tabOrder by viewModel.tabOrder.collectAsStateWithLifecycle()
    val hiddenTabs by viewModel.hiddenTabs.collectAsStateWithLifecycle()
    val sectionOrder by viewModel.sectionOrder.collectAsStateWithLifecycle()
    val hiddenSections by viewModel.hiddenSections.collectAsStateWithLifecycle()
    val progressStyle by viewModel.progressStyle.collectAsStateWithLifecycle()
    val completionCountMode by viewModel.completionCountMode.collectAsStateWithLifecycle()
    val swipePrefs by viewModel.swipePrefs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ThemedSubScreenTitle("Layout & Navigation") },
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
            NavigationSection(
                tabOrder = tabOrder,
                hiddenTabs = hiddenTabs,
                onHiddenTabsChange = viewModel::setHiddenTabs,
                onTabOrderChange = viewModel::setTabOrder,
                onResetTabDefaults = viewModel::resetTabDefaults
            )

            DashboardSection(
                progressStyle = progressStyle,
                completionCountMode = completionCountMode,
                sectionOrder = sectionOrder,
                hiddenSections = hiddenSections,
                onProgressStyleChange = viewModel::setProgressStyle,
                onCompletionCountModeChange = viewModel::setCompletionCountMode,
                onHiddenSectionsChange = viewModel::setHiddenSections,
                onSectionOrderChange = viewModel::setSectionOrder,
                onResetDashboardDefaults = viewModel::resetDashboardDefaults
            )

            SwipeActionsSection(
                swipePrefs = swipePrefs,
                onSwipeRightChange = viewModel::setSwipeRight,
                onSwipeLeftChange = viewModel::setSwipeLeft
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
