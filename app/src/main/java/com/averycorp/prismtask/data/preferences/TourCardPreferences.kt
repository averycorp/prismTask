package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.tourCardDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "tour_card_prefs")

/**
 * Backing store for the post-onboarding "Guided Tour" card on Today.
 *
 * The card surfaces a small set of breadth-overview steps after a user
 * finishes onboarding. State is device-local and intentionally NOT
 * synced — a returning user on a fresh install starts with their own
 * dismissed/eligible state, which matches the behaviour the tour is
 * meant to provide (don't re-tour a returning user).
 *
 * **Eligibility model.** [eligible] is gated by an explicit flip from
 * `OnboardingViewModel.completeOnboarding()` — the SetupPage path. The
 * existing-user skip path (`checkExistingUserAndMaybeSkip`) does NOT
 * flip eligibility, so users who finish onboarding by virtue of having
 * cloud data already never see the tour. RestorePending users also
 * never reach `completeOnboarding`, so they're excluded by the same
 * gate.
 *
 * **Dismissal model.** [dismissed] is set when the user finishes the
 * last step ("Got it" on step N-1) or taps "Don't show again" at any
 * point. Once dismissed, the card never returns; a debug "Show Tutorial
 * Again" reset clears all three keys via [resetTourCard] so the card
 * can re-appear once onboarding re-runs.
 */
@Singleton
class TourCardPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val TOUR_CARD_ELIGIBLE = booleanPreferencesKey("tour_card_eligible")
        private val TOUR_CARD_DISMISSED = booleanPreferencesKey("tour_card_dismissed")
        private val TOUR_STEP_INDEX = intPreferencesKey("tour_step_index")
        // Coachmark tour (post-onboarding 13-surface walkthrough). Reuses
        // the same DataStore so account-wipe (PR #1180) and debug-reset
        // paths stay single-store. Eligibility shares the same gate as
        // the 5-step Today card: only `markEligible()` flips it.
        private val COACHMARK_TOUR_COMPLETED = booleanPreferencesKey("coachmark_tour_completed")
        private val COACHMARK_TOUR_DISMISSED = booleanPreferencesKey("coachmark_tour_dismissed")
        private val COACHMARK_STEP_INDEX = intPreferencesKey("coachmark_step_index")
    }

    /** True iff the user finished onboarding via SetupPage and the tour
     *  has not been dismissed yet. */
    fun eligible(): Flow<Boolean> = context.tourCardDataStore.data.map { prefs ->
        prefs[TOUR_CARD_ELIGIBLE] ?: false
    }

    fun dismissed(): Flow<Boolean> = context.tourCardDataStore.data.map { prefs ->
        prefs[TOUR_CARD_DISMISSED] ?: false
    }

    fun stepIndex(): Flow<Int> = context.tourCardDataStore.data.map { prefs ->
        prefs[TOUR_STEP_INDEX] ?: 0
    }

    /** Called from `OnboardingViewModel.completeOnboarding()` only. */
    suspend fun markEligible() {
        context.tourCardDataStore.edit { prefs ->
            prefs[TOUR_CARD_ELIGIBLE] = true
        }
    }

    suspend fun setStepIndex(index: Int) {
        context.tourCardDataStore.edit { prefs ->
            prefs[TOUR_STEP_INDEX] = index.coerceAtLeast(0)
        }
    }

    suspend fun markDismissed() {
        context.tourCardDataStore.edit { prefs ->
            prefs[TOUR_CARD_DISMISSED] = true
        }
    }

    fun coachmarkCompleted(): Flow<Boolean> = context.tourCardDataStore.data.map { prefs ->
        prefs[COACHMARK_TOUR_COMPLETED] ?: false
    }

    fun coachmarkDismissed(): Flow<Boolean> = context.tourCardDataStore.data.map { prefs ->
        prefs[COACHMARK_TOUR_DISMISSED] ?: false
    }

    fun coachmarkStepIndex(): Flow<Int> = context.tourCardDataStore.data.map { prefs ->
        prefs[COACHMARK_STEP_INDEX] ?: 0
    }

    suspend fun setCoachmarkStepIndex(index: Int) {
        context.tourCardDataStore.edit { prefs ->
            prefs[COACHMARK_STEP_INDEX] = index.coerceAtLeast(0)
        }
    }

    suspend fun markCoachmarkCompleted() {
        context.tourCardDataStore.edit { prefs ->
            prefs[COACHMARK_TOUR_COMPLETED] = true
        }
    }

    suspend fun markCoachmarkDismissed() {
        context.tourCardDataStore.edit { prefs ->
            prefs[COACHMARK_TOUR_DISMISSED] = true
        }
    }

    /** Debug-only: clear all tour flags so the card re-appears alongside
     *  a re-run of onboarding. Wired into `SettingsViewModel.resetOnboarding`. */
    suspend fun resetTourCard() {
        context.tourCardDataStore.edit { prefs ->
            prefs.remove(TOUR_CARD_ELIGIBLE)
            prefs.remove(TOUR_CARD_DISMISSED)
            prefs.remove(TOUR_STEP_INDEX)
            prefs.remove(COACHMARK_TOUR_COMPLETED)
            prefs.remove(COACHMARK_TOUR_DISMISSED)
            prefs.remove(COACHMARK_STEP_INDEX)
        }
    }
}
