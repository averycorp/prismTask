package com.averycorp.prismtask

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.averycorp.prismtask.data.diagnostics.MigrationInstrumentor
import dagger.hilt.android.testing.HiltTestApplication

class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)

    /**
     * Production [com.averycorp.prismtask.PrismTaskApplication] implements
     * `Configuration.Provider` and wires WorkManager via its injected
     * `HiltWorkerFactory`. Instrumentation replaces the Application with
     * [HiltTestApplication], which does NOT implement `Configuration.Provider`,
     * so any MainActivity startup path that touches WorkManager (e.g. a
     * scheduled job firing during the Activity's lifetime) crashes with
     * "WorkManager is not initialized properly" and the Activity never reaches
     * setContent — turning every compose assertion into "No compose
     * hierarchies found in the app." Initialize WorkManager synchronously
     * here with the test-only synchronous executor so the test process has a
     * valid WorkManager by the time any Activity launches.
     *
     * For the same reason — `HiltTestApplication` replaces
     * `PrismTaskApplication` — the production code's
     * `configureFirebaseEmulator()` hook never fires under instrumented
     * tests. Re-implement it here so the default `FirebaseFirestore` /
     * `FirebaseAuth` clients route at the local Firebase Emulator Suite
     * when [BuildConfig.USE_FIREBASE_EMULATOR] is true. Without this,
     * cross-device sync tests hit production Firestore and fail with
     * `PERMISSION_DENIED` (the bug that surfaced after PR #780 fixed the
     * `cross-device-tests` script-execution issue).
     */
    override fun onStart() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            targetContext,
            Configuration.Builder().build()
        )
        configureFirebaseEmulator()
        preGrantRuntimePermissions()
        // Mirror of PrismTaskApplication.onCreate's belt-and-braces flush for
        // migration events that fired before Firebase was ready on cold-boot
        // (BootReceiver) paths. Production wraps this in a try/swallow because
        // migration events are best-effort by design; do the same here so a
        // diagnostics hiccup can never break tests.
        try {
            MigrationInstrumentor.flushPending(targetContext)
        } catch (_: Exception) {
            // Drop — migration events are best-effort by design.
        }
        // Production onCreate steps we intentionally do not mirror:
        // configureCrashlytics() — Intentionally skipped: tests must not enable real Crashlytics writes
        // installRatingPromptCrashHook() — Intentionally skipped: would stamp last_crash_at on every JUnit failure and pollute test signal
        // scheduleAutoArchive() — Intentionally skipped: 24h periodic worker — no-op in test process, no need to enqueue
        // scheduleDailyReset() — Intentionally skipped: unbounded Flow collection on getDayStartHour would leak coroutines across tests
        // scheduleNotificationWorkers() — Intentionally skipped: would schedule briefing/summary/overload/re-engagement periodic workers
        // scheduleWidgetRefresh() — Intentionally skipped: production behavior is just a cancel of a legacy work name, unnecessary in tests
        // observeThemeChangesForWidgets() — Intentionally skipped: unbounded combine Flow collection would leak coroutines across tests
        // scheduleCalendarSync() — Intentionally skipped: would schedule periodic calendar sync work (network) — unsafe in tests
        // scheduleBatchUndoSweep() — Intentionally skipped: daily sweep — tests that exercise batch_undo_log insert rows directly
        // seedStructuralHabits() — Intentionally skipped: needs Hilt component instance — belongs in test-base helper, not runner
        // seedBuiltInTemplates() — Intentionally skipped: needs Hilt component instance — belongs in test-base helper, not runner
        // runBuiltInBackfill() — Intentionally skipped: needs Hilt component instance — belongs in test-base helper, not runner
        // runDriftCleanup() — Intentionally skipped: needs Hilt component instance — belongs in test-base helper, not runner
        // runLifeCategoryBackfill() — Intentionally skipped: needs Hilt component instance — belongs in test-base helper, not runner
        // runMedicationMigrationPasses() — Intentionally skipped: one-shot migration passes — migration tests run their own SQL
        // startMedicationReschedulers() — Intentionally skipped: would schedule real AlarmManager alarms and start unbounded observer Flows
        // startAutomationEngine() — Intentionally skipped: would boot in-process automation bus collector + per-minute self-rescheduling worker
        super.onStart()
    }

    /**
     * Mirror of [com.averycorp.prismtask.PrismTaskApplication.configureFirebaseEmulator]
     * for the instrumented-test process. Must run before any
     * `FirebaseFirestore` / `FirebaseAuth` operation — putting it in
     * `onStart()` (which fires before any `@Test` method) guarantees that.
     *
     * Host selection mirrors the production code: `10.0.2.2` from inside
     * the Android emulator (the alias for the host loopback where the
     * Firebase emulator binds), `localhost` on physical devices.
     */
    private fun configureFirebaseEmulator() {
        if (!BuildConfig.USE_FIREBASE_EMULATOR) return
        val host = if (isAndroidEmulator()) "10.0.2.2" else "localhost"
        try {
            FirebaseFirestore.getInstance().useEmulator(host, FIRESTORE_EMULATOR_PORT)
            FirebaseFirestore.getInstance().firestoreSettings =
                FirebaseFirestoreSettings
                    .Builder()
                    .setPersistenceEnabled(false)
                    .build()
            FirebaseAuth.getInstance().useEmulator(host, AUTH_EMULATOR_PORT)
            android.util.Log.i(
                "HiltTestRunner",
                "Firebase emulator routing active: firestore=$host:$FIRESTORE_EMULATOR_PORT " +
                    "auth=$host:$AUTH_EMULATOR_PORT"
            )
        } catch (e: Exception) {
            // useEmulator throws if called after the first Firestore op.
            // Log loudly so we don't silently fall back to production —
            // every cross-device test would fail with PERMISSION_DENIED
            // and the cause would be invisible.
            android.util.Log.e("HiltTestRunner", "Firebase emulator wiring failed", e)
        }
    }

    private fun isAndroidEmulator(): Boolean =
        // `Build.HARDWARE` is the most reliable signal across API levels:
        // QEMU2 (API 25+) reports "ranchu", older QEMU reports "goldfish".
        // The legacy FINGERPRINT / MODEL / PRODUCT heuristics below all
        // fail on modern `sdk_gphone64_x86_64` images (API 33+), which
        // is why cross-device-tests on `api-level: 34` runs were silently
        // routing Firestore at "localhost" instead of "10.0.2.2" and
        // hanging on ECONNREFUSED until the 45-min job timeout fired
        // (run `24974393528` on `c5c0fefc`). Kept the older heuristics
        // for backward compat with stale AVD images / Genymotion.
        Build.HARDWARE == "ranchu" ||
            Build.HARDWARE == "goldfish" ||
            Build.PRODUCT.startsWith("sdk_") ||
            Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
            "google_sdk" == Build.PRODUCT

    private companion object {
        private const val FIRESTORE_EMULATOR_PORT = 8080
        private const val AUTH_EMULATOR_PORT = 9099
    }

    /**
     * MainActivity's onCreate fires a POST_NOTIFICATIONS permission request
     * on API 33+. When the test rule launches MainActivity, the system
     * permission dialog (`GrantPermissionsActivity`) slides in front, pauses
     * MainActivity, and Compose never renders into a visible window — so
     * every smoke test fails with "No compose hierarchies found in the app."
     * Grant the permissions via `UiAutomation` before the first Activity
     * launches so the prompt is a no-op.
     *
     * Also grant a small set of other runtime permissions the app may ask
     * for (contacts/calendar/storage) so any indirect prompt doesn't race
     * with a test later in the suite.
     */
    private fun preGrantRuntimePermissions() {
        val pkg = targetContext.packageName
        val permissions = listOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR"
        )
        val automation = uiAutomation
        for (p in permissions) {
            try {
                automation.executeShellCommand("pm grant $pkg $p").close()
            } catch (_: Exception) {
                // Best-effort — grant may already be set or the permission
                // may not be declared on the current SDK; tests that depend
                // on a specific grant already have per-test GrantPermissionRule.
            }
        }
    }
}
