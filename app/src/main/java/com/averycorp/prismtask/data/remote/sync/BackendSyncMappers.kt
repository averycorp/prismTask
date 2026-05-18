package com.averycorp.prismtask.data.remote.sync

import com.averycorp.prismtask.data.local.entity.DailyEssentialSlotCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.data.local.entity.MedicationTierStateEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTemplateEntity
import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// Entity-to-operation mappers + JSON helpers extracted from BackendSyncService.

internal fun taskToOperation(task: TaskEntity): SyncOperation {
    val data = JsonObject().apply {
        addProperty("id", task.id)
        addProperty("title", task.title)
        if (task.description != null) addProperty("description", task.description)
        if (task.dueDate != null) addProperty("due_date", task.dueDate)
        if (task.dueTime != null) addProperty("due_time", task.dueTime)
        addProperty("priority", task.priority)
        addProperty("is_completed", task.isCompleted)
        if (task.projectId != null) addProperty("project_id", task.projectId)
        if (task.parentTaskId != null) addProperty("parent_task_id", task.parentTaskId)
        if (task.recurrenceRule != null) addProperty("recurrence_rule", task.recurrenceRule)
        if (task.reminderOffset != null) addProperty("reminder_offset", task.reminderOffset)
        addProperty("created_at", task.createdAt)
        addProperty("updated_at", task.updatedAt)
        if (task.completedAt != null) addProperty("completed_at", task.completedAt)
        if (task.archivedAt != null) addProperty("archived_at", task.archivedAt)
        if (task.notes != null) addProperty("notes", task.notes)
        if (task.plannedDate != null) addProperty("planned_date", task.plannedDate)
        if (task.estimatedDuration != null) addProperty("estimated_duration", task.estimatedDuration)
        if (task.scheduledStartTime != null) addProperty("scheduled_start_time", task.scheduledStartTime)
        if (task.sourceHabitId != null) addProperty("source_habit_id", task.sourceHabitId)
        if (task.lifeCategory != null) addProperty("life_category", task.lifeCategory)
        if (task.taskMode != null) addProperty("task_mode", task.taskMode)
        if (task.cognitiveLoad != null) addProperty("cognitive_load", task.cognitiveLoad)
        if (task.goodEnoughMinutesOverride != null) addProperty("good_enough_minutes_override", task.goodEnoughMinutesOverride)
        if (task.maxRevisionsOverride != null) addProperty("max_revisions_override", task.maxRevisionsOverride)
        addProperty("revision_count", task.revisionCount)
        addProperty("revision_locked", task.revisionLocked)
        addProperty("cumulative_edit_minutes", task.cumulativeEditMinutes)
    }
    return SyncOperation(
        entityType = "task",
        operation = "update",
        entityId = task.id,
        data = data,
        clientTimestamp = millisToIso(task.updatedAt)
    )
}

internal fun projectToOperation(project: ProjectEntity): SyncOperation {
    val data = JsonObject().apply {
        addProperty("id", project.id)
        addProperty("name", project.name)
        addProperty("color", project.color)
        addProperty("icon", project.icon)
        addProperty("created_at", project.createdAt)
        addProperty("updated_at", project.updatedAt)
    }
    return SyncOperation(
        entityType = "project",
        operation = "update",
        entityId = project.id,
        data = data,
        clientTimestamp = millisToIso(project.updatedAt)
    )
}

internal fun tagToOperation(tag: TagEntity): SyncOperation {
    val data = JsonObject().apply {
        addProperty("id", tag.id)
        addProperty("name", tag.name)
        addProperty("color", tag.color)
        addProperty("created_at", tag.createdAt)
    }
    return SyncOperation(
        entityType = "tag",
        operation = "update",
        entityId = tag.id,
        data = data,
        clientTimestamp = millisToIso(tag.createdAt)
    )
}

internal fun habitToOperation(habit: HabitEntity): SyncOperation {
    val data = JsonObject().apply {
        addProperty("id", habit.id)
        addProperty("name", habit.name)
        if (habit.description != null) addProperty("description", habit.description)
        addProperty("target_frequency", habit.targetFrequency)
        addProperty("frequency_period", habit.frequencyPeriod)
        if (habit.activeDays != null) addProperty("active_days", habit.activeDays)
        addProperty("color", habit.color)
        addProperty("icon", habit.icon)
        if (habit.reminderTime != null) addProperty("reminder_time", habit.reminderTime)
        addProperty("sort_order", habit.sortOrder)
        addProperty("is_archived", habit.isArchived)
        if (habit.category != null) addProperty("category", habit.category)
        addProperty("create_daily_task", habit.createDailyTask)
        if (habit.reminderIntervalMillis != null) addProperty("reminder_interval_millis", habit.reminderIntervalMillis)
        addProperty("reminder_times_per_day", habit.reminderTimesPerDay)
        addProperty("has_logging", habit.hasLogging)
        addProperty("track_booking", habit.trackBooking)
        addProperty("track_previous_period", habit.trackPreviousPeriod)
        addProperty("is_bookable", habit.isBookable)
        addProperty("is_booked", habit.isBooked)
        if (habit.bookedDate != null) addProperty("booked_date", habit.bookedDate)
        if (habit.bookedNote != null) addProperty("booked_note", habit.bookedNote)
        addProperty("show_streak", habit.showStreak)
        addProperty("nag_suppression_override_enabled", habit.nagSuppressionOverrideEnabled)
        addProperty("nag_suppression_days_override", habit.nagSuppressionDaysOverride)
        addProperty("today_skip_after_complete_days", habit.todaySkipAfterCompleteDays)
        addProperty("today_skip_before_schedule_days", habit.todaySkipBeforeScheduleDays)
        addProperty("streak_max_missed_days", habit.streakMaxMissedDays)
        addProperty("forgiveness_enabled", habit.forgivenessEnabled)
        addProperty("forgiveness_allowed_misses", habit.forgivenessAllowedMisses)
        addProperty("forgiveness_grace_period_days", habit.forgivenessGracePeriodDays)
        addProperty("created_at", habit.createdAt)
        addProperty("updated_at", habit.updatedAt)
    }
    return SyncOperation(
        entityType = "habit",
        operation = "update",
        entityId = habit.id,
        data = data,
        clientTimestamp = millisToIso(habit.updatedAt)
    )
}

internal fun habitCompletionToOperation(completion: HabitCompletionEntity): SyncOperation {
    val data = JsonObject().apply {
        addProperty("id", completion.id)
        addProperty("habit_id", completion.habitId)
        addProperty("completed_date", completion.completedDate)
        addProperty(
            "completed_date_local",
            completion.completedDateLocal ?: millisToLocalDate(completion.completedDate)
        )
        addProperty("completed_at", completion.completedAt)
        addProperty("is_skipped", completion.isSkipped)
        if (completion.notes != null) addProperty("notes", completion.notes)
    }
    return SyncOperation(
        entityType = "habit_completion",
        operation = "update",
        entityId = completion.id,
        data = data,
        clientTimestamp = millisToIso(completion.completedAt)
    )
}

internal fun slotCompletionToOperation(
    row: DailyEssentialSlotCompletionEntity
): SyncOperation {
    val data = JsonObject().apply {
        addProperty("id", row.id)
        addProperty("date", millisToLocalDate(row.date))
        addProperty("slot_key", row.slotKey)
        addProperty("med_ids_json", row.medIdsJson)
        if (row.takenAt != null) {
            addProperty("taken_at", millisToIso(row.takenAt))
        }
    }
    return SyncOperation(
        entityType = "daily_essential_slot_completion",
        operation = "update",
        entityId = row.id,
        data = data,
        clientTimestamp = millisToIso(row.updatedAt)
    )
}

internal fun taskTemplateToOperation(template: TaskTemplateEntity): SyncOperation {
    val data = JsonObject().apply {
        addProperty("id", template.id)
        addProperty("name", template.name)
        if (template.description != null) addProperty("description", template.description)
        if (template.icon != null) addProperty("icon", template.icon)
        if (template.category != null) addProperty("category", template.category)
        if (template.templateTitle != null) addProperty("template_title", template.templateTitle)
        if (template.templateDescription != null) {
            addProperty("template_description", template.templateDescription)
        }
        if (template.templatePriority != null) {
            addProperty("template_priority", template.templatePriority)
        }
        if (template.templateProjectId != null) {
            addProperty("template_project_id", template.templateProjectId)
        }
        if (template.templateTagsJson != null) {
            addProperty("template_tags_json", template.templateTagsJson)
        }
        if (template.templateRecurrenceJson != null) {
            addProperty("template_recurrence_json", template.templateRecurrenceJson)
        }
        if (template.templateDuration != null) {
            addProperty("template_duration", template.templateDuration)
        }
        if (template.templateSubtasksJson != null) {
            addProperty("template_subtasks_json", template.templateSubtasksJson)
        }
        addProperty("is_built_in", template.isBuiltIn)
        addProperty("usage_count", template.usageCount)
        if (template.lastUsedAt != null) addProperty("last_used_at", template.lastUsedAt)
        addProperty("created_at", template.createdAt)
        addProperty("updated_at", template.updatedAt)
    }
    return SyncOperation(
        entityType = "task_template",
        operation = "update",
        entityId = template.id,
        data = data,
        clientTimestamp = millisToIso(template.updatedAt)
    )
}

internal fun JsonObject.optString(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.asString

internal fun JsonObject.optLong(key: String): Long? =
    get(key)?.takeIf { !it.isJsonNull }?.asLong

internal fun JsonObject.optInt(key: String): Int? =
    get(key)?.takeIf { !it.isJsonNull }?.asInt

internal fun JsonObject.optBool(key: String): Boolean? =
    get(key)?.takeIf { !it.isJsonNull }?.asBoolean

// endregion

// region Timestamp helpers

/**
 * Convert epoch milliseconds to an ISO 8601 UTC string that Pydantic's
 * `datetime` type accepts (e.g. "2026-04-09T12:34:56.789Z").
 */
internal fun millisToIso(millis: Long): String =
    Instant.ofEpochMilli(millis).toString()

// region Medication time-logging entities (PR4 follow-up)
//
// Medication entities (medication, medication_slot, medication_tier_state)
// sync to the backend through the same /sync/push surface as tasks/habits,
// but tier_state references its parents by `*_cloud_id` rather than local
// integer FK. Local Android ids and backend integer ids never agree, so
// the only safe cross-system handle is the user-generated cloud_id. The
// resolver `_resolve_cloud_fk_for_medication` in routers/sync.py turns
// those back into integer FKs at write time.
//
// `medication_mark` was historically a fourth entity here (per the
// original PR4 chain) but the table was never populated by any production
// write path — the per-medication intended_time ended up living on
// `medication_tier_states` instead. The mark mapper, DAO, table, and
// backend model were dropped in chore/drop-orphan-medication-marks.

internal fun medicationToOperation(med: MedicationEntity): SyncOperation {
    val data = JsonObject().apply {
        if (med.cloudId != null) addProperty("cloud_id", med.cloudId)
        addProperty("name", med.name)
        if (med.notes.isNotBlank()) addProperty("notes", med.notes)
        addProperty("is_active", !med.isArchived)
    }
    return SyncOperation(
        entityType = "medication",
        operation = "update",
        entityId = med.id,
        data = data,
        clientTimestamp = millisToIso(med.updatedAt)
    )
}

internal fun medicationSlotToOperation(slot: MedicationSlotEntity): SyncOperation {
    val data = JsonObject().apply {
        if (slot.cloudId != null) addProperty("cloud_id", slot.cloudId)
        addProperty("slot_key", slot.name)
        addProperty("ideal_time", slot.idealTime)
        addProperty("drift_minutes", slot.driftMinutes)
        addProperty("is_active", slot.isActive)
    }
    return SyncOperation(
        entityType = "medication_slot",
        operation = "update",
        entityId = slot.id,
        data = data,
        clientTimestamp = millisToIso(slot.updatedAt)
    )
}

/**
 * Build a tier-state push op. Caller must supply [medicationCloudId] and
 * [slotCloudId] — looked up from the parents' Room rows. Returns null if
 * either parent doesn't have a cloud_id yet (parents must sync first).
 */
internal fun medicationTierStateToOperation(
    state: MedicationTierStateEntity,
    medicationCloudId: String?,
    slotCloudId: String?
): SyncOperation? {
    if (medicationCloudId.isNullOrBlank() || slotCloudId.isNullOrBlank()) return null
    val data = JsonObject().apply {
        if (state.cloudId != null) addProperty("cloud_id", state.cloudId)
        addProperty("medication_cloud_id", medicationCloudId)
        addProperty("slot_cloud_id", slotCloudId)
        addProperty("log_date", state.logDate)
        addProperty("tier", state.tier)
        addProperty("tier_source", state.tierSource)
        if (state.intendedTime != null) {
            addProperty("intended_time", millisToIso(state.intendedTime))
        }
        addProperty("logged_at", millisToIso(state.loggedAt))
    }
    return SyncOperation(
        entityType = "medication_tier_state",
        operation = "update",
        entityId = state.id,
        data = data,
        clientTimestamp = millisToIso(state.updatedAt)
    )
}

// endregion

/**
 * Convert an epoch-millis day-start into the ``YYYY-MM-DD`` form that
 * Pydantic's `date` type accepts. Uses the device's default timezone so the
 * server-side ``date`` column matches the user's local day.
 */
internal fun millisToLocalDate(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toString()

/**
 * Parse a backend ``YYYY-MM-DD`` string back into a local day-start millis.
 * Returns null if the input is null or malformed.
 */
internal fun localDateToMillisOrNull(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        LocalDate.parse(value)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

/**
 * Parse an ISO 8601 datetime string (with or without timezone offset) into
 * epoch milliseconds. Returns null if the string can't be parsed.
 */
internal fun isoToMillisOrNull(iso: String): Long? = try {
    OffsetDateTime.parse(iso).toInstant().toEpochMilli()
} catch (_: Exception) {
    try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

// endregion
