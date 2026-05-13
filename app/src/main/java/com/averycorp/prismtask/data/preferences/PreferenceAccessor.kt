package com.averycorp.prismtask.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Generic helper that collapses the read/write/flow/blocking boilerplate
 * repeated across the ~30 preference files in this package down to a single
 * one-liner per typed key:
 *
 * ```
 * private val sessionCount = PreferenceAccessor(dataStore, longPreferencesKey("session_count"), default = 0L)
 *
 * sessionCount.flow                  // Flow<Long>
 * sessionCount.get()                 // suspend fun -> Long
 * sessionCount.set(42L)              // suspend fun
 * sessionCount.update { it + 1 }     // suspend fun, read-modify-write
 * sessionCount.getBlocking()         // for OkHttp interceptors only
 * ```
 *
 * Supports every type that [Preferences.Key] supports natively (String, Int,
 * Long, Float, Boolean, `Set<String>`) — anything composite (Gson-encoded
 * payloads, multi-key bundles like `BuiltInSortOrders`) should stay on the
 * raw `DataStore.edit { }` API.
 *
 * Pilot scope: this helper is wired into [RatingPromptPreferences] and
 * [HabitListPreferences] only. The remaining preference files migrate in a
 * follow-up pass (T2.2 in `docs/audits/REFACTOR_TIERS_1_3_AUDIT.md`).
 */
class PreferenceAccessor<T>(
    private val dataStore: DataStore<Preferences>,
    private val key: Preferences.Key<T>,
    private val default: T
) {
    /** Cold flow that always emits the current value, falling back to [default]. */
    val flow: Flow<T> = dataStore.data.map { prefs -> prefs[key] ?: default }

    /** Suspend read of the current value (or [default] if absent). */
    suspend fun get(): T = dataStore.data.first()[key] ?: default

    /** Suspend write of [value]. */
    suspend fun set(value: T) {
        dataStore.edit { it[key] = value }
    }

    /** Read-modify-write inside a single `edit { }` block. */
    suspend fun update(transform: (T) -> T) {
        dataStore.edit { prefs ->
            val current = prefs[key] ?: default
            prefs[key] = transform(current)
        }
    }

    /**
     * Blocking read for callers that can't suspend (OkHttp interceptors, etc.).
     * Mirrors the `getAccessTokenBlocking()` pattern in [AuthTokenPreferences].
     */
    fun getBlocking(): T = runBlocking { get() }

    /** Blocking write — same caveat as [getBlocking]. */
    fun setBlocking(value: T) {
        runBlocking { set(value) }
    }
}
