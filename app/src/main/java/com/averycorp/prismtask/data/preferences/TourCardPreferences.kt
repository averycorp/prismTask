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
 * Backing store for the post-onboarding coachmark tour.
 *
 * The tour surfaces a 13-surface anchored walkthrough after a user
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
 * **Historical note.** A 5-step in-flow `GuidedTourCard` on Today also
 * read this store. It was retired in favour of the anchored coachmark
 * tour (audit doc `docs/audits/ONBOARDING_OVERLAP_AUDIT.md` finding
 * #1); its `tour_card_dismissed` / `tour_step_index` DataStore keys are
 * no longer written. `tour_card_eligible` is retained — the coachmark
 * controller still uses it as the post-onboarding eligibility signal.
 */
@Singleton
class TourCardPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Post-onboarding eligibility flag. Still load-bearing — the
        // CoachmarkController reads it via `eligible()` to gate
        // `tryStart`. Only flipped by `OnboardingViewModel.completeOnboarding`.
        private val TOUR_CARD_ELIGIBLE = booleanPreferencesKey("tour_card_eligible")

        // Coachmark tour (post-onboarding 13-surface walkthrough). Reuses
        // the same DataStore so account-wipe (PR #1180) and debug-reset
        // paths stay single-store.
        private val COACHMARK_TOUR_COMPLETED = booleanPreferencesKey("coachmark_tour_completed")
        private val COACHMARK_TOUR_DISMISSED = booleanPreferencesKey("coachmark_tour_dismissed")
        private val COACHMARK_STEP_INDEX = intPreferencesKey("coachmark_step_index")
    }

    /** True iff the user finished onboarding via SetupPage and the tour
     *  has not been dismissed yet. */
    fun eligible(): Flow<Boolean> = context.tourCardDataStore.data.map { prefs ->
        prefs[TOUR_CARD_ELIGIBLE] ?: false
    }

    /** Called from `OnboardingViewModel.completeOnboarding()` only. */
    suspend fun markEligible() {
        context.tourCardDataStore.edit { prefs ->
            prefs[TOUR_CARD_ELIGIBLE] = true
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

    /** Clears all tour flags so the tour re-fires alongside a future
     *  re-run of onboarding. Used by Settings → Reset App Data when the
     *  user wipes preferences. */
    suspend fun resetTourCard() {
        context.tourCardDataStore.edit { prefs ->
            prefs.remove(TOUR_CARD_ELIGIBLE)
            prefs.remove(COACHMARK_TOUR_COMPLETED)
            prefs.remove(COACHMARK_TOUR_DISMISSED)
            prefs.remove(COACHMARK_STEP_INDEX)
        }
    }
}
