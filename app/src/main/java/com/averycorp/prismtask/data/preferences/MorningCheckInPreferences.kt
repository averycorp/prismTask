package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.morningCheckInDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "morning_checkin_prefs")

/**
 * Persists Today-screen morning check-in banner state (v1.4.0 polish):
 * per-day dismissal so the banner stays hidden for the rest of the
 * calendar day once the user Xs it out, and a feature-enabled toggle
 * the Settings screen can flip.
 */
@Singleton
class MorningCheckInPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val BANNER_DISMISSED_DATE_KEY = stringPreferencesKey("banner_dismissed_date")
        private val FEATURE_ENABLED_KEY = booleanPreferencesKey("feature_enabled")
    }

    private fun todayString(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    /** Live dismissal date (ISO yyyy-MM-dd) or empty string when not dismissed. */
    fun bannerDismissedDate(): Flow<String> = context.morningCheckInDataStore.data.map { prefs ->
        prefs[BANNER_DISMISSED_DATE_KEY] ?: ""
    }

    /** Live feature-enabled toggle. Defaults to true. */
    fun featureEnabled(): Flow<Boolean> = context.morningCheckInDataStore.data.map { prefs ->
        prefs[FEATURE_ENABLED_KEY] ?: true
    }

    /** True when the user has dismissed the banner earlier today. */
    suspend fun isBannerDismissedToday(): Boolean {
        val prefs = context.morningCheckInDataStore.data.first()
        return prefs[BANNER_DISMISSED_DATE_KEY] == todayString()
    }

    /**
     * Records today's dismissal so the banner stays hidden until tomorrow.
     * Callers that know the user's logical (SoD-aware) date should pass it
     * in as [logicalDateIso] so the dismissal stays consistent with the
     * reader-side comparison. Defaults to the wall-clock date for callers
     * with no SoD context.
     *
     * See `docs/audits/MORNING_CHECKIN_SOD_BOUNDARY_AUDIT.md` § 3.
     */
    suspend fun dismissBannerToday(logicalDateIso: String = todayString()) {
        context.morningCheckInDataStore.edit { prefs ->
            prefs[BANNER_DISMISSED_DATE_KEY] = logicalDateIso
        }
    }

    suspend fun setFeatureEnabled(enabled: Boolean) {
        context.morningCheckInDataStore.edit { prefs ->
            prefs[FEATURE_ENABLED_KEY] = enabled
        }
    }
}
