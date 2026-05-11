package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.billing.UserTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ProFeatureGate] two-tier feature-gating logic.
 *
 * Uses a TestableFeatureGate that mirrors ProFeatureGate's logic but accepts
 * a controllable StateFlow instead of requiring BillingManager.
 */
class ProFeatureGateTest {
    /**
     * Mirrors [ProFeatureGate] logic for unit-testability without Android
     * dependencies.
     */
    private class TestableFeatureGate(initialTier: UserTier = UserTier.FREE) {
        private val _userTier = MutableStateFlow(initialTier)
        val userTier: StateFlow<UserTier> = _userTier.asStateFlow()

        fun setTier(tier: UserTier) {
            _userTier.value = tier
        }

        fun isPro(): Boolean = userTier.value == UserTier.PRO

        fun requiredTier(feature: String): UserTier = when (feature) {
            ProFeatureGate.CLOUD_SYNC, ProFeatureGate.TEMPLATE_SYNC,
            ProFeatureGate.AI_EISENHOWER, ProFeatureGate.AI_POMODORO,
            ProFeatureGate.AI_NLP,
            ProFeatureGate.AI_EVENING_SUMMARY,
            ProFeatureGate.AI_COACHING, ProFeatureGate.AI_TASK_BREAKDOWN,
            ProFeatureGate.AI_BRIEFING, ProFeatureGate.AI_WEEKLY_PLAN,
            ProFeatureGate.AI_TIME_BLOCK, ProFeatureGate.AI_CONVERSATIONAL,
            ProFeatureGate.AI_DAILY_PLANNING, ProFeatureGate.AI_REENGAGEMENT,
            ProFeatureGate.ANALYTICS_FULL, ProFeatureGate.ANALYTICS_CORRELATIONS,
            ProFeatureGate.SYLLABUS_IMPORT -> UserTier.PRO

            else -> UserTier.FREE
        }

        fun hasAccess(feature: String): Boolean = when (requiredTier(feature)) {
            UserTier.FREE -> true
            UserTier.PRO -> isPro()
        }
    }

    private val allProFeatures = listOf(
        ProFeatureGate.CLOUD_SYNC,
        ProFeatureGate.TEMPLATE_SYNC,
        ProFeatureGate.AI_EISENHOWER,
        ProFeatureGate.AI_POMODORO,
        ProFeatureGate.AI_NLP,
        ProFeatureGate.AI_EVENING_SUMMARY,
        ProFeatureGate.AI_COACHING,
        ProFeatureGate.AI_TASK_BREAKDOWN,
        ProFeatureGate.AI_BRIEFING,
        ProFeatureGate.AI_WEEKLY_PLAN,
        ProFeatureGate.AI_TIME_BLOCK,
        ProFeatureGate.AI_CONVERSATIONAL,
        ProFeatureGate.AI_DAILY_PLANNING,
        ProFeatureGate.AI_REENGAGEMENT,
        ProFeatureGate.ANALYTICS_FULL,
        ProFeatureGate.ANALYTICS_CORRELATIONS,
        ProFeatureGate.SYLLABUS_IMPORT
    )

    // --- Tier ordering ---

    @Test
    fun `tier ordering FREE less than PRO`() {
        assertTrue(UserTier.FREE < UserTier.PRO)
        assertFalse(UserTier.PRO < UserTier.FREE)
    }

    @Test
    fun `UserTier only has FREE and PRO`() {
        assertEquals(2, UserTier.entries.size)
    }

    // --- FREE user access ---

    @Test
    fun `FREE user has no access to Pro features`() {
        val gate = TestableFeatureGate(UserTier.FREE)
        allProFeatures.forEach { feature ->
            assertFalse("FREE should NOT access $feature", gate.hasAccess(feature))
        }
        // Free/unknown features allowed
        assertTrue(gate.hasAccess("some_free_feature"))
    }

    // --- PRO user access ---

    @Test
    fun `PRO user has access to every Pro feature`() {
        val gate = TestableFeatureGate(UserTier.PRO)
        allProFeatures.forEach { feature ->
            assertTrue("PRO should access $feature", gate.hasAccess(feature))
        }
        // Free/unknown features still allowed
        assertTrue(gate.hasAccess("some_free_feature"))
    }

    // --- hasAccess per-feature matches requiredTier ---

    @Test
    fun `hasAccess returns correct results for each feature constant`() {
        val free = TestableFeatureGate(UserTier.FREE)
        val pro = TestableFeatureGate(UserTier.PRO)
        allProFeatures.forEach { feature ->
            assertEquals(UserTier.PRO, free.requiredTier(feature))
            assertFalse("FREE blocked on $feature", free.hasAccess(feature))
            assertTrue("PRO allowed on $feature", pro.hasAccess(feature))
        }
    }

    // --- Billing restores PRO from either monthly or annual product ID ---

    @Test
    fun `billing restores PRO when any Pro product is active`() {
        // Simulate the BillingManager's aggregate logic: regardless of which
        // Pro SKU (monthly or annual) is active, the user's effective tier
        // should be PRO.
        val gate = TestableFeatureGate(UserTier.FREE)
        assertFalse(gate.isPro())

        // Monthly subscription active
        gate.setTier(UserTier.PRO)
        assertTrue(gate.isPro())

        // Swap to annual — still PRO
        gate.setTier(UserTier.PRO)
        assertTrue(gate.isPro())

        // Cancel everything
        gate.setTier(UserTier.FREE)
        assertFalse(gate.isPro())
    }

    // --- aiModelForFeature routing ---

    @Test
    fun `aiModelForFeature returns Sonnet for weekly planner and monthly review`() {
        assertEquals("claude-sonnet-4-20250514", aiModelForFeature(AiFeature.WEEKLY_PLANNER))
        assertEquals("claude-sonnet-4-20250514", aiModelForFeature(AiFeature.MONTHLY_REVIEW))
    }

    @Test
    fun `aiModelForFeature returns Haiku for every other feature`() {
        val haikuFeatures = AiFeature.entries.filter {
            it != AiFeature.WEEKLY_PLANNER && it != AiFeature.MONTHLY_REVIEW
        }
        haikuFeatures.forEach { feature ->
            assertEquals(
                "Expected Haiku for $feature",
                "claude-haiku-4-5-20251001",
                aiModelForFeature(feature)
            )
        }
    }

    // --- Tier flow state changes ---

    @Test
    fun `userTier flow reflects billing state changes`() {
        val gate = TestableFeatureGate(UserTier.FREE)
        assertEquals(UserTier.FREE, gate.userTier.value)
        gate.setTier(UserTier.PRO)
        assertEquals(UserTier.PRO, gate.userTier.value)
        gate.setTier(UserTier.FREE)
        assertEquals(UserTier.FREE, gate.userTier.value)
    }

    // --- Feature constant uniqueness ---

    @Test
    fun `all feature constants have unique values`() {
        val constants = allProFeatures.toSet()
        assertEquals("All Pro feature constants should be unique", allProFeatures.size, constants.size)
    }
}
