package com.averycorp.prismtask.domain.usecase

/**
 * A task candidate extracted from a block of conversation text
 * (v1.4.0 V9).
 *
 * Confidence is a rough 0..1 heuristic used to sort the review list:
 * explicit "TODO:" and "Action item:" patterns score higher than
 * "I should..." hints. The Premium path can replace this with a Claude
 * Haiku classifier that also fills in `suggestedDueDate` and
 * `suggestedPriority`; the offline regex path only sets titles.
 */
data class ExtractedTask(
    val title: String,
    val confidence: Float,
    val source: String? = null,
    val suggestedPriority: Int = 0,
    val suggestedDueDate: Long? = null,
    val suggestedProject: String? = null
)

/**
 * Offline regex-based conversation → tasks extractor.
 *
 * Looks for common action-item patterns in pasted text from Claude, ChatGPT,
 * meeting notes, email threads, etc. The goal is to surface obvious TODOs
 * without requiring a network round-trip, so Free-tier users still get
 * value and Premium users have a reliable fallback when the Claude backend
 * is unreachable.
 *
 * Supported patterns (ordered by descending confidence):
 *  - `TODO: <text>` or `TODO <text>`
 *  - `Action item: <text>`
 *  - `Action: <text>`
 *  - `I'll <text>` / `I will <text>`
 *  - `I should <text>` / `I need to <text>` / `I have to <text>`
 *  - `Let's <text>`
 *  - `Can you <text>` / `Could you <text>`
 *  - Bullet list items starting with `- ` or `* ` when they look imperative
 *
 * The returned titles are trimmed, de-duplicated by lowercase comparison,
 * and title-cased (first letter capitalized). Titles shorter than 3 chars
 * or longer than 120 chars are dropped.
 */
class ConversationTaskExtractor(
    private val config: com.averycorp.prismtask.data.preferences.ExtractorConfig =
        com.averycorp.prismtask.data.preferences.ExtractorConfig()
) {
    data class Pattern(
        val regex: Regex,
        val confidence: Float
    )

    private val patterns: List<Pattern> = listOf(
        // Explicit TODO / Action item markers — highest confidence.
        Pattern(Regex("""(?im)^(?:\s*)todo:?\s+(.+?)$"""), 0.95f),
        Pattern(Regex("""(?im)^(?:\s*)action\s*item:?\s+(.+?)$"""), 0.95f),
        Pattern(Regex("""(?im)^(?:\s*)action:\s+(.+?)$"""), 0.90f),
        // "I'll / I will ..." — usually a commitment.
        Pattern(Regex("""(?im)\bI['']?ll\s+(.+?)(?:[.!?\n]|$)"""), 0.80f),
        Pattern(Regex("""(?im)\bI\s+will\s+(.+?)(?:[.!?\n]|$)"""), 0.80f),
        // "I should / I need to / I have to ..."
        Pattern(Regex("""(?im)\bI\s+should\s+(.+?)(?:[.!?\n]|$)"""), 0.70f),
        Pattern(Regex("""(?im)\bI\s+need\s+to\s+(.+?)(?:[.!?\n]|$)"""), 0.75f),
        Pattern(Regex("""(?im)\bI\s+have\s+to\s+(.+?)(?:[.!?\n]|$)"""), 0.70f),
        // "Let's ..."
        Pattern(Regex("""(?im)\bLet['']?s\s+(.+?)(?:[.!?\n]|$)"""), 0.60f),
        // "Can you ... / Could you ..."
        Pattern(Regex("""(?im)\bCan\s+you\s+(.+?)(?:[?\n]|$)"""), 0.55f),
        Pattern(Regex("""(?im)\bCould\s+you\s+(.+?)(?:[?\n]|$)"""), 0.55f),
        // Markdown bullet list item — imperative (starts with a verb).
        Pattern(Regex("""(?im)^\s*[-*]\s+([A-Z][a-zA-Z]+\b.+?)$"""), 0.50f)
    )

    fun extract(text: String, source: String? = null): List<ExtractedTask> {
        if (text.isBlank() || text.length > config.maxInputChars) return emptyList()
        val results = mutableListOf<ExtractedTask>()
        val seen = mutableSetOf<String>()
        for (pattern in patterns) {
            for (match in pattern.regex.findAll(text)) {
                val raw = match.groupValues.getOrNull(1)?.trim() ?: continue
                val cleaned = clean(raw) ?: continue
                val key = cleaned.lowercase()
                if (seen.add(key)) {
                    results.add(
                        ExtractedTask(
                            title = cleaned,
                            confidence = pattern.confidence,
                            source = source
                        )
                    )
                }
            }
        }
        return results.sortedByDescending { it.confidence }
    }

    private fun clean(raw: String): String? {
        var s = raw.trim().trimEnd('.', '!', '?', ',', ';', ':')
        if (s.length < 3 || s.length > config.maxTitleChars) return null
        // Title-case the first letter, leave the rest alone.
        s = s[0].uppercaseChar() + s.substring(1)
        return s
    }

    companion object {
        const val MAX_INPUT_SIZE = 10_000
        const val MAX_TITLE_LENGTH = 120
    }
}
