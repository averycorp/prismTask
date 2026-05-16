package com.averycorp.prismtask.domain.model

/**
 * An external constraint or gate attached to a project (or a phase
 * within a project). Polymorphic by design: PrismTask-timeline-style
 * planning needs to bind a phase to a calendar deadline, a credit
 * threshold, or a boolean release gate, all in the same surface.
 *
 * Stored on disk as a single JSON string in `external_anchors.anchor_json`
 * via [com.averycorp.prismtask.data.remote.adapter.ExternalAnchorJsonAdapter],
 * which mirrors the `AutomationJsonAdapter` polymorphic pattern from
 * PR #1056.
 *
 * Added in v1.8.x as part of the PrismTask-timeline-class scope (PR-3).
 */
sealed class ExternalAnchor {
    /** A specific calendar deadline expressed as epoch millis. */
    data class CalendarDeadline(val epochMs: Long) : ExternalAnchor()

    /**
     * A numeric threshold (e.g. credit balance, $$$ budget, story
     * points remaining). [op] is one of `<`, `<=`, `>`, `>=`, `==`.
     * The comparison runs at evaluation time against the named
     * [metric] resolved by the caller's metric registry.
     */
    data class NumericThreshold(
        val metric: String,
        val op: ComparisonOp,
        val value: Double
    ) : ExternalAnchor()

    /**
     * A boolean release gate (e.g. "phase-f-kickoff has passed",
     * "beta opt-in complete"). [gateKey] resolves to a Boolean at
     * evaluation time; [expectedState] is what the gate must read for
     * the anchor to be considered satisfied.
     */
    data class BooleanGate(
        val gateKey: String,
        val expectedState: Boolean
    ) : ExternalAnchor()
}

/**
 * Comparison operators usable in [ExternalAnchor.NumericThreshold].
 * Stored as the enum name; unknown / null values reject the threshold
 * at decode time rather than silently coercing.
 */
enum class ComparisonOp(val symbol: String) {
    LT("<"),
    LTE("<="),
    GT(">"),
    GTE(">="),
    EQ("==");

    companion object {
        fun fromSymbol(symbol: String?): ComparisonOp? = when (symbol) {
            "<" -> LT
            "<=" -> LTE
            ">" -> GT
            ">=" -> GTE
            "==" -> EQ
            else -> null
        }
    }
}
