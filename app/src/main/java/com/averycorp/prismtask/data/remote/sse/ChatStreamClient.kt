package com.averycorp.prismtask.data.remote.sse

import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.data.remote.api.ChatActionResponse
import com.averycorp.prismtask.data.remote.api.ChatRequest
import com.averycorp.prismtask.data.remote.api.ChatStreamEvent
import com.averycorp.prismtask.data.remote.api.ChatTokensUsed
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F7 D.1 + F8 D.2 — SSE consumer for `POST /api/v1/ai/chat/stream`.
 *
 * Wraps OkHttp's first-party SSE companion artifact in a [callbackFlow]
 * so the rest of the chat stack can consume the stream as
 * `Flow<ChatStreamEvent>`. The flow's collector cancellation closes
 * the underlying [EventSource], which closes the HTTP connection,
 * which the FastAPI generator detects on the next chunk write and
 * exits the upstream Anthropic stream cleanly. That's the D.2 cancel
 * path — no in-band signal needed because SSE is one-way.
 *
 * Auth + AI-feature gate are applied transparently by the singleton
 * [OkHttpClient] interceptors (NetworkModule), so this class doesn't
 * touch token state directly.
 */
@Singleton
class ChatStreamClient
@Inject
constructor(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) {
    private val baseUrl: String = normalizeBaseUrl(BuildConfig.API_BASE_URL)

    fun stream(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val httpRequest = Request.Builder()
            .url("${baseUrl}api/v1/ai/chat/stream")
            .post(gson.toJson(request).toRequestBody(JSON_MEDIA))
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                val event = parseEvent(type, data) ?: return
                trySend(event)
                if (event is ChatStreamEvent.Done || event is ChatStreamEvent.Error) {
                    // Server signalled stream end; close the channel so
                    // collectors stop waiting. EventSource itself will
                    // close shortly after.
                    close()
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val mapped = mapFailure(t, response)
                trySend(mapped)
                close()
            }
        }

        val source = EventSources.createFactory(httpClient).newEventSource(httpRequest, listener)
        awaitClose { source.cancel() }
    }

    private fun parseEvent(type: String?, data: String): ChatStreamEvent? {
        return try {
            val json = gson.fromJson(data, JsonObject::class.java)
            when (type) {
                "token" -> ChatStreamEvent.Token(
                    text = json.get("text")?.asString.orEmpty()
                )
                "done" -> {
                    val message = json.get("message")?.asString.orEmpty()
                    val actionsType = object : TypeToken<List<ChatActionResponse>>() {}.type
                    val actions: List<ChatActionResponse> =
                        json.get("actions")?.let { gson.fromJson(it, actionsType) }
                            ?: emptyList()
                    val tokensUsed = json.get("tokens_used")?.takeIf { !it.isJsonNull }?.let {
                        gson.fromJson(it, ChatTokensUsed::class.java)
                    }
                    ChatStreamEvent.Done(
                        message = message,
                        actions = actions,
                        tokensUsed = tokensUsed
                    )
                }
                "error" -> ChatStreamEvent.Error(
                    message = json.get("message")?.asString
                        ?: "Chat stream failed.",
                    code = json.get("code")?.takeIf { !it.isJsonNull }?.asString
                )
                else -> null
            }
        } catch (_: Exception) {
            ChatStreamEvent.Error(
                message = "Chat stream returned a malformed event.",
                code = "client_parse_error"
            )
        }
    }

    private fun mapFailure(t: Throwable?, response: Response?): ChatStreamEvent.Error {
        val code = response?.code
        if (code != null) {
            val msg = when (code) {
                401 -> "Sign in to use chat — your session has expired."
                403 -> "Chat requires Pro. Upgrade in Settings to continue."
                429 -> "Daily chat limit reached. Try again later."
                451 -> "AI features are disabled. Re-enable them in Settings → AI Features."
                503 -> "Chat backend is unavailable. Try again in a moment."
                else -> "Chat is unavailable right now (HTTP $code)."
            }
            return ChatStreamEvent.Error(message = msg, code = "http_$code")
        }
        val msg = when (t) {
            is java.net.UnknownHostException,
            is java.net.ConnectException ->
                "I need an internet connection to chat. Your tasks are still available offline."
            null -> "Chat stream closed unexpectedly."
            else -> "Chat is unavailable right now: ${t.javaClass.simpleName}"
        }
        return ChatStreamEvent.Error(message = msg, code = "transport")
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun normalizeBaseUrl(url: String): String =
            if (url.endsWith("/")) url else "$url/"
    }
}
