package com.averycorp.prismtask.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.ui.coachmark.coachmarkAnchor
import com.averycorp.prismtask.ui.components.settings.SettingsGroup
import com.averycorp.prismtask.ui.components.settings.SettingsNavRow
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.screens.settings.sections.AboutSection
import com.averycorp.prismtask.ui.screens.settings.sections.AdminBugReportsSection
import com.averycorp.prismtask.ui.screens.settings.sections.AdminNotificationLogSection
import com.averycorp.prismtask.ui.screens.settings.sections.DebugLogAdminSection
import com.averycorp.prismtask.ui.screens.settings.sections.DebugOnboardingSection
import com.averycorp.prismtask.ui.screens.settings.sections.DebugTierSection
import com.averycorp.prismtask.ui.screens.settings.sections.PrismThemeSection
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismFonts
import com.averycorp.prismtask.ui.theme.expandedWidthCap
import com.averycorp.prismtask.ui.theme.gridFloor

private val ColAccount = Color(0xFFE6F1FB)
private val ColSubscription = Color(0xFFFAEEDA)
private val ColAppearance = Color(0xFFEEEDFE)
private val ColLayout = Color(0xFFE1F5EE)
private val ColTaskDefaults = Color(0xFFEAF3DE)
private val ColHabits = Color(0xFFFBEAF0)
private val ColLifeModes = Color(0xFFFAECE7)
private val ColFocusTimer = Color(0xFFE1F5EE)
private val ColAi = Color(0xFFEEEDFE)
private val ColBrainMode = Color(0xFFFAEEDA)
private val ColWellbeing = Color(0xFFEAF3DE)
private val ColCalendar = Color(0xFFE6F1FB)
private val ColNotifications = Color(0xFFFAEEDA)
private val ColAccessibility = Color(0xFFE1F5EE)
private val ColDataBackup = Color(0xFFF1EFE8)
private val ColSupport = Color(0xFFF1EFE8)
private val ColAdvanced = Color(0xFFEDE7F6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()
    val debugTierOverride by viewModel.debugTierOverride.collectAsStateWithLifecycle()

    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()

    val latestReleaseTag by viewModel.latestReleaseTag.collectAsStateWithLifecycle()
    val clinicalReportUri by viewModel.clinicalReportUri.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(clinicalReportUri) {
        val uri = clinicalReportUri
        if (uri != null) {
            snackbarHostState.showSnackbar("Health report saved to Downloads")
            viewModel.clearClinicalReportUri()
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val prismColors = LocalPrismColors.current
    val prismAttrs = LocalPrismAttrs.current
    val displayFont = LocalPrismFonts.current.display

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = prismColors.background,
        topBar = {
            TopAppBar(
                title = {
                    when {
                        prismAttrs.editorial -> {
                            // Void: "Settings." with trailing dot in primary color
                            BasicText(
                                text = buildAnnotatedString {
                                    append("Settings")
                                    withStyle(SpanStyle(color = prismColors.primary)) { append(".") }
                                },
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontFamily = displayFont,
                                    fontWeight = FontWeight.Medium,
                                    color = prismColors.onBackground
                                )
                            )
                        }
                        else -> Text(
                            text = when {
                                prismAttrs.terminal -> "SETTINGS"
                                prismAttrs.displayUpper -> "SETTINGS"
                                else -> "Settings"
                            },
                            fontFamily = displayFont,
                            fontWeight = FontWeight.Bold,
                            color = prismColors.onBackground,
                            letterSpacing = prismAttrs.displayTracking.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = prismColors.background,
                    titleContentColor = prismColors.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .gridFloor()
                .padding(padding)
        ) {
            AnimatedVisibility(visible = isSyncing || isImporting || isExporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .expandedWidthCap()
            ) {
                item {
                    SettingsGroup(label = "Account") {
                        SettingsNavRow(
                            title = "Account & Sync",
                            subtitle = "Sign in, sync across devices",
                            iconEmoji = "\uD83D\uDC64",
                            iconBgColor = ColAccount,
                            onClick = { navController.navigate("settings/account_sync") }
                        )
                        SettingsNavRow(
                            title = "Subscription",
                            subtitle = "Manage your plan",
                            iconEmoji = "\u2B50",
                            iconBgColor = ColSubscription,
                            onClick = { navController.navigate("settings/subscription") }
                        )
                        SettingsNavRow(
                            title = "Redeem Beta Code",
                            subtitle = "Unlock Pro with a tester code",
                            iconEmoji = "\uD83C\uDFAB",
                            iconBgColor = ColSubscription,
                            onClick = { navController.navigate("settings/beta_code") }
                        )
                    }
                }

                item {
                    // Theme picker — ALL tiers
                    PrismThemeSection()
                    // Widget-only theme override (Glance home-screen widgets)
                    com.averycorp.prismtask.ui.screens.settings.sections.WidgetThemeSection()
                    SettingsNavRow(
                        title = "Advanced Appearance",
                        subtitle = "Accent color, overrides, font size",
                        iconEmoji = "\uD83C\uDFA8",
                        iconBgColor = ColAppearance,
                        modifier = Modifier.coachmarkAnchor(
                            com.averycorp.prismtask.ui.coachmark.CoachmarkAnchors.SETTINGS_APPEARANCE_ENTRY
                        ),
                        onClick = { navController.navigate("settings/appearance") }
                    )
                    SettingsNavRow(
                        title = "Layout & Navigation",
                        subtitle = "Tabs, dashboard, swipe actions",
                        iconEmoji = "\uD83D\uDCD0",
                        iconBgColor = ColLayout,
                        onClick = { navController.navigate("settings/layout") }
                    )
                }

                item {
                    SettingsGroup(label = "Tasks & Habits") {
                        // Global Defaults — STANDARD+
                        // POWER-only items within (default duration, default project, auto-due-date, start of week)
                        SettingsNavRow(
                            title = "Global Defaults",
                            subtitle = "Day of week, day start, sort, urgency",
                            iconEmoji = "\u2705",
                            iconBgColor = ColTaskDefaults,
                            onClick = { navController.navigate("settings/task_defaults") }
                        )
                        SettingsNavRow(
                            title = "Habits & Streaks",
                            subtitle = "Streak tolerance, forgiveness",
                            iconEmoji = "\uD83D\uDD01",
                            iconBgColor = ColHabits,
                            onClick = { navController.navigate("settings/habits_streaks") }
                        )
                        SettingsNavRow(
                            title = "Life Modes",
                            subtitle = "Self-care, school, medication, leisure",
                            iconEmoji = "\uD83E\uDDE9",
                            iconBgColor = ColLifeModes,
                            onClick = { navController.navigate("settings/life_modes") }
                        )
                        SettingsNavRow(
                            title = "Medication Slots",
                            subtitle = "Times of day for medication doses",
                            iconEmoji = "💊",
                            iconBgColor = ColLifeModes,
                            onClick = { navController.navigate("settings/medication_slots") }
                        )
                        SettingsNavRow(
                            title = "Focus Timer",
                            subtitle = "Pomodoro durations and preferences",
                            iconEmoji = "\u23F1",
                            iconBgColor = ColFocusTimer,
                            onClick = { navController.navigate("settings/focus_timer") }
                        )
                    }
                }

                // Productivity — STANDARD+
                item {
                    SettingsGroup(label = "Productivity") {
                        // AI Features moved to the Today "AI Tools" hub sheet
                        // (docs/audits/AI_FEATURES_TO_TODAY_RELOCATION_AUDIT.md).
                        SettingsNavRow(
                            title = "Brain Mode",
                            subtitle = "ADHD, Calm, Focus Release",
                            iconEmoji = "\uD83E\uDDE0",
                            iconBgColor = ColBrainMode,
                            onClick = { navController.navigate("settings/brain_mode") }
                        )
                        SettingsNavRow(
                            title = "Wellbeing",
                            subtitle = "Work-life balance, boundaries, reports",
                            iconEmoji = "\u2696\uFE0F",
                            iconBgColor = ColWellbeing,
                            onClick = { navController.navigate("settings/wellbeing") }
                        )
                    }
                }

                // Integrations — STANDARD+
                item {
                    SettingsGroup(label = "Integrations") {
                        SettingsNavRow(
                            title = "Calendar",
                            subtitle = "Device calendar, Google Calendar",
                            iconEmoji = "\uD83D\uDCC5",
                            iconBgColor = ColCalendar,
                            onClick = { navController.navigate("settings/calendar") }
                        )
                    }
                }

                item {
                    SettingsGroup(label = "Alerts") {
                        SettingsNavRow(
                            title = "Notifications",
                            subtitle = "Reminders, briefings, channels",
                            iconEmoji = "\uD83D\uDD14",
                            iconBgColor = ColNotifications,
                            onClick = { navController.navigate("settings/notifications") }
                        )
                    }
                }

                item {
                    SettingsGroup(label = "Accessibility") {
                        SettingsNavRow(
                            title = "Accessibility",
                            subtitle = "Motion, contrast, voice",
                            iconEmoji = "\u267F",
                            iconBgColor = ColAccessibility,
                            onClick = { navController.navigate("settings/accessibility") }
                        )
                    }
                }

                item {
                    SettingsGroup(label = "Data") {
                        SettingsNavRow(
                            title = "Data & Backup",
                            subtitle = "Export, import, archive, reset",
                            iconEmoji = "\uD83D\uDCBE",
                            iconBgColor = ColDataBackup,
                            onClick = { navController.navigate("settings/data_backup") }
                        )
                        SettingsNavRow(
                            title = "Batch Command History",
                            subtitle = "Review or undo recent AI batches (24h)",
                            iconEmoji = "⏱",
                            iconBgColor = ColDataBackup,
                            onClick = { navController.navigate(PrismTaskRoute.BatchHistory.route) }
                        )
                        SettingsNavRow(
                            title = "Automation",
                            subtitle = "If-this-then-that rules across tasks, habits, time",
                            iconEmoji = "⚡",
                            iconBgColor = ColAi,
                            onClick = { navController.navigate(PrismTaskRoute.Automation.route) }
                        )
                    }
                }

                // Advanced — Power-user defaults are exposed by default since #952
                item {
                    SettingsGroup(label = "Advanced") {
                        SettingsNavRow(
                            title = "Advanced Tuning",
                            subtitle = "Power-user knobs for scoring, schedules, widgets",
                            iconEmoji = "🔧",
                            iconBgColor = ColAdvanced,
                            onClick = { navController.navigate("settings/advanced_tuning") }
                        )
                    }
                }

                item {
                    SettingsGroup(label = "Support") {
                        SettingsNavRow(
                            title = "Report a Bug",
                            subtitle = "Tell us what went wrong",
                            iconEmoji = "\u2753",
                            iconBgColor = ColSupport,
                            onClick = {
                                navController.navigate(
                                    PrismTaskRoute.BugReport.createRoute("Settings")
                                )
                            }
                        )
                        SettingsNavRow(
                            title = "Request a Feature",
                            subtitle = "Tell us what you'd like to see",
                            iconEmoji = "\u2753",
                            iconBgColor = ColSupport,
                            onClick = {
                                navController.navigate(PrismTaskRoute.FeatureRequest.route)
                            }
                        )
                        AboutSection(
                            latestReleaseTag = latestReleaseTag,
                            onRefreshWidgets = viewModel::refreshWidgets,
                            onDebugReseed = viewModel::debugReseedDefaults
                        )
                    }
                }

                if (isAdmin) {
                    item {
                        DebugTierSection(
                            debugTierOverride = debugTierOverride,
                            onSetDebugTier = viewModel::setDebugTier,
                            onClearDebugTier = viewModel::clearDebugTier
                        )

                        DebugOnboardingSection(
                            onShowTutorial = {
                                viewModel.resetOnboarding {
                                    navController.navigate(PrismTaskRoute.Onboarding.route) {
                                        popUpTo(PrismTaskRoute.MainTabs.route) { inclusive = true }
                                    }
                                }
                            }
                        )

                        DebugLogAdminSection(
                            onViewDebugLog = {
                                navController.navigate(PrismTaskRoute.DebugLog.route)
                            }
                        )

                        AdminNotificationLogSection(
                            onViewNotificationLog = {
                                navController.navigate(PrismTaskRoute.AdminNotificationLog.route)
                            }
                        )

                        AdminBugReportsSection(
                            onViewBugReports = {
                                navController.navigate(PrismTaskRoute.AdminBugReports.route)
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}
