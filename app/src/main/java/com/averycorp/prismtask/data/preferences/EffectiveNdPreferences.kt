package com.averycorp.prismtask.data.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Combines a base [NdPreferences] flow with an active [CustomBrainMode]
 * flow into the effective ND preferences seen by dispatch sites.
 *
 * Dispatch consumers that should reflect the active custom mode (batch
 * preview's `simplifiedUi`, widget data provider's quiet-mode read,
 * downstream use cases keyed on mode flags) call this in place of
 * reading `ndPreferencesFlow` directly. Settings, onboarding, and the
 * data exporter intentionally keep reading the base flow — they edit
 * and surface what's persisted, not the resolved view.
 */
fun NdPreferencesDataStore.effectiveNdPreferencesFlow(
    customBrainModePreferences: CustomBrainModePreferences
): Flow<NdPreferences> =
    combine(ndPreferencesFlow, customBrainModePreferences.observeActive()) { base, active ->
        BrainModeResolver.resolveEffective(base, active)
    }
