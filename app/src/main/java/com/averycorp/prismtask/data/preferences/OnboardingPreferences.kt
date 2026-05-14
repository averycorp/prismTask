package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(name = "onboarding_prefs")

@Singleton
class OnboardingPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        private val ONBOARDING_COMPLETED_AT = longPreferencesKey("onboarding_completed_at")
        private val HAS_SHOWN_BATTERY_OPTIMIZATION_PROMPT =
            booleanPreferencesKey("has_shown_battery_optimization_prompt")

        // Mental-Health-First § G6: when the user picks "I have low-energy
        // days often" on the onboarding tuning step, we prime them for the
        // forthcoming Rest Day surface (audit G3). The Rest Day screen
        // reads this flag on first launch to decide whether to surface a
        // one-time intro card. Independent of `forgivenessStreaks` because
        // a returning user who already has forgiveness on shouldn't get
        // a re-intro on next install.
        private val REST_DAY_PRIMED = booleanPreferencesKey("rest_day_primed")
    }

    fun hasCompletedOnboarding(): Flow<Boolean> = context.onboardingDataStore.data.map { prefs ->
        prefs[HAS_COMPLETED_ONBOARDING] ?: false
    }

    fun getOnboardingCompletedAt(): Flow<Long> = context.onboardingDataStore.data.map { prefs ->
        prefs[ONBOARDING_COMPLETED_AT] ?: 0L
    }

    suspend fun setOnboardingCompleted() {
        setOnboardingCompleted(System.currentTimeMillis())
    }

    /**
     * Stamps onboarding as complete with an explicit timestamp. Used by
     * [hydrateFromCanonicalCloud] when the cross-device canonical flag
     * (`users/{uid}.onboardingCompletedAt`) carries an earlier completion
     * timestamp than "now". Keeping the original timestamp matters for
     * analytics that bucket users by sign-up cohort and for the data
     * exporter, which round-trips this field.
     */
    suspend fun setOnboardingCompleted(timestampMs: Long) {
        context.onboardingDataStore.edit { prefs ->
            prefs[HAS_COMPLETED_ONBOARDING] = true
            prefs[ONBOARDING_COMPLETED_AT] = timestampMs
        }
    }

    /**
     * Bridges the cross-platform canonical onboarding flag
     * (`users/{uid}.onboardingCompletedAt`, written by web and by
     * [com.averycorp.prismtask.data.remote.CanonicalOnboardingSync]) into
     * the device-local DataStore mirror.
     *
     * Idempotent and one-way: if the local mirror already says completed,
     * this no-ops (the local DataStore is the freshest source for this
     * device). If `canonicalCompletedAt` is `null` or non-positive, this
     * also no-ops — Firestore being unreachable must not flip a real
     * "completed" flag back to "pending". Otherwise, stamps the local
     * mirror with the canonical timestamp so the onboarding gate releases
     * on subsequent reads.
     *
     * Returns `true` iff the local mirror was actually updated, so
     * callers can log / metric the cross-device hand-off.
     */
    suspend fun hydrateFromCanonicalCloud(canonicalCompletedAt: Long?): Boolean {
        if (canonicalCompletedAt == null || canonicalCompletedAt <= 0L) return false
        val alreadyCompleted = context.onboardingDataStore.data
            .map { it[HAS_COMPLETED_ONBOARDING] ?: false }
            .first()
        if (alreadyCompleted) return false
        setOnboardingCompleted(canonicalCompletedAt)
        return true
    }

    /**
     * Whether we've already shown the Samsung/OEM battery-optimization
     * prompt. Used by MainActivity so the dialog appears at most once per
     * install, even if the user declines.
     */
    fun hasShownBatteryOptimizationPrompt(): Flow<Boolean> =
        context.onboardingDataStore.data.map { prefs ->
            prefs[HAS_SHOWN_BATTERY_OPTIMIZATION_PROMPT] ?: false
        }

    suspend fun setBatteryOptimizationPromptShown() {
        context.onboardingDataStore.edit { prefs ->
            prefs[HAS_SHOWN_BATTERY_OPTIMIZATION_PROMPT] = true
        }
    }

    /**
     * Whether the user has been primed for the Rest Day feature via the
     * onboarding tuning step (audit § G6 → "I have low-energy days
     * often"). The Rest Day surface (audit § G3) reads this on first
     * launch to decide whether to show a one-time intro card.
     */
    fun isRestDayPrimed(): Flow<Boolean> =
        context.onboardingDataStore.data.map { prefs ->
            prefs[REST_DAY_PRIMED] ?: false
        }

    suspend fun setRestDayPrimed(primed: Boolean) {
        context.onboardingDataStore.edit { prefs ->
            prefs[REST_DAY_PRIMED] = primed
        }
    }

    /**
     * Restores onboarding state from a JSON backup. Unlike [setOnboardingCompleted]
     * (which stamps `completed_at` to `now`), this writes the exact original
     * timestamp so a restored install doesn't look like it just finished
     * onboarding. Used by [com.averycorp.prismtask.data.export.DataImporter].
     */
    suspend fun restoreImportedState(
        hasCompletedOnboarding: Boolean,
        onboardingCompletedAt: Long,
        hasShownBatteryOptimizationPrompt: Boolean
    ) {
        context.onboardingDataStore.edit { prefs ->
            prefs[HAS_COMPLETED_ONBOARDING] = hasCompletedOnboarding
            prefs[ONBOARDING_COMPLETED_AT] = onboardingCompletedAt
            prefs[HAS_SHOWN_BATTERY_OPTIMIZATION_PROMPT] = hasShownBatteryOptimizationPrompt
        }
    }

    /** Debug-only: clear the onboarding flag so the tutorial plays again. */
    suspend fun resetOnboarding() {
        context.onboardingDataStore.edit { prefs ->
            prefs.remove(HAS_COMPLETED_ONBOARDING)
            prefs.remove(ONBOARDING_COMPLETED_AT)
            prefs.remove(HAS_SHOWN_BATTERY_OPTIMIZATION_PROMPT)
            prefs.remove(REST_DAY_PRIMED)
        }
    }
}
