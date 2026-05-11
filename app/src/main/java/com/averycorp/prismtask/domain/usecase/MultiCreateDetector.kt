package com.averycorp.prismtask.domain.usecase

/**
 * Heuristic detector for multi-task QuickAddBar input (Phase B / PR-A of
 * the multi-task creation audit).
 *
 * Single-task NLP is the default — false positives would route normal
 * users (`buy milk, eggs, bread`) into a heavier preview flow they did
 * not opt into. The detector is deliberately conservative: it requires
 * either an explicit newline-separated list OR a comma-separated list
 * with strong time-marker density.
 *
 * Rules (audit doc Item 2 — clears the named adversarial set):
 *
 *  - **(a) newline rule:** the input contains ≥1 newline AND has ≥2
 *    non-empty trimmed lines, where each line is ≥4 chars and does not
 *    start with a continuation conjunction
 *    (`then|or|and|but|so|because|while|if`). Newlines win over commas:
 *    if rule (a) matches, we never check rule (b).
 *
 *  - **(b) comma rule:** the input contains ≥3 comma-separated segments
 *    AND ≥50% of segments contain a recognized date/time marker
 *    (`today`, `tonight`, `tomorrow`, weekday names, `this week`,
 *    `next week`, `\d+\s*(am|pm)`, `by <day>`, `\d{1,2}:\d{2}`).
 *
 * Cost of conservatism: a comma-only list without time markers
 * (`buy milk, eggs, bread`) silently stays on the single-task path. The
 * user can either add a third task with a marker or use newlines to opt
 * in. False-positives on a heavy NLP path are worse than the user
 * pressing Enter.
 */
class MultiCreateDetector {
    fun detect(rawText: String): Result {
        val text = rawText.trim()
        if (text.isEmpty()) return Result.NotMulti

        // Rule (a): newlines win over commas. If we see ≥2 valid lines,
        // emit MultiCreate and skip rule (b).
        if (text.contains('\n')) {
            val lines = text.split('\n').map { it.trim() }
            val candidateLines = lines.filter { it.isNotEmpty() }
            val acceptedLines = candidateLines.filter(::isValidNewlineSegment)
            if (acceptedLines.size >= 2) {
                return Result.MultiCreate(rawText = text, segments = acceptedLines)
            }
            // Newline present but didn't pass rule (a) — fall through to
            // rule (b). A user typing `buy milk\n` (one line then enter)
            // is still a single task.
        }

        // Rule (b): ≥3 comma segments AND ≥50% have a time marker.
        if (text.contains(',')) {
            val segments = text.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (segments.size >= 3) {
                val withMarker = segments.count(::hasTimeMarker)
                // Strict majority: a 3-segment list needs 2 markers, a
                // 4-segment list needs 2, a 5-segment list needs 3, etc.
                if (withMarker * 2 >= segments.size) {
                    return Result.MultiCreate(rawText = text, segments = segments)
                }
            }
        }

        return Result.NotMulti
    }

    private fun isValidNewlineSegment(line: String): Boolean {
        if (line.length < MIN_SEGMENT_LENGTH) return false
        val firstWord = line.substringBefore(' ').lowercase()
        return firstWord !in CONTINUATION_CONJUNCTIONS
    }

    private fun hasTimeMarker(segment: String): Boolean =
        TIME_MARKER_REGEX.containsMatchIn(segment)

    sealed class Result {
        /** Stays on the single-task NLP path. */
        data object NotMulti : Result()

        /**
         * Route the [rawText] into the multi-task creation flow. The
         * raw text is forwarded as-is (no client-side splitting) — the
         * server-side Haiku extractor handles parsing into structured
         * candidates. [segments] is the detector's segmentation,
         * exposed for callers that want a count for telemetry / preview
         * but not used as the canonical task list.
         */
        data class MultiCreate(val rawText: String, val segments: List<String>) : Result()
    }

    companion object {
        private const val MIN_SEGMENT_LENGTH = 4

        private val CONTINUATION_CONJUNCTIONS = setOf(
            "then",
            "or",
            "and",
            "but",
            "so",
            "because",
            "while",
            "if"
        )

        private val TIME_MARKER_REGEX = Regex(
            buildString {
                append("(?i)")
                append("\\b(?:")
                // Single-word tokens.
                append("today|tonight|tomorrow|")
                append("monday|tuesday|wednesday|thursday|friday|saturday|sunday|")
                // Multi-word horizons.
                append("this\\s+week|next\\s+week|")
                // "by monday", "by next week", "by tomorrow".
                append("by\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|next\\s+week|tomorrow)|")
                // Standalone clock times: "5pm", "5 pm", "5:30", "17:00".
                append("\\d{1,2}\\s*(?:am|pm)|")
                append("\\d{1,2}:\\d{2}")
                append(")\\b")
            }
        )
    }
}
