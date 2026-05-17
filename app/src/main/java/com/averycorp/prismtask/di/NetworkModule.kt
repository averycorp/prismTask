package com.averycorp.prismtask.di

import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.remote.api.AdminModelOverrideInterceptor
import com.averycorp.prismtask.data.remote.api.AiFeatureGateInterceptor
import com.averycorp.prismtask.data.remote.api.AuthInterceptor
import com.averycorp.prismtask.data.remote.api.CalendarBackendApi
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.TokenAuthenticator
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module that provides the singletons needed to talk to the FastAPI
 * backend: OkHttp client (with logging + auth + 401-refresh), Retrofit, and
 * the [PrismTaskApi] interface.
 *
 * Base URL comes from `BuildConfig.API_BASE_URL`:
 *   - debug:   https://averytask-production.up.railway.app   (override with
 *              API_BASE_URL_DEBUG env var, e.g. http://10.0.2.2:8000 for
 *              emulator → host loopback against a local FastAPI server)
 *   - release: https://averytask-production.up.railway.app
 *
 * Note: [AuthInterceptor] and [TokenAuthenticator] are constructor-injected
 * (`@Inject constructor`) rather than provided here, so this module never has
 * to reference `AuthTokenPreferences` directly. That avoids a KSP processing
 * order issue where Hilt couldn't resolve the token-preferences symbol when
 * it appeared only inside a sibling module's `@Provides` parameter list.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /**
     * Read timeout floor — kept above the user-configurable connect
     * timeout because read can legitimately wait longer for a slow
     * backend response than for an initial socket open.
     */
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val WRITE_TIMEOUT_SECONDS = 60L

    @Provides
    @Singleton
    fun provideHttpLoggingInterceptor(): HttpLoggingInterceptor {
        val interceptor = HttpLoggingInterceptor()
        interceptor.level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
        return interceptor
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor,
        aiFeatureGateInterceptor: AiFeatureGateInterceptor,
        adminModelOverrideInterceptor: AdminModelOverrideInterceptor,
        tokenAuthenticator: TokenAuthenticator,
        advancedTuningPreferences: AdvancedTuningPreferences
    ): OkHttpClient {
        // Read once at provision time — OkHttpClient is a process-singleton.
        // Live updates would require a per-call interceptor; restart-to-apply
        // is acceptable for an advanced power-user knob.
        val config = runBlocking { advancedTuningPreferences.getApiNetworkConfig().first() }
        return OkHttpClient
            .Builder()
            .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(maxOf(READ_TIMEOUT_SECONDS, config.timeoutSeconds.toLong()), TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Order matters: gate first so we don't waste an auth-token
            // refresh on a request that's about to be short-circuited.
            .addInterceptor(aiFeatureGateInterceptor)
            .addInterceptor(adminModelOverrideInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .authenticator(tokenAuthenticator)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        gson: Gson
    ): Retrofit = Retrofit
        .Builder()
        .baseUrl(normalizeBaseUrl(BuildConfig.API_BASE_URL))
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    @Provides
    @Singleton
    fun providePrismTaskApi(retrofit: Retrofit): PrismTaskApi =
        retrofit.create(PrismTaskApi::class.java)

    @Provides
    @Singleton
    fun provideCalendarBackendApi(retrofit: Retrofit): CalendarBackendApi =
        retrofit.create(CalendarBackendApi::class.java)

    private fun normalizeBaseUrl(url: String): String =
        if (url.endsWith("/")) url else "$url/"
}
