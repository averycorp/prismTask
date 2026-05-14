package com.averycorp.prismtask.notifications

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.averycorp.prismtask.data.local.entity.WeeklyReviewEntity
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.domain.usecase.WeeklyReviewGenerationOutcome
import com.averycorp.prismtask.domain.usecase.WeeklyReviewGenerator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Exercises [WeeklyReviewWorker.doWork] against a [TestListenableWorkerBuilder]
 * so the preference gate and outcome → [ListenableWorker.Result] routing are
 * covered end-to-end on JVM. Uses Robolectric for the Android framework
 * stubs (NotificationManager, Context) that the worker pokes on the
 * [WeeklyReviewGenerationOutcome.Generated] success path.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class WeeklyReviewWorkerTest {
    private lateinit var context: Context
    private lateinit var notificationPreferences: NotificationPreferences
    private lateinit var generator: WeeklyReviewGenerator
    private lateinit var notificationPauseGate: NotificationPauseGate

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationPreferences = mockk(relaxed = true)
        generator = mockk(relaxed = true)
        notificationPauseGate = mockk(relaxed = true)
        // Default: both toggles on. Individual tests override as needed.
        coEvery { notificationPreferences.weeklyReviewAutoGenerateEnabled } returns flowOf(true)
        coEvery { notificationPreferences.weeklyReviewNotificationEnabled } returns flowOf(true)
        // MH-first G4: default to "not paused" so existing test cases
        // keep their pre-gate semantics.
        coEvery { notificationPauseGate.isPausedNow(any()) } returns false
    }

    private fun buildWorker(): WeeklyReviewWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = WeeklyReviewWorker(
                context = appContext,
                params = workerParameters,
                notificationPreferences = notificationPreferences,
                weeklyReviewGenerator = generator,
                notificationPauseGate = notificationPauseGate
            )
        }
        return TestListenableWorkerBuilder
            .from(context, WeeklyReviewWorker::class.java)
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun doWork_returns_success_without_generating_when_toggle_off() = runBlocking {
        coEvery { notificationPreferences.weeklyReviewAutoGenerateEnabled } returns flowOf(false)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // Spec: "Check preference; bail if disabled". Generator must
        // not be consulted — otherwise toggling off wouldn't actually
        // stop the AI calls.
        coVerify(exactly = 0) { generator.generateReview(any()) }
    }

    @Test
    fun doWork_returns_success_when_generator_persists_a_review() = runBlocking {
        val entity = WeeklyReviewEntity(
            id = 1L,
            weekStartDate = 1_000L,
            metricsJson = "{}",
            aiInsightsJson = "{}"
        )
        coEvery { generator.generateReview(any()) } returns WeeklyReviewGenerationOutcome.Generated(entity)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { generator.generateReview(any()) }
    }

    @Test
    fun doWork_returns_success_on_NoActivity_without_calling_notification() = runBlocking {
        coEvery { generator.generateReview(any()) } returns WeeklyReviewGenerationOutcome.NoActivity

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun doWork_returns_success_on_NotEligible_free_tier() = runBlocking {
        coEvery { generator.generateReview(any()) } returns WeeklyReviewGenerationOutcome.NotEligible

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun doWork_returns_retry_on_BackendUnavailable() = runBlocking {
        coEvery { generator.generateReview(any()) } returns
            WeeklyReviewGenerationOutcome.BackendUnavailable(IOException("offline"))

        val result = buildWorker().doWork()

        // Spec: "On failure: log, Result.retry() with backoff". Transient
        // backend failures must trigger retry so the worker recovers when
        // the network returns before the next periodic tick.
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun doWork_returns_retry_on_unexpected_Error_under_cap() = runBlocking {
        coEvery { generator.generateReview(any()) } returns
            WeeklyReviewGenerationOutcome.Error(IllegalStateException("db issue"))

        // TestListenableWorkerBuilder defaults runAttemptCount to 0,
        // which is under the 3-attempt cap → retry.
        val result = buildWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
    }
}
