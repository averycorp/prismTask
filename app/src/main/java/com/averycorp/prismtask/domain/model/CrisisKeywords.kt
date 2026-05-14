package com.averycorp.prismtask.domain.model

/**
 * Crisis-keyword pre-filter for the AI chat surface (G2 + R5).
 *
 * Mirrors `backend/app/services/crisis_keywords.py`. The two lists must
 * stay in lockstep — the backend uses this same set for its
 * defense-in-depth router pre-filter, and the Android client uses it for
 * two paths:
 *
 * 1. **R5** — Free-tier crisis-only chat fallback. When a Free user
 *    types a crisis-signal message, `ChatViewModel` renders the static
 *    safety response client-side WITHOUT hitting the backend (which
 *    would 403 on the AI-Conversational gate) and WITHOUT surfacing the
 *    paywall prompt.
 * 2. **Defense in depth** — even on Pro, the backend short-circuit is
 *    the authoritative path. This client copy exists primarily for R5.
 *
 * The phrase set is intentionally tight — phrase-anchored, not single
 * common words like "die" — to keep the false-positive rate low.
 */
object CrisisKeywords {
    /**
     * Phrase-anchored matches. Kept in lockstep with the backend's
     * `_CRISIS_PHRASES` tuple. Word-boundary delimited, case-insensitive.
     */
    private val PHRASES: List<String> = listOf(
        "kill myself",
        "killing myself",
        "want to die",
        "wanna die",
        "hurt myself",
        "hurting myself",
        "harm myself",
        "harming myself",
        "end my life",
        "ending my life",
        "take my own life",
        "taking my own life",
        "suicide",
        "suicidal",
        "self harm",
        "self-harm"
    )

    private val PATTERN: Regex = run {
        val alternatives = PHRASES.joinToString("|") { phrase ->
            phrase.split(" ").joinToString("\\s+") { Regex.escape(it) }
        }
        Regex("\\b(?:$alternatives)\\b", RegexOption.IGNORE_CASE)
    }

    /**
     * Static crisis-safety reply. Same voice as the backend's
     * `CRISIS_STATIC_REPLY` — warm, plain, no minimizing, no
     * lecturing — and points to the in-app crisis resources surface.
     */
    const val STATIC_REPLY: String =
        "I'm really glad you told me. I'm not the right kind of help for this, " +
            "but the resources in 'If you need help now' (Settings or the link at " +
            "the bottom of Mood & Energy) are open 24/7. Please reach out — you " +
            "matter more than any task."

    /**
     * Returns true when [message] carries a high-confidence crisis term.
     * Empty / whitespace-only input always returns false.
     */
    fun containsCrisisSignal(message: String): Boolean {
        if (message.isBlank()) return false
        return PATTERN.containsMatchIn(message)
    }
}
