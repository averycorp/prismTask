package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
 */
@Singleton
class RatingPromptPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private val KEY_TASKS_COMPLETED_COUNT = longPreferencesKey("tasks_completed_count")
        private val KEY_SESSION_COUNT = longPreferencesKey("session_count")
        private val KEY_FIRST_LAUNCH_AT = longPreferencesKey("first_launch_at")
        private val KEY_LAST_PLAY_REVIEW_SHOWN_AT = longPreferencesKey("last_play_review_shown_at")
        private val KEY_LAST_CUSTOM_PROMPT_SHOWN_AT = longPreferencesKey("last_custom_prompt_shown_at")
        private val KEY_LAST_CRASH_AT = longPreferencesKey("last_crash_at")
    }

    fun tasksCompletedCount(): Flow<Long> =
        context.ratingPromptDataStore.data.map { it[KEY_TASKS_COMPLETED_COUNT] ?: 0L }

    fun sessionCount(): Flow<Long> =
        context.ratingPromptDataStore.data.map { it[KEY_SESSION_COUNT] ?: 0L }

    fun firstLaunchAt(): Flow<Long> =
        context.ratingPromptDataStore.data.map { it[KEY_FIRST_LAUNCH_AT] ?: 0L }

    fun lastPlayReviewShownAt(): Flow<Long> =
        context.ratingPromptDataStore.data.map { it[KEY_LAST_PLAY_REVIEW_SHOWN_AT] ?: 0L }

    fun lastCustomPromptShownAt(): Flow<Long> =
        context.ratingPromptDataStore.data.map { it[KEY_LAST_CUSTOM_PROMPT_SHOWN_AT] ?: 0L }

    fun lastCrashAt(): Flow<Long> =
        context.ratingPromptDataStore.data.map { it[KEY_LAST_CRASH_AT] ?: 0L }

    suspend fun incrementTasksCompletedCount() {
        context.ratingPromptDataStore.edit { prefs ->
            prefs[KEY_TASKS_COMPLETED_COUNT] = (prefs[KEY_TASKS_COMPLETED_COUNT] ?: 0L) + 1
        }
    }

    suspend fun incrementSessionCount() {
        context.ratingPromptDataStore.edit { prefs ->
            prefs[KEY_SESSION_COUNT] = (prefs[KEY_SESSION_COUNT] ?: 0L) + 1
        }
    }

    suspend fun setFirstLaunchAtIfAbsent(nowMs: Long) {
        context.ratingPromptDataStore.edit { prefs ->
            if ((prefs[KEY_FIRST_LAUNCH_AT] ?: 0L) == 0L) {
                prefs[KEY_FIRST_LAUNCH_AT] = nowMs
            }
        }
    }

    suspend fun setLastPlayReviewShownAt(nowMs: Long) {
        context.ratingPromptDataStore.edit { prefs ->
            prefs[KEY_LAST_PLAY_REVIEW_SHOWN_AT] = nowMs
        }
    }

    suspend fun setLastCustomPromptShownAt(nowMs: Long) {
        context.ratingPromptDataStore.edit { prefs ->
            prefs[KEY_LAST_CUSTOM_PROMPT_SHOWN_AT] = nowMs
        }
    }

    suspend fun setLastCrashAt(nowMs: Long) {
        context.ratingPromptDataStore.edit { prefs ->
            prefs[KEY_LAST_CRASH_AT] = nowMs
        }
    }
}
