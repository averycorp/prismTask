package com.averycorp.prismtask.data.remote.api

/**
 * One frame received from the SSE chat-streaming endpoint
 * (`POST /api/v1/ai/chat/stream`). Ephemeral — never persisted, never
 * forwarded back to the backend, never enters [ChatHistoryEntry] (which
 * carries committed-turn text only).
 *
 * The backend emits three event types per turn:
 * - [Token] for each new chunk of the assistant's `message` field as
 *   the upstream JSON envelope accumulates.
 * - [Done] once the upstream stream completes and the JSON parses +
 *   actions validate. Carries the final message text + validated actions.
 * - [Error] on upstream or parse failure. The stream then closes; no
 *   [Done] follows.
 */
sealed interface ChatStreamEvent {
    data class Token(val text: String) : ChatStreamEvent

    data class Done(
        val message: String,
        val actions: List<ChatActionResponse>,
        val tokensUsed: ChatTokensUsed?,
        // D12 Gate (b): server-assigned PKs of the rows just persisted by
        // `/chat/stream`'s done-event handler (D12 Gate (a)). The
        // repository uses these as the local Room PKs so pullHistory()'s
        // REPLACE-on-PK upsert collapses cleanly. Nullable for resilience
        // against older backends or persistence-failure paths; the
        // repository falls back to fresh client-side UUIDs in that case.
        val userMessageId: String? = null,
        val assistantMessageId: String? = null
    ) : ChatStreamEvent

    data class Error(
        val message: String,
        val code: String?
    ) : ChatStreamEvent
}
