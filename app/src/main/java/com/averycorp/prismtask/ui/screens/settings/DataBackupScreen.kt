package com.averycorp.prismtask.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.screens.settings.sections.BackupExportSection
import com.averycorp.prismtask.ui.screens.settings.sections.DataSection
import com.averycorp.prismtask.ui.screens.settings.sections.DeleteMentalHealthDataSection
import com.averycorp.prismtask.ui.theme.ThemedSubScreenTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataBackupScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    val autoArchiveDays by viewModel.autoArchiveDays.collectAsStateWithLifecycle()
    val archivedCount by viewModel.archivedCount.collectAsStateWithLifecycle()
    val isResetting by viewModel.isResetting.collectAsStateWithLifecycle()
    val isWipingMentalHealthData by viewModel.isWipingMentalHealthData.collectAsStateWithLifecycle()
    val duplicateCleanupState by viewModel.duplicateCleanupState.collectAsStateWithLifecycle()
    val pendingJson by viewModel.pendingJsonExport.collectAsStateWithLifecycle()
    val pendingCsv by viewModel.pendingCsvExport.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    val createJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            val data = pendingJson ?: return@rememberLauncherForActivityResult
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(data.toByteArray())
            }
            viewModel.clearPendingExports()
        }
    }

    val createCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            val data = pendingCsv ?: return@rememberLauncherForActivityResult
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(data.toByteArray())
            }
            viewModel.clearPendingExports()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            }
            if (jsonString != null) {
                viewModel.onImportJson(jsonString)
            }
        }
    }

    LaunchedEffect(pendingJson) {
        if (pendingJson != null) createJsonLauncher.launch("prismtask_backup.json")
    }

    LaunchedEffect(pendingCsv) {
        if (pendingCsv != null) createCsvLauncher.launch("prismtask_tasks.csv")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ThemedSubScreenTitle("Data & Backup") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            DataSection(
                autoArchiveDays = autoArchiveDays,
                archivedCount = archivedCount,
                isResetting = isResetting,
                duplicateCleanupState = duplicateCleanupState,
                onAutoArchiveDaysChange = viewModel::setAutoArchiveDays,
                onResetAppData = { options ->
                    viewModel.resetAppData(options) { navigateToOnboarding ->
                        if (navigateToOnboarding) {
                            navController.navigate(PrismTaskRoute.Onboarding.route) {
                                popUpTo(PrismTaskRoute.MainTabs.route) { inclusive = true }
                            }
                        } else if (options.preferencesAndSettings) {
                            (context as? android.app.Activity)?.recreate()
                        }
                    }
                },
                onScanDuplicates = viewModel::scanForDuplicates,
                onConfirmDeleteDuplicates = viewModel::confirmDeleteDuplicates,
                onDismissDuplicateDialog = viewModel::dismissDuplicateDialog,
                onNavigateToTags = { navController.navigate("tag_management") },
                onNavigateToProjects = { navController.navigate("project_list") },
                onNavigateToTemplates = { navController.navigate("templates") },
                onNavigateToArchive = { navController.navigate("archive") }
            )

            BackupExportSection(
                onExportJson = viewModel::onExportJson,
                onExportCsv = viewModel::onExportCsv,
                onImportJson = { importLauncher.launch(arrayOf("application/json", "*/*")) }
            )

            // Mental-Health-First Audit § G5: partial wipe of mood /
            // check-in / weekly-review / boundary / focus-release data.
            // Lives here in the Data hub rather than on Account & Sync
            // because it's a data-management action — adjacent to Reset
            // App Data, distinct from Delete Account.
            DeleteMentalHealthDataSection(
                isWiping = isWipingMentalHealthData,
                onConfirmWipe = viewModel::wipeMentalHealthData
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
