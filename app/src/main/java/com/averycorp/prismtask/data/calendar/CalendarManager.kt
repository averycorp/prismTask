package com.averycorp.prismtask.data.calendar

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class CalendarInfo(val id: String, val name: String, val color: String, val isPrimary: Boolean)

sealed class CalendarConnectionResult {
    data object Connected : CalendarConnectionResult()

    data class NeedsConsent(val signInIntent: Intent) : CalendarConnectionResult()

    data class Failed(val message: String) : CalendarConnectionResult()
}

@Singleton
class CalendarManager
@Inject
constructor(@ApplicationContext private val context: Context) {
    private val _isCalendarConnected = MutableStateFlow(false)
    val isCalendarConnected: StateFlow<Boolean> = _isCalendarConnected.asStateFlow()

    private val _connectedAccountEmail = MutableStateFlow<String?>(null)
    val connectedAccountEmail: StateFlow<String?> = _connectedAccountEmail.asStateFlow()

    private var cachedCalendars: List<CalendarInfo>? = null
    private var cacheTimestamp: Long = 0
    private val cacheDurationMs = 30 * 60 * 1000L // 30 minutes

    init {
        // Check if calendar is already connected from existing Google account
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null &&
            account.grantedScopes.any {
                it.scopeUri == CalendarScopes.CALENDAR
            }
        ) {
            _isCalendarConnected.value = true
            _connectedAccountEmail.value = account.email
        }
    }

    private fun buildCalendarSignInClient(): com.google.android.gms.auth.api.signin.GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(CalendarScopes.CALENDAR))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    /**
     * Connects to Google Calendar. First checks if the calendar scope is already
     * granted, then tries silent sign-in. If neither works, returns [CalendarConnectionResult.NeedsConsent]
     * with an intent that the caller must launch to obtain user consent.
     */
    suspend fun connectCalendar(): CalendarConnectionResult = withContext(Dispatchers.IO) {
        try {
            // Check if already have the scope from a previous connection
            val existing = GoogleSignIn.getLastSignedInAccount(context)
            if (existing != null && existing.grantedScopes.any { it.scopeUri == CalendarScopes.CALENDAR }) {
                _isCalendarConnected.value = true
                _connectedAccountEmail.value = existing.email
                refreshCalendarCache()
                return@withContext CalendarConnectionResult.Connected
            }

            // Try silent sign-in with Calendar scope
            val client = buildCalendarSignInClient()
            try {
                val account = client.silentSignIn().await()
                if (account.grantedScopes.any { it.scopeUri == CalendarScopes.CALENDAR }) {
                    _isCalendarConnected.value = true
                    _connectedAccountEmail.value = account.email
                    refreshCalendarCache()
                    return@withContext CalendarConnectionResult.Connected
                }
            } catch (_: Exception) {
                // Silent sign-in failed — need interactive consent
            }

            // Need user to grant Calendar scope via consent UI
            CalendarConnectionResult.NeedsConsent(client.signInIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect calendar", e)
            CalendarConnectionResult.Failed(e.message ?: "Failed to connect Google Calendar")
        }
    }

    /**
     * Processes the result intent returned by the consent activity launched
     * from [CalendarConnectionResult.NeedsConsent].
     */
    suspend fun handleSignInResult(data: Intent?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            if (account.grantedScopes.any { it.scopeUri == CalendarScopes.CALENDAR }) {
                _isCalendarConnected.value = true
                _connectedAccountEmail.value = account.email
                refreshCalendarCache()
                Result.success(Unit)
            } else {
                Result.failure(CalendarScopeRequiredException("Calendar permission was not granted"))
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Calendar sign-in failed: status=${e.statusCode}", e)
            Result.failure(Exception("Google Calendar authorization failed"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle sign-in result", e)
            Result.failure(e)
        }
    }

    /**
     * Disconnects from Google Calendar by clearing local state.
     * Does not revoke the Google account — just disables calendar features.
     */
    suspend fun disconnectCalendar() {
        _isCalendarConnected.value = false
        _connectedAccountEmail.value = null
        cachedCalendars = null
        cacheTimestamp = 0
    }

    /**
     * Returns a configured Google Calendar API service instance using the
     * authenticated user's credential. Returns null if not signed in.
     */
    fun getCalendarService(): Calendar? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(CalendarScopes.CALENDAR)
        )
        credential.selectedAccount = account.account
        return Calendar
            .Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("PrismTask")
            .build()
    }

    /**
     * Queries the user's calendar list from Google Calendar API.
     * Results are cached for 30 minutes.
     */
    suspend fun getUserCalendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedCalendars
        if (cached != null && (now - cacheTimestamp) < cacheDurationMs) {
            return@withContext cached
        }

        try {
            val service = getCalendarService()
                ?: return@withContext emptyList()

            val calendarList = service
                .calendarList()
                .list()
                .setMinAccessRole("writer")
                .execute()

            val calendars = calendarList.items
                ?.map { entry ->
                    CalendarInfo(
                        id = entry.id,
                        name = entry.summary ?: entry.id,
                        color = entry.backgroundColor ?: "#4285F4",
                        isPrimary = entry.isPrimary == true
                    )
                }?.sortedByDescending { it.isPrimary } ?: emptyList()

            cachedCalendars = calendars
            cacheTimestamp = now
            calendars
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch calendars", e)
            cachedCalendars ?: emptyList()
        }
    }

    /**
     * Refreshes the calendar cache by clearing the timestamp and re-fetching.
     */
    private suspend fun refreshCalendarCache() {
        cacheTimestamp = 0
        cachedCalendars = null
        getUserCalendars()
    }

    /**
     * Checks if the current Google account has the calendar scope granted.
     */
    fun hasCalendarScope(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return account.grantedScopes.any { it.scopeUri == CalendarScopes.CALENDAR }
    }

    companion object {
        private const val TAG = "CalendarManager"
    }
}

/**
 * Thrown when the calendar OAuth scope is not granted and the caller
 * needs to trigger re-consent.
 */
class CalendarScopeRequiredException(message: String) : Exception(message)
