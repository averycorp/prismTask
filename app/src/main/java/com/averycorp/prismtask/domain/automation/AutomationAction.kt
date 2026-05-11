package com.averycorp.prismtask.domain.automation

/**
 * What a rule does once its trigger fires + condition passes. One handler
 * per [type] — see § A5 of `docs/audits/AUTOMATION_ENGINE_ARCHITECTURE.md`
 * for the dispatch matrix.
 *
 * `ai.complete` and `ai.summarize` egress data to Anthropic via the
 * backend and are gated by [AiFeatureGateInterceptor] — § A5 PII matrix.
 */
sealed class AutomationAction(val type: String) {

    data class Notify(val title: String, val body: String, val priority: Int = 0) : AutomationAction("notify")

    /**
     * Mutate fields on the trigger event's task. [updates] is a map of
     * field name -> new value (matched against the field paths supported
     * by [MutateTaskActionHandler]).
     */
    data class MutateTask(val updates: Map<String, Any?>) : AutomationAction("mutate.task")

    data class MutateHabit(val updates: Map<String, Any?>) : AutomationAction("mutate.habit")

    data class MutateMedication(val updates: Map<String, Any?>) : AutomationAction("mutate.medication")

    data class ScheduleTimer(val durationMinutes: Int, val mode: String = "FOCUS") : AutomationAction("schedule.timer")

    /**
     * Apply a list of pre-built batch mutations through
     * [BatchOperationsRepository.applyBatch]. Bypasses the AI parse step
     * (the mutations are already structured), so this action is *not*
     * AI-gated even though the underlying repository can be.
     */
    data class ApplyBatch(val mutations: List<Map<String, Any?>>) : AutomationAction("apply.batch")

    /**
     * Send the trigger event's entity through Anthropic for completion.
     * [prompt] is user-authored; the backend wraps it with the entity
     * payload. **AI-gated** via [AiFeatureGateInterceptor].
     */
    data class AiComplete(val prompt: String, val targetField: String? = null) : AutomationAction("ai.complete")

    data class AiSummarize(val scope: String, val maxItems: Int = 50) : AutomationAction("ai.summarize")

    /**
     * Pure observability — writes [message] into the
     * `automation_logs.actions_executed_json` for the firing. Useful for
     * dry-run / debugging rule chains.
     */
    data class LogMessage(val message: String) : AutomationAction("log")
}
