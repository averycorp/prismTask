package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.templateDataStore: DataStore<Preferences> by preferencesDataStore(name = "template_prefs")

/**
 * Persisted flags for the template subsystem:
 *
 *  - `templates_seeded` marks that [com.averycorp.prismtask.data.seed.TemplateSeeder]
 *    has already inserted the built-in templates, so we don't re-insert them on
 *    every app launch (even if the user deleted some of them).
 *  - `templates_first_sync_done` tracks whether we've pushed the user's local
 *    templates to the backend at least once, so the first-connect push/merge in
 *    [com.averycorp.prismtask.data.remote.sync.BackendSyncService] runs exactly
 *    once per install/account pair.
 */
@Singleton
class TemplatePreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val TEMPLATES_SEEDED_KEY = booleanPreferencesKey("templates_seeded")
        private val TEMPLATES_FIRST_SYNC_DONE_KEY =
            booleanPreferencesKey("templates_first_sync_done")
    }

    suspend fun isSeeded(): Boolean =
        context.templateDataStore.data.first()[TEMPLATES_SEEDED_KEY] ?: false

    suspend fun setSeeded(seeded: Boolean) {
        context.templateDataStore.edit { prefs ->
            prefs[TEMPLATES_SEEDED_KEY] = seeded
        }
    }

    suspend fun isFirstSyncDone(): Boolean =
        context.templateDataStore.data.first()[TEMPLATES_FIRST_SYNC_DONE_KEY] ?: false

    suspend fun setFirstSyncDone(done: Boolean) {
        context.templateDataStore.edit { prefs ->
            prefs[TEMPLATES_FIRST_SYNC_DONE_KEY] = done
        }
    }

    suspend fun clear() {
        context.templateDataStore.edit { it.clear() }
    }
}
