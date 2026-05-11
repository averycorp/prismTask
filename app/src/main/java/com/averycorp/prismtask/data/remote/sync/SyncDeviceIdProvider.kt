package com.averycorp.prismtask.data.remote.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncDeviceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sync_device_prefs"
)

/**
 * Stable, anonymous per-install identifier used by
 * [com.averycorp.prismtask.data.remote.GenericPreferenceSyncService] to tell its
 * own writes apart from writes from sibling devices on the same Firebase user.
 *
 * Generated lazily on first read and persisted to a tiny local-only DataStore.
 * Not tied to hardware IDs — a fresh install gets a new UUID, which is the
 * desired semantic (the reinstall is effectively a new device for sync
 * echo-detection purposes).
 */
@Singleton
class SyncDeviceIdProvider
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val DEVICE_ID_KEY = stringPreferencesKey("sync_device_id")
    }

    @Volatile private var cached: String? = null

    suspend fun get(): String {
        cached?.let { return it }
        val existing = context.syncDeviceDataStore.data.first()[DEVICE_ID_KEY]
        if (existing != null) {
            cached = existing
            return existing
        }
        val generated = UUID.randomUUID().toString()
        context.syncDeviceDataStore.edit { it[DEVICE_ID_KEY] = generated }
        cached = generated
        return generated
    }
}
