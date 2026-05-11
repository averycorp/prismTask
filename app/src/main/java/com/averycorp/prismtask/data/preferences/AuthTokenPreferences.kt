package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authTokenDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_token_prefs")

/**
 * DataStore-backed storage for backend API JWT tokens (access + refresh).
 *
 * Separate from the Firebase/Google auth flow — these tokens are issued by the
 * FastAPI backend (`/api/v1/auth/...`) and attached to requests by the OkHttp
 * auth interceptor.
 */
@Singleton
class AuthTokenPreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
    }

    val accessTokenFlow: Flow<String?> = context.authTokenDataStore.data.map { prefs ->
        prefs[ACCESS_TOKEN_KEY]
    }

    val refreshTokenFlow: Flow<String?> = context.authTokenDataStore.data.map { prefs ->
        prefs[REFRESH_TOKEN_KEY]
    }

    suspend fun getAccessToken(): String? =
        context.authTokenDataStore.data.first()[ACCESS_TOKEN_KEY]

    suspend fun getRefreshToken(): String? =
        context.authTokenDataStore.data.first()[REFRESH_TOKEN_KEY]

    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        context.authTokenDataStore.edit { prefs ->
            prefs[ACCESS_TOKEN_KEY] = accessToken
            prefs[REFRESH_TOKEN_KEY] = refreshToken
        }
    }

    suspend fun clearTokens() {
        context.authTokenDataStore.edit { it.clear() }
    }

    /**
     * Blocking read of the current access token. Used by the OkHttp interceptor,
     * which runs on a network thread outside of a coroutine scope.
     */
    fun getAccessTokenBlocking(): String? = runBlocking { getAccessToken() }

    fun getRefreshTokenBlocking(): String? = runBlocking { getRefreshToken() }

    fun setTokensBlocking(accessToken: String, refreshToken: String) {
        runBlocking { saveTokens(accessToken, refreshToken) }
    }
}
