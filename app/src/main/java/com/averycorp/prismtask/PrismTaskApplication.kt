package com.averycorp.prismtask

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.averycorp.prismtask.data.diagnostics.MigrationInstrumentor
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.ThemePreferences
import com.averycorp.prismtask.data.remote.AutomationDuplicateBackfiller
import com.averycorp.prismtask.data.remote.BuiltInHabitReconciler
import com.averycorp.prismtask.data.remote.LifeCategoryBackfiller
import com.averycorp.prismtask.data.remote.MedicationMigrationRunner
import com.averycorp.prismtask.data.repository.LeisureRepository
import com.averycorp.prismtask.data.repository.SchoolworkRepository
import com.averycorp.prismtask.data.seed.AutomationSampleRulesSeeder
import com.averycorp.prismtask.data.seed.TemplateSeeder
import com.averycorp.prismtask.domain.automation.AutomationEngine
import com.averycorp.prismtask.domain.rating.RecentCrashSignal
import com.averycorp.prismtask.notifications.MedicationClockRescheduler
import com.averycorp.prismtask.notifications.MedicationIntervalRescheduler
import com.averycorp.prismtask.notifications.MedicationReminderScheduler
import com.averycorp.prismtask.notifications.NotificationWorkerScheduler
import com.averycorp.prismtask.widget.WidgetUpdateManager
import com.averycorp.prismtask.workers.AutoArchiveWorker
import com.averycorp.prismtask.workers.AutomationTimeTickWorker
import com.averycorp.prismtask.workers.CalendarSyncScheduler
import com.averycorp.prismtask.workers.DailyResetWorker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class PrismTaskApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var schoolworkRepository: SchoolworkRepository

    @Inject
    lateinit var leisureRepository: LeisureRepository

    @Inject
    lateinit var taskBehaviorPreferences: TaskBehaviorPreferences

    @Inject
    lateinit var templateSeeder: TemplateSeeder

    @Inject
    lateinit var builtInHabitReconciler: BuiltInHabitReconciler

    @Inject
    lateinit var lifeCategoryBackfiller: LifeCategoryBackfiller

    @Inject
    lateinit var medicationMigrationRunner: MedicationMigrationRunner

    @Inject
    lateinit var calendarSyncScheduler: CalendarSyncScheduler

    @Inject
    lateinit var notificationWorkerScheduler: NotificationWorkerScheduler

    @Inject
    lateinit var medicationIntervalRescheduler: MedicationIntervalRescheduler

    @Inject
    lateinit var medicationClockRescheduler: MedicationClockRescheduler

    @Inject
    lateinit var medicationReminderScheduler: MedicationReminderScheduler

    @Inject
    lateinit var themePreferences: ThemePreferences

    @Inject
    lateinit var widgetUpdateManager: WidgetUpdateManager

    @Inject
    lateinit var automationEngine: AutomationEngine

    @Inject
    lateinit var automationSampleRulesSeeder: AutomationSampleRulesSeeder

    @Inject
    lateinit var automationDuplicateBackfiller: AutomationDuplicateBackfiller

    @Inject
    lateinit var recentCrashSignal: RecentCrashSignal

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val FIRESTORE_EMULATOR_PORT = 8080
        const val AUTH_EMULATOR_PORT = 9099
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration
            .Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        configureFirebaseEmulator()
        configureCrashlytics()
        installRatingPromptCrashHook()
        // Belt-and-braces flush for migration events that fired
        // before Firebase was ready on cold-boot (BootReceiver) paths.
        // The RoomDatabase.Callback.onOpen hook is the primary flush
        // point; this catches the rare race where the DB was opened
        // by a worker before Application.onCreate ran.
        try {
            MigrationInstrumentor.flushPending(this)
        } catch (_: Exception) {
            // Drop — migration events are best-effort by design.
        }
        try {
            scheduleAutoArchive()
            scheduleDailyReset()
            scheduleNotificationWorkers()
            scheduleWidgetRefresh()
            observeThemeChangesForWidgets()
            scheduleCalendarSync()
            scheduleBatchUndoSweep()
        } catch (e: Exception) {
            android.util.Log.e("PrismTaskApp", "Worker scheduling failed", e)
            try {
                FirebaseCrashlytics.getInstance().recordException(e)
            } catch (_: Exception) {
                // Firebase not available
            }
        }
        try {
            seedStructuralHabits()
            seedBuiltInTemplates()
            runBuiltInBackfill()
            runDriftCleanup()
            runLifeCategoryBackfill()
            runMedicationMigrationPasses()
            startMedicationReschedulers()
            startAutomationEngine()
        } catch (e: Exception) {
            android.util.Log.e("PrismTaskApp", "Seeding kickoff failed", e)
            try {
                FirebaseCrashlytics.getInstance().recordException(e)
            } catch (_: Exception) {
                // Firebase not available
            }
        }
    }

    /**
     * Stamps `last_crash_at` on process-fatal crashes so the in-app rating
     * trigger heuristic suppresses prompts for 24h after a crash. Chains to
     * the previously installed handler (Crashlytics's) so existing behavior
     * is preserved. See `docs/audits/E2_IN_APP_RATINGS_AUDIT.md` § Item 3.
     */
    private fun installRatingPromptCrashHook() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                appScope.launch { recentCrashSignal.recordCrash() }
            } catch (_: Throwable) {
                // Don't let bookkeeping failure suppress the real crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun configureCrashlytics() {
        try {
            FirebaseCrashlytics
                .getInstance()
                .setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        } catch (e: Exception) {
            android.util.Log.e("PrismTaskApp", "Crashlytics init failed — Firebase may not be configured", e)
        }
    }

    /**
     * Routes Firestore and Auth at the local Firebase Emulator Suite when the
     * compile-time [BuildConfig.USE_FIREBASE_EMULATOR] flag is on (debug-only
     * by default). `useEmulator` must run BEFORE any Firestore / Auth
     * operation — all existing call sites use `by lazy` or read Firebase
     * inside functions, so running this at the top of `onCreate()` is safe.
     *
     * Host selection:
     *  - Android emulator: `10.0.2.2` (alias for host loopback).
     *  - Physical device:  `localhost`, which relies on the developer running
     *    `adb reverse tcp:8080 tcp:8080 && adb reverse tcp:9099 tcp:9099`
     *    after connecting the device. See docs/FIREBASE_EMULATOR.md.
     */
    private fun configureFirebaseEmulator() {
        if (!BuildConfig.USE_FIREBASE_EMULATOR) return
        val host = if (isAndroidEmulator()) "10.0.2.2" else "localhost"
        try {
            FirebaseFirestore.getInstance().useEmulator(host, FIRESTORE_EMULATOR_PORT)
            // Disable persistent cache so the emulator always reflects the
            // fresh server state — makes two-device sync tests deterministic.
            FirebaseFirestore.getInstance().firestoreSettings =
                FirebaseFirestoreSettings
                    .Builder()
                    .setPersistenceEnabled(false)
                    .build()
            FirebaseAuth.getInstance().useEmulator(host, AUTH_EMULATOR_PORT)
            android.util.Log.i(
                "PrismTaskApp",
                "Firebase emulator routing active: firestore=$host:$FIRESTORE_EMULATOR_PORT " +
                    "auth=$host:$AUTH_EMULATOR_PORT"
            )
        } catch (e: Exception) {
            // useEmulator throws if called after the first Firestore op. If
            // that ever happens we want to fail loudly in debug rather than
            // silently talk to production.
            android.util.Log.e("PrismTaskApp", "Firebase emulator wiring failed", e)
        }
    }

    private fun isAndroidEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic") ||
            Build.PRODUCT == "google_sdk" ||
            Build.PRODUCT == "sdk_google_phone_x86" ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")

    /**
     * Applies the user's summary-worker toggles to WorkManager on cold
     * start. Covers daily briefing, evening summary, weekly summary,
     * overload check, and re-engagement — each gated on its own
     * [NotificationPreferences] flag. Uses UPDATE policy inside the
     * scheduler so a hot toggle or schedule change doesn't duplicate jobs.
     */
    private fun scheduleNotificationWorkers() {
        appScope.launch {
            try {
                notificationWorkerScheduler.applyAll()
            } catch (e: Exception) {
                android.util.Log.e("PrismTaskApp", "Notification worker scheduling failed", e)
            }
        }
    }

    /**
     * Daily sweep of `batch_undo_log` (A2 PR3). No user toggle — the sweep
     * is pure maintenance that drops expired or stale-undone rows so the
     * table doesn't grow unbounded. Re-scheduling on every launch is a
     * no-op via [androidx.work.ExistingPeriodicWorkPolicy.UPDATE].
     */
    private fun scheduleBatchUndoSweep() {
        try {
            com.averycorp.prismtask.notifications.BatchUndoSweepWorker.schedule(this)
        } catch (e: Exception) {
            android.util.Log.e("PrismTaskApp", "Batch undo sweep scheduling failed", e)
        }
    }

    /**
     * Inserts the six built-in task templates on first launch. Gated by a
     * `templates_seeded` flag in [com.averycorp.prismtask.data.preferences.TemplatePreferences]
     * so it runs exactly once per install and never resurrects deleted defaults.
     */
    private fun seedBuiltInTemplates() {
        appScope.launch {
            templateSeeder.seedIfNeeded()
        }
    }

    /**
     * Schedules the daily reset worker to fire at the configured day-start
     * hour, and re-schedules whenever the user changes the setting so the
     * change takes effect immediately.
     */
    private fun scheduleDailyReset() {
        appScope.launch {
            taskBehaviorPreferences.getDayStartHour().collectLatest { hour ->
                DailyResetWorker.schedule(this@PrismTaskApplication, hour)
            }
        }
    }

    /**
     * Collapses any duplicate built-in habits that exist locally (e.g. from
     * a prior partial sync). Runs once per install, gated by a DataStore flag.
     * The post-sync reconciliation in [BuiltInHabitReconciler] handles the
     * cloud-vs-local case after the first successful sign-in sync.
     */
    private fun runBuiltInBackfill() {
        appScope.launch {
            builtInHabitReconciler.runBackfillIfNeeded()
        }
    }

    private fun runDriftCleanup() {
        appScope.launch {
            builtInHabitReconciler.runDriftCleanupIfNeeded()
        }
    }

    /**
     * Runs the one-shot life-category classifier pass over every legacy
     * `tasks.life_category IS NULL` row. Gated by a DataStore flag so it
     * fires at most once per install. Details: [LifeCategoryBackfiller].
     */
    private fun runLifeCategoryBackfill() {
        appScope.launch {
            lifeCategoryBackfiller.runIfNeeded()
        }
    }

    /**
     * Runs the two post-migration passes for the v53 → v54 medication
     * refactor — schedule preservation from the pre-migration
     * `MedicationPreferences` / built-in Medication habit, then dose
     * backfill from legacy `self_care_logs`. Each pass has its own
     * one-shot flag so a mid-run crash stays retryable.
     *
     * Details: [MedicationMigrationRunner]. Post-sync dedup runs through
     * [com.averycorp.prismtask.data.remote.BuiltInMedicationReconciler]
     * from inside [com.averycorp.prismtask.data.remote.SyncService].
     */
    private fun runMedicationMigrationPasses() {
        appScope.launch {
            medicationMigrationRunner.recordPostV54LaunchIfApplicable()
            medicationMigrationRunner.preserveScheduleIfNeeded()
            medicationMigrationRunner.backfillDosesIfNeeded()
        }
    }

    /**
     * Ensures the schoolwork and leisure habit "shells" exist on app start.
     * Self-care / housework / medication habits are no longer auto-created —
     * users opt into them via the onboarding template picker or the Browse
     * Templates entry in Settings. Existing installs keep their pre-seeded
     * self-care habits because the self-care repository's habit creation is
     * idempotent and still runs the next time the user actively picks a
     * self-care template.
     */
    private fun seedStructuralHabits() {
        appScope.launch {
            schoolworkRepository.ensureHabitExists()
            leisureRepository.ensureHabitExists()
        }
    }

    private fun scheduleWidgetRefresh() {
        // Widgets disabled for v1.0 — cancel periodic refresh worker instead of scheduling.
        // Re-enable in v1.2: replace cancelUniqueWork with WidgetRefreshWorker.schedule(...)
        WorkManager.getInstance(this).cancelUniqueWork("widget_refresh_periodic")
    }

    /**
     * Drives a same-process listener that repaints every Glance widget the
     * moment the user (or a cloud-sync write) changes the active app theme
     * or widget-theme override. Glance widgets cache RemoteViews and never
     * observe DataStore on their own, so without this hook a remote theme
     * change waits on the periodic refresh worker (currently disabled —
     * see [scheduleWidgetRefresh]).
     *
     * The first emission from `combine` is dropped so cold-start launches
     * don't trigger a redundant repaint right after boot — the widget
     * already painted with the current theme on its first `provideGlance`.
     * `distinctUntilChanged` plus the singleton [WidgetUpdateManager]'s
     * built-in 500ms debounce coalesce rapid double-emissions (e.g. when
     * the user picks a theme and the override is cleared in the same edit).
     *
     * Misses theme changes that happen while the app process is dead;
     * those get applied on the next app open. That's the explicit design
     * tradeoff vs. an AlarmManager poll loop.
     */
    private fun observeThemeChangesForWidgets() {
        appScope.launch {
            try {
                combine(
                    themePreferences.getPrismTheme(),
                    themePreferences.getWidgetThemeOverride()
                ) { app, override -> app to override }
                    .distinctUntilChanged()
                    .drop(1)
                    .collectLatest {
                        widgetUpdateManager.updateAllWidgets()
                    }
            } catch (e: Exception) {
                android.util.Log.e("PrismTaskApp", "Theme listener for widgets failed", e)
            }
        }
    }

    /**
     * Applies calendar-sync preferences on startup. Uses a unique-periodic
     * work request with UPDATE policy inside [CalendarSyncScheduler] so
     * restarts don't pile up duplicate jobs.
     *
     * The underlying scheduler uses runBlocking to read DataStore, which can
     * ANR if DataStore is slow on cold start. Dispatch off Main to be safe.
     */
    private fun scheduleCalendarSync() {
        appScope.launch {
            try {
                calendarSyncScheduler.applyPreferences()
            } catch (e: Exception) {
                android.util.Log.e("PrismTaskApp", "Calendar sync scheduling failed", e)
            }
        }
    }

    /**
     * Re-arms every medication-reminder pipeline at app launch:
     *
     *  - The legacy [MedicationReminderScheduler]'s `rescheduleAll`, which
     *    used to run only on `BOOT_COMPLETED`. Without this call a fresh
     *    install / app update with a previously migrated medication would
     *    have no CLOCK alarms until the device next rebooted.
     *  - The slot-level [MedicationClockRescheduler] for CLOCK-mode slots,
     *    plus its reactive slot-change Flow observer so a slot rename or
     *    `idealTime` edit re-arms the alarm in place of the stale one.
     *  - The slot/medication [MedicationIntervalRescheduler] for INTERVAL
     *    mode, plus its reactive dose-change + slot-change Flow observers
     *    so subsequent doses re-anchor the next reminder and slot edits
     *    don't leave the rolling alarm pointing at a stale label.
     */
    private fun startMedicationReschedulers() {
        appScope.launch {
            try {
                medicationReminderScheduler.rescheduleAll()
            } catch (e: Exception) {
                android.util.Log.e("PrismTaskApp", "Initial legacy reschedule failed", e)
            }
        }
        appScope.launch {
            try {
                medicationClockRescheduler.rescheduleAll()
            } catch (e: Exception) {
                android.util.Log.e("PrismTaskApp", "Initial clock reschedule failed", e)
            }
        }
        appScope.launch {
            try {
                medicationIntervalRescheduler.rescheduleAll()
            } catch (e: Exception) {
                android.util.Log.e("PrismTaskApp", "Initial interval reschedule failed", e)
            }
        }
        medicationClockRescheduler.start(appScope)
        medicationIntervalRescheduler.start(appScope)
    }

    /**
     * Cold-start hook for the automation engine: seed sample rules on first
     * install (idempotent — keyed off `templateKey`), enqueue the periodic
     * time-tick worker that drives `TimeOfDay` triggers, and start the bus
     * collector. The engine is `@Singleton` and `start()` is idempotent, so
     * a second call from a hot restart is a no-op.
     */
    private fun startAutomationEngine() {
        appScope.launch {
            try {
                automationSampleRulesSeeder.seedIfNeeded()
            } catch (e: Exception) {
                android.util.Log.e("PrismTaskApp", "Automation rules seed failed", e)
            }
            // One-shot dedup pass for the small #1070→naturalKeyLookup
            // window. Idempotent and gated by a DataStore flag; runs after
            // seeding so we never collapse a seed-row against an in-flight
            // pull from the same install.
            try {
                automationDuplicateBackfiller.runIfNeeded()
            } catch (e: Exception) {
                android.util.Log.e("PrismTaskApp", "Automation dup backfill failed", e)
            }
        }
        try {
            // Bootstraps the per-minute self-rescheduling chain. Same
            // unique work name as the legacy PeriodicWork(15min) call
            // site, so first launch after upgrade transparently swaps
            // the scheduling shape via ExistingWorkPolicy.REPLACE. See
            // docs/audits/AUTOMATION_MINUTE_CADENCE_PHASE_2_AUDIT.md.
            AutomationTimeTickWorker.schedule(this)
        } catch (e: Exception) {
            android.util.Log.e("PrismTaskApp", "Automation time-tick scheduling failed", e)
        }
        automationEngine.start()
    }

    private fun scheduleAutoArchive() {
        val workRequest = PeriodicWorkRequestBuilder<AutoArchiveWorker>(
            24,
            TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "auto_archive",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}
