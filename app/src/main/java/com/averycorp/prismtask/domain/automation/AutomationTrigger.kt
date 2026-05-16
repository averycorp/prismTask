package com.averycorp.prismtask.domain.automation

/**
 * What causes a rule to fire. One of five kinds:
 *
 *  - [EntityEvent]    — fires when an entity-CRUD event occurs (e.g. a task is created).
 *  - [TimeOfDay]      — fires at a wall-clock time once per day.
 *  - [DayOfWeekTime]  — fires at a wall-clock time on selected days of week
 *                       (the starter-library extension; see
 *                       `docs/audits/AUTOMATION_STARTER_LIBRARY_ARCHITECTURE.md` § A0).
 *  - [Manual]         — fires only via "Run Now" from the rule list.
 *  - [Composed]       — fires when another rule fires.
 *
 * Discriminated by [type] for JSON round-trip via [AutomationJsonAdapter].
 */
sealed class AutomationTrigger(val type: String) {

    /**
     * Subscribes to a specific [AutomationEvent] kind. [eventKind] is the
     * `simpleName` of the event sealed type (e.g. "TaskCreated",
     * "HabitCompleted"); see [AutomationEvent] for the canonical list.
     */
    data class EntityEvent(
        val eventKind: String
    ) : AutomationTrigger(TYPE) {
        companion object {
            const val TYPE = "ENTITY_EVENT"
        }
    }

    /**
     * Wall-clock trigger. [hour] / [minute] are 24h local time. The engine's
     * [AutomationTimeTickWorker] enqueues a [AutomationEvent.TimeTick] at
     * 1-minute granularity; matching rules fire on exact `hour`/`minute`
     * equality, so the rule
     * lands within ~1 minute of its target time on awake devices. Doze
     * may defer firings to the next maintenance window on sleeping
     * devices — see worker kdoc for caveats.
     */
    data class TimeOfDay(
        val hour: Int,
        val minute: Int
    ) : AutomationTrigger(TYPE) {
        companion object {
            const val TYPE = "TIME_OF_DAY"
        }
    }

    /**
     * Wall-clock trigger restricted to a set of days of week. [daysOfWeek]
     * is a non-empty set of [java.time.DayOfWeek] names (e.g. "MONDAY",
     * "SUNDAY"); [hour]/[minute] match the same 1-minute-granularity
     * cadence as [TimeOfDay]. Engine matches when the
     * [AutomationEvent.TimeTick] lands on one of [daysOfWeek] and
     * `hour`/`minute` line up.
     */
    data class DayOfWeekTime(
        val daysOfWeek: Set<String>,
        val hour: Int,
        val minute: Int
    ) : AutomationTrigger(TYPE) {
        companion object {
            const val TYPE = "DAY_OF_WEEK_TIME"
        }
    }

    /** No automatic firing — must be invoked via "Run Now" from the UI. */
    object Manual : AutomationTrigger("MANUAL") {
        const val TYPE = "MANUAL"
    }

    /**
     * Composed trigger — fires when [parentRuleId] fires. Cycle detection
     * lives in [AutomationEngine]; a chain of length 5+ aborts.
     */
    data class Composed(
        val parentRuleId: Long
    ) : AutomationTrigger(TYPE) {
        companion object {
            const val TYPE = "COMPOSED"
        }
    }
}
