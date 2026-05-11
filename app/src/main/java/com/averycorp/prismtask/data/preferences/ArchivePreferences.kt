package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.archiveDataStore: DataStore<Preferences> by preferencesDataStore(name = "archive_prefs")

@Singleton
class ArchivePreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val AUTO_ARCHIVE_DAYS_KEY = intPreferencesKey("auto_archive_days")
    }

    fun getAutoArchiveDays(): Flow<Int> = context.archiveDataStore.data.map { prefs ->
        prefs[AUTO_ARCHIVE_DAYS_KEY] ?: 7
    }

    suspend fun setAutoArchiveDays(days: Int) {
        context.archiveDataStore.edit { prefs ->
            prefs[AUTO_ARCHIVE_DAYS_KEY] = days
        }
    }

    suspend fun clearAll() {
        context.archiveDataStore.edit { it.clear() }
    }
}
