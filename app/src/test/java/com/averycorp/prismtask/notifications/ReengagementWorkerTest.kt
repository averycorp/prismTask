package com.averycorp.prismtask.notifications

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.averycorp.prismtask.data.diagnostics.DiagnosticLogger
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.ReengagementConfig
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.ReengagementRequest
import com.averycorp.prismtask.data.remote.api.ReengagementResponse
import com.averycorp.prismtask.data.repository.RestDayRepository
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.domain.usecase.RecentMoodSignal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Pins the boolean→counter semantic shift introduced by PR #997 squash
 * commit 7390a1db. The prior `KEY_REENGAGEMENT_SENT` boolean became
 * `KEY_REENGAGEMENT_SENT_COUNT` (intPreferencesKey) gated by
 * [ReengagementConfig.maxNudges] (default 1). At default the behavior is
 * identical to the prior boolean — exactly one nudge per absence period.
 *
 * These tests lock that contract so a future bump of `maxNudges` (or a
 * stored override from the Advanced Tuning UI) cannot silently leak >1
 * nudge to existing users without flipping a JVM test red.
 *
 * Mirrors the Robolectric + [TestListenableWorkerBuilder] pattern used by
 * [WeeklyReviewWorkerTest]. Reaches into the worker's DataStore via the
 * (now `internal`) `reengagementStore` extension; declaring a separate
 * test-side `preferencesDataStore` delegate against the same name fails
 * at runtime with `OkioStorage` "multiple DataStore" errors.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class ReengagementWorkerTest {
    private lateinit var context: Context
    private lateinit var api: PrismTaskApi
    private lateinit var taskDao: TaskDao
    private lateinit var proFeatureGate: ProFeatureGate
    private lateinit var notificationPreferences: NotificationPreferences
    private lateinit var advancedTuningPreferences: AdvancedTuningPreferences
    private lateinit var notificationPauseGate: NotificationPauseGate
    private lateinit var recentMoodSignal: RecentMoodSignal
    private lateinit var restDayRepository: RestDayRepository
    private lateinit var diagnosticLogger: DiagnosticLogger

    private val keySentCount = intPreferencesKey("reengagement_sent_count")
    private val keyLastOpenTime = longPreferencesKey("last_open_time")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        api = mockk(relaxed = true)
        taskDao = mockk(relaxed = true)
        proFeatureGate = mockk(relaxed = true)
        notificationPreferences = mockk(relaxed = true)
        advancedTuningPreferences = mockk(relaxed = true)
        notificationPauseGate = mockk(relaxed = true)
        recentMoodSignal = mockk(relaxed = true)
        restDayRepository = mockk(relaxed = true)
        diagnosticLogger = mockk(relaxed = true)

        coEvery { proFeatureGate.hasAccess(ProFeatureGate.AI_REENGAGEMENT) } returns true
        coEvery { notificationPreferences.reengagementEnabled } returns flowOf(true)
        coEvery { advancedTuningPreferences.getReengagementConfig() } returns flowOf(ReengagementConfig())
        coEvery { taskDao.getLastCompletedTask() } returns null
        coEvery { taskDao.getIncompleteTaskCount() } returns 0
        coEvery { api.getReengagementNudge(any()) } returns ReengagementResponse(nudge = "Welcome back")
        // MH-first G4: default to "not paused" so existing test cases
        // (which pre-date the gate) keep their original semantics.
        coEvery { notificationPauseGate.isPausedNow(any()) } returns false
        // MH-first G7: default to "no recent low mood" so existing test
        // cases keep their original semantics.
        coEvery { recentMoodSignal.isLowMoodWithin(any()) } returns false
        // MH-first G3: default to "not a rest day" so existing test
        // cases keep their original semantics. Rest-day-specific
        // behavior is covered by a dedicated test further down.
        coEvery { restDayRepository.isRestDayToday(any()) } returns false
    }

    private fun buildWorker(): ReengagementWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = ReengagementWorker(
                context = appContext,
                params = workerParameters,
                api = api,
                taskDao = taskDao,
                proFeatureGate = proFeatureGate,
                notificationPreferences = notificationPreferences,
                advancedTuningPreferences = advancedTuningPreferences,
                notificationPauseGate = notificationPauseGate,
                recentMoodSignal = recentMoodSignal,
                restDayRepository = restDayRepository,
                diagnosticLogger = diagnosticLogger
            )
        }
        return TestListenableWorkerBuilder
            .from(context, ReengagementWorker::class.java)
            .setWorkerFactory(factory)
            .build()
    }

    private suspend fun seedAbsenceDays(days: Int, sentCount: Int = 0) {
        context.reengagementStore.edit { prefs ->
            prefs[keyLastOpenTime] = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
            prefs[keySentCount] = sentCount
        }
    }

    private suspend fun storedSentCount(): Int =
        context.reengagementStore.data.first()[keySentCount] ?: 0

    @Test
    fun doWork_default_config_fires_exactly_one_nudge_per_absence_period() = runBlocking {
        seedAbsenceDays(3)

        val first = buildWorker().doWork()
        val second = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), first)
        assertEquals(ListenableWorker.Result.success(), second)
        // Spec: maxNudges=1 default means exactly one API call per absence
        // period — the second invocation must short-circuit on the cap.
        coVerify(exactly = 1) { api.getReengagementNudge(any<ReengagementRequest>()) }
        assertEquals(1, storedSentCount())
    }

    @Test
    fun doWork_skips_when_count_already_at_cap() = runBlocking {
        seedAbsenceDays(3, sentCount = 1)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { api.getReengagementNudge(any<ReengagementRequest>()) }
        assertEquals(1, storedSentCount())
    }

    @Test
    fun doWork_skips_when_recent_low_mood_within_48h() = runBlocking {
        // Mental-Health-First § G7 — re-engagement nudges must defer
        // silently when a ≤2/5 mood was logged in the last 48h.
        seedAbsenceDays(3)
        coEvery { recentMoodSignal.isLowMoodWithin(any()) } returns true

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { api.getReengagementNudge(any<ReengagementRequest>()) }
        // Counter must NOT increment — silent deferral, not a "used"
        // nudge. Otherwise we'd permanently lose the user's only nudge
        // for this absence period to a mood spike.
        assertEquals(0, storedSentCount())
    }

    @Test
    fun onAppOpened_resets_counter_to_zero() = runBlocking {
        seedAbsenceDays(3, sentCount = 1)
        assertEquals(1, storedSentCount())

        ReengagementWorker.onAppOpened(context)

        assertEquals(0, storedSentCount())
    }

    @Test
    fun doWork_restDay_suppresses_nudge_without_consuming_counter() = runBlocking {
        // MH-First audit § G3 — reengagement nudges are non-medication
        // notifications and pause on a rest day. Critically: the sent
        // counter must NOT be touched, so tomorrow's run (when rest day
        // is over) can still fire the once-per-period nudge.
        coEvery { restDayRepository.isRestDayToday(any()) } returns true
        seedAbsenceDays(3)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { api.getReengagementNudge(any<ReengagementRequest>()) }
        assertEquals(0, storedSentCount())
    }
}
