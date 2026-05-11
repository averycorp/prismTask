package com.averycorp.prismtask.domain.automation

/**
 * Events emitted by repositories + the [AutomationTimeTickWorker] that
 * automation rules can subscribe to. Closed sealed hierarchy — adding a
 * new event kind requires an exhaustive `when` update at every consumer
 * (intentional: keeps the event surface auditable).
 *
 * `kind()` returns the `simpleName` used as the discriminator in
 * [AutomationTrigger.EntityEvent.eventKind].
 */
sealed class AutomationEvent {
    abstract val occurredAt: Long
    fun kind(): String = this::class.java.simpleName

    data class TaskCreated(val taskId: Long, override val occurredAt: Long = System.currentTimeMillis()) : AutomationEvent()

    data class TaskUpdated(
        val taskId: Long,
        val changedFields: Set<String> = emptySet(),
        override val occurredAt: Long = System.currentTimeMillis()
    ) : AutomationEvent()

    data class TaskCompleted(val taskId: Long, override val occurredAt: Long = System.currentTimeMillis()) : AutomationEvent()

    data class TaskDeleted(val taskId: Long, override val occurredAt: Long = System.currentTimeMillis()) : AutomationEvent()

    data class HabitCompleted(val habitId: Long, val date: String, override val occurredAt: Long = System.currentTimeMillis()) :
        AutomationEvent()

    data class HabitStreakHit(val habitId: Long, val streak: Int, override val occurredAt: Long = System.currentTimeMillis()) :
        AutomationEvent()

    data class MedicationLogged(val medicationId: Long, val slotKey: String, override val occurredAt: Long = System.currentTimeMillis()) :
        AutomationEvent()

    data class TimeTick(val hour: Int, val minute: Int, override val occurredAt: Long = System.currentTimeMillis()) : AutomationEvent()

    data class ManualTrigger(val ruleId: Long, override val occurredAt: Long = System.currentTimeMillis()) : AutomationEvent()

    /** Composed-trigger event — one rule's firing announces itself. */
    data class RuleFired(val ruleId: Long, val parentLogId: Long?, override val occurredAt: Long = System.currentTimeMillis()) :
        AutomationEvent()
}
