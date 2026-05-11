package com.averycorp.prismtask.domain.usecase

import java.text.Normalizer
import java.util.Locale

/**
 * Pure-function deterministic medication-name resolver. Runs before the
 * Haiku batch path on Android, web, and backend so identical commands
 * resolve identically across surfaces — case mismatches, whitespace
 * variants, and Unicode quirks all collapse to the same match.
 *
 * NEVER does fuzzy or substring matching. Typos and partial words return
 * [MatchResult.NoMatch] so the caller falls through to Haiku (which can
 * still flag them as ambiguous via its own pass). This is the audit's
 * failure-mode #2 firewall: silent typo confidence is exactly the bug we
 * are guarding against, so a stricter "exact whole-word match or nothing"
 * contract is the safe default.
 *
 * Normalization (must stay byte-identical with the Python and TS twins):
 *   1. Unicode NFC normalize
 *   2. trim()
 *   3. lowercase under [Locale.ROOT]
 *   4. strip trailing ASCII punctuation [.,!?;:]
 *
 * Word boundaries are detected by [isWordChar]: ASCII letters, digits, or
 * underscore. The matcher scans left-to-right, longest-key-first, so
 * "Wellbutrin XL" wins over "Wellbutrin" when both candidates exist and
 * the user wrote the full name.
 */
object MedicationNameMatcher {

    data class Medication(val id: String, val name: String, val displayLabel: String? = null)

    data class AmbiguousPhrase(val phrase: String, val candidateEntityIds: List<String>)

    sealed class MatchResult {
        data object NoMatch : MatchResult()
        data class Unambiguous(val matches: Map<String, String>) : MatchResult()
        data class Ambiguous(val phrases: List<AmbiguousPhrase>) : MatchResult()
        data class Mixed(val unambiguous: Map<String, String>, val ambiguous: List<AmbiguousPhrase>) : MatchResult()
    }

    fun match(commandText: String, medications: List<Medication>): MatchResult {
        if (commandText.isBlank() || medications.isEmpty()) return MatchResult.NoMatch
        val normalizedCommand = normalize(commandText)
        if (normalizedCommand.isBlank()) return MatchResult.NoMatch

        val keyToMedIds = buildKeyIndex(medications)
        if (keyToMedIds.isEmpty()) return MatchResult.NoMatch

        val keys = keyToMedIds.keys.sortedByDescending { it.length }
        val unambiguous = LinkedHashMap<String, String>()
        val ambiguousMap = LinkedHashMap<String, MutableSet<String>>()

        var i = 0
        while (i < normalizedCommand.length) {
            val atWordStart = isWordChar(normalizedCommand[i]) &&
                (i == 0 || !isWordChar(normalizedCommand[i - 1]))
            if (!atWordStart) {
                i++
                continue
            }
            val matchedKey = findLongestKeyAt(normalizedCommand, i, keys)
            if (matchedKey != null) {
                val ids = keyToMedIds.getValue(matchedKey)
                if (ids.size == 1) {
                    unambiguous[matchedKey] = ids.first()
                } else {
                    ambiguousMap.getOrPut(matchedKey) { LinkedHashSet() }.addAll(ids)
                }
                i += matchedKey.length
            } else {
                while (i < normalizedCommand.length && isWordChar(normalizedCommand[i])) i++
            }
        }

        return classify(unambiguous, ambiguousMap)
    }

    private fun buildKeyIndex(
        medications: List<Medication>
    ): Map<String, Set<String>> {
        val keyToMedIds = LinkedHashMap<String, MutableSet<String>>()
        for (med in medications) {
            val nameKey = normalize(med.name)
            if (nameKey.isNotBlank()) {
                keyToMedIds.getOrPut(nameKey) { LinkedHashSet() }.add(med.id)
            }
            val labelKey = med.displayLabel?.let { normalize(it) }
            if (!labelKey.isNullOrBlank()) {
                keyToMedIds.getOrPut(labelKey) { LinkedHashSet() }.add(med.id)
            }
        }
        return keyToMedIds
    }

    private fun findLongestKeyAt(haystack: String, start: Int, keys: List<String>): String? {
        for (k in keys) {
            val end = start + k.length
            if (end > haystack.length) continue
            if (end < haystack.length && isWordChar(haystack[end])) continue
            if (haystack.regionMatches(start, k, 0, k.length)) return k
        }
        return null
    }

    private fun classify(
        unambiguous: Map<String, String>,
        ambiguousMap: Map<String, Set<String>>
    ): MatchResult {
        val ambiguousList = ambiguousMap.map { (phrase, ids) ->
            AmbiguousPhrase(phrase, ids.toList().sorted())
        }
        return when {
            unambiguous.isEmpty() && ambiguousList.isEmpty() -> MatchResult.NoMatch
            ambiguousList.isEmpty() -> MatchResult.Unambiguous(unambiguous)
            unambiguous.isEmpty() -> MatchResult.Ambiguous(ambiguousList)
            else -> MatchResult.Mixed(unambiguous, ambiguousList)
        }
    }

    fun normalize(s: String): String {
        val nfc = Normalizer.normalize(s, Normalizer.Form.NFC)
        val trimmed = nfc.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        var end = lower.length
        while (end > 0 && lower[end - 1] in TRAILING_PUNCT) end--
        return lower.substring(0, end)
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private val TRAILING_PUNCT = setOf('.', ',', '!', '?', ';', ':')
}
