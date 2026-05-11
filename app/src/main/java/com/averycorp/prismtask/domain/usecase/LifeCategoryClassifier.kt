package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords
import com.averycorp.prismtask.domain.model.LifeCategory

/**
 * Fast, offline keyword-based classifier that guesses a [LifeCategory]
 * for a task from its title + optional description.
 *
 * This is the local fallback path used whenever the user didn't manually
 * set a category and AI classification (Premium) is either disabled or
 * unavailable. The AI classifier can wrap this to ensure we always return
 * something reasonable even when the network is down.
 *
 * Matching rules:
 *  - Case-insensitive.
 *  - The category with the most keyword hits wins.
 *  - Ties are broken in a stable priority order: HEALTH > SELF_CARE > WORK > PERSONAL.
 *    (Health wins ties because missing it is the most harmful misclassification.)
 *  - No keyword hits → [LifeCategory.UNCATEGORIZED].
 */
class LifeCategoryClassifier(private val keywords: Map<LifeCategory, List<String>> = DEFAULT_KEYWORDS) {
    /**
     * Classify a task's text into one of the four tracked [LifeCategory] values,
     * or [LifeCategory.UNCATEGORIZED] when nothing matches.
     */
    fun classify(title: String, description: String? = null): LifeCategory {
        val haystack = buildString {
            append(title.lowercase())
            if (!description.isNullOrBlank()) {
                append(' ')
                append(description.lowercase())
            }
        }
        if (haystack.isBlank()) return LifeCategory.UNCATEGORIZED

        val scores = mutableMapOf<LifeCategory, Int>()
        for ((category, words) in keywords) {
            var hits = 0
            for (word in words) {
                if (containsWholeWord(haystack, word.lowercase())) {
                    hits++
                }
            }
            if (hits > 0) scores[category] = hits
        }
        if (scores.isEmpty()) return LifeCategory.UNCATEGORIZED

        val maxScore = scores.values.max()
        val topCategories = scores.filterValues { it == maxScore }.keys
        // Stable tie-break priority.
        return TIE_BREAK_ORDER.firstOrNull { it in topCategories } ?: LifeCategory.UNCATEGORIZED
    }

    /**
     * Word-boundary match: matches "yoga" in "do yoga" but not in "yogawear".
     * Keeps multi-word keywords like "lab work" working as a substring.
     */
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
        private val TIE_BREAK_ORDER: List<LifeCategory> =
            listOf(LifeCategory.HEALTH, LifeCategory.SELF_CARE, LifeCategory.WORK, LifeCategory.PERSONAL)

        val DEFAULT_KEYWORDS: Map<LifeCategory, List<String>> = mapOf(
            LifeCategory.WORK to listOf(
                "meeting",
                "report",
                "deadline",
                "client",
                "project",
                "email",
                "presentation",
                "sprint",
                "deploy",
                "review",
                "standup",
                "1:1",
                "pr",
                "bug",
                "ticket",
                "jira",
                "invoice"
            ),
            LifeCategory.PERSONAL to listOf(
                "grocery",
                "clean",
                "laundry",
                "cook",
                "call",
                "birthday",
                "errand",
                "bank",
                "rent",
                "bills",
                "mail",
                "package",
                "shopping"
            ),
            LifeCategory.SELF_CARE to listOf(
                "yoga",
                "meditate",
                "meditation",
                "exercise",
                "walk",
                "read",
                "journal",
                "rest",
                "hobby",
                "music",
                "stretch",
                "nap",
                "self-care",
                "self care",
                "hike",
                "breathe"
            ),
            LifeCategory.HEALTH to listOf(
                "medication",
                "meds",
                "doctor",
                "therapy",
                "prescription",
                "refill",
                "appointment",
                "lab",
                "pharmacy",
                "vitamins",
                "dentist",
                "dental",
                "checkup",
                "physical",
                "therapist"
            )
        )

        /**
         * Build a [LifeCategoryClassifier] whose keyword lists are
         * [DEFAULT_KEYWORDS] augmented by user-supplied CSV strings. CSV is
         * trimmed and lowercased; blank entries are dropped.
         */
        fun withCustomKeywords(custom: LifeCategoryCustomKeywords): LifeCategoryClassifier {
            fun split(csv: String): List<String> = csv
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
            val merged = mapOf(
                LifeCategory.WORK to (DEFAULT_KEYWORDS[LifeCategory.WORK].orEmpty() + split(custom.work)),
                LifeCategory.PERSONAL to (DEFAULT_KEYWORDS[LifeCategory.PERSONAL].orEmpty() + split(custom.personal)),
                LifeCategory.SELF_CARE to (DEFAULT_KEYWORDS[LifeCategory.SELF_CARE].orEmpty() + split(custom.selfCare)),
                LifeCategory.HEALTH to (DEFAULT_KEYWORDS[LifeCategory.HEALTH].orEmpty() + split(custom.health))
            )
            return LifeCategoryClassifier(merged)
        }
    }
}
