package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.domain.model.BoundaryRule
import com.averycorp.prismtask.domain.model.BoundaryRuleType
import com.averycorp.prismtask.domain.model.LifeCategory
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Decision the boundary enforcer hands back to the editor. The UI acts on
 * this as: [ALLOW] → no change; [BLOCK] → show a warning dialog with
 * "Create Anyway" and "Reschedule"; [SUGGEST] → pre-fill the suggested
 * category in the editor.
 */
sealed class BoundaryDecision {
    data object Allow : BoundaryDecision()

    data class Block(val rule: BoundaryRule, val reason: String) : BoundaryDecision()

    data class Suggest(val rule: BoundaryRule, val category: LifeCategory) : BoundaryDecision()
}

/**
 * Evaluates [BoundaryRule]s against an attempted task creation/edit
 * (v1.4.0 V3).
 *
 * Pure function: takes a snapshot of the configured rules plus the
 * task's intended category and a "now" timestamp, and returns the
 * first decision that applies. BLOCK rules win over SUGGEST rules
 * because blocks are user-set hard limits; REMIND rules don't affect
 * task creation at all (they're fired by the notification layer).
 */
class BoundaryEnforcer {
    fun evaluate(
        rules: List<BoundaryRule>,
        category: LifeCategory,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): BoundaryDecision {
        val localTime = LocalTime.from(Instant.ofEpochMilli(now).atZone(zone))
        val day = Instant.ofEpochMilli(now).atZone(zone).dayOfWeek

        val active = rules.filter { it.isEnabled && it.containsNow(localTime, day) }

        // BLOCK rules take precedence.
        val block = active.firstOrNull { rule ->
            rule.ruleType == BoundaryRuleType.BLOCK_CATEGORY && rule.category == category
        }
        if (block != null) {
            return BoundaryDecision.Block(
                rule = block,
                reason = "This is outside your '${block.name}' boundary. Create anyway?"
            )
        }

        val suggest = active.firstOrNull { it.ruleType == BoundaryRuleType.SUGGEST_CATEGORY }
        if (suggest != null) {
            return BoundaryDecision.Suggest(suggest, suggest.category)
        }

        return BoundaryDecision.Allow
    }

    companion object {
        /**
         * Built-in rules seeded on first use. These mirror the vision deck
         * examples so users see something meaningful out of the box.
         */
        val BUILT_IN: List<BoundaryRule> = listOf(
            BoundaryRule(
                name = "Evening Wind-Down",
                ruleType = BoundaryRuleType.BLOCK_CATEGORY,
                category = LifeCategory.WORK,
                startTime = LocalTime.of(20, 0),
                endTime = LocalTime.of(23, 59),
                activeDays = BoundaryRule.WEEKDAYS
            ),
            BoundaryRule(
                name = "Weekend Rest",
                ruleType = BoundaryRuleType.SUGGEST_CATEGORY,
                category = LifeCategory.SELF_CARE,
                startTime = LocalTime.of(0, 0),
                endTime = LocalTime.of(23, 59),
                activeDays = BoundaryRule.WEEKEND
            )
        )
    }
}
