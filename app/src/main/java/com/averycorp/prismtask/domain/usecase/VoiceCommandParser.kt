package com.averycorp.prismtask.domain.usecase

import javax.inject.Inject
import javax.inject.Singleton

/** Structured voice command recognised from a raw transcript. */
sealed class VoiceCommand {
    data class CompleteTask(val query: String) : VoiceCommand()

    data class DeleteTask(val query: String) : VoiceCommand()

    data class RescheduleTask(val query: String, val dateText: String) : VoiceCommand()

    data class MoveToProject(val projectQuery: String) : VoiceCommand()

    data class StartTimer(val query: String) : VoiceCommand()

    object StopTimer : VoiceCommand()

    object WhatsNext : VoiceCommand()

    object TaskCount : VoiceCommand()

    object StartFocus : VoiceCommand()

    object ExitVoiceMode : VoiceCommand()
}

/**
 * Lightweight, allocation-free regex/keyword matcher that turns a raw
 * speech transcript into a [VoiceCommand]. Returns `null` when nothing
 * matches — callers should then treat the text as a task-creation request.
 *
 * The parser is intentionally simple and instant (no ML/AI). All matches
 * are case-insensitive and tolerant of leading filler words ("hey",
 * "please", etc.).
 */
@Singleton
class VoiceCommandParser
@Inject
constructor() {
    fun parseCommand(rawText: String): VoiceCommand? {
        val text = rawText
            .trim()
            .lowercase()
            .removePrefix("hey ")
            .removePrefix("ok ")
            .removePrefix("please ")
            .trim(' ', ',', '.', '!', '?')

        if (text.isEmpty()) return null

        // Standalone commands first — shortest possible utterances win.
        when (text) {
            "stop timer", "stop the timer", "pause timer" -> return VoiceCommand.StopTimer
            "what's next", "whats next", "what is next",
            "what's next?", "whats next?", "what do i do next" -> return VoiceCommand.WhatsNext
            "how many tasks today", "how many tasks do i have today",
            "task count", "how many tasks" -> return VoiceCommand.TaskCount
            "start focus", "start focus session", "begin focus",
            "start pomodoro", "focus mode" -> return VoiceCommand.StartFocus
            "exit", "exit voice", "exit voice mode", "stop listening", "done" ->
                return VoiceCommand.ExitVoiceMode
        }

        // "complete [task]" / "mark [task] complete" / "finish [task]"
        COMPLETE_PATTERNS.forEach { regex ->
            regex.find(text)?.let { m ->
                val query = m.groupValues[1].trim()
                if (query.isNotEmpty()) return VoiceCommand.CompleteTask(query)
            }
        }

        // "delete [task]" / "remove [task]"
        DELETE_PATTERNS.forEach { regex ->
            regex.find(text)?.let { m ->
                val query = m.groupValues[1].trim()
                if (query.isNotEmpty()) return VoiceCommand.DeleteTask(query)
            }
        }

        // "reschedule [task] to [date]" / "move [task] to [date]"
        RESCHEDULE_PATTERNS.forEach { regex ->
            regex.find(text)?.let { m ->
                val query = m.groupValues[1].trim()
                val date = m.groupValues[2].trim()
                if (query.isNotEmpty() && date.isNotEmpty()) {
                    return VoiceCommand.RescheduleTask(query, date)
                }
            }
        }

        // "add to [project]" — acts on the most recent task.
        ADD_TO_PROJECT_PATTERNS.forEach { regex ->
            regex.find(text)?.let { m ->
                val query = m.groupValues[1].trim()
                if (query.isNotEmpty()) return VoiceCommand.MoveToProject(query)
            }
        }

        // "start timer on [task]" / "track time on [task]"
        START_TIMER_PATTERNS.forEach { regex ->
            regex.find(text)?.let { m ->
                val query = m.groupValues[1].trim()
                if (query.isNotEmpty()) return VoiceCommand.StartTimer(query)
            }
        }

        return null
    }

    /**
     * Rank [candidates] against [query] and return the best fuzzy match, or
     * null if no candidate reaches a reasonable similarity threshold.
     *
     * Scoring: exact match > starts-with > substring > token-overlap. For
     * token overlap we allow off-by-one typos via Levenshtein distance.
     */
    fun <T> fuzzyMatch(
        candidates: List<T>,
        query: String,
        nameOf: (T) -> String
    ): T? {
        if (candidates.isEmpty() || query.isBlank()) return null
        val q = query.trim().lowercase()

        var best: T? = null
        var bestScore = Int.MAX_VALUE

        candidates.forEach { candidate ->
            val name = nameOf(candidate).lowercase()
            val score = when {
                name == q -> 0
                name.startsWith(q) -> 1
                name.contains(q) -> 2
                q.contains(name) && name.length >= 3 -> 3
                else -> {
                    // Token-level overlap: any query token within edit distance 1
                    val nameTokens = name.split(Regex("\\s+"))
                    val queryTokens = q.split(Regex("\\s+"))
                    val overlap = queryTokens.count { qt ->
                        nameTokens.any { nt ->
                            nt.startsWith(qt) ||
                                qt.startsWith(nt) ||
                                levenshtein(nt, qt) <= 1
                        }
                    }
                    if (overlap >= (queryTokens.size + 1) / 2) {
                        10 - overlap
                    } else {
                        Int.MAX_VALUE
                    }
                }
            }
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return if (bestScore <= 9) best else null
    }

    /** Bounded Levenshtein — early-exits when the distance exceeds [max]. */
    private fun levenshtein(a: String, b: String, max: Int = 2): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > max) return max + 1
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.length]
    }

    companion object {
        private val COMPLETE_PATTERNS = listOf(
            Regex("""^(?:complete|completed|finish|finished|mark done|done with|check off)\s+(?:the\s+)?(.+)$"""),
            Regex("""^mark\s+(.+?)\s+(?:complete|completed|done)$"""),
            Regex("""^(?:i\s+)?finished\s+(.+)$""")
        )

        private val DELETE_PATTERNS = listOf(
            Regex("""^(?:delete|remove|cancel|drop)\s+(?:the\s+)?(.+)$""")
        )

        private val RESCHEDULE_PATTERNS = listOf(
            Regex("""^(?:reschedule|move|push|postpone|defer)\s+(.+?)\s+(?:to|until|till|for)\s+(.+)$""")
        )

        private val ADD_TO_PROJECT_PATTERNS = listOf(
            Regex("""^(?:add|move)\s+(?:it\s+|this\s+|that\s+)?(?:to\s+)(?:the\s+)?(?:project\s+)?(.+?)\s*(?:project)?$""")
        )

        private val START_TIMER_PATTERNS = listOf(
            Regex("""^(?:start|begin)\s+(?:the\s+)?timer\s+(?:on|for)\s+(.+)$"""),
            Regex("""^(?:track|time)\s+(?:time\s+)?(?:on|for)\s+(.+)$""")
        )
    }
}
