package com.averycorp.prismtask.domain.rating

import com.averycorp.prismtask.data.preferences.OnboardingPreferences
import com.averycorp.prismtask.data.preferences.RatingPromptPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class RatingPromptTriggerHelperTest {
    private val prefs: RatingPromptPreferences = mockk(relaxed = true)
    private val crash: RecentCrashSignal = mockk()
    private val onboarding: OnboardingPreferences = mockk()

    private fun helper(now: Long = NOW): RatingPromptTriggerHelper =
        RatingPromptTriggerHelper(
            prefs = prefs,
            crashSignal = crash,
            onboardingPreferences = onboarding,
            clock = { now },
        )

    private fun stubBaseline(
        sessions: Long = 5,
        firstLaunchAt: Long = NOW - TimeUnit.DAYS.toMillis(30),
        tasksCompletedAfterIncrement: Long = 11,
        lastPlay: Long = 0L,
        lastCustom: Long = 0L,
        onboardingDone: Boolean = true,
        recentCrash: Boolean = false,
    ) {
        coEvery { onboarding.hasCompletedOnboarding() } returns flowOf(onboardingDone)
        coEvery { prefs.sessionCount() } returns flowOf(sessions)
        coEvery { prefs.firstLaunchAt() } returns flowOf(firstLaunchAt)
        coEvery { prefs.tasksCompletedCount() } returns flowOf(tasksCompletedAfterIncrement)
        coEvery { prefs.lastPlayReviewShownAt() } returns flowOf(lastPlay)
        coEvery { prefs.lastCustomPromptShownAt() } returns flowOf(lastCustom)
        coEvery { crash.hadRecentCrash(any()) } returns recentCrash
    }

    @Test
    fun `onTaskCompleted increments task counter then evaluates`() = runTest {
        stubBaseline()
        helper().onTaskCompleted()
        coVerify(exactly = 1) { prefs.incrementTasksCompletedCount() }
    }

    @Test
    fun `gate returns PlayReview when all thresholds satisfied`() = runTest {
        stubBaseline(tasksCompletedAfterIncrement = 10, firstLaunchAt = NOW - TimeUnit.DAYS.toMillis(7))
        val decision = helper().onTaskCompleted()
        assertEquals(RatingPromptDecision.PlayReview, decision)
    }

    @Test
    fun `gate returns CustomPrompt below Play threshold but above custom`() = runTest {
        stubBaseline(tasksCompletedAfterIncrement = 5, firstLaunchAt = NOW - TimeUnit.DAYS.toMillis(3))
        val decision = helper().onTaskCompleted()
        assertEquals(RatingPromptDecision.CustomPrompt, decision)
    }

    @Test
    fun `gate returns None when onboarding incomplete`() = runTest {
        stubBaseline(onboardingDone = false)
        assertEquals(RatingPromptDecision.None, helper().onTaskCompleted())
    }

    @Test
    fun `gate returns None when sessionCount under MIN_SESSIONS`() = runTest {
        stubBaseline(sessions = 2)
        assertEquals(RatingPromptDecision.None, helper().onTaskCompleted())
    }

    @Test
    fun `gate returns None when crash within 24h`() = runTest {
        stubBaseline(recentCrash = true)
        assertEquals(RatingPromptDecision.None, helper().onTaskCompleted())
    }

    @Test
    fun `gate falls through to Custom when Play cooldown not elapsed`() = runTest {
        stubBaseline(lastPlay = NOW - TimeUnit.DAYS.toMillis(30))
        // Play gated out by 30d-old shown-at vs 90d cooldown; Custom passes
        // (tasks=11 >= 5, daysSinceFirstLaunch=30 >= 3, lastCustom=0).
        assertEquals(RatingPromptDecision.CustomPrompt, helper().onTaskCompleted())
    }

    @Test
    fun `gate returns None when custom cooldown not elapsed and Play below threshold`() = runTest {
        stubBaseline(
            tasksCompletedAfterIncrement = 5,
            firstLaunchAt = NOW - TimeUnit.DAYS.toMillis(3),
            lastCustom = NOW - TimeUnit.DAYS.toMillis(15),
        )
        assertEquals(RatingPromptDecision.None, helper().onTaskCompleted())
    }

    @Test
    fun `inter-prompt suppression blocks second prompt in same session`() = runTest {
        stubBaseline(tasksCompletedAfterIncrement = 11, firstLaunchAt = NOW - TimeUnit.DAYS.toMillis(7))
        val h = helper()
        assertEquals(RatingPromptDecision.PlayReview, h.onTaskCompleted())
        h.recordPlayReviewShown()
        assertEquals(RatingPromptDecision.None, h.onTaskCompleted())
    }

    @Test
    fun `onAppStart stamps install timestamp and bumps session counter`() = runTest {
        helper().onAppStart()
        coVerify { prefs.setFirstLaunchAtIfAbsent(NOW) }
        coVerify { prefs.incrementSessionCount() }
    }

    private companion object {
        const val NOW: Long = 1_715_300_000_000L
    }
}
