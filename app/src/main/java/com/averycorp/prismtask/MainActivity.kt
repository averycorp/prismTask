package com.averycorp.prismtask

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.averycorp.prismtask.core.time.LocalDateFlow
import com.averycorp.prismtask.data.billing.BillingManager
import com.averycorp.prismtask.data.diagnostics.DiagnosticLogger
import com.averycorp.prismtask.data.preferences.AppearancePrefs
import com.averycorp.prismtask.data.preferences.OnboardingPreferences
import com.averycorp.prismtask.data.preferences.TabPreferences
import com.averycorp.prismtask.data.preferences.ThemePreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.GenericPreferenceSyncService
import com.averycorp.prismtask.data.remote.SortPreferencesSyncService
import com.averycorp.prismtask.data.remote.SyncService
import com.averycorp.prismtask.data.remote.ThemePreferencesSyncService
import com.averycorp.prismtask.data.remote.sync.BackendSyncService
import com.averycorp.prismtask.domain.automation.AutomationEvent
import com.averycorp.prismtask.domain.automation.AutomationEventBus
import com.averycorp.prismtask.domain.rating.RatingPromptDecision
import com.averycorp.prismtask.domain.rating.RatingPromptTriggerHelper
import com.averycorp.prismtask.notifications.NotificationHelper
import com.averycorp.prismtask.ui.rating.PlayReviewLauncher
import com.averycorp.prismtask.ui.rating.RatingPromptSheet
import com.averycorp.prismtask.ui.navigation.PrismTaskNavGraph
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.screens.tasklist.components.DayBounds
import com.averycorp.prismtask.ui.screens.tasklist.components.LocalDayBounds
import com.averycorp.prismtask.ui.theme.LocalWindowSizeClass
import com.averycorp.prismtask.ui.theme.PriorityColors
import com.averycorp.prismtask.ui.theme.PrismTaskTheme
import com.averycorp.prismtask.ui.theme.ThemeViewModel
import com.averycorp.prismtask.ui.theme.themeOverlay
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var themePreferences: ThemePreferences

    @Inject
    lateinit var tabPreferences: TabPreferences

    @Inject
    lateinit var syncService: SyncService

    @Inject
    lateinit var sortPreferencesSyncService: SortPreferencesSyncService

    @Inject
    lateinit var themePreferencesSyncService: ThemePreferencesSyncService

    @Inject
    lateinit var genericPreferenceSyncService: GenericPreferenceSyncService

    @Inject
    lateinit var onboardingPreferences: OnboardingPreferences

    @Inject
    lateinit var taskBehaviorPreferences: com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences

    @Inject
    lateinit var billingManager: BillingManager

    @Inject
    lateinit var a11yPreferences: com.averycorp.prismtask.data.preferences.A11yPreferences

    @Inject
    lateinit var habitListPreferences: com.averycorp.prismtask.data.preferences.HabitListPreferences

    @Inject
    lateinit var userPreferencesDataStore: UserPreferencesDataStore

    @Inject
    lateinit var diagnosticLogger: DiagnosticLogger

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var canonicalOnboardingSync: com.averycorp.prismtask.data.remote.CanonicalOnboardingSync

    @Inject
    lateinit var backendSyncService: BackendSyncService

    @Inject
    lateinit var localDateFlow: LocalDateFlow

    @Inject
    lateinit var coachmarkController: com.averycorp.prismtask.ui.coachmark.CoachmarkController

    @Inject
    lateinit var ratingPromptTriggerHelper: RatingPromptTriggerHelper

    @Inject
    lateinit var playReviewLauncher: PlayReviewLauncher

    @Inject
    lateinit var automationEventBus: AutomationEventBus

    companion object {
        /** Intent extra key set by widgets to route deep-links. Wire-id values
         * live on [WidgetLaunchAction] subclasses. */
        const val EXTRA_LAUNCH_ACTION = "com.averycorp.prismtask.LAUNCH_ACTION"
        const val EXTRA_TASK_ID = "task_id"
    }

    private val launchActionState = mutableStateOf<WidgetLaunchAction?>(null)
    private val pendingCustomPromptState = mutableStateOf(false)

    @OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Channel creation reads DataStore preferences, so it runs off the
        // Main thread via lifecycleScope + IO dispatcher. Users see the app
        // before the channel finishes registering; that's fine because any
        // notification posted before it completes will lazily trigger its
        // own channel creation via the same suspend path.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                NotificationHelper.createNotificationChannel(this@MainActivity)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to create notification channel", e)
            }
        }
        try {
            syncService.startAutoSync()
        } catch (e: Exception) {
            Log.e("MainActivity", "Auto-sync failed to start", e)
        }
        try {
            sortPreferencesSyncService.startPushObserver()
        } catch (e: Exception) {
            Log.e("MainActivity", "Sort prefs push observer failed to start", e)
        }
        try {
            themePreferencesSyncService.startPushObserver()
        } catch (e: Exception) {
            Log.e("MainActivity", "Theme prefs push observer failed to start", e)
        }
        try {
            themePreferencesSyncService.ensurePullListener()
        } catch (e: Exception) {
            Log.e("MainActivity", "Theme prefs pull listener failed to start", e)
        }
        try {
            genericPreferenceSyncService.startPushObserver()
            genericPreferenceSyncService.ensurePullListener()
        } catch (e: Exception) {
            Log.e("MainActivity", "Generic prefs sync failed to start", e)
        }
        try {
            billingManager.initialize(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "Billing init failed", e)
        }
        try {
            setCrashlyticsUserId()
        } catch (e: Exception) {
            Log.e("MainActivity", "Crashlytics user ID setup failed", e)
        }
        // Rating-prompt counter bookkeeping: stamp first-launch (no-op
        // after the first cold start) and bump session count.
        lifecycleScope.launch {
            try {
                ratingPromptTriggerHelper.onAppStart()
            } catch (e: Exception) {
                Log.w("MainActivity", "Rating prompt onAppStart failed", e)
            }
        }
        // Subscribe to TaskCompleted bus events. On each real completion
        // (the bus emit guards against no-ops at TaskRepository.kt:412)
        // the helper updates counters and returns whether to surface a
        // prompt. Activity-scoped collector means prompts only fire while
        // the UI is alive — background/widget completions don't trigger.
        lifecycleScope.launch {
            automationEventBus.events
                .filterIsInstance<AutomationEvent.TaskCompleted>()
                .collect {
                    try {
                        when (ratingPromptTriggerHelper.onTaskCompleted()) {
                            RatingPromptDecision.PlayReview -> {
                                val launched = playReviewLauncher.launch(this@MainActivity)
                                if (launched) {
                                    ratingPromptTriggerHelper.recordPlayReviewShown()
                                }
                            }
                            RatingPromptDecision.CustomPrompt -> {
                                pendingCustomPromptState.value = true
                                ratingPromptTriggerHelper.recordCustomPromptShown()
                            }
                            RatingPromptDecision.None -> Unit
                        }
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Rating prompt evaluation failed", e)
                    }
                }
        }
        launchActionState.value = parseLaunchAction(intent)
        // v1.4.0 V9: support Android share-intent entry into the Paste
        // Conversation screen. When another app sends text to PrismTask
        // (ACTION_SEND, text/plain) the text is forwarded to the nav
        // graph which navigates to PasteConversation with a pre-filled
        // input. Non-share launches leave this null.
        val initialSharedText: String? = when {
            intent?.action == Intent.ACTION_SEND && intent?.type == "text/plain" ->
                intent?.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        setContent {
            // Hilt-scoped ThemeViewModel owns the persisted PrismTheme choice
            // and writes back through ThemePreferences when the user picks a
            // new theme in Settings.
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val currentPrismTheme by themeViewModel.currentTheme
                .collectAsStateWithLifecycle()

            val themeMode by themePreferences
                .getThemeMode()
                .collectAsStateWithLifecycle(initialValue = "system")
            val accentColor by themePreferences
                .getAccentColor()
                .collectAsStateWithLifecycle(initialValue = "#2563EB")
            val backgroundColorOverride by themePreferences
                .getBackgroundColor()
                .collectAsStateWithLifecycle(initialValue = "")
            val surfaceColorOverride by themePreferences
                .getSurfaceColor()
                .collectAsStateWithLifecycle(initialValue = "")
            val errorColorOverride by themePreferences
                .getErrorColor()
                .collectAsStateWithLifecycle(initialValue = "")
            val fontScale by themePreferences
                .getFontScale()
                .collectAsStateWithLifecycle(initialValue = 1.0f)

            val priorityNone by themePreferences
                .getPriorityColorNone()
                .collectAsStateWithLifecycle(initialValue = "")
            val priorityLow by themePreferences
                .getPriorityColorLow()
                .collectAsStateWithLifecycle(initialValue = "")
            val priorityMedium by themePreferences
                .getPriorityColorMedium()
                .collectAsStateWithLifecycle(initialValue = "")
            val priorityHigh by themePreferences
                .getPriorityColorHigh()
                .collectAsStateWithLifecycle(initialValue = "")
            val priorityUrgent by themePreferences
                .getPriorityColorUrgent()
                .collectAsStateWithLifecycle(initialValue = "")

            val hasCompletedOnboarding by onboardingPreferences
                .hasCompletedOnboarding()
                .collectAsStateWithLifecycle(initialValue = null as Boolean?)

            val reduceMotion by a11yPreferences
                .getReduceMotion()
                .collectAsStateWithLifecycle(initialValue = false)
            val highContrast by a11yPreferences
                .getHighContrast()
                .collectAsStateWithLifecycle(initialValue = false)
            val largeTouchTargets by a11yPreferences
                .getLargeTouchTargets()
                .collectAsStateWithLifecycle(initialValue = false)

            val tabOrder by tabPreferences
                .getTabOrder()
                .collectAsStateWithLifecycle(initialValue = TabPreferences.DEFAULT_ORDER)
            val baseHiddenTabs by tabPreferences
                .getHiddenTabs()
                .collectAsStateWithLifecycle(initialValue = emptySet())
            // Medications top-level nav tile gated on the same
            // isMedicationEnabled toggle that already hides the
            // SelfCareItem("medication") row in HabitListViewModel —
            // single source of truth across both surfaces.
            val medicationEnabled by habitListPreferences
                .isMedicationEnabled()
                .collectAsStateWithLifecycle(initialValue = true)
            val hiddenTabs = if (medicationEnabled) {
                baseHiddenTabs
            } else {
                baseHiddenTabs + com.averycorp.prismtask.ui.navigation.PrismTaskRoute.Medication.route
            }

            val appearance by userPreferencesDataStore.appearanceFlow
                .collectAsStateWithLifecycle(initialValue = AppearancePrefs())

            val priorityColors = PriorityColors(
                none = parseColorOrDefault(priorityNone, PriorityColors().none),
                low = parseColorOrDefault(priorityLow, PriorityColors().low),
                medium = parseColorOrDefault(priorityMedium, PriorityColors().medium),
                high = parseColorOrDefault(priorityHigh, PriorityColors().high),
                urgent = parseColorOrDefault(priorityUrgent, PriorityColors().urgent)
            )

            val notificationSnackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
            val notificationSnackbarScope = rememberCoroutineScope()
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (!granted) {
                    notificationSnackbarScope.launch {
                        notificationSnackbarHostState.showSnackbar(
                            message = "Notifications disabled \u2014 reminders won't work. Enable in Settings.",
                            duration = androidx.compose.material3.SnackbarDuration.Long
                        )
                    }
                }
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            // Exact-alarm permission. On API 33+ we declare USE_EXACT_ALARM
            // which is auto-granted for reminder-style apps, so no prompt is
            // needed. On API 31-32 the user must toggle SCHEDULE_EXACT_ALARM
            // via system Settings, otherwise reminders silently fall back to
            // inexact alarms (which Samsung/other OEMs aggressively delay).
            var showExactAlarmDialog by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                ) {
                    val am = getSystemService(AlarmManager::class.java)
                    if (am != null && !am.canScheduleExactAlarms()) {
                        showExactAlarmDialog = true
                    }
                }
            }
            if (showExactAlarmDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showExactAlarmDialog = false },
                    title = { androidx.compose.material3.Text("Enable Exact Reminders") },
                    text = {
                        androidx.compose.material3.Text(
                            "PrismTask needs exact alarm permission for reliable " +
                                "reminders. Without it, notifications may be delayed " +
                                "or skipped by the system."
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showExactAlarmDialog = false
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .apply { data = Uri.parse("package:$packageName") }
                                startActivity(intent)
                            } catch (e: Exception) {
                                Log.w("MainActivity", "Failed to open exact alarm settings", e)
                            }
                        }) {
                            androidx.compose.material3.Text("Open Settings")
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showExactAlarmDialog = false
                        }) {
                            androidx.compose.material3.Text("Not Now")
                        }
                    }
                )
            }

            // Start of Day first-launch prompt. Only shown after the user has
            // completed onboarding, and only once. The user can still change the
            // value later from Settings -> Global Defaults -> Start of Day.
            //
            // The backfill + gate check are deliberately collapsed into a single
            // LaunchedEffect body (rather than two LaunchedEffects) so the
            // backfill's DataStore write always completes before the gate check
            // reads. Two separate LaunchedEffects would race via Compose's
            // coroutine scheduling and could let the gate check decide to show
            // the prompt before the backfill's write took effect.
            var showStartOfDayPrompt by remember { mutableStateOf(false) }
            LaunchedEffect(hasCompletedOnboarding) {
                if (hasCompletedOnboarding != true) return@LaunchedEffect

                // Migration backfill for v1.4.0 SoD skip-race. Heals any install
                // that landed in the transitional state
                // `hasCompletedOnboarding = true` with `hasSetStartOfDay = false`
                // — either because it shipped before the
                // `OnboardingViewModel.checkExistingUserAndMaybeSkip` write
                // reorder fix, or because a variant of the cross-DataStore
                // observer race slipped through. Guarded implicitly on
                // `completed == true` via the early-return above, so
                // mid-onboarding users are untouched.
                //
                // TODO(v2.2): remove once the pre-fix install population has
                // rolled over. The gate check below is still correct on its
                // own; this backfill just prevents the prompt from showing
                // during the transition window.
                val sodSetBefore = taskBehaviorPreferences.getHasSetStartOfDay().first()
                if (!sodSetBefore) {
                    taskBehaviorPreferences.setHasSetStartOfDay(true)
                }

                // Gate check. Kept as defensive coverage for any future path
                // that lands on MainTabs with `hasCompletedOnboarding = true`
                // and `hasSetStartOfDay = false` that the backfill above didn't
                // heal. In the current codebase this branch is unreachable
                // (the backfill just wrote true), but removing it would couple
                // the prompt's correctness to the backfill's invariant — better
                // to keep both.
                val alreadySet = taskBehaviorPreferences.getHasSetStartOfDay().first()
                if (!alreadySet) showStartOfDayPrompt = true
            }
            if (showStartOfDayPrompt) {
                com.averycorp.prismtask.ui.components.StartOfDayPickerDialog(
                    // Default the picker to 4 AM as the spec's recommended
                    // starting value. The user can still pick anything.
                    initialHour = 4,
                    initialMinute = 0,
                    dismissable = false,
                    onConfirm = { h, m ->
                        showStartOfDayPrompt = false
                        notificationSnackbarScope.launch {
                            taskBehaviorPreferences.setStartOfDay(h, m)
                            com.averycorp.prismtask.workers.DailyResetWorker
                                .schedule(this@MainActivity, h, m)
                        }
                    },
                    onDismiss = { }
                )
            }

            // Samsung (and other OEM) battery optimization prompt. Samsung
            // devices aggressively kill background alarms even when exact
            // alarms are granted, so we ask the user once to whitelist
            // PrismTask from battery optimization. The dialog is shown at
            // most once per install regardless of the user's choice.
            var showBatteryOptimizationDialog by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return@LaunchedEffect
                val alreadyShown = onboardingPreferences
                    .hasShownBatteryOptimizationPrompt()
                    .first()
                if (alreadyShown) return@LaunchedEffect
                val pm = getSystemService(PowerManager::class.java)
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    showBatteryOptimizationDialog = true
                }
            }
            if (showBatteryOptimizationDialog) {
                val dismissAndRecord: () -> Unit = {
                    showBatteryOptimizationDialog = false
                    notificationSnackbarScope.launch {
                        onboardingPreferences.setBatteryOptimizationPromptShown()
                    }
                }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = dismissAndRecord,
                    title = { androidx.compose.material3.Text("Improve Reminder Reliability") },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            androidx.compose.material3.Text(
                                "Samsung devices may delay notifications. Tap below to " +
                                    "disable battery optimization for PrismTask so " +
                                    "reminders fire on time."
                            )
                            androidx.compose.foundation.layout.Spacer(
                                modifier = Modifier.size(8.dp)
                            )
                            androidx.compose.material3.Text(
                                "Samsung also keeps \"Put Unused Apps to Sleep\" and " +
                                    "\"Deep Sleeping Apps\" lists under Settings \u2192 " +
                                    "Battery. If reminders still miss, make sure " +
                                    "PrismTask isn't on those lists."
                            )
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            try {
                                @SuppressLint("BatteryLife")
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                ).apply { data = Uri.parse("package:$packageName") }
                                startActivity(intent)
                            } catch (e: Exception) {
                                Log.w(
                                    "MainActivity",
                                    "Failed to open battery optimization settings",
                                    e
                                )
                            }
                            dismissAndRecord()
                        }) {
                            androidx.compose.material3.Text("Open Settings")
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = dismissAndRecord) {
                            androidx.compose.material3.Text("Not Now")
                        }
                    }
                )
            }

            // Once Firebase confirms we're signed in, refresh admin status
            // from the backend so the UI (and ProFeatureGate) reflects admin
            // privileges without waiting for a manual sync to run.
            val isSignedIn by authManager.isSignedIn.collectAsStateWithLifecycle()
            LaunchedEffect(isSignedIn) {
                if (isSignedIn) {
                    try {
                        backendSyncService.checkAdminStatus()
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Admin status check failed", e)
                    }
                }
            }

            // Cross-platform onboarding hand-off: when signed in, pull the
            // canonical `users/{uid}.onboardingCompletedAt` field that the
            // web client writes (see `web/src/stores/onboardingStore.ts`)
            // and stamp the local DataStore mirror so a user who completed
            // onboarding on web doesn't see it again on Android. The hydrate
            // helper is one-way and idempotent — if local already says
            // completed, nothing happens.
            LaunchedEffect(isSignedIn) {
                if (!isSignedIn) return@LaunchedEffect
                val uid = authManager.userId ?: return@LaunchedEffect
                try {
                    val canonical = canonicalOnboardingSync.readCompletedAt(uid)
                    onboardingPreferences.hydrateFromCanonicalCloud(canonical)
                } catch (e: Exception) {
                    Log.w("MainActivity", "Canonical onboarding hydrate failed", e)
                }
            }

            PrismTaskTheme(
                prismTheme = currentPrismTheme,
                themeMode = themeMode,
                accentColor = accentColor,
                backgroundColorOverride = backgroundColorOverride,
                surfaceColorOverride = surfaceColorOverride,
                errorColorOverride = errorColorOverride,
                fontScale = fontScale,
                priorityColors = priorityColors,
                reduceMotion = reduceMotion,
                highContrast = highContrast,
                largeTouchTargets = largeTouchTargets,
                compactMode = appearance.compactMode,
                cardCornerRadius = appearance.cardCornerRadius,
                showCardBorders = appearance.showTaskCardBorders
            ) {
                val navController = androidx.navigation.compose.rememberNavController()

                if (hasCompletedOnboarding == null) {
                    // DataStore hasn't emitted yet — show a minimal loading state
                    // so we don't flash the wrong screen
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    // SoD-anchored day bounds for due-date labels. Tracks the
                    // user's logical date via LocalDateFlow so cards re-key at
                    // every Start-of-Day boundary crossing — see
                    // docs/audits/TODAY_LABEL_SOD_BOUNDARY_AUDIT.md.
                    val logicalDate by localDateFlow
                        .observe(taskBehaviorPreferences.getStartOfDay())
                        .collectAsStateWithLifecycle(initialValue = java.time.LocalDate.now())
                    val dayBounds = remember(logicalDate) { DayBounds.logical(logicalDate) }

                    val windowSizeClass = androidx.compose.material3.windowsizeclass
                        .calculateWindowSizeClass(this@MainActivity)
                    CompositionLocalProvider(
                        LocalDayBounds provides dayBounds,
                        LocalWindowSizeClass provides windowSizeClass
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .themeOverlay(currentPrismTheme)
                        ) {
                            val launchAction by launchActionState
                            // Mount the coachmark host above the nav graph so
                            // the overlay sits on top of every screen and the
                            // anchor registry is available to all participating
                            // surfaces. The host is a no-op until the
                            // controller's state machine moves to ShowingStep.
                            com.averycorp.prismtask.ui.coachmark.CoachmarkHost(
                                controller = coachmarkController,
                                onNavigateRoute = { routeKey ->
                                    handleCoachmarkRoute(routeKey, navController)
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                PrismTaskNavGraph(
                                    modifier = Modifier.fillMaxSize(),
                                    navController = navController,
                                    tabOrder = tabOrder,
                                    hiddenTabs = hiddenTabs,
                                    initialLaunchAction = launchAction,
                                    initialSharedText = initialSharedText,
                                    hasCompletedOnboarding = hasCompletedOnboarding!!
                                )
                            }
                            // Auto-launch the coachmark tour on first entry
                            // post-onboarding. `tryStart` is a no-op when
                            // ineligible, completed, or dismissed.
                            LaunchedEffect(hasCompletedOnboarding) {
                                if (hasCompletedOnboarding == true) {
                                    coachmarkController.tryStart(isProActive = true)
                                }
                            }

                            // Notification permission denial snackbar
                            androidx.compose.material3.SnackbarHost(
                                hostState = notificationSnackbarHostState,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 80.dp)
                            )

                            // Floating feedback button for beta/debug builds
                            if (BuildConfig.DEBUG) {
                                com.averycorp.prismtask.ui.components.FeedbackButton(
                                    onClick = {
                                        navController.navigate(PrismTaskRoute.BugReport.createRoute("FloatingButton"))
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 16.dp, bottom = 140.dp)
                                )
                            }

                            // Custom in-app rating prompt (E2). Shown when
                            // RatingPromptTriggerHelper.onTaskCompleted
                            // returned CustomPrompt (gates: post-onboarding,
                            // sessionCount > 3, no-recent-crash, N/M/cooldown
                            // satisfied). Posts to /api/v1/feedback/in-app on
                            // submit; auto-dismisses on success.
                            val showCustomPrompt by pendingCustomPromptState
                            if (showCustomPrompt) {
                                RatingPromptSheet(
                                    onDismiss = { pendingCustomPromptState.value = false }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchActionState.value = parseLaunchAction(intent)
    }

    private fun parseLaunchAction(intent: Intent?): WidgetLaunchAction? {
        if (intent == null) return null
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it >= 0 }
        return WidgetLaunchAction.deserialize(
            wireId = intent.getStringExtra(EXTRA_LAUNCH_ACTION),
            taskId = taskId
        )
    }

    /**
     * Translate a coachmark navigation route key into an actual NavController
     * call. The CoachmarkHost emits a route key when a tour step's action is
     * [com.averycorp.prismtask.ui.coachmark.CoachmarkAction.Navigate]; this
     * function maps each known route to a navigation call and is a no-op for
     * unknown keys (so adding a new route in tour content doesn't crash).
     */
    private fun handleCoachmarkRoute(
        routeKey: String,
        navController: androidx.navigation.NavController
    ) {
        when (routeKey) {
            com.averycorp.prismtask.ui.coachmark.CoachmarkRoutes.TASKS,
            com.averycorp.prismtask.ui.coachmark.CoachmarkRoutes.HABITS,
            com.averycorp.prismtask.ui.coachmark.CoachmarkRoutes.MEDICATIONS,
            com.averycorp.prismtask.ui.coachmark.CoachmarkRoutes.SETTINGS -> {
                // Top-level bottom-nav targets — host pager is the navigator,
                // not the NavController. Tour steps that target tabs leave
                // the user on Today; the spotlight on the tab itself is the
                // teaching surface, not a navigation.
            }
            com.averycorp.prismtask.ui.coachmark.CoachmarkRoutes.EISENHOWER ->
                navController.navigate(PrismTaskRoute.EisenhowerMatrix.route)
            com.averycorp.prismtask.ui.coachmark.CoachmarkRoutes.TIMER ->
                navController.navigate(PrismTaskRoute.Timer.route)
            com.averycorp.prismtask.ui.coachmark.CoachmarkRoutes.SETTINGS_APPEARANCE ->
                navController.navigate(PrismTaskRoute.Settings.route)
        }
    }

    private fun setCrashlyticsUserId() {
        val user = FirebaseAuth.getInstance().currentUser
        FirebaseCrashlytics.getInstance().setUserId(user?.uid ?: "anonymous")
    }

    private fun parseColorOrDefault(hex: String, default: Color): Color {
        if (hex.isBlank()) return default
        return try {
            Color(android.graphics.Color.parseColor(hex))
        } catch (_: Exception) {
            default
        }
    }
}
