package com.averycorp.prismtask.domain.model

import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.SelfCareStepEntity
import com.averycorp.prismtask.data.seed.BuiltInStepDefinition

/**
 * One field on the parent habit row that differs between the user's
 * accepted version and the registry's current version. `userModified` is
 * a row-level heuristic — true when the user has previously edited any
 * field on this habit. The UI uses it to default the per-field checkbox
 * to unchecked so we don't silently overwrite user edits.
 */
data class FieldChange(val fieldName: String, val currentValue: String?, val proposedValue: String?, val userModified: Boolean)

/**
 * One step that exists in both the user's instance and the proposed
 * registry version under the same `step_id`, but with at least one
 * differing sub-field.
 */
data class StepChange(
    val stepId: String,
    val current: SelfCareStepEntity,
    val proposed: BuiltInStepDefinition,
    val labelChanged: Boolean,
    val durationChanged: Boolean,
    val tierChanged: Boolean,
    val phaseChanged: Boolean,
    val sortOrderChanged: Boolean,
    val noteChanged: Boolean
)

/**
 * The complete diff between one user habit row (and its steps) and the
 * registry's current definition for the same `template_key`. Returned by
 * [com.averycorp.prismtask.domain.usecase.BuiltInTemplateDiffer.diff].
 *
 * `null` (from the differ) means "no diff to surface" — either the user
 * is already at the current version, the row is detached, or the registry
 * has no entry for this key.
 */
data class TemplateDiff(
    val templateKey: String,
    val fromVersion: Int,
    val toVersion: Int,
    val habitFieldChanges: List<FieldChange>,
    val addedSteps: List<BuiltInStepDefinition>,
    val removedSteps: List<SelfCareStepEntity>,
    val modifiedSteps: List<StepChange>,
    val preservedUserSteps: List<SelfCareStepEntity>
) {
    val hasAnyChanges: Boolean
        get() = habitFieldChanges.isNotEmpty() ||
            addedSteps.isNotEmpty() ||
            removedSteps.isNotEmpty() ||
            modifiedSteps.isNotEmpty()
}

/**
 * Lightweight view of "you have a pending update for this template" used
 * by the list UI. Built from a [TemplateDiff] but doesn't carry the full
 * step data so the list screen stays cheap.
 */
data class PendingBuiltInUpdate(
    val templateKey: String,
    val displayName: String,
    val fromVersion: Int,
    val toVersion: Int,
    val addedStepCount: Int,
    val removedStepCount: Int,
    val modifiedStepCount: Int,
    val habitFieldChangeCount: Int
)

/**
 * The user's checkbox selections from the per-template diff screen. The
 * `applyUpdate` use case translates each selection into the corresponding
 * write against `habits` / `self_care_steps`.
 */
data class AcceptedChanges(
    val habit: HabitEntity,
    val acceptedFieldNames: Set<String>,
    val acceptedAddedStepIds: Set<String>,
    val acceptedRemovedStepIds: Set<String>,
    val acceptedModifiedStepIds: Set<String>
)
