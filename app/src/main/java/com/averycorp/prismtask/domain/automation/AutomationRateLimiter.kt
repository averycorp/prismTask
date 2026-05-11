package com.averycorp.prismtask.domain.automation

import com.averycorp.prismtask.data.local.dao.AutomationLogDao
import com.averycorp.prismtask.data.local.entity.AutomationRuleEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hard caps that bound rule firing volume. § A6 design notes:
 *
 *  - Per-rule daily cap: protects against a single misconfigured rule
 *    spamming notifications.
 *  - Per-user global hourly cap: protects against fan-out across many
 *    rules during a burst (e.g. a mass import that fires "TaskCreated"
 *    300 times in 5 seconds).
 *  - AI-action daily cap: separate stricter limit because Anthropic
 *    calls cost money. Counted across all rules with `ai.*` actions.
 *
 * Defaults below are intentionally conservative; the per-rule cap is
 * overridable via JSON `"maxFiresPerDay": N` on the rule's trigger blob.
 */
@Singleton
class AutomationRateLimiter @Inject constructor(private val logDao: AutomationLogDao) {
    suspend fun canFire(rule: AutomationRuleEntity, now: Long): Decision {
        // Per-rule daily cap.
        val cap = rule.perRuleDailyCap()
        if (rule.dailyFireCount >= cap) {
            return Decision.Blocked("per-rule daily cap reached ($cap)")
        }
        // Global per-user hourly cap.
        val globalCount = logDao.countSince(now - HOUR_MS)
        if (globalCount >= MAX_GLOBAL_FIRES_PER_HOUR) {
            return Decision.Blocked("global hourly cap reached ($MAX_GLOBAL_FIRES_PER_HOUR)")
        }
        // AI-action daily cap (only checked when this rule's actions
        // include an `ai.*` action — cheap LIKE on the JSON blob).
        if (rule.actionJson.contains("\"type\":\"ai.")) {
            val aiCount = logDao.countAiSince(now - DAY_MS)
            if (aiCount >= MAX_AI_ACTIONS_PER_DAY) {
                return Decision.Blocked("AI-action daily cap reached ($MAX_AI_ACTIONS_PER_DAY)")
            }
        }
        return Decision.Allowed
    }

    sealed class Decision {
        object Allowed : Decision()
        data class Blocked(val reason: String) : Decision()
    }

    private fun AutomationRuleEntity.perRuleDailyCap(): Int {
        // Optional JSON override: `"maxFiresPerDay": N` on trigger_json
        // wins over the global default. Cheap regex check; falls back to
        // the default on any parse hiccup.
        val match = Regex("\"maxFiresPerDay\"\\s*:\\s*(\\d+)").find(triggerJson)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: MAX_FIRES_PER_RULE_PER_DAY
    }

    companion object {
        const val MAX_FIRES_PER_RULE_PER_DAY = 100
        const val MAX_GLOBAL_FIRES_PER_HOUR = 500
        const val MAX_AI_ACTIONS_PER_DAY = 50
        const val HOUR_MS = 60L * 60_000L
        const val DAY_MS = 24L * HOUR_MS
    }
}
