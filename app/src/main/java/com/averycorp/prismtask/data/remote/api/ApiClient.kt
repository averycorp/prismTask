package com.averycorp.prismtask.data.remote.api

import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.AuthTokenPreferences
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the cached JWT to every outgoing request as a Bearer token.
 *
 * Skips auth endpoints (`/auth/login`, `/auth/register`, `/auth/refresh`) so
 * that obtaining or refreshing a token never sends a stale one.
 */
@Singleton
class AuthInterceptor
@Inject
constructor(private val tokenPreferences: AuthTokenPreferences) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (isAuthEndpoint(original)) {
            return chain.proceed(original)
        }

        val token = tokenPreferences.getAccessTokenBlocking()
        val request = if (token.isNullOrBlank()) {
            original
        } else {
            original
                .newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }

    private fun isAuthEndpoint(request: Request): Boolean {
        val path = request.url.encodedPath
        return path.endsWith("/auth/login") ||
            path.endsWith("/auth/register") ||
            path.endsWith("/auth/refresh") ||
            path.endsWith("/auth/firebase")
    }
}

/**
 * OkHttp [Authenticator] that handles 401 responses by attempting to refresh
 * the access token using the stored refresh token, then retrying the original
 * request once.
 *
 * Implemented as an Authenticator (rather than a plain Interceptor) so OkHttp
 * handles the request replay automatically and avoids retry loops.
 */
@Singleton
class TokenAuthenticator
@Inject
constructor(
    private val tokenPreferences: AuthTokenPreferences,
    private val gson: Gson,
    private val advancedTuningPreferences: AdvancedTuningPreferences
) : Authenticator {
    private val networkConfig by lazy {
        runBlocking { advancedTuningPreferences.getApiNetworkConfig().first() }
    }

    // Reuse a single OkHttpClient for all refresh calls. Previously a new
    // client was created per 401, which spins up a fresh connection pool
    // each time — a slow leak under refresh storms.
    private val refreshClient: OkHttpClient by lazy {
        val timeout = networkConfig.timeoutSeconds.toLong()
        OkHttpClient
            .Builder()
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .build()
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        // Avoid infinite retry loops: cap refresh attempts at the
        // user-configured count (default 2 — the original behavior).
        if (responseCount(response) >= networkConfig.retryAttempts) return null

        val refreshToken = tokenPreferences.getRefreshTokenBlocking()
        if (refreshToken.isNullOrBlank()) return null

        val newTokens = synchronized(this) {
            // Re-check in case another thread already refreshed.
            val currentAccess = tokenPreferences.getAccessTokenBlocking()
            val authHeader = response.request.header("Authorization")
            if (currentAccess != null && authHeader != "Bearer $currentAccess") {
                // Tokens were refreshed by another thread; reuse them.
                TokenResponse(
                    accessToken = currentAccess,
                    refreshToken = refreshToken,
                    tokenType = "bearer"
                )
            } else {
                refreshTokens(response, refreshToken)
            }
        } ?: return null

        tokenPreferences.setTokensBlocking(newTokens.accessToken, newTokens.refreshToken)

        return response.request
            .newBuilder()
            .header("Authorization", "Bearer ${newTokens.accessToken}")
            .build()
    }

    private fun refreshTokens(response: Response, refreshToken: String): TokenResponse? {
        return try {
            val refreshUrl = response.request.url
                .newBuilder()
                .encodedPath("/api/v1/auth/refresh")
                .query(null)
                .build()

            val body = gson
                .toJson(RefreshRequest(refreshToken))
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request
                .Builder()
                .url(refreshUrl)
                .post(body)
                .build()

            // Use the bare refresh client (no auth interceptor / no
            // authenticator) to avoid recursing back into this Authenticator.
            refreshClient.newCall(request).execute().use { refreshResponse ->
                if (!refreshResponse.isSuccessful) return@use null
                val responseBody = refreshResponse.body?.string() ?: return@use null
                gson.fromJson(responseBody, TokenResponse::class.java)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
