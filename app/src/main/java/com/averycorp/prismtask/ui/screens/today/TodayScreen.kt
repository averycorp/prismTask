package com.averycorp.prismtask.ui.screens.today

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.repository.LeisureRepository
import com.averycorp.prismtask.data.repository.SchoolworkRepository
import com.averycorp.prismtask.ui.coachmark.coachmarkAnchor
import com.averycorp.prismtask.ui.components.EnergyCheckInCard
import com.averycorp.prismtask.ui.components.HabitChipRowSkeleton
import com.averycorp.prismtask.ui.components.MoveToProjectSheet
import com.averycorp.prismtask.ui.components.ProGatedFeature
import com.averycorp.prismtask.ui.components.ProUpsellSheet
import com.averycorp.prismtask.ui.components.ProgressHeaderSkeleton
import com.averycorp.prismtask.ui.components.QuickReschedulePopup
import com.averycorp.prismtask.ui.components.RichEmptyState
import com.averycorp.prismtask.ui.components.TaskListSkeleton
import com.averycorp.prismtask.ui.components.UpgradePrompt
import com.averycorp.prismtask.ui.components.WelcomeBackDialog
import com.averycorp.prismtask.ui.components.sync.SyncIndicatorHost
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.screens.addedittask.AddEditTaskSheetHost
import com.averycorp.prismtask.ui.screens.coaching.CoachingViewModel
import com.averycorp.prismtask.ui.screens.today.ai.TodayAiHubSheet
import com.averycorp.prismtask.ui.screens.today.components.AllCaughtUpCard
import com.averycorp.prismtask.ui.screens.today.components.BookableHabitReminderCard
import com.averycorp.prismtask.ui.screens.today.components.CheckInCompleteChip
import com.averycorp.prismtask.ui.screens.today.components.CollapsibleSection
import com.averycorp.prismtask.ui.screens.today.components.CompactProgressHeader
import com.averycorp.prismtask.ui.screens.today.components.CompletedTaskItem
import com.averycorp.prismtask.ui.screens.today.components.FloatingQuickAddBar
import com.averycorp.prismtask.ui.screens.today.components.GUIDED_TOUR_STEPS
import com.averycorp.prismtask.ui.screens.today.components.GuidedTourCard
import com.averycorp.prismtask.ui.screens.today.components.HabitChipRow
import com.averycorp.prismtask.ui.screens.today.components.MorningCheckInBanner
import com.averycorp.prismtask.ui.screens.today.components.OverloadBanner
import com.averycorp.prismtask.ui.screens.today.components.PlanForTodaySheet
import com.averycorp.prismtask.ui.screens.today.components.ProductivityScoreBadge
import com.averycorp.prismtask.ui.screens.today.components.SelfCareNudgeCard
import com.averycorp.prismtask.ui.screens.today.components.SwipeableTaskItem
import com.averycorp.prismtask.ui.screens.today.components.TodayBalanceSection
import com.averycorp.prismtask.ui.screens.today.components.TodayCognitiveLoadSection
import com.averycorp.prismtask.ui.screens.today.components.neutralGray
import com.averycorp.prismtask.ui.screens.today.dailyessentials.DailyEssentialsActions
import com.averycorp.prismtask.ui.screens.today.dailyessentials.DailyEssentialsSection
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.expandedWidthCap
import com.averycorp.prismtask.ui.theme.gridFloor
import com.averycorp.prismtask.ui.theme.prismGlow

private const val SECTION_OVERDUE = "overdue"
private const val SECTION_TODAY_TASKS = "today_tasks"
private const val SECTION_HABITS = "habits"
private const val SECTION_DAILY_ESSENTIALS = "daily_essentials"
private const val SECTION_SCHEDULED = "scheduled_today"
private const val SECTION_PLANNED = "planned"
private const val SECTION_PLAN_MORE = "plan_more"
private const val SECTION_COMPLETED = "completed"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    navController: NavController,
    viewModel: TodayViewModel = hiltViewModel(),
    coachingViewModel: CoachingViewModel = hiltViewModel(),
    autoStartVoice: Boolean = false,
    onVoiceAutoStartConsumed: () -> Unit = {},
    onNavigateToHabits: () -> Unit = {}
) {
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val overdueTasks by viewModel.overdueTasks.collectAsStateWithLifecycle()
    val todayTasks by viewModel.todayTasks.collectAsStateWithLifecycle()
    val plannedTasks by viewModel.plannedTasks.collectAsStateWithLifecycle()
    val completedToday by viewModel.completedToday.collectAsStateWithLifecycle()
    val taskTagsMap by viewModel.taskTagsMap.collectAsStateWithLifecycle()
    val showPlanSheet by viewModel.showPlanSheet.collectAsStateWithLifecycle()
    val tasksNotInToday by viewModel.tasksNotInToday.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val startOfToday by viewModel.startOfToday.collectAsStateWithLifecycle()
    val startOfTomorrow by viewModel.startOfTomorrow.collectAsStateWithLifecycle()
    val todayHabits by viewModel.todayHabits.collectAsStateWithLifecycle()
    val scheduledTodayHabits by viewModel.scheduledTodayHabits.collectAsStateWithLifecycle()
    val overdueBookableHabits by viewModel.overdueBookableHabits.collectAsStateWithLifecycle()
    val combinedTotal by viewModel.combinedTotal.collectAsStateWithLifecycle()
    val combinedCompleted by viewModel.combinedCompleted.collectAsStateWithLifecycle()
    val combinedProgress by viewModel.combinedProgress.collectAsStateWithLifecycle()
    val hiddenSections by viewModel.hiddenSections.collectAsStateWithLifecycle()
    val collapsedSections by viewModel.collapsedSections.collectAsStateWithLifecycle()
    val allHabitsCompleted by viewModel.allHabitsCompletedToday.collectAsStateWithLifecycle()
    val habitCompletedCount by viewModel.habitCompletedCount.collectAsStateWithLifecycle()
    val habitTotalCount by viewModel.habitTotalCount.collectAsStateWithLifecycle()
    val balanceState by viewModel.balanceState.collectAsStateWithLifecycle()
    val cognitiveLoadBalanceState by viewModel.cognitiveLoadBalanceState.collectAsStateWithLifecycle()
    val workLifeBalancePrefs by viewModel.workLifeBalancePrefs.collectAsStateWithLifecycle()
    val burnoutResult by viewModel.burnoutResult.collectAsStateWithLifecycle()
    val showCheckInPrompt by viewModel.showCheckInPrompt.collectAsStateWithLifecycle()
    val checkInGreeting by viewModel.checkInGreeting.collectAsStateWithLifecycle()
    val checkInSummary by viewModel.checkInSummaryFlow.collectAsStateWithLifecycle()
    val showCheckInCompleteChip by viewModel.showCompletionChip.collectAsStateWithLifecycle()
    val currentNudge by viewModel.currentNudge.collectAsStateWithLifecycle()
    val dailyEssentials by viewModel.dailyEssentials.collectAsStateWithLifecycle()
    val tourCardState by viewModel.tourCardState.collectAsStateWithLifecycle()
    val resumeTourVisible by viewModel.resumeTourVisible.collectAsStateWithLifecycle()
    val perFeatureAiPrefs by viewModel.perFeatureAiPrefs.collectAsStateWithLifecycle()
    var overloadBannerDismissed by remember { mutableStateOf(false) }

    // A2 NLP batch ops — listens to BatchUndoEventBus so we can offer
    // a "Undo" Snackbar after a batch lands while the user is back here.
    val batchUndoListener: com.averycorp.prismtask.ui.screens.batch.BatchUndoListenerViewModel = hiltViewModel()
    LaunchedEffect(batchUndoListener) {
        batchUndoListener.events.collect { event ->
            val msg = if (event.skippedCount > 0) {
                "${event.appliedCount} changes applied (${event.skippedCount} skipped)"
            } else {
                "${event.appliedCount} changes applied"
            }
            // Long (~10s) — Phase 1.2 of the BatchPreview audit found the
            // 4s default un-clickable for users on slower-tap workflows.
            viewModel.showSnackbar(
                message = msg,
                actionLabel = "Undo",
                duration = androidx.compose.material3.SnackbarDuration.Long
            ) {
                batchUndoListener.undo(event.batchId)
            }
        }
    }

    val coachingUserTier by coachingViewModel.userTier.collectAsStateWithLifecycle()
    val showEnergyCheckIn by coachingViewModel.showEnergyCheckIn.collectAsStateWithLifecycle()
    val selectedEnergy by coachingViewModel.selectedEnergy.collectAsStateWithLifecycle()
    val energyPlanMessage by coachingViewModel.energyPlanMessage.collectAsStateWithLifecycle()
    val energyPlanLoading by coachingViewModel.energyPlanLoading.collectAsStateWithLifecycle()
    val showWelcomeBack by coachingViewModel.showWelcomeBack.collectAsStateWithLifecycle()
    val welcomeBackMessage by coachingViewModel.welcomeBackMessage.collectAsStateWithLifecycle()
    val welcomeBackLoading by coachingViewModel.welcomeBackLoading.collectAsStateWithLifecycle()
    val coachingUpgradePrompt by coachingViewModel.showUpgradePrompt.collectAsStateWithLifecycle()

    LaunchedEffect(isLoading) {
        if (!isLoading) {
            val todayCount = todayTasks.size + plannedTasks.size
            coachingViewModel.checkEnergyCheckIn(todayCount)
            coachingViewModel.checkWelcomeBack(
                overdueCount = overdueTasks.size,
                recentCompletions = completedToday.size
            )
        }
    }

    val progressStyle by viewModel.progressStyle.collectAsStateWithLifecycle()
    val totalForHeader = combinedTotal
    val allTodayDone = remember(overdueTasks, todayTasks, plannedTasks, completedToday, allHabitsCompleted) {
        overdueTasks.isEmpty() && todayTasks.isEmpty() && plannedTasks.isEmpty() && completedToday.isNotEmpty() && allHabitsCompleted
    }
    val nothingToday = remember(overdueTasks, todayTasks, plannedTasks, completedToday) {
        overdueTasks.isEmpty() && todayTasks.isEmpty() && plannedTasks.isEmpty() && completedToday.isEmpty()
    }

    var editorSheetTaskId by remember { mutableStateOf<Long?>(null) }
    var showEditorSheet by remember { mutableStateOf(false) }
    var showAiHub by remember { mutableStateOf(false) }
    var upsellFeature by remember { mutableStateOf<ProGatedFeature?>(null) }
    var reschedulePopupTask by remember { mutableStateOf<TaskEntity?>(null) }
    var moveToProjectSheetTask by remember { mutableStateOf<TaskEntity?>(null) }
    var cascadeConfirmState by remember { mutableStateOf<Pair<TaskEntity, Long?>?>(null) }
    val taskCountByProject by viewModel.taskCountByProject.collectAsStateWithLifecycle()

    // Shared "move to tomorrow" builder so overdue/today/planned sections don't
    // each duplicate the same closure. Uses [startOfTomorrow] (SoD-aware) so a
    // user at 02:00 with SoD = 04:00 — still inside the previous logical day —
    // moves the task to today's calendar date, not the day after.
    val onMoveTaskToTomorrow: (TaskEntity) -> Unit = { task ->
        viewModel.onRescheduleTask(task.id, startOfTomorrow)
        viewModel.showSnackbar("Moved to tomorrow", "Undo") {
            viewModel.onRescheduleTask(task.id, task.dueDate)
        }
    }

    val prismColors = LocalPrismColors.current
    val attrs = LocalPrismAttrs.current
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = viewModel.snackbarHostState) },
        containerColor = prismColors.background,
        topBar = {
            CompactProgressHeader(
                completed = combinedCompleted,
                total = totalForHeader,
                progress = combinedProgress,
                progressStyle = progressStyle,
                onAnalyticsClick = { navController.navigate(PrismTaskRoute.TaskAnalytics.createRoute()) },
                productivityBadge = {
                    ProductivityScoreBadge(
                        onClick = { navController.navigate(PrismTaskRoute.TaskAnalytics.createRoute()) }
                    )
                },
                trailingActions = { SyncIndicatorHost() }
            )
        },
        bottomBar = {
            FloatingQuickAddBar(
                autoStartVoice = autoStartVoice,
                onVoiceAutoStartConsumed = onVoiceAutoStartConsumed,
                onBatchCommand = { commandText ->
                    navController.navigate(
                        com.averycorp.prismtask.ui.navigation.PrismTaskRoute
                            .BatchPreview.createRoute(commandText)
                    )
                },
                onMultiCreate = { rawText ->
                    navController.navigate(
                        com.averycorp.prismtask.ui.navigation.PrismTaskRoute
                            .MultiCreate.createRoute(rawText)
                    )
                }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (perFeatureAiPrefs.chatEnabled) {
                    SmallFloatingActionButton(
                        onClick = {
                            if (viewModel.isPro) {
                                navController.navigate(PrismTaskRoute.AiChat.createRoute())
                            } else {
                                upsellFeature = ProGatedFeature.AI_CHAT
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.coachmarkAnchor(
                            com.averycorp.prismtask.ui.coachmark.CoachmarkAnchors.AI_COACH_FAB
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "AI Coach",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                FloatingActionButton(
                    onClick = {
                        editorSheetTaskId = null
                        showEditorSheet = true
                    },
                    modifier = Modifier.prismGlow(prismColors.primary, attrs.glow),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Task")
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { padding ->
        androidx.compose.animation.Crossfade(targetState = isLoading, label = "today_loading") { loading ->
            if (loading) {
                Column(modifier = Modifier.fillMaxSize().gridFloor().padding(padding).padding(horizontal = 16.dp)) {
                    ProgressHeaderSkeleton()
                    Spacer(modifier = Modifier.height(16.dp))
                    TaskListSkeleton(count = 3)
                    Spacer(modifier = Modifier.height(16.dp))
                    HabitChipRowSkeleton(count = 4)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .gridFloor()
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                        .expandedWidthCap(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (nothingToday && !allTodayDone) {
                        item(key = "day_clear") {
                            RichEmptyState(
                                icon = "\u2600\uFE0F",
                                title = "Nothing Planned for Today",
                                description = "That's fine \u2014 rest is productive too.",
                                actionLabel = "Plan Your Day",
                                onAction = { viewModel.onShowPlanSheet() },
                                secondaryActionLabel = "Create a Task",
                                onSecondaryAction = {
                                    editorSheetTaskId = null
                                    showEditorSheet = true
                                }
                            )
                        }
                    }

                    if (allTodayDone) {
                        item(key = "all_caught_up") {
                            AllCaughtUpCard(
                                taskCount = completedToday.size,
                                habitCount = habitCompletedCount,
                                habitTotal = habitTotalCount,
                                onPlanTomorrow = { viewModel.onShowPlanSheet() }
                            )
                        }
                    }

                    if (showEnergyCheckIn) {
                        item(key = "energy_checkin") {
                            EnergyCheckInCard(
                                visible = true,
                                isLoading = energyPlanLoading,
                                selectedEnergy = selectedEnergy,
                                planMessage = energyPlanMessage,
                                userTier = coachingUserTier,
                                onSelectEnergy = { level ->
                                    coachingViewModel.onSelectEnergy(
                                        level = level,
                                        todayTasks = todayTasks + plannedTasks,
                                        overdueCount = overdueTasks.size,
                                        yesterdayCompleted = completedToday.size,
                                        yesterdayTotal = combinedTotal
                                    )
                                },
                                onDismiss = { coachingViewModel.dismissEnergyCheckIn() },
                                onUpgrade = {
                                    navController.navigate(PrismTaskRoute.Settings.route)
                                },
                                onViewTrends = {
                                    navController.navigate(PrismTaskRoute.MoodAnalytics.route)
                                }
                            )
                        }
                    }

                    if (workLifeBalancePrefs.showBalanceBar) {
                        item(key = "balance_bar") {
                            TodayBalanceSection(
                                state = balanceState,
                                burnout = burnoutResult,
                                onClick = { navController.navigate(PrismTaskRoute.WeeklyBalanceReport.route) }
                            )
                        }
                        item(key = "cognitive_load_bar") {
                            TodayCognitiveLoadSection(
                                state = cognitiveLoadBalanceState,
                                onClick = { navController.navigate(PrismTaskRoute.WeeklyBalanceReport.route) }
                            )
                        }
                    }

                    if (showCheckInPrompt) {
                        item(key = "checkin_prompt") {
                            MorningCheckInBanner(
                                greeting = checkInGreeting,
                                summary = checkInSummary,
                                onStart = {
                                    navController.navigate(PrismTaskRoute.MorningCheckIn.route)
                                },
                                onDismiss = { viewModel.dismissCheckInPrompt() }
                            )
                        }
                    } else if (showCheckInCompleteChip) {
                        item(key = "checkin_complete_chip") {
                            CheckInCompleteChip(
                                visible = true,
                                onAutoDismiss = { viewModel.clearCompletionChip() }
                            )
                        }
                    }

                    // Self-care nudge card (v1.4.0 V2). Only shows when the nudge
                    // engine picks one based on balance state + burnout score.
                    currentNudge?.let { nudge ->
                        item(key = "self_care_nudge") {
                            SelfCareNudgeCard(
                                nudge = nudge,
                                onDidIt = { viewModel.nudgeDidIt() },
                                onSnooze = { viewModel.snoozeNudge() },
                                onDismiss = { viewModel.dismissNudge() }
                            )
                        }
                    }

                    // Overload alert banner: shows once per day when the user's
                    // work ratio blows past their target + configured threshold
                    // (v1.4.0 V2). Dismiss is local to this screen session.
                    if (balanceState.isOverloaded && !overloadBannerDismissed) {
                        item(key = "overload_banner") {
                            val workPctNow = (
                                (
                                    balanceState.currentRatios[
                                        com.averycorp.prismtask.domain.model.LifeCategory.WORK
                                    ] ?: 0f
                                    ) * 100f
                                ).toInt()
                            OverloadBanner(
                                workPct = workPctNow,
                                targetPct = workLifeBalancePrefs.workTarget,
                                onDismiss = { overloadBannerDismissed = true }
                            )
                        }
                    }

                    // Post-onboarding Guided Tour card. Visible only when
                    // tourCardState is non-null (eligible AND not yet
                    // dismissed). Sits below status banners (check-in,
                    // overload) but above task sections so it surfaces
                    // breadth without subordinating daily signals.
                    tourCardState?.let { state ->
                        val safeIndex = state.stepIndex.coerceIn(0, GUIDED_TOUR_STEPS.size - 1)
                        item(key = "guided_tour_card") {
                            GuidedTourCard(
                                step = GUIDED_TOUR_STEPS[safeIndex],
                                stepNumber = safeIndex + 1,
                                totalSteps = GUIDED_TOUR_STEPS.size,
                                onAdvance = { viewModel.advanceTourCard(GUIDED_TOUR_STEPS.size) },
                                onDismiss = { viewModel.dismissTourCard() }
                            )
                        }
                    }

                    // Quick action chips — STANDARD+
                    // Horizontally scrollable so the row scales as more AI
                    // entry points are added without breaking small-screen
                    // layout. See docs/audits/AI_TODAY_ACCESS_AUDIT.md.
                    item(key = "quick_actions") {
                        val chipColors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            leadingIconContentColor = MaterialTheme.colorScheme.onSurface
                        )
                        val chipBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        androidx.compose.foundation.layout.Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .padding(vertical = 2.dp)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            if (resumeTourVisible) {
                                AssistChip(
                                    onClick = { viewModel.resumeTour() },
                                    label = { Text("Resume Tour") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    colors = chipColors,
                                    border = chipBorder
                                )
                            }
                            if (perFeatureAiPrefs.dailyBriefingEnabled) {
                                AssistChip(
                                    onClick = {
                                        if (viewModel.isPro) {
                                            navController.navigate(PrismTaskRoute.DailyBriefing.route)
                                        } else {
                                            upsellFeature = ProGatedFeature.AI_BRIEFING
                                        }
                                    },
                                    label = { Text("Briefing") },
                                    leadingIcon = {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    colors = chipColors,
                                    border = chipBorder
                                )
                            }
                            if (perFeatureAiPrefs.smartPomodoroEnabled) {
                                AssistChip(
                                    onClick = {
                                        if (viewModel.isPro) {
                                            navController.navigate(PrismTaskRoute.SmartPomodoro.route)
                                        } else {
                                            upsellFeature = ProGatedFeature.SMART_POMODORO
                                        }
                                    },
                                    label = { Text("Focus") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    colors = chipColors,
                                    border = chipBorder
                                )
                            }
                            if (perFeatureAiPrefs.weeklyPlannerEnabled) {
                                AssistChip(
                                    onClick = {
                                        if (viewModel.isPro) {
                                            navController.navigate(PrismTaskRoute.WeeklyPlanner.route)
                                        } else {
                                            upsellFeature = ProGatedFeature.WEEKLY_PLANNER
                                        }
                                    },
                                    label = { Text("Plan Week") },
                                    leadingIcon = {
                                        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    colors = chipColors,
                                    border = chipBorder
                                )
                            }
                            AssistChip(
                                onClick = {
                                    if (viewModel.isPro) {
                                        navController.navigate(PrismTaskRoute.EisenhowerMatrix.route)
                                    } else {
                                        upsellFeature = ProGatedFeature.EISENHOWER
                                    }
                                },
                                label = { Text("Matrix") },
                                leadingIcon = {
                                    Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = chipColors,
                                border = chipBorder
                            )
                            AssistChip(
                                onClick = {
                                    if (viewModel.isPro) {
                                        navController.navigate(PrismTaskRoute.PasteConversation.route)
                                    } else {
                                        upsellFeature = ProGatedFeature.PASTE_EXTRACT
                                    }
                                },
                                label = { Text("Extract") },
                                leadingIcon = {
                                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = chipColors,
                                border = chipBorder
                            )
                            AssistChip(
                                onClick = {
                                    if (viewModel.isPro) {
                                        navController.navigate(PrismTaskRoute.WeeklyReview.route)
                                    } else {
                                        upsellFeature = ProGatedFeature.WEEKLY_REVIEW
                                    }
                                },
                                label = { Text("Review") },
                                leadingIcon = {
                                    Icon(Icons.Default.RateReview, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = chipColors,
                                border = chipBorder
                            )
                            AssistChip(
                                onClick = { showAiHub = true },
                                label = { Text("AI Tools") },
                                leadingIcon = {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = chipColors,
                                border = chipBorder,
                                modifier = Modifier.coachmarkAnchor(
                                    com.averycorp.prismtask.ui.coachmark.CoachmarkAnchors.TODAY_AI_TOOLS_CHIP
                                )
                            )
                        }
                    }

                    if (SECTION_OVERDUE !in hiddenSections && overdueTasks.isNotEmpty()) {
                        val expanded = SECTION_OVERDUE !in collapsedSections
                        item(key = "section_overdue") {
                            CollapsibleSection(
                                emoji = "\uD83D\uDCC2",
                                title = "From Earlier",
                                count = overdueTasks.size,
                                accentColor = neutralGray(),
                                expanded = expanded,
                                onToggle = { viewModel.onToggleSectionCollapsed(SECTION_OVERDUE) }
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    overdueTasks.forEach { task ->
                                        SwipeableTaskItem(
                                            task = task,
                                            tags = taskTagsMap[task.id].orEmpty(),
                                            isOverdue = false,
                                            onComplete = { viewModel.onCompleteWithUndo(task.id) },
                                            onClick = {
                                                editorSheetTaskId = task.id
                                                showEditorSheet = true
                                            },
                                            onReschedule = { reschedulePopupTask = task },
                                            onMoveToProject = { moveToProjectSheetTask = task },
                                            onDuplicate = { viewModel.onDuplicateTask(task.id) },
                                            onDelete = { viewModel.onDeleteTaskWithUndo(task.id) },
                                            onMoveToTomorrow = { onMoveTaskToTomorrow(task) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (SECTION_TODAY_TASKS !in hiddenSections && todayTasks.isNotEmpty()) {
                        val expanded = SECTION_TODAY_TASKS !in collapsedSections
                        item(key = "section_today_tasks") {
                            CollapsibleSection(
                                emoji = "\uD83D\uDCCB",
                                title = "Today Tasks",
                                count = todayTasks.size,
                                accentColor = prismColors.primary,
                                expanded = expanded,
                                onToggle = { viewModel.onToggleSectionCollapsed(SECTION_TODAY_TASKS) }
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    todayTasks.forEach { task ->
                                        SwipeableTaskItem(
                                            task = task,
                                            tags = taskTagsMap[task.id].orEmpty(),
                                            onComplete = { viewModel.onCompleteWithUndo(task.id) },
                                            onClick = {
                                                editorSheetTaskId = task.id
                                                showEditorSheet = true
                                            },
                                            onReschedule = { reschedulePopupTask = task },
                                            onMoveToProject = { moveToProjectSheetTask = task },
                                            onDuplicate = { viewModel.onDuplicateTask(task.id) },
                                            onDelete = { viewModel.onDeleteTaskWithUndo(task.id) },
                                            onMoveToTomorrow = { onMoveTaskToTomorrow(task) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (SECTION_HABITS !in hiddenSections && todayHabits.isNotEmpty()) {
                        val expanded = SECTION_HABITS !in collapsedSections
                        val habitDoneCount = todayHabits.count { it.isCompletedToday }
                        item(key = "section_habits") {
                            CollapsibleSection(
                                emoji = "\uD83D\uDCAA",
                                title = "Habits",
                                count = todayHabits.size,
                                countLabel = "$habitDoneCount done",
                                accentColor = prismColors.secondary,
                                expanded = expanded,
                                onToggle = { viewModel.onToggleSectionCollapsed(SECTION_HABITS) }
                            ) {
                                HabitChipRow(
                                    habits = todayHabits,
                                    onToggle = { hws ->
                                        val route = when (hws.habit.name) {
                                            SchoolworkRepository.SCHOOL_HABIT_NAME ->
                                                PrismTaskRoute.Schoolwork.route
                                            LeisureRepository.LEISURE_HABIT_NAME ->
                                                PrismTaskRoute.Leisure.route
                                            else -> null
                                        }
                                        if (route != null) {
                                            navController.navigate(route)
                                        } else {
                                            viewModel.onToggleHabitCompletion(
                                                hws.habit.id,
                                                hws.isCompletedToday
                                            )
                                        }
                                    },
                                    onSeeAll = onNavigateToHabits
                                )
                            }
                        }
                    }

                    if (SECTION_DAILY_ESSENTIALS !in hiddenSections) {
                        val expanded = SECTION_DAILY_ESSENTIALS !in collapsedSections
                        item(key = "section_daily_essentials") {
                            DailyEssentialsSection(
                                state = dailyEssentials,
                                expanded = expanded,
                                onToggleExpanded = {
                                    viewModel.onToggleSectionCollapsed(SECTION_DAILY_ESSENTIALS)
                                },
                                actions = DailyEssentialsActions(
                                    onToggleRoutineStep = { routineType, stepId ->
                                        viewModel.onToggleRoutineStep(routineType, stepId)
                                    },
                                    onToggleHousework = { viewModel.onToggleHouseworkHabit() },
                                    onToggleSchoolworkHabit = { viewModel.onToggleSchoolworkHabit() },
                                    onOpenAssignment = {
                                        navController.navigate(PrismTaskRoute.Schoolwork.route)
                                    },
                                    onPickMusic = {
                                        navController.navigate(PrismTaskRoute.Leisure.route)
                                    },
                                    onToggleMusicDone = { viewModel.onToggleMusicDone() },
                                    onPickFlex = {
                                        navController.navigate(PrismTaskRoute.Leisure.route)
                                    },
                                    onToggleFlexDone = { viewModel.onToggleFlexDone() },
                                    onDismissHint = { viewModel.onDismissDailyEssentialsHint() },
                                    onOpenSettings = {
                                        navController.navigate("settings/layout")
                                    }
                                )
                            )
                        }
                    }

                    if (scheduledTodayHabits.isNotEmpty()) {
                        item(key = "section_scheduled_habits") {
                            val expanded = SECTION_SCHEDULED !in collapsedSections
                            CollapsibleSection(
                                emoji = "\uD83D\uDCC5",
                                title = "Scheduled Today",
                                count = scheduledTodayHabits.size,
                                accentColor = prismColors.secondary,
                                expanded = expanded,
                                onToggle = { viewModel.onToggleSectionCollapsed(SECTION_SCHEDULED) }
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    scheduledTodayHabits.forEach { hws ->
                                        BookableHabitReminderCard(
                                            habitWithStatus = hws,
                                            onClick = {
                                                navController.navigate(
                                                    PrismTaskRoute.HabitDetail.createRoute(hws.habit.id)
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (overdueBookableHabits.isNotEmpty()) {
                        items(overdueBookableHabits, key = { "overdue_bookable_${it.habit.id}" }) { hws ->
                            val daysAgo = if (hws.lastLogDate != null) {
                                java.util.concurrent.TimeUnit.MILLISECONDS.toDays(
                                    System.currentTimeMillis() - hws.lastLogDate
                                )
                            } else {
                                null
                            }
                            val label = if (daysAgo != null) "last done $daysAgo days ago" else "never done"
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        navController.navigate(
                                            PrismTaskRoute.HabitDetail.createRoute(hws.habit.id)
                                        )
                                    },
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                )
                            ) {
                                androidx.compose.foundation.layout.Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "\uD83D\uDCCB",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${hws.habit.name} \u2014 $label",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (SECTION_PLANNED !in hiddenSections && plannedTasks.isNotEmpty()) {
                        val expanded = SECTION_PLANNED !in collapsedSections
                        item(key = "section_planned") {
                            CollapsibleSection(
                                emoji = "\uD83D\uDCCC",
                                title = "Planned",
                                count = plannedTasks.size,
                                accentColor = prismColors.secondary,
                                expanded = expanded,
                                onToggle = { viewModel.onToggleSectionCollapsed(SECTION_PLANNED) }
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    plannedTasks.forEach { task ->
                                        SwipeableTaskItem(
                                            task = task,
                                            tags = taskTagsMap[task.id].orEmpty(),
                                            isPlanned = true,
                                            onComplete = { viewModel.onCompleteWithUndo(task.id) },
                                            onClick = {
                                                editorSheetTaskId = task.id
                                                showEditorSheet = true
                                            },
                                            onReschedule = { reschedulePopupTask = task },
                                            onMoveToProject = { moveToProjectSheetTask = task },
                                            onDuplicate = { viewModel.onDuplicateTask(task.id) },
                                            onDelete = { viewModel.onDeleteTaskWithUndo(task.id) },
                                            onMoveToTomorrow = { onMoveTaskToTomorrow(task) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (SECTION_PLAN_MORE !in hiddenSections) {
                        item(key = "plan_more") {
                            FilledTonalButton(
                                onClick = { viewModel.onShowPlanSheet() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Plan More")
                            }
                        }
                    }

                    if (SECTION_COMPLETED !in hiddenSections && completedToday.isNotEmpty()) {
                        val expanded = SECTION_COMPLETED !in collapsedSections
                        item(key = "section_completed") {
                            CollapsibleSection(
                                emoji = "\u2705",
                                title = "Completed",
                                count = completedToday.size,
                                accentColor = prismColors.primary,
                                expanded = expanded,
                                onToggle = { viewModel.onToggleSectionCollapsed(SECTION_COMPLETED) }
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    completedToday.forEach { task ->
                                        CompletedTaskItem(
                                            task = task,
                                            onUncomplete = { viewModel.onToggleComplete(task.id, true) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(key = "bottom_pad") {
                        Spacer(modifier = Modifier.height(120.dp))
                    }
                }
            }
        }
    }

    val topTemplates by viewModel.topTemplates.collectAsStateWithLifecycle()
    if (showPlanSheet) {
        PlanForTodaySheet(
            plannedTasks = plannedTasks,
            overdueTasks = overdueTasks,
            upcomingTasks = tasksNotInToday,
            projects = projects,
            startOfToday = startOfToday,
            topTemplates = topTemplates,
            onPlan = { viewModel.onPlanForToday(it) },
            onPlanMany = { viewModel.onPlanForToday(it) },
            onPlanAllOverdue = { viewModel.onPlanAllOverdue() },
            onUnplan = { viewModel.onRemoveFromToday(it) },
            onUseTemplate = { viewModel.onCreateTaskFromTemplateForToday(it) },
            onOpenManageTemplates = {
                viewModel.onDismissPlanSheet()
                navController.navigate(PrismTaskRoute.TemplateList.route)
            },
            onDismiss = { viewModel.onDismissPlanSheet() },
            onMultiCreate = { rawText ->
                viewModel.onDismissPlanSheet()
                navController.navigate(
                    PrismTaskRoute.MultiCreate.createRoute(rawText)
                )
            },
            onBatchCommand = { commandText ->
                viewModel.onDismissPlanSheet()
                navController.navigate(
                    PrismTaskRoute.BatchPreview.createRoute(commandText)
                )
            }
        )
    }

    if (showEditorSheet) {
        AddEditTaskSheetHost(
            taskId = editorSheetTaskId,
            projectId = null,
            initialDate = if (editorSheetTaskId == null) startOfToday else null,
            onDismiss = { showEditorSheet = false },
            onDeleteTask = { id -> viewModel.onDeleteTaskWithUndo(id) },
            onManageTemplates = {
                showEditorSheet = false
                navController.navigate(PrismTaskRoute.TemplateList.route)
            }
        )
    }

    if (showAiHub) {
        TodayAiHubSheet(
            navController = navController,
            onDismiss = { showAiHub = false },
            onShowUpsell = { feature -> upsellFeature = feature }
        )
    }

    upsellFeature?.let { feature ->
        ProUpsellSheet(
            feature = feature,
            currentTier = if (viewModel.isPro) {
                com.averycorp.prismtask.data.billing.UserTier.PRO
            } else {
                com.averycorp.prismtask.data.billing.UserTier.FREE
            },
            onUpgrade = {
                upsellFeature = null
                navController.navigate("settings/subscription")
            },
            onDismiss = { upsellFeature = null }
        )
    }

    reschedulePopupTask?.let { task ->
        val sod by viewModel.startOfDay.collectAsStateWithLifecycle()
        QuickReschedulePopup(
            hasDueDate = task.dueDate != null,
            onDismiss = { reschedulePopupTask = null },
            onReschedule = { newDate -> viewModel.onRescheduleTask(task.id, newDate) },
            onPlanForToday = { viewModel.onPlanTaskForToday(task.id) },
            sodHour = sod.hour,
            sodMinute = sod.minute
        )
    }

    moveToProjectSheetTask?.let { task ->
        var subtaskCount by remember(task.id) { mutableStateOf(0) }
        LaunchedEffect(task.id) {
            subtaskCount = viewModel.getSubtaskCount(task.id)
        }
        MoveToProjectSheet(
            projects = projects,
            taskCountByProject = taskCountByProject,
            currentProjectId = task.projectId,
            onDismiss = { moveToProjectSheetTask = null },
            onMove = { newProjectId ->
                moveToProjectSheetTask = null
                if (subtaskCount > 0) {
                    cascadeConfirmState = task to newProjectId
                } else {
                    viewModel.onMoveToProject(task.id, newProjectId)
                }
            },
            onCreateAndMove = { name ->
                moveToProjectSheetTask = null
                viewModel.onCreateProjectAndMoveTask(task.id, name, cascadeSubtasks = subtaskCount > 0)
            }
        )
    }

    cascadeConfirmState?.let { (task, newProjectId) ->
        AlertDialog(
            onDismissRequest = { cascadeConfirmState = null },
            title = { Text("Move Subtasks Too?") },
            text = { Text("'${task.title}' has subtasks. Should they move to the same project?") },
            confirmButton = {
                TextButton(onClick = {
                    cascadeConfirmState = null
                    viewModel.onMoveToProject(task.id, newProjectId, cascadeSubtasks = true)
                }) { Text("Yes, Move All") }
            },
            dismissButton = {
                TextButton(onClick = {
                    cascadeConfirmState = null
                    viewModel.onMoveToProject(task.id, newProjectId, cascadeSubtasks = false)
                }) { Text("No, Just This") }
            }
        )
    }

    if (showWelcomeBack) {
        WelcomeBackDialog(
            isLoading = welcomeBackLoading,
            message = welcomeBackMessage,
            onDismiss = { coachingViewModel.dismissWelcomeBack() },
            onStartFresh = {
                coachingViewModel.dismissWelcomeBack()
                viewModel.onPlanAllOverdue()
            }
        )
    }

    if (coachingUpgradePrompt) {
        AlertDialog(
            onDismissRequest = { coachingViewModel.dismissUpgradePrompt() }
        ) {
            UpgradePrompt(
                currentTier = coachingUserTier,
                feature = "AI Coaching",
                description = "Get personalized help when you're stuck on a task, plus energy-adaptive daily planning",
                onUpgrade = { _ ->
                    coachingViewModel.dismissUpgradePrompt()
                    navController.navigate("settings/subscription")
                },
                onDismiss = { coachingViewModel.dismissUpgradePrompt() }
            )
        }
    }
}
