package com.averycorp.prismtask.data.remote.api

import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AiFeatureGateInterceptor] — the OkHttp interceptor that
 * enforces the privacy invariant for the master AI features toggle (PR #790).
 *
 * Phase F privacy invariant: when `Settings → AI Features → "Use Claude AI
 * for advanced features"` is OFF, no PrismTask data — including medication
 * names — must reach the backend or Anthropic. The interceptor enforces
 * this on the client by short-circuiting AI-touching requests with a
 * synthetic 451 response without calling `chain.proceed()`.
 *
 * Surfaces tested:
 *  - Pass-through when AI is enabled (regardless of path)
 *  - Pass-through when AI is disabled but path is non-AI
 *  - Short-circuit when AI is disabled AND path is AI-touching
 *  - Synthetic response shape (status, header, JSON body)
 *  - Path-prefix matching for every AI_PATH_PREFIXES entry
 *  - Non-matching paths bypass the gate
 *
 * The "concurrent toggle race" case (audit § 9 P0 #4) is not covered here
 * because Kotlin/JVM gives `isAiFeaturesEnabledBlocking()` a single
 * synchronous read per `intercept()` call — there's no in-flight
 * mutation window. If the underlying preference becomes Flow-backed in
 * the future, add coverage then.
 *
 * See `docs/audits/PRE_PHASE_F_MEGA_AUDIT.md` § 9 for the audit that
 * surfaced this test gap.
 */
class AiFeatureGateInterceptorTest {

    private lateinit var prefs: UserPreferencesDataStore
    private lateinit var chain: Interceptor.Chain
    private lateinit var interceptor: AiFeatureGateInterceptor

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        chain = mockk(relaxed = true)
        interceptor = AiFeatureGateInterceptor(prefs)
    }

    private fun requestTo(url: String, method: String = "POST"): Request {
        val builder = Request.Builder().url(url)
        if (method != "GET") {
            builder.method(method, "{}".toRequestBody("application/json".toMediaType()))
        }
        return builder.build()
    }

    private fun successResponse(req: Request): Response = Response.Builder()
        .request(req)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("""{"ok":true}""".toResponseBody("application/json".toMediaType()))
        .build()

    // ── Pass-through cases ──────────────────────────────────────────────

    @Test
    fun `passes through when AI is enabled and path is AI-touching`() {
        val req = requestTo("https://api.prismtask.app/api/v1/ai/batch/parse")
        every { prefs.isAiFeaturesEnabledBlocking() } returns true
        every { chain.request() } returns req
        every { chain.proceed(req) } returns successResponse(req)

        val result = interceptor.intercept(chain)

        assertEquals(200, result.code)
        verify(exactly = 1) { chain.proceed(req) }
    }

    @Test
    fun `passes through non-AI path even when AI is disabled`() {
        val req = requestTo("https://api.prismtask.app/api/v1/tasks/list", method = "GET")
        every { prefs.isAiFeaturesEnabledBlocking() } returns false
        every { chain.request() } returns req
        every { chain.proceed(req) } returns successResponse(req)

        val result = interceptor.intercept(chain)

        assertEquals(200, result.code)
        verify(exactly = 1) { chain.proceed(req) }
    }

    // ── Short-circuit case (the privacy invariant) ──────────────────────

    @Test
    fun `short-circuits AI request when AI is disabled — chain proceed NEVER called`() {
        val req = requestTo("https://api.prismtask.app/api/v1/ai/batch/parse")
        every { prefs.isAiFeaturesEnabledBlocking() } returns false
        every { chain.request() } returns req

        val result = interceptor.intercept(chain)

        assertEquals(
            "Disabled AI request must return synthetic 451",
            451,
            result.code
        )
        verify(exactly = 0) {
            chain.proceed(any())
        }
    }

    @Test
    fun `synthetic response carries the disabled X-PrismTask-AI-Features header`() {
        val req = requestTo("https://api.prismtask.app/api/v1/ai/batch/parse")
        every { prefs.isAiFeaturesEnabledBlocking() } returns false
        every { chain.request() } returns req

        val result = interceptor.intercept(chain)

        assertEquals(
            "disabled",
            result.header(AiFeatureGateInterceptor.HEADER_AI_FEATURES)
        )
    }

    @Test
    fun `synthetic response body is parseable JSON with a detail field`() {
        val req = requestTo("https://api.prismtask.app/api/v1/ai/batch/parse")
        every { prefs.isAiFeaturesEnabledBlocking() } returns false
        every { chain.request() } returns req

        val result = interceptor.intercept(chain)
        val body = result.body?.string()

        assertNotNull("Synthetic body must be present", body)
        // JSON shape: {"detail": "..."}. Loose-parse via substring rather
        // than pulling in a JSON dep — the body is constant, the test
        // just protects against accidental double-encoding / missing field.
        assertTrue(
            "Body should contain a `detail` field, got: $body",
            body!!.contains("\"detail\"")
        )
        assertTrue(
            "Body should reference the toggle location for the user, got: $body",
            body.contains("Settings") && body.contains("AI Features")
        )
    }

    // ── Path matching coverage ──────────────────────────────────────────

    @Test
    fun `every AI_PATH_PREFIXES entry triggers the gate when AI is disabled`() {
        every { prefs.isAiFeaturesEnabledBlocking() } returns false

        AiFeatureGateInterceptor.AI_PATH_PREFIXES.forEach { prefix ->
            // Build a path that starts with the prefix. Some prefixes end
            // with `/` (e.g. `/ai/`); some don't (e.g. `/tasks/parse`).
            // Append a deterministic suffix so the path is always longer
            // than the prefix.
            val path = if (prefix.endsWith("/")) prefix + "endpoint" else "$prefix/endpoint"
            val req = requestTo("https://api.prismtask.app$path")
            every { chain.request() } returns req

            val result = interceptor.intercept(chain)

            assertEquals(
                "Prefix $prefix should trigger the gate, got code=${result.code}",
                451,
                result.code
            )
        }
    }

    @Test
    fun `non-AI paths do NOT trigger the gate even when AI is disabled`() {
        every { prefs.isAiFeaturesEnabledBlocking() } returns false

        // Paths that should NOT match any AI_PATH_PREFIXES entry. These
        // mirror real production URL paths (Retrofit baseUrl + endpoint
        // resolves to `/api/v1/...`), so the gate must distinguish AI-
        // touching paths from sibling non-AI paths under the same prefix.
        val nonAiPaths = listOf(
            "/api/v1/tasks/list",
            "/api/v1/tasks/123/complete",
            "/api/v1/habits/today",
            "/api/v1/sync/push",
            "/api/v1/auth/me",
            "/api/v1/projects/active"
        )

        nonAiPaths.forEach { path ->
            val req = requestTo("https://api.prismtask.app$path", method = "GET")
            every { chain.request() } returns req
            every { chain.proceed(req) } returns successResponse(req)

            val result = interceptor.intercept(chain)

            assertEquals("Path $path should NOT be gated", 200, result.code)
        }

        verify(atLeast = nonAiPaths.size) { chain.proceed(any()) }
    }

    @Test
    fun `tasks slash list does NOT match the tasks slash parse prefix`() {
        // Defensive — `/api/v1/tasks/parse` is the prefix; `/api/v1/tasks/list`
        // shares the `/api/v1/tasks/` root but must not be gated. Catches a
        // regression where someone shortens the prefix to `/api/v1/tasks/`.
        val req = requestTo("https://api.prismtask.app/api/v1/tasks/list", method = "GET")
        every { prefs.isAiFeaturesEnabledBlocking() } returns false
        every { chain.request() } returns req
        every { chain.proceed(req) } returns successResponse(req)

        val result = interceptor.intercept(chain)

        assertEquals(200, result.code)
        verify(exactly = 1) { chain.proceed(req) }
    }

    @Test
    fun `disabled flag is checked once per intercept call`() {
        val req = requestTo("https://api.prismtask.app/api/v1/ai/batch/parse")
        every { prefs.isAiFeaturesEnabledBlocking() } returns false
        every { chain.request() } returns req

        interceptor.intercept(chain)

        verify(exactly = 1) { prefs.isAiFeaturesEnabledBlocking() }
    }

    @Test
    fun `non-AI path skips the flag check entirely`() {
        // Optimization invariant — if the path is non-AI, the interceptor
        // must NOT call `isAiFeaturesEnabledBlocking()` (avoids a DataStore
        // read on every non-AI request).
        val req = requestTo("https://api.prismtask.app/api/v1/tasks/list", method = "GET")
        every { chain.request() } returns req
        every { chain.proceed(req) } returns successResponse(req)

        interceptor.intercept(chain)

        verify(exactly = 0) { prefs.isAiFeaturesEnabledBlocking() }
        assertFalse(
            "AI path matcher must reject /api/v1/tasks/list",
            AiFeatureGateInterceptor.AI_PATH_PREFIXES.any {
                "/api/v1/tasks/list".startsWith(it)
            }
        )
    }

    // ── Gmail integration scan gate (PII re-audit follow-up, 2026-05-01) ─

    @Test
    fun `gmail scan path short-circuits when AI is disabled`() {
        // Privacy invariant: when the user disables AI features, the
        // Gmail scan endpoint (which ships email subjects/snippets to
        // Anthropic) must NOT make a network call. Closes the gap from
        // `cowork_outputs/pii_leak_surface_reaudit_REPORT.md` (2026-05-01).
        val req = requestTo("https://api.prismtask.app/api/v1/integrations/gmail/scan")
        every { prefs.isAiFeaturesEnabledBlocking() } returns false
        every { chain.request() } returns req

        val result = interceptor.intercept(chain)

        assertEquals(
            "Disabled AI request to /integrations/gmail/scan must return synthetic 451",
            451,
            result.code
        )
        verify(exactly = 0) { chain.proceed(any()) }
    }

    @Test
    fun `gmail scan path proceeds and stamps disable header is absent when AI is enabled`() {
        // Happy path — when the user has AI enabled, the request proceeds
        // unchanged and the interceptor does NOT inject the disable header
        // (otherwise the backend would 451 the opted-in user).
        val req = requestTo("https://api.prismtask.app/api/v1/integrations/gmail/scan")
        every { prefs.isAiFeaturesEnabledBlocking() } returns true
        every { chain.request() } returns req
        val captured = slot<okhttp3.Request>()
        every { chain.proceed(capture(captured)) } returns successResponse(req)

        val result = interceptor.intercept(chain)

        assertEquals(200, result.code)
        verify(exactly = 1) { chain.proceed(any()) }
        assertEquals(
            "Enabled-state outbound request must NOT carry the disabled header",
            null,
            captured.captured.header(AiFeatureGateInterceptor.HEADER_AI_FEATURES)
        )
    }

    @Test
    fun `synthetic 451 carries the disable header on its request as defense-in-depth`() {
        // The interceptor stamps `X-PrismTask-AI-Features: disabled` on the
        // request that the synthetic 451 response references. If a future
        // refactor or interceptor reordering converts this short-circuit
        // into a real `chain.proceed`, the header is already present so
        // the server-side `require_ai_features_enabled` dependency still
        // rejects the call.
        val req = requestTo("https://api.prismtask.app/api/v1/integrations/gmail/scan")
        every { prefs.isAiFeaturesEnabledBlocking() } returns false
        every { chain.request() } returns req

        val result = interceptor.intercept(chain)

        assertEquals(
            "Tagged request must carry the disable header",
            "disabled",
            result.request.header(AiFeatureGateInterceptor.HEADER_AI_FEATURES)
        )
    }

    @Test
    fun `non-anthropic integrations paths are not gated when AI is disabled`() {
        // Regression guard: only `/integrations/gmail/scan` egresses to
        // Anthropic. The suggestion inbox / accept / reject endpoints do
        // not touch Anthropic and MUST keep working when the user opts
        // out — otherwise the user can't manage suggestions that were
        // already extracted before the toggle was flipped. Catches a
        // regression where someone shortens the prefix to `/integrations/`.
        every { prefs.isAiFeaturesEnabledBlocking() } returns false

        val nonAnthropicIntegrationPaths = listOf(
            "/api/v1/integrations/suggestions",
            "/api/v1/integrations/suggestions/42/accept",
            "/api/v1/integrations/suggestions/42/reject",
            "/api/v1/integrations/suggestions/batch",
            "/api/v1/integrations/calendar/status",
            "/api/v1/integrations/calendar/authorize"
        )

        nonAnthropicIntegrationPaths.forEach { path ->
            val req = requestTo("https://api.prismtask.app$path")
            every { chain.request() } returns req
            every { chain.proceed(req) } returns successResponse(req)

            val result = interceptor.intercept(chain)

            assertEquals(
                "Path $path must NOT be gated — only /integrations/gmail/scan touches Anthropic",
                200,
                result.code
            )
        }
    }
}
