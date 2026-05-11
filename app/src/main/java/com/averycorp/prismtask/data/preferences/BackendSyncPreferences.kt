package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backendSyncDataStore: DataStore<Preferences> by preferencesDataStore(name = "backend_sync_prefs")

/**
 * Stores metadata for the FastAPI backend sync: the timestamp of the last
 * successful pull so subsequent pulls only fetch incremental changes.
 *
 * This is distinct from [AuthTokenPreferences] (which holds the JWTs) and from
 * the Firebase [com.averycorp.prismtask.data.remote.SyncService] sync-metadata.
 */
@Singleton
class BackendSyncPreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val LAST_SYNC_AT_KEY = longPreferencesKey("last_sync_at")
    }

    val lastSyncAtFlow: Flow<Long> = context.backendSyncDataStore.data.map { prefs ->
        prefs[LAST_SYNC_AT_KEY] ?: 0L
    }

    suspend fun getLastSyncAt(): Long =
        context.backendSyncDataStore.data.first()[LAST_SYNC_AT_KEY] ?: 0L

    suspend fun setLastSyncAt(timestamp: Long) {
        context.backendSyncDataStore.edit { prefs ->
            prefs[LAST_SYNC_AT_KEY] = timestamp
        }
    }

    suspend fun clear() {
        context.backendSyncDataStore.edit { it.clear() }
    }
}
