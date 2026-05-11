package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.seed.AutomationStarterLibrary
import com.averycorp.prismtask.data.seed.AutomationTemplate
import com.averycorp.prismtask.data.seed.AutomationTemplateCategory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only access to the bundled starter automation library plus the
 * one-tap "import this template" hook.
 *
 * Templates themselves are device-local APK content — they don't sync.
 * Imported rules sync via the existing [AutomationRuleRepository.create]
 * call site (which calls `syncTracker.trackCreate(id, "automation_rule")`)
 * once SyncService routing for that entity type lands; cross-device sync
 * is gated behind that work and is out of scope for this PR per audit
 * § A6.
 */
@Singleton
class AutomationTemplateRepository @Inject constructor(private val ruleRepository: AutomationRuleRepository) {

    /** All shipped templates, in inventory order. */
    fun templates(): List<AutomationTemplate> = AutomationStarterLibrary.ALL_TEMPLATES

    /** Templates grouped by category (key order: enum declaration order). */
    fun templatesByCategory(): Map<AutomationTemplateCategory, List<AutomationTemplate>> =
        AutomationStarterLibrary.TEMPLATES_BY_CATEGORY

    /** Lookup by template id — returns null if the id isn't bundled. */
    fun findById(id: String): AutomationTemplate? =
        AutomationStarterLibrary.findById(id)

    /**
     * Substring match across name + description, case-insensitive. Used by
     * the library screen's search bar.
     */
    fun search(query: String): List<AutomationTemplate> {
        val needle = query.trim()
        if (needle.isEmpty()) return templates()
        return templates().filter {
            it.name.contains(needle, ignoreCase = true) ||
                it.description.contains(needle, ignoreCase = true)
        }
    }

    /**
     * One-tap import. Persists the template as a user rule with
     * `enabled = false` (per PR #1056 UX choice — never auto-fire on
     * update install).
     *
     * `isBuiltIn = false` so the rule is user-deletable; `templateKey`
     * carries the source template id so later UI can show "imported from
     * X" without grepping JSON.
     *
     * Idempotent on `templateKey`: if a rule with the same `templateKey`
     * already exists on this device, returns [ImportResult.AlreadyImported]
     * with the existing rule id and does not insert a second row. PR #1077
     * handles the cross-device case via natural-key adoption; this guard
     * closes the same-device case.
     */
    suspend fun importTemplate(templateId: String): ImportResult {
        val template = findById(templateId) ?: return ImportResult.NotFound
        ruleRepository.getByTemplateKeyOnce(template.id)?.let { existing ->
            return ImportResult.AlreadyImported(existing.id)
        }
        val newId = ruleRepository.create(
            name = template.name,
            description = template.description,
            trigger = template.trigger,
            condition = template.condition,
            actions = template.actions,
            priority = 0,
            enabled = false,
            isBuiltIn = false,
            templateKey = template.id
        )
        return ImportResult.Created(newId)
    }

    sealed interface ImportResult {
        data class Created(val ruleId: Long) : ImportResult
        data class AlreadyImported(val ruleId: Long) : ImportResult
        data object NotFound : ImportResult
    }
}
