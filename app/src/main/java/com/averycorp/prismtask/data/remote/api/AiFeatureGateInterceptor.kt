package com.averycorp.prismtask.data.remote.api

import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that short-circuits Anthropic-touching requests when
 * the user has disabled AI features in Settings → AI Features
 * (PII egress audit, 2026-04-26).
 *
 * Privacy invariant: when [UserPreferencesDataStore.isAiFeaturesEnabledBlocking]
 * returns false, no PrismTask data should reach Anthropic via the backend.
 * This interceptor enforces that on the client by intercepting requests
 * to AI-touching paths and returning a synthetic 451
 * "Unavailable For Legal Reasons" response without making the network call.
 *
 * The interceptor also stamps `X-PrismTask-AI-Features: disabled` on the
 * synthetic response and on outbound requests when the flag is off, so the
 * backend's [require_ai_features_enabled] middleware can reject any
 * request that does manage to reach it (e.g. from a non-Android client
 * or a buggy build that registered the interceptor in the wrong order).
 *
 * Path matching is conservative: any path starting with one of the
 * known AI-touching prefixes is treated as in-scope. Adding a new
 * Anthropic-touching backend route requires updating [AI_PATH_PREFIXES].
 */
@Singleton
class AiFeatureGateInterceptor
@Inject
constructor(
    private val userPreferencesDataStore: UserPreferencesDataStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!isAiTouchingPath(original)) {
            return chain.proceed(original)
        }

        if (userPreferencesDataStore.isAiFeaturesEnabledBlocking()) {
            // AI is enabled — proceed normally, no header injected.
            return chain.proceed(original)
        }

        // AI is disabled — stamp the disable header on the request *before*
        // short-circuiting (defense-in-depth: if a buggy build re-orders
        // interceptors so the synthetic-451 step gets bypassed, the request
        // already carries the header and the server's
        // ``require_ai_features_enabled`` dependency will still reject it).
        val tagged = original.newBuilder()
            .header(HEADER_AI_FEATURES, HEADER_VALUE_DISABLED)
            .build()

        // Short-circuit with a 451. The backend never sees the request, so
        // no PrismTask data reaches Anthropic.
        return Response.Builder()
            .request(tagged)
            .protocol(Protocol.HTTP_1_1)
            .code(HTTP_451_UNAVAILABLE_FOR_LEGAL_REASONS)
            .header(HEADER_AI_FEATURES, HEADER_VALUE_DISABLED)
            .message("AI features disabled in PrismTask Settings")
            .body(SYNTHETIC_BODY.toResponseBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun isAiTouchingPath(request: Request): Boolean {
        val path = request.url.encodedPath
        return AI_PATH_PREFIXES.any { path.startsWith(it) }
    }

    companion object {
        const val HEADER_AI_FEATURES: String = "X-PrismTask-AI-Features"
        const val HEADER_VALUE_DISABLED: String = "disabled"
        const val HTTP_451_UNAVAILABLE_FOR_LEGAL_REASONS: Int = 451

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val SYNTHETIC_BODY =
            """{"detail":"AI features are disabled in PrismTask Settings → AI Features. Re-enable to use this feature."}"""

        /**
         * Path prefixes that egress user data to Anthropic via the backend.
         *
         * Prefixes must match the *full* outbound URL path (including the
         * `/api/v1` API version prefix), because OkHttp interceptors see
         * the resolved Retrofit baseUrl + endpoint path — not the relative
         * Retrofit annotation path. Stripping the version prefix here
         * silently disables the gate for every real production request.
         *
         * Keep in sync with `backend/app/middleware/ai_gate.py` and the
         * router-level dependency wiring on the backend.
         */
        val AI_PATH_PREFIXES: List<String> = listOf(
            "/api/v1/ai/",
            "/api/v1/tasks/parse",
            "/api/v1/syllabus/parse",
            // PII egress to Anthropic via Gmail scan — the integrations
            // router landed two weeks before PR #790 and the original audit
            // missed it; closed by 2026-05-01 follow-up. The precise
            // `/integrations/gmail/scan` prefix is intentional: the
            // suggestion inbox / accept / reject endpoints under
            // `/integrations/suggestions*` do NOT call Anthropic and must
            // keep working when the user opts out.
            "/api/v1/integrations/gmail/scan"
        )
    }
}
