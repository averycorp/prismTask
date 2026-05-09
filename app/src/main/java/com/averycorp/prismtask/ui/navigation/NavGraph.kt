package com.averycorp.prismtask.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.averycorp.prismtask.ui.coachmark.CoachmarkAnchors
import com.averycorp.prismtask.ui.coachmark.coachmarkAnchor
import com.averycorp.prismtask.ui.screens.habits.HabitListScreen
import com.averycorp.prismtask.ui.screens.onboarding.OnboardingScreen
import com.averycorp.prismtask.ui.screens.onboarding.OnboardingViewModel
import com.averycorp.prismtask.ui.screens.settings.SettingsScreen
import com.averycorp.prismtask.ui.screens.tasklist.TaskListScreen
import com.averycorp.prismtask.ui.screens.timer.TimerScreen
import com.averycorp.prismtask.ui.screens.today.TodayScreen
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismFonts
import com.averycorp.prismtask.ui.theme.PrismHudDivider
import com.averycorp.prismtask.ui.theme.prismGlow
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import kotlinx.coroutines.launch

sealed class PrismTaskRoute(
    val route: String
) {
    data object Today : PrismTaskRoute("today")

    data object TaskList : PrismTaskRoute("task_list")

    data object AddEditTask : PrismTaskRoute("add_edit_task?taskId={taskId}") {
        fun createRoute(taskId: Long? = null): String =
            if (taskId != null) "add_edit_task?taskId=$taskId" else "add_edit_task"
    }

    data object ProjectList : PrismTaskRoute("project_list")

    data object AddEditProject : PrismTaskRoute("add_edit_project?projectId={projectId}") {
        fun createRoute(projectId: Long? = null): String =
            if (projectId != null) "add_edit_project?projectId=$projectId" else "add_edit_project"
    }

    /** v1.4.0 Projects feature detail screen (Overview / Milestones / Tasks). */
    data object ProjectDetail : PrismTaskRoute("project_detail?projectId={projectId}") {
        fun createRoute(projectId: Long): String = "project_detail?projectId=$projectId"
    }

    /**
     * PrismTask-timeline-class scope (v1.8.x), audit § P10 option (b)
     * per O3 override: read-only roadmap surface — phases-with-tasks
     * (fractional progress bars), risk register, external anchors.
     * Distinct from [Timeline] (the daily time-block screen) per the
     * audit's naming-collision flag — Roadmap stays reserved for the
     * phase-Gantt surface.
     */
    data object ProjectRoadmap : PrismTaskRoute("project_roadmap?projectId={projectId}") {
        fun createRoute(projectId: Long): String = "project_roadmap?projectId=$projectId"
    }

    data object Settings : PrismTaskRoute("settings")

    data object TagManagement : PrismTaskRoute("tag_management")

    data object Search : PrismTaskRoute("search")

    data object Archive : PrismTaskRoute("archive")

    data object Auth : PrismTaskRoute("auth")

    data object WeekView : PrismTaskRoute("week_view")

    data object MonthView : PrismTaskRoute("month_view")

    data object Timeline : PrismTaskRoute("timeline")

    data object HabitList : PrismTaskRoute("habit_list")

    data object HabitsRecurring : PrismTaskRoute("habits_recurring")

    data object Timer : PrismTaskRoute("timer")

    data object AddEditHabit : PrismTaskRoute("add_edit_habit?habitId={habitId}") {
        fun createRoute(habitId: Long? = null): String =
            if (habitId != null) "add_edit_habit?habitId=$habitId" else "add_edit_habit"
    }

    data object HabitAnalytics : PrismTaskRoute("habit_analytics?habitId={habitId}") {
        fun createRoute(habitId: Long): String = "habit_analytics?habitId=$habitId"
    }

    data object HabitDetail : PrismTaskRoute("habit_detail?habitId={habitId}") {
        fun createRoute(habitId: Long): String = "habit_detail?habitId=$habitId"
    }

    data object SelfCare : PrismTaskRoute("self_care?routineType={routineType}") {
        fun createRoute(routineType: String = "morning"): String = "self_care?routineType=$routineType"
    }

    data object Medication : PrismTaskRoute("medication")

    data object MedicationLog : PrismTaskRoute("medication_log")

    data object Leisure : PrismTaskRoute("leisure")

    data object LeisureSettings : PrismTaskRoute("leisure_settings")

    data object Schoolwork : PrismTaskRoute("schoolwork")

    data object SyllabusReview : PrismTaskRoute("syllabus_review?uri={uri}") {
        fun createRoute(uri: String): String = "syllabus_review?uri=${android.net.Uri.encode(uri)}"
    }

    data object AddEditCourse : PrismTaskRoute("add_edit_course?courseId={courseId}") {
        fun createRoute(courseId: Long? = null): String =
            if (courseId != null) "add_edit_course?courseId=$courseId" else "add_edit_course"
    }

    data object EisenhowerMatrix : PrismTaskRoute("eisenhower_matrix")

    data object SmartPomodoro : PrismTaskRoute("smart_pomodoro")

    data object DailyBriefing : PrismTaskRoute("daily_briefing")

    data object WeeklyPlanner : PrismTaskRoute("weekly_planner")

    data object TemplateList : PrismTaskRoute("templates")

    data object TemplateBrowser : PrismTaskRoute("templates/browse")

    data object AddEditTemplate : PrismTaskRoute("templates/edit?templateId={templateId}") {
        fun createRoute(templateId: Long? = null): String =
            if (templateId != null) "templates/edit?templateId=$templateId" else "templates/edit"
    }

    data object AiChat : PrismTaskRoute("ai_chat?taskId={taskId}") {
        fun createRoute(taskId: Long? = null): String =
            if (taskId != null) "ai_chat?taskId=$taskId" else "ai_chat"
    }

    data object MainTabs : PrismTaskRoute("main_tabs")

    data object Onboarding : PrismTaskRoute("onboarding")

    data object MorningCheckIn : PrismTaskRoute("morning_check_in")

    data object BatchPreview : PrismTaskRoute("batch_preview?command={command}") {
        fun createRoute(command: String): String =
            "batch_preview?command=${android.net.Uri.encode(command)}"
    }

    /**
     * Project import preview — shows the parsed plan (project name,
     * phases, tasks, risks, anchors, dependencies) before any rows
     * land in Room. `uri` is null for paste imports (the content is
     * staged in [com.averycorp.prismtask.ui.screens.projects.PendingImportContent]
     * because nav args have a length cap).
     */
    data object ProjectImportPreview :
        PrismTaskRoute("project_import_preview?uri={uri}&asProject={asProject}") {
        fun createRoute(uri: String?, asProject: Boolean): String {
            val uriPart = uri?.let { "uri=${android.net.Uri.encode(it)}" } ?: "uri="
            return "project_import_preview?$uriPart&asProject=$asProject"
        }
    }

    data object BatchHistory : PrismTaskRoute("settings/batch_history")

    data object MultiCreate : PrismTaskRoute("multi_create?text={text}") {
        fun createRoute(text: String): String =
            "multi_create?text=${android.net.Uri.encode(text)}"
    }

    data object MoodAnalytics : PrismTaskRoute("mood_analytics")

    data object WeeklyBalanceReport : PrismTaskRoute("weekly_balance_report")

    data object BuiltInUpdates : PrismTaskRoute("builtin_updates")

    data object BuiltInUpdateDiff : PrismTaskRoute("builtin_updates/{templateKey}") {
        fun createRoute(templateKey: String): String = "builtin_updates/$templateKey"
    }

    data object PasteConversation : PrismTaskRoute("paste_conversation")

    data object WeeklyReview : PrismTaskRoute("weekly_review")

    data object WeeklyReviewsList : PrismTaskRoute("weekly_reviews_list")

    data object WeeklyReviewDetail : PrismTaskRoute("weekly_reviews_detail/{reviewId}") {
        fun createRoute(reviewId: Long): String = "weekly_reviews_detail/$reviewId"
    }

    data object MedicationRefill : PrismTaskRoute("medication_refill")

    data object BugReport : PrismTaskRoute(
        "bug_report?fromScreen={fromScreen}&screenshotUri={screenshotUri}"
    ) {
        fun createRoute(fromScreen: String = "", screenshotUri: String? = null): String {
            val base = "bug_report?fromScreen=${android.net.Uri.encode(fromScreen)}"
            return if (!screenshotUri.isNullOrBlank()) {
                "$base&screenshotUri=${android.net.Uri.encode(screenshotUri)}"
            } else {
                base
            }
        }
    }

    data object FeatureRequest : PrismTaskRoute("feature_request")

    data object DebugLog : PrismTaskRoute("debug_log")

    data object AdminBugReports : PrismTaskRoute("admin_bug_reports")

    data object AdminNotificationLog : PrismTaskRoute("admin_notification_log")

    data object TaskAnalytics : PrismTaskRoute("task_analytics?projectId={projectId}") {
        fun createRoute(projectId: Long? = null): String =
            if (projectId != null) "task_analytics?projectId=$projectId" else "task_analytics"
    }

    // v1.4.0 Notifications Overhaul: top-level hub + per-domain sub-screens
    data object NotificationsHub : PrismTaskRoute("notifications_hub")

    data object NotificationProfiles : PrismTaskRoute("notifications_profiles")

    data object NotificationTypes : PrismTaskRoute("notifications_types")

    data object NotificationBriefing : PrismTaskRoute("notifications_briefing")

    data object NotificationStreak : PrismTaskRoute("notifications_streak")

    data object NotificationCollaborator : PrismTaskRoute("notifications_collab")

    data object NotificationSound : PrismTaskRoute("notifications_sound")

    data object NotificationVibration : PrismTaskRoute("notifications_vibration")

    data object NotificationVisual : PrismTaskRoute("notifications_visual")

    data object NotificationLockScreen : PrismTaskRoute("notifications_lockscreen")

    data object NotificationQuietHours : PrismTaskRoute("notifications_quiet_hours")

    data object NotificationSnooze : PrismTaskRoute("notifications_snooze")

    data object NotificationEscalation : PrismTaskRoute("notifications_escalation")

    data object NotificationWatch : PrismTaskRoute("notifications_watch")

    data object NotificationTester : PrismTaskRoute("notifications_tester")

    /** v1.7+ Automation engine — list of user rules. */
    data object Automation : PrismTaskRoute("automation")

    /** v1.7+ Automation engine — execution log; optional ruleId filter. */
    data object AutomationLog : PrismTaskRoute("automation_log?ruleId={ruleId}") {
        fun createRoute(ruleId: Long? = null): String =
            if (ruleId != null) "automation_log?ruleId=$ruleId" else "automation_log"
    }

    /** v1.7+ Automation starter library — browse + import templates. */
    data object AutomationTemplateLibrary : PrismTaskRoute("automation_template_library")

    /** v1.7+ Automation engine — block-based rule editor; null ruleId = create. */
    data object AutomationEdit : PrismTaskRoute("automation_edit?ruleId={ruleId}") {
        fun createRoute(ruleId: Long? = null): String =
            if (ruleId != null) "automation_edit?ruleId=$ruleId" else "automation_edit"
    }
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

val ALL_BOTTOM_NAV_ITEMS = listOf(
    BottomNavItem(PrismTaskRoute.Today.route, "Today", Icons.Filled.Today, Icons.Outlined.Today),
    BottomNavItem(
        PrismTaskRoute.TaskList.route,
        "Tasks",
        Icons.AutoMirrored.Filled.FormatListBulleted,
        Icons.AutoMirrored.Outlined.FormatListBulleted
    ),
    BottomNavItem(PrismTaskRoute.HabitList.route, "Daily", Icons.Filled.FitnessCenter, Icons.Outlined.FitnessCenter),
    BottomNavItem(PrismTaskRoute.HabitsRecurring.route, "Recurring", Icons.Filled.Repeat, Icons.Outlined.Repeat),
    BottomNavItem(
        PrismTaskRoute.Medication.route,
        "Meds",
        Icons.Filled.LocalPharmacy,
        Icons.Outlined.LocalPharmacy
    ),
    BottomNavItem(PrismTaskRoute.Timer.route, "Timer", Icons.Filled.Timer, Icons.Outlined.Timer),
    BottomNavItem(PrismTaskRoute.Settings.route, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
)

private const val NAV_ANIM_DURATION = 300

/**
 * Map a bottom-nav route to its coachmark anchor id, or null if the route
 * is not a tour target. Used by the [PrismTaskNavGraph] bottom-bar loop to
 * register per-tab anchors on the [NavigationBarItem] that owns each route.
 */
private fun bottomNavCoachmarkAnchor(route: String): String? = when (route) {
    PrismTaskRoute.TaskList.route -> CoachmarkAnchors.NAV_TASKS_TAB
    PrismTaskRoute.HabitList.route -> CoachmarkAnchors.NAV_HABITS_TAB
    PrismTaskRoute.Medication.route -> CoachmarkAnchors.NAV_MEDS_TAB
    PrismTaskRoute.Timer.route -> CoachmarkAnchors.OPEN_TIMER_ENTRY
    PrismTaskRoute.Settings.route -> CoachmarkAnchors.NAV_SETTINGS_TAB
    else -> null
}

@Composable
fun PrismTaskNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    tabOrder: List<String> = ALL_BOTTOM_NAV_ITEMS.map { it.route },
    hiddenTabs: Set<String> = emptySet(),
    initialLaunchAction: WidgetLaunchAction? = null,
    initialSharedText: String? = null,
    hasCompletedOnboarding: Boolean = true
) {
    // Voice-input is the only launch action handled inline on Today (the rest
    // route via the exhaustive `when` below), so it gets its own keep-alive
    // state.
    val autoStartVoice = androidx.compose.runtime.remember(initialLaunchAction) {
        androidx.compose.runtime.mutableStateOf(
            initialLaunchAction is WidgetLaunchAction.VoiceInput
        )
    }
    // v1.4.0 V9: route incoming shared text into the Paste Conversation
    // screen with a pre-filled input. The screen observes its
    // SavedStateHandle for the "shared_text" arg and forwards it to
    // PasteConversationViewModel on first composition.
    // Track the shared text in mutable state so the NavHost can clear it
    // after the destination consumes it. Null when there's nothing to
    // forward.
    var pendingSharedText by androidx.compose.runtime.remember(initialSharedText) {
        androidx.compose.runtime.mutableStateOf(initialSharedText)
    }
    LaunchedEffect(initialSharedText) {
        if (!initialSharedText.isNullOrBlank()) {
            navController.navigate(PrismTaskRoute.PasteConversation.route)
        }
    }
    // Append any tabs that aren't yet in the saved order (e.g. new tabs added in an update).
    val effectiveOrder = tabOrder + ALL_BOTTOM_NAV_ITEMS.map { it.route }.filter { it !in tabOrder }
    val bottomNavItems = effectiveOrder
        .mapNotNull { route -> ALL_BOTTOM_NAV_ITEMS.find { it.route == route } }
        .filter { it.route !in hiddenTabs }
        .ifEmpty { ALL_BOTTOM_NAV_ITEMS.take(2) }

    val pagerState = rememberPagerState(pageCount = { bottomNavItems.size })
    val coroutineScope = rememberCoroutineScope()

    // Single dispatch site for every widget launch action. The compiler
    // enforces exhaustiveness against [WidgetLaunchAction]; adding a new
    // subclass without wiring it up is a build error rather than a silent
    // no-op (see docs/audits/DEFECT_FAMILY_HARDENING_AUDIT.md §C).
    LaunchedEffect(initialLaunchAction) {
        // Exhaustive `when` expression. Assigning to `_` forces the compiler
        // to validate every sealed subclass is covered — adding a new case
        // without a branch here is a build error.
        @Suppress("UNUSED_VARIABLE")
        val handled: Unit = when (val action = initialLaunchAction) {
            null -> Unit
            WidgetLaunchAction.OpenToday -> Unit
            WidgetLaunchAction.OpenTemplates ->
                navController.navigate(PrismTaskRoute.TemplateList.route)
            WidgetLaunchAction.OpenMatrix ->
                navController.navigate(PrismTaskRoute.EisenhowerMatrix.route)
            WidgetLaunchAction.OpenInbox ->
                navController.navigate(PrismTaskRoute.TaskList.route)
            WidgetLaunchAction.OpenMedication ->
                navController.navigate(PrismTaskRoute.Medication.route)
            WidgetLaunchAction.OpenInsights ->
                navController.navigate(PrismTaskRoute.TaskAnalytics.createRoute())
            WidgetLaunchAction.QuickAdd ->
                navController.navigate(PrismTaskRoute.AddEditTask.createRoute())
            is WidgetLaunchAction.OpenTask ->
                navController.navigate(
                    PrismTaskRoute.AddEditTask.createRoute(taskId = action.taskId)
                )
            // VoiceInput keeps the user on Today and is consumed by
            // [autoStartVoice] above — speech recognition starts inline
            // without a navigate() call.
            WidgetLaunchAction.VoiceInput -> Unit
            WidgetLaunchAction.OpenHabits ->
                bottomNavItems
                    .indexOfFirst { it.route == PrismTaskRoute.HabitList.route }
                    .let { idx -> if (idx >= 0) pagerState.scrollToPage(idx) }
            WidgetLaunchAction.OpenTimer ->
                bottomNavItems
                    .indexOfFirst { it.route == PrismTaskRoute.Timer.route }
                    .let { idx -> if (idx >= 0) pagerState.scrollToPage(idx) }
            // Coachmark tour resume entry — currently no external trigger
            // wires this; retained for future notification / widget hooks.
            // The in-app resume chip talks to the controller directly and
            // doesn't reach this branch.
            is WidgetLaunchAction.OpenTourStep -> Unit
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute == null || currentRoute == PrismTaskRoute.MainTabs.route

    // Keyboard shortcuts: Ctrl+1..4 switches tabs, Ctrl+N opens quick add,
    // Ctrl+F focuses search, Escape pops the backstack. These are best-effort
    // — the hosting Activity decides whether a hardware keyboard is present.
    val focusRequester = androidx.compose.runtime.remember {
        androidx.compose.ui.focus
            .FocusRequester()
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }
    val shortcutModifier = modifier
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { event ->
            if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (!event.isCtrlPressed && event.key != androidx.compose.ui.input.key.Key.Escape) {
                return@onPreviewKeyEvent false
            }
            when (event.key) {
                androidx.compose.ui.input.key.Key.N -> {
                    navController.navigate(PrismTaskRoute.AddEditTask.createRoute())
                    true
                }
                androidx.compose.ui.input.key.Key.F -> {
                    navController.navigate(PrismTaskRoute.Search.route)
                    true
                }
                androidx.compose.ui.input.key.Key.One -> {
                    coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    true
                }
                androidx.compose.ui.input.key.Key.Two -> {
                    if (bottomNavItems.size > 1) {
                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                    }
                    true
                }
                androidx.compose.ui.input.key.Key.Three -> {
                    if (bottomNavItems.size > 2) {
                        coroutineScope.launch { pagerState.animateScrollToPage(2) }
                    }
                    true
                }
                androidx.compose.ui.input.key.Key.Four -> {
                    if (bottomNavItems.size > 3) {
                        coroutineScope.launch { pagerState.animateScrollToPage(3) }
                    }
                    true
                }
                androidx.compose.ui.input.key.Key.Escape -> {
                    navController.popBackStack()
                    true
                }
                else -> false
            }
        }

    val prismColors = LocalPrismColors.current
    val prismFonts = LocalPrismFonts.current.body
    val attrs = LocalPrismAttrs.current

    Scaffold(
        modifier = shortcutModifier,
        containerColor = prismColors.background,
        bottomBar = {
            if (showBottomBar) {
                Column(
                    modifier = androidx.compose.ui.Modifier.coachmarkAnchor(
                        CoachmarkAnchors.BOTTOM_NAV_ROW
                    )
                ) {
                    PrismHudDivider()
                    NavigationBar(
                        containerColor = prismColors.background,
                        contentColor = prismColors.onBackground,
                        tonalElevation = 0.dp
                    ) {
                        bottomNavItems.forEachIndexed { index, item ->
                            val selected = pagerState.currentPage == index
                            val tabAnchorId = bottomNavCoachmarkAnchor(item.route)

                            NavigationBarItem(
                                selected = selected,
                                modifier = if (tabAnchorId != null) {
                                    Modifier.coachmarkAnchor(tabAnchorId)
                                } else {
                                    Modifier
                                },
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                                icon = {
                                    val iconScale by androidx.compose.animation.core.animateFloatAsState(
                                        targetValue = if (selected) 1.1f else 1f,
                                        animationSpec = androidx.compose.animation.core.spring(
                                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy
                                        ),
                                        label = "nav_icon_scale"
                                    )
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label,
                                        modifier = Modifier
                                            .scale(iconScale)
                                            .then(if (selected) Modifier.prismGlow(prismColors.primary, attrs.glow) else Modifier)
                                    )
                                },
                                label = {
                                    Text(
                                        text = item.label,
                                        fontFamily = prismFonts,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        softWrap = false
                                    )
                                },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = prismColors.primary,
                                    selectedTextColor = prismColors.primary,
                                    unselectedIconColor = prismColors.muted,
                                    unselectedTextColor = prismColors.muted,
                                    indicatorColor = prismColors.primary.copy(alpha = 0.12f)
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        val startDest = if (hasCompletedOnboarding) PrismTaskRoute.MainTabs.route else PrismTaskRoute.Onboarding.route

        NavHost(
            navController = navController,
            startDestination = startDest,
            modifier = Modifier.padding(innerPadding)
        ) {
            // Onboarding screen
            composable(
                route = PrismTaskRoute.Onboarding.route,
                enterTransition = { fadeIn(animationSpec = tween(NAV_ANIM_DURATION)) },
                exitTransition = { fadeOut(animationSpec = tween(NAV_ANIM_DURATION)) }
            ) {
                val onboardingViewModel: OnboardingViewModel = hiltViewModel()
                OnboardingScreen(
                    viewModel = onboardingViewModel,
                    onComplete = {
                        navController.navigate(PrismTaskRoute.MainTabs.route) {
                            popUpTo(PrismTaskRoute.Onboarding.route) { inclusive = true }
                        }
                    }
                )
            }

            // Main tab screens — swipeable via HorizontalPager
            composable(
                route = PrismTaskRoute.MainTabs.route,
                enterTransition = { fadeIn(animationSpec = tween(NAV_ANIM_DURATION)) },
                exitTransition = { fadeOut(animationSpec = tween(NAV_ANIM_DURATION)) },
                popEnterTransition = { fadeIn(animationSpec = tween(NAV_ANIM_DURATION)) },
                popExitTransition = { fadeOut(animationSpec = tween(NAV_ANIM_DURATION)) }
            ) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    key = { bottomNavItems[it].route },
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (bottomNavItems[page].route) {
                        PrismTaskRoute.Today.route -> TodayScreen(
                            navController = navController,
                            autoStartVoice = autoStartVoice.value,
                            onVoiceAutoStartConsumed = { autoStartVoice.value = false },
                            onNavigateToHabits = {
                                val habitIndex = bottomNavItems.indexOfFirst {
                                    it.route == PrismTaskRoute.HabitList.route
                                }
                                if (habitIndex >= 0) {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(habitIndex)
                                    }
                                }
                            }
                        )
                        PrismTaskRoute.TaskList.route -> TaskListScreen(navController)
                        PrismTaskRoute.HabitList.route -> HabitListScreen(navController, filter = "daily")
                        PrismTaskRoute.HabitsRecurring.route -> HabitListScreen(navController, filter = "recurring")
                        PrismTaskRoute.Medication.route ->
                            com.averycorp.prismtask.ui.screens.medication.MedicationScreen(navController)
                        PrismTaskRoute.Timer.route -> TimerScreen(navController)
                        PrismTaskRoute.Settings.route -> SettingsScreen(navController)
                    }
                }
            }

            featureRoutes(navController, initialSharedText = pendingSharedText)
        }
    }
}
