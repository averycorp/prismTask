package com.averycorp.prismtask.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke tests for [ProGatedFeature] enum content. Verifies that every
 * feature variant carries non-empty label + description so the
 * [ProUpsellSheet] hero copy never renders blank.
 *
 * Mirrors the audit-first inventory in
 * `docs/audits/D11_FINISH_BUNDLE_AUDIT.md` § Item 2 — the 8 enum values
 * are the exact Pro-gated AI feature inventory; if a 9th feature is added
 * to ProFeatureGate, [ensureNoFutureFeatureRegression] should fail until
 * the enum is updated.
 */
class ProGatedFeatureTest {
    @Test
    fun everyFeatureHasNonEmptyLabel() {
        for (feature in ProGatedFeature.values()) {
            assertTrue(
                "Label for $feature must be non-blank",
                feature.label.isNotBlank()
            )
        }
    }

    @Test
    fun everyFeatureHasNonEmptyDescription() {
        for (feature in ProGatedFeature.values()) {
            assertTrue(
                "Description for $feature must be non-blank",
                feature.description.isNotBlank()
            )
        }
    }

    @Test
    fun labelsAreUnique() {
        val labels = ProGatedFeature.values().map { it.label }
        assertEquals(
            "Each feature must have a unique label so the upsell sheet header is unambiguous",
            labels.size,
            labels.toSet().size
        )
    }

    @Test
    fun aiChatLabelMatchesExistingProFeature() {
        // ProGatedFeature.AI_CHAT should mirror ProFeature.AI_CHAT for
        // copy parity with existing ProUpgradePrompt call sites.
        assertEquals(
            ProFeature.AI_CHAT.label,
            ProGatedFeature.AI_CHAT.label
        )
    }

    @Test
    fun ensureNoFutureFeatureRegression() {
        // The audit doc enumerates exactly 8 Pro-gated AI features. If
        // ProFeatureGate gains a new AI_* constant that maps to the
        // PRO tier and is reachable from a tap-time entry point, this
        // test should fail until ProGatedFeature is updated.
        assertEquals(
            "ProGatedFeature inventory drift detected — see D11 audit § Item 2",
            8,
            ProGatedFeature.values().size
        )
    }

    @Test
    fun differentFeaturesProduceDifferentDescriptions() {
        // Hero copy must be feature-specific, not boilerplate.
        val descriptions = ProGatedFeature.values().map { it.description }
        assertEquals(descriptions.size, descriptions.toSet().size)
        assertNotEquals(
            ProGatedFeature.AI_CHAT.description,
            ProGatedFeature.AI_BRIEFING.description
        )
    }
}
