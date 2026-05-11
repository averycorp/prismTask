package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.preferences.TaskModeCustomKeywords
import com.averycorp.prismtask.domain.model.TaskMode

/**
 * Fast, offline keyword-based classifier that guesses a [TaskMode]
 * for a task from its title + optional description.
 *
 * Mirror of [LifeCategoryClassifier] for the orthogonal mode dimension
 * (see `docs/WORK_PLAY_RELAX.md`). Used as the local fallback whenever
 * the user didn't manually set a mode and the AI classifier is either
 * disabled or unavailable.
 *
 * Matching rules:
 *  - Case-insensitive.
 *  - The mode with the most keyword hits wins.
 *  - Ties are broken in priority order: RELAX > PLAY > WORK. This is the
 *    "lean toward the restorative read" bias documented in
 *    `docs/WORK_PLAY_RELAX.md` § Inference rules — when the classifier
 *    can't tell, it deliberately does not inflate Work.
 *  - No keyword hits → [TaskMode.UNCATEGORIZED].
 */
class TaskModeClassifier(private val keywords: Map<TaskMode, List<String>> = DEFAULT_KEYWORDS) {
    fun classify(title: String, description: String? = null): TaskMode {
        val haystack = buildString {
            append(title.lowercase())
            if (!description.isNullOrBlank()) {
                append(' ')
                append(description.lowercase())
            }
        }
        if (haystack.isBlank()) return TaskMode.UNCATEGORIZED

        val scores = mutableMapOf<TaskMode, Int>()
        for ((mode, words) in keywords) {
            var hits = 0
            for (word in words) {
                if (containsWholeWord(haystack, word.lowercase())) hits++
            }
            if (hits > 0) scores[mode] = hits
        }
        if (scores.isEmpty()) return TaskMode.UNCATEGORIZED

        val maxScore = scores.values.max()
        val topModes = scores.filterValues { it == maxScore }.keys
        return TIE_BREAK_ORDER.firstOrNull { it in topModes } ?: TaskMode.UNCATEGORIZED
    }

    private fun containsWholeWord(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        if (needle.contains(' ')) return haystack.contains(needle)
        var idx = 0
        while (idx <= haystack.length - needle.length) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) return false
            val before = if (found == 0) ' ' else haystack[found - 1]
            val afterIdx = found + needle.length
            val after = if (afterIdx >= haystack.length) ' ' else haystack[afterIdx]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            idx = found + 1
        }
        return false
    }

    companion object {
        private val TIE_BREAK_ORDER: List<TaskMode> =
            listOf(TaskMode.RELAX, TaskMode.PLAY, TaskMode.WORK)

        val DEFAULT_KEYWORDS: Map<TaskMode, List<String>> = mapOf(
            TaskMode.WORK to listOf(
                "ship", "finish", "fix", "send", "write", "review",
                "file", "submit", "deliver", "deadline", "meeting",
                "call", "invoice", "email", "report", "draft",
                "schedule", "present", "prepare", "plan", "complete"
            ),
            TaskMode.PLAY to listOf(
                "play", "game", "hobby", "bake", "climb", "hike",
                "paint", "draw", "watch", "listen", "jam", "dance",
                "visit", "party", "picnic", "brunch"
            ),
            TaskMode.RELAX to listOf(
                "rest", "nap", "sleep", "breathe", "meditate",
                "stretch", "soak", "bath", "spa", "sunbathe", "tea",
                "unwind", "recover", "decompress"
            )
        )

        fun withCustomKeywords(custom: TaskModeCustomKeywords): TaskModeClassifier {
            fun split(csv: String): List<String> = csv
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
            val merged = mapOf(
                TaskMode.WORK to (DEFAULT_KEYWORDS[TaskMode.WORK].orEmpty() + split(custom.work)),
                TaskMode.PLAY to (DEFAULT_KEYWORDS[TaskMode.PLAY].orEmpty() + split(custom.play)),
                TaskMode.RELAX to (DEFAULT_KEYWORDS[TaskMode.RELAX].orEmpty() + split(custom.relax))
            )
            return TaskModeClassifier(merged)
        }
    }
}
