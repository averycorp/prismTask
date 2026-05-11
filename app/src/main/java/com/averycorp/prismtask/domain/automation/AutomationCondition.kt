package com.averycorp.prismtask.domain.automation

/**
 * Restricted boolean expression DSL — pick CAUSE-Z from § A4 of the
 * architecture doc. Stored as JSON in `automation_rules.condition_json`,
 * evaluated at runtime by [ConditionEvaluator].
 *
 * Two node kinds:
 *
 *  - [Compare] — a single field-vs-value comparison.
 *  - [And] / [Or] / [Not] — boolean tree shape.
 *
 * The DSL deliberately has no scripting escape hatch (security + JIT cost
 * argument from § A4). All comparison operators are enumerated in [Op].
 */
sealed class AutomationCondition(val op: String) {

    enum class Op(val key: String) {
        EQ("EQ"),
        NE("NE"),
        GT("GT"),
        GTE("GTE"),
        LT("LT"),
        LTE("LTE"),
        CONTAINS("CONTAINS"),
        STARTS_WITH("STARTS_WITH"),
        EXISTS("EXISTS"),
        WITHIN_LAST_MS("WITHIN_LAST_MS")
    }

    /**
     * Leaf node. [field] is a dotted path into the trigger event's entity
     * (see [EvaluationContext] for the exhaustive list — `task.priority`,
     * `habit.streakCount`, etc.). [value] is a literal; the special object
     * `{"@now": null}` resolves to current millis.
     */
    data class Compare(val opType: Op, val field: String, val value: Any? = null) : AutomationCondition(opType.key)

    data class And(val children: List<AutomationCondition>) : AutomationCondition("AND")

    data class Or(val children: List<AutomationCondition>) : AutomationCondition("OR")

    data class Not(val child: AutomationCondition) : AutomationCondition("NOT")
}
