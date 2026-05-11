package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.SelfCareStepEntity
import com.averycorp.prismtask.data.seed.BuiltInHabitDefinition
import com.averycorp.prismtask.data.seed.BuiltInStepDefinition
import com.averycorp.prismtask.domain.model.FieldChange
import com.averycorp.prismtask.domain.model.StepChange
import com.averycorp.prismtask.domain.model.TemplateDiff
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes a [TemplateDiff] between a user's current habit row + its
 * steps and a [BuiltInHabitDefinition] from the registry. Pure function;
 * no I/O. Returns `null` when there is nothing to surface (detached row,
 * already at proposed version, or proposed version older than current).
 *
 * Step identity is the stable `step_id`. Renames are modifications, not
 * remove + add. User-added steps (no matching `step_id` in the registry)
 * go to `preservedUserSteps` and are never suggested for removal.
 */
@Singleton
class BuiltInTemplateDiffer @Inject constructor() {

    fun diff(
        habit: HabitEntity,
        steps: List<SelfCareStepEntity>,
        proposed: BuiltInHabitDefinition
    ): TemplateDiff? {
        if (habit.isDetachedFromTemplate) return null
        if (habit.templateKey != proposed.templateKey) return null
        // source_version 0 = pre-versioning rows; treat as v1 (the seed shape).
        val fromVersion = habit.sourceVersion.coerceAtLeast(1)
        if (fromVersion >= proposed.version) return null

        val proposedById = proposed.steps.associateBy { it.stepId }
        val currentById = steps.associateBy { it.stepId }
        val proposedKnownIds = proposedById.keys

        val added = proposed.steps.filter { it.stepId !in currentById }
        val removed = steps.filter { it.stepId in proposedKnownIds && it.stepId !in proposedById }
        val preservedUser = steps.filter { it.stepId !in proposedKnownIds }

        val modified = proposed.steps.mapNotNull { proposedStep ->
            val currentStep = currentById[proposedStep.stepId] ?: return@mapNotNull null
            stepChangeOrNull(currentStep, proposedStep)
        }

        val habitFieldChanges = habitFieldDiff(habit, proposed)

        val diff = TemplateDiff(
            templateKey = proposed.templateKey,
            fromVersion = fromVersion,
            toVersion = proposed.version,
            habitFieldChanges = habitFieldChanges,
            addedSteps = added,
            removedSteps = removed,
            modifiedSteps = modified,
            preservedUserSteps = preservedUser
        )
        return if (diff.hasAnyChanges) diff else null
    }

    private fun habitFieldDiff(
        habit: HabitEntity,
        proposed: BuiltInHabitDefinition
    ): List<FieldChange> {
        val mod = habit.isUserModified
        val out = mutableListOf<FieldChange>()
        if (habit.name != proposed.name) {
            out += FieldChange("name", habit.name, proposed.name, mod)
        }
        if ((habit.description ?: "") != (proposed.description ?: "")) {
            out += FieldChange("description", habit.description, proposed.description, mod)
        }
        if (habit.frequencyPeriod != proposed.frequency) {
            out += FieldChange("frequencyPeriod", habit.frequencyPeriod, proposed.frequency, mod)
        }
        if (habit.targetFrequency != proposed.targetCount) {
            out += FieldChange(
                fieldName = "targetFrequency",
                currentValue = habit.targetFrequency.toString(),
                proposedValue = proposed.targetCount.toString(),
                userModified = mod
            )
        }
        if ((habit.activeDays ?: "") != proposed.activeDaysCsv) {
            out += FieldChange("activeDays", habit.activeDays, proposed.activeDaysCsv, mod)
        }
        return out
    }

    private fun stepChangeOrNull(
        current: SelfCareStepEntity,
        proposed: BuiltInStepDefinition
    ): StepChange? {
        val labelChanged = current.label != proposed.label
        val durationChanged = current.duration != proposed.duration
        val tierChanged = current.tier != proposed.tier
        val phaseChanged = current.phase != proposed.phase
        val sortOrderChanged = current.sortOrder != proposed.sortOrder
        val noteChanged = current.note != proposed.note
        val anyChange = labelChanged ||
            durationChanged ||
            tierChanged ||
            phaseChanged ||
            sortOrderChanged ||
            noteChanged
        if (!anyChange) return null
        return StepChange(
            stepId = current.stepId,
            current = current,
            proposed = proposed,
            labelChanged = labelChanged,
            durationChanged = durationChanged,
            tierChanged = tierChanged,
            phaseChanged = phaseChanged,
            sortOrderChanged = sortOrderChanged,
            noteChanged = noteChanged
        )
    }
}
