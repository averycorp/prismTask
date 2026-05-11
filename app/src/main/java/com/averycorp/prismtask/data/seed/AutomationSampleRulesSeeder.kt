package com.averycorp.prismtask.data.seed

import com.averycorp.prismtask.data.repository.AutomationRuleRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds the first-install subset of [AutomationStarterLibrary] (5 rules
 * carrying the original `builtin.*` template keys from PR #1056) on first
 * install. Each row is keyed by [AutomationTemplate.id] so re-seed
 * attempts are no-ops once the row exists.
 *
 * Seeding is *opt-out* — sample rules ship as `enabled = false` so they
 * appear in the rule list as ready-to-toggle templates rather than firing
 * the moment the user updates the app. The user enables them deliberately.
 *
 * The full 27-rule library lives in [AutomationStarterLibrary]; users
 * browse it via the Template Library screen and import any rule on demand.
 */
@Singleton
class AutomationSampleRulesSeeder @Inject constructor(private val ruleRepository: AutomationRuleRepository) {
    suspend fun seedIfNeeded() {
        val seedTemplates = AutomationStarterLibrary.ALL_TEMPLATES
            .filter { it.id in AutomationStarterLibrary.FIRST_INSTALL_SEED_IDS }
        for (template in seedTemplates) {
            if (ruleRepository.getByTemplateKeyOnce(template.id) != null) continue
            ruleRepository.create(
                name = template.name,
                description = template.description,
                trigger = template.trigger,
                condition = template.condition,
                actions = template.actions,
                priority = 0,
                enabled = false,
                isBuiltIn = true,
                templateKey = template.id
            )
        }
    }
}
