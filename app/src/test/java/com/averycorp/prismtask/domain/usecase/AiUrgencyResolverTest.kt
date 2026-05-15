package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.billing.BillingManager
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.AiFeaturePrefs
import com.averycorp.prismtask.data.preferences.PerFeatureAiPrefs
import com.averycorp.prismtask.data.preferences.UrgencyWeights
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.remote.UrgencyClassifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class AiUrgencyResolverTest {
    private lateinit var classifier: UrgencyClassifier
    private lateinit var billingManager: BillingManager
    private lateinit var prefs: UserPreferencesDataStore
    private lateinit var proFeatureGate: ProFeatureGate
    private lateinit var resolver: AiUrgencyResolver

    private val sampleTask = TaskEntity(
        id = 42L,
        title = "Ship feature",
        description = "Wire urgency to backend",
        dueDate = 1_800_000_000_000L,
        priority = 3
    )

    private val tierFlow = MutableStateFlow(UserTier.FREE)

    @Before
    fun setUp() {
        classifier = mockk()
        billingManager = mockk()
        prefs = mockk()
        // ProFeatureGate reads billingManager.userTier eagerly in its
        // property initializer, so the stub must be in place BEFORE
        // we construct the gate. Each test mutates [tierFlow] via
        // [stubTier] to switch Pro/Free without re-creating the gate.
        every { billingManager.userTier } returns tierFlow
        proFeatureGate = ProFeatureGate(billingManager)
        resolver = AiUrgencyResolver(classifier, proFeatureGate, prefs)
    }

    private fun stubTier(tier: UserTier) {
        tierFlow.value = tier
    }

    private fun stubAiPrefs(masterEnabled: Boolean = true, urgencyEnabled: Boolean = true) {
        coEvery { prefs.aiFeaturePrefsFlow } returns flowOf(AiFeaturePrefs(enabled = masterEnabled))
        coEvery { prefs.perFeatureAiPrefsFlow } returns flowOf(
            PerFeatureAiPrefs(urgencyEnabled = urgencyEnabled)
        )
    }

    @Test
    fun shouldUseAi_falseForFreeUserEvenIfTogglesOn() = runBlocking {
        stubTier(UserTier.FREE)
        stubAiPrefs(masterEnabled = true, urgencyEnabled = true)

        assertEquals(false, resolver.shouldUseAi())
    }

    @Test
    fun shouldUseAi_falseWhenMasterAiOff() = runBlocking {
        stubTier(UserTier.PRO)
        stubAiPrefs(masterEnabled = false, urgencyEnabled = true)

        assertEquals(false, resolver.shouldUseAi())
    }

    @Test
    fun shouldUseAi_falseWhenPerFeatureOff() = runBlocking {
        stubTier(UserTier.PRO)
        stubAiPrefs(masterEnabled = true, urgencyEnabled = false)

        assertEquals(false, resolver.shouldUseAi())
    }

    @Test
    fun shouldUseAi_trueWhenProAndBothTogglesOn() = runBlocking {
        stubTier(UserTier.PRO)
        stubAiPrefs(masterEnabled = true, urgencyEnabled = true)

        assertEquals(true, resolver.shouldUseAi())
    }

    @Test
    fun resolveScores_returnsLocalForEveryTaskWhenFreeUser() = runBlocking {
        stubTier(UserTier.FREE)
        stubAiPrefs()
        // Free path must not even touch the classifier (no Claude budget burn).
        val scores = resolver.resolveScores(listOf(sampleTask))

        assertTrue(scores.containsKey(42L))
        assertNotNull(scores[42L])
        coVerify(exactly = 0) { classifier.scoreBatch(any(), any()) }
    }

    @Test
    fun resolveScores_returnsAiScoresWhenProAndCallSucceeds() = runBlocking {
        stubTier(UserTier.PRO)
        stubAiPrefs()
        coEvery { classifier.scoreBatch(any(), any()) } returns Result.success(mapOf(42L to 0.87f))

        val scores = resolver.resolveScores(listOf(sampleTask))

        assertEquals(0.87f, scores[42L])
    }

    @Test
    fun resolveScores_fallsBackToLocalWhenClassifierFails() = runBlocking {
        stubTier(UserTier.PRO)
        stubAiPrefs()
        coEvery { classifier.scoreBatch(any(), any()) } returns Result.failure(IOException("net"))

        val scores = resolver.resolveScores(listOf(sampleTask))

        // Failure must NOT drop the task — the on-device formula fills in
        // so the URGENCY sort stays consistent even when AI is down.
        assertTrue(scores.containsKey(42L))
        val localExpected = UrgencyScorer.calculateScore(sampleTask, weights = UrgencyWeights())
        assertEquals(localExpected, scores[42L])
    }

    @Test
    fun resolveScores_fallsBackPerTaskWhenAiReturnsPartialBatch() = runBlocking {
        stubTier(UserTier.PRO)
        stubAiPrefs()
        val taskA = sampleTask.copy(id = 1L)
        val taskB = sampleTask.copy(id = 2L)
        // AI only returns a score for taskA; taskB should use the on-device formula.
        coEvery { classifier.scoreBatch(any(), any()) } returns Result.success(mapOf(1L to 0.65f))

        val scores = resolver.resolveScores(listOf(taskA, taskB))

        assertEquals(0.65f, scores[1L])
        val localExpected = UrgencyScorer.calculateScore(taskB, weights = UrgencyWeights())
        assertEquals(localExpected, scores[2L])
    }

    @Test
    fun resolveScores_emptyListReturnsEmptyMap() = runBlocking {
        stubTier(UserTier.PRO)
        stubAiPrefs()

        val scores = resolver.resolveScores(emptyList())

        assertEquals(emptyMap<Long, Float>(), scores)
        coVerify(exactly = 0) { classifier.scoreBatch(any(), any()) }
    }
}
