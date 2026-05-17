package com.averycorp.prismtask.data.remote.api

import com.averycorp.prismtask.data.preferences.ProStatusPreferences
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stamps `X-PrismTask-Admin-Model-Override: sonnet` on AI-touching
 * requests when the admin "Use Sonnet" toggle is on in Settings → Admin.
 *
 * The backend respects the header only for users with `is_admin = true`
 * (see `app/middleware/admin_model_override.py`); for everyone else the
 * header is silently ignored, so an attacker can't use it to bypass
 * model-tier gating.
 *
 * Path matching mirrors [AiFeatureGateInterceptor.AI_PATH_PREFIXES] so we
 * only attach the header to requests that actually call `get_model()` on
 * the backend.
 */
@Singleton
class AdminModelOverrideInterceptor
@Inject
constructor(
    private val proStatusPreferences: ProStatusPreferences
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!isAiTouchingPath(original.url.encodedPath)) {
            return chain.proceed(original)
        }
        if (!proStatusPreferences.getAdminUseSonnetBlocking()) {
            return chain.proceed(original)
        }
        val tagged = original.newBuilder()
            .header(HEADER_NAME, HEADER_VALUE_SONNET)
            .build()
        return chain.proceed(tagged)
    }

    private fun isAiTouchingPath(path: String): Boolean =
        AiFeatureGateInterceptor.AI_PATH_PREFIXES.any { path.startsWith(it) }

    companion object {
        const val HEADER_NAME: String = "X-PrismTask-Admin-Model-Override"
        const val HEADER_VALUE_SONNET: String = "sonnet"
    }
}
