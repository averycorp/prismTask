package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.ratingPromptDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "rating_prompt_prefs"
)

/**
 * DataStore-backed counters + cooldown timestamps for the in-app rating
 * trigger heuristic (E2 in-app ratings, see
 * `docs/audits/E2_IN_APP_RATINGS_AUDIT.md` § Item 3). Mirrors the
 * `PerFeatureAiPrefs` pattern: KEY_* constants + suspend accessors +
 * `Flow<Long>` reads.
 *
 * `last_crash_at` lives here (not in a separate Crashlytics-mirror file)
 * because it is solely consumed by the trigger heuristic — see
 * `RecentCrashSignal`.
 *
 * Backed by [PreferenceAccessor] (pilot of T2.2 in
 * `docs/audits/REFACTOR_TIERS_1_3_AUDIT.md`); the public method shape is
 * byte-identical so existing callers and MockK stubs still compile.
 */
@Singleton
class RatingPromptPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    private val ds: DataStore<Preferences> get() = context.ratingPromptDataStore

    private val tasksCompleted = PreferenceAccessor(ds, KEY_TASKS_COMPLETED_COUNT, default = 0L)
    private val sessions = PreferenceAccessor(ds, KEY_SESSION_COUNT, default = 0L)
    private val firstLaunch = PreferenceAccessor(ds, KEY_FIRST_LAUNCH_AT, default = 0L)
    private val lastPlayReviewShown = PreferenceAccessor(ds, KEY_LAST_PLAY_REVIEW_SHOWN_AT, default = 0L)
    private val lastCustomPromptShown = PreferenceAccessor(ds, KEY_LAST_CUSTOM_PROMPT_SHOWN_AT, default = 0L)
    private val lastCrash = PreferenceAccessor(ds, KEY_LAST_CRASH_AT, default = 0L)

    fun tasksCompletedCount(): Flow<Long> = tasksCompleted.flow

    fun sessionCount(): Flow<Long> = sessions.flow

    fun firstLaunchAt(): Flow<Long> = firstLaunch.flow

    fun lastPlayReviewShownAt(): Flow<Long> = lastPlayReviewShown.flow

    fun lastCustomPromptShownAt(): Flow<Long> = lastCustomPromptShown.flow

    fun lastCrashAt(): Flow<Long> = lastCrash.flow

    suspend fun incrementTasksCompletedCount() {
        tasksCompleted.update { it + 1 }
    }

    suspend fun incrementSessionCount() {
        sessions.update { it + 1 }
    }

    suspend fun setFirstLaunchAtIfAbsent(nowMs: Long) {
        // Read-then-write inside a single edit { } so a racing call can't
        // overwrite a stamp that's already been set.
        ds.edit { prefs ->
            if ((prefs[KEY_FIRST_LAUNCH_AT] ?: 0L) == 0L) {
                prefs[KEY_FIRST_LAUNCH_AT] = nowMs
            }
        }
    }

    suspend fun setLastPlayReviewShownAt(nowMs: Long) {
        lastPlayReviewShown.set(nowMs)
    }

    suspend fun setLastCustomPromptShownAt(nowMs: Long) {
        lastCustomPromptShown.set(nowMs)
    }

    suspend fun setLastCrashAt(nowMs: Long) {
        lastCrash.set(nowMs)
    }

    companion object {
        private val KEY_TASKS_COMPLETED_COUNT = longPreferencesKey("tasks_completed_count")
        private val KEY_SESSION_COUNT = longPreferencesKey("session_count")
        private val KEY_FIRST_LAUNCH_AT = longPreferencesKey("first_launch_at")
        private val KEY_LAST_PLAY_REVIEW_SHOWN_AT = longPreferencesKey("last_play_review_shown_at")
        private val KEY_LAST_CUSTOM_PROMPT_SHOWN_AT = longPreferencesKey("last_custom_prompt_shown_at")
        private val KEY_LAST_CRASH_AT = longPreferencesKey("last_crash_at")
    }
}
