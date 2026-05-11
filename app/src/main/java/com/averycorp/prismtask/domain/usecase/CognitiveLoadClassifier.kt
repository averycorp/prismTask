package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.preferences.CognitiveLoadCustomKeywords
import com.averycorp.prismtask.domain.model.CognitiveLoad

/**
 * Fast, offline keyword-based classifier that guesses a [CognitiveLoad]
 * for a task from its title + optional description.
 *
 * Mirror of [TaskModeClassifier] / [LifeCategoryClassifier] for the
 * orthogonal start-friction dimension (see `docs/COGNITIVE_LOAD.md`).
 * Used as the local fallback whenever the user didn't manually set a
 * cognitive load and the AI classifier is either disabled or unavailable.
 *
 * Matching rules:
 *  - Case-insensitive.
 *  - The load with the most keyword hits wins.
 *  - Ties are broken in priority order: EASY > MEDIUM > HARD. This is
 *    the "never inflate difficulty" bias — when the classifier can't
 *    tell, it deliberately does not make a task look harder than it is,
 *    because over-classifying as HARD triggers procrastination
 *    preemptively. Mirrors `TaskModeClassifier`'s
 *    `RELAX > PLAY > WORK` "lean toward the restorative read" bias on
 *    the inverted axis.
 *  - No keyword hits → [CognitiveLoad.UNCATEGORIZED].
 */
class CognitiveLoadClassifier(private val keywords: Map<CognitiveLoad, List<String>> = DEFAULT_KEYWORDS) {
    fun classify(title: String, description: String? = null): CognitiveLoad {
        val haystack = buildString {
            append(title.lowercase())
            if (!description.isNullOrBlank()) {
                append(' ')
                append(description.lowercase())
            }
        }
        if (haystack.isBlank()) return CognitiveLoad.UNCATEGORIZED

        val scores = mutableMapOf<CognitiveLoad, Int>()
        for ((load, words) in keywords) {
            var hits = 0
            for (word in words) {
                if (containsWholeWord(haystack, word.lowercase())) hits++
            }
            if (hits > 0) scores[load] = hits
        }
        if (scores.isEmpty()) return CognitiveLoad.UNCATEGORIZED

        val maxScore = scores.values.max()
        val topLoads = scores.filterValues { it == maxScore }.keys
        return TIE_BREAK_ORDER.firstOrNull { it in topLoads } ?: CognitiveLoad.UNCATEGORIZED
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
        private val TIE_BREAK_ORDER: List<CognitiveLoad> =
            listOf(CognitiveLoad.EASY, CognitiveLoad.MEDIUM, CognitiveLoad.HARD)

        val DEFAULT_KEYWORDS: Map<CognitiveLoad, List<String>> = mapOf(
            CognitiveLoad.EASY to listOf(
                "quick", "brief", "simple", "reply", "confirm", "check",
                "glance", "skim", "archive", "clean", "tidy", "clear",
                "sort", "dust", "water", "refill", "restock", "trivial"
            ),
            CognitiveLoad.MEDIUM to listOf(
                "review", "edit", "compose", "draft", "organize", "prepare",
                "schedule", "book", "register", "log", "summarize",
                "transcribe", "tidy-up", "follow-up"
            ),
            CognitiveLoad.HARD to listOf(
                "start", "create", "build", "design", "research", "decide",
                "negotiate", "confront", "debug", "refactor", "investigate",
                "diagnose", "present", "interview", "rewrite", "difficult",
                "tough", "blocker", "refuse", "complicated"
            )
        )

        fun withCustomKeywords(custom: CognitiveLoadCustomKeywords): CognitiveLoadClassifier {
            fun split(csv: String): List<String> = csv
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
            val merged = mapOf(
                CognitiveLoad.EASY to (DEFAULT_KEYWORDS[CognitiveLoad.EASY].orEmpty() + split(custom.easy)),
                CognitiveLoad.MEDIUM to (DEFAULT_KEYWORDS[CognitiveLoad.MEDIUM].orEmpty() + split(custom.medium)),
                CognitiveLoad.HARD to (DEFAULT_KEYWORDS[CognitiveLoad.HARD].orEmpty() + split(custom.hard))
            )
            return CognitiveLoadClassifier(merged)
        }
    }
}
