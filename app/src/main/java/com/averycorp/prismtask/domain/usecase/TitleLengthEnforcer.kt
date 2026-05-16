package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.preferences.TitleLengthLimit

/**
 * Pure helpers for the user-configurable task/subtask title length cap.
 *
 * Enforcement is "hard cap on input": [enforce] caps freshly typed input at
 * the configured length, so the editor and quick-add inputs reject characters
 * past the limit. Existing over-cap titles loaded from the database are never
 * auto-truncated — callers only run [enforce] on the typing path.
 */
object TitleLengthEnforcer {
    /** Characters before the cap at which the inline counter should appear. */
    const val COUNTER_WARNING_THRESHOLD = 10

    /** Caps [input] at [limit] characters, or returns it unchanged when [limit] is null. */
    fun enforce(input: String, limit: Int?): String =
        if (limit == null || input.length <= limit) input else input.take(limit)

    /** True when a "N / max" counter should be visible next to the input. */
    fun shouldShowCounter(
        currentLength: Int,
        limit: Int?,
        warningThreshold: Int = COUNTER_WARNING_THRESHOLD
    ): Boolean = limit != null && currentLength >= (limit - warningThreshold)
}

/** Convenience overload for [TitleLengthLimit] callers. */
fun TitleLengthLimit.enforce(input: String): String =
    TitleLengthEnforcer.enforce(input, limit)
