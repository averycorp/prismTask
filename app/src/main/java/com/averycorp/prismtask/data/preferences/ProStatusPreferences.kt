package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pro_status_prefs")

@Singleton
class ProStatusPreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val CACHED_TIER_KEY = stringPreferencesKey("cached_tier")
        private val CACHED_BILLING_PERIOD_KEY = stringPreferencesKey("cached_billing_period")
        private val TIER_EXPIRES_AT_KEY = longPreferencesKey("tier_expires_at")
        private val LAST_VERIFIED_AT_KEY = longPreferencesKey("last_verified_at")
    }

    suspend fun getCachedTier(): String = context.dataStore.data
        .map { prefs ->
            prefs[CACHED_TIER_KEY] ?: "FREE"
        }.first()

    suspend fun getCachedBillingPeriod(): String = context.dataStore.data
        .map { prefs ->
            prefs[CACHED_BILLING_PERIOD_KEY] ?: "NONE"
        }.first()

    suspend fun tierExpiresAt(): Long = context.dataStore.data
        .map { prefs ->
            prefs[TIER_EXPIRES_AT_KEY] ?: 0L
        }.first()

    suspend fun lastVerifiedAt(): Long = context.dataStore.data
        .map { prefs ->
            prefs[LAST_VERIFIED_AT_KEY] ?: 0L
        }.first()

    suspend fun setCachedTier(tier: String) {
        context.dataStore.edit { prefs -> prefs[CACHED_TIER_KEY] = tier }
    }

    suspend fun setCachedBillingPeriod(period: String) {
        context.dataStore.edit { prefs -> prefs[CACHED_BILLING_PERIOD_KEY] = period }
    }

    suspend fun setTierExpiresAt(expiresAt: Long) {
        context.dataStore.edit { prefs -> prefs[TIER_EXPIRES_AT_KEY] = expiresAt }
    }

    suspend fun setLastVerifiedAt(verifiedAt: Long) {
        context.dataStore.edit { prefs -> prefs[LAST_VERIFIED_AT_KEY] = verifiedAt }
    }

    /**
     * Clear cached tier + billing-period state. Used by the account-deletion
     * path so a deleted user's Pro entitlement doesn't carry over to the next
     * person who signs in on the same device.
     */
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
