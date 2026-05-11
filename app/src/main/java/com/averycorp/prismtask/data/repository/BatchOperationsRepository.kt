package com.averycorp.prismtask.data.repository

import androidx.room.withTransaction
import com.averycorp.prismtask.data.local.dao.BatchUndoLogDao
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationDoseDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotDao
import com.averycorp.prismtask.data.local.dao.MedicationTierStateDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.local.entity.BatchUndoLogEntry
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.MedicationDoseEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.data.local.entity.MedicationTierStateEntity
import com.averycorp.prismtask.data.remote.api.BatchParseResponse
import com.averycorp.prismtask.data.remote.api.ForcedAmbiguousPhrase
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.ProposedMutationResponse
import com.averycorp.prismtask.domain.model.BatchEntityType
import com.averycorp.prismtask.domain.model.BatchMutationType
import com.averycorp.prismtask.domain.usecase.BatchUserContextProvider
import com.averycorp.prismtask.domain.usecase.MedicationNameMatcher
import com.google.gson.Gson
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the round-trip between QuickAddBar's batch intent and Room.
 *
 *  1. [parseCommand] calls the backend `/api/v1/ai/batch-parse` endpoint
 *     with a fresh user-context snapshot and returns the mutation plan.
 *  2. [applyBatch] commits a list of mutations atomically: snapshot the
 *     pre-mutation state of every affected entity, write the snapshots to
 *     `batch_undo_log` under one shared `batch_id`, then apply the
 *     mutations. Returns the `batch_id` so the caller can render an
 *     "Undo" Snackbar that knows which batch to reverse.
 *  3. [undoBatch] reverses every entry for a `batch_id` using the saved
 *     `pre_state_json`, marks each entry `undone_at = now`, and returns
 *     a per-mutation result so partial failures surface in the UI.
 *
 * **Scope**: Tasks (RESCHEDULE / DELETE / COMPLETE / PRIORITY_CHANGE /
 * TAG_CHANGE / PROJECT_MOVE), Habits (COMPLETE / SKIP / ARCHIVE), Projects
 * (ARCHIVE), Medications (COMPLETE / SKIP / DELETE / STATE_CHANGE).
 *
 * **Undo strategy**: Hard deletes would force us to capture and restore
 * relations (tags, subtasks, attachments). Until we need that, DELETE is
 * routed through the same soft-delete path as the swipe-to-delete UX
 * (`archivedAt = now()`), which makes undo a one-column flip.
 */
@Singleton
class BatchOperationsRepository
@Inject
constructor(
    private val database: PrismTaskDatabase,
    private val api: PrismTaskApi,
    private val taskDao: TaskDao,
    private val habitDao: HabitDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val batchUndoLogDao: BatchUndoLogDao,
    private val medicationDao: MedicationDao,
    private val medicationDoseDao: MedicationDoseDao,
    private val medicationSlotDao: MedicationSlotDao,
    private val medicationTierStateDao: MedicationTierStateDao,
    private val contextProvider: BatchUserContextProvider
) {
    private val gson = Gson()
    private val isoDate: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Calls the backend's `/api/v1/ai/batch-parse` with a fresh snapshot
     * of the user's tasks/habits/projects/medications. Caller is responsible
     * for ProFeatureGate enforcement before calling.
     *
     * Before the network call we run [MedicationNameMatcher] locally — its
     * result becomes the `committed_medication_matches` and
     * `forced_ambiguous_phrases` hints we send to the backend, and the
     * committed entity_ids ride back on [BatchParseOutcome] so the
     * ViewModel can override its auto-strip safeguard for them.
     */
    suspend fun parseCommand(commandText: String): BatchParseOutcome {
        val ctx = contextProvider.build()
        val matcherInputs = ctx.medications.map {
            MedicationNameMatcher.Medication(
                id = it.id,
                name = it.name,
                displayLabel = it.displayLabel
            )
        }
        val matchResult = MedicationNameMatcher.match(commandText, matcherInputs)
        val (committed, ambiguous) = expandMatchResult(matchResult)
        val forcedPhrases = ambiguous.map {
            ForcedAmbiguousPhrase(
                phrase = it.phrase,
                candidateEntityType = "MEDICATION",
                candidateEntityIds = it.candidateEntityIds
            )
        }
        val enrichedCtx = ctx.copy(
            committedMedicationMatches = committed,
            forcedAmbiguousPhrases = forcedPhrases
        )
        val response = api.parseBatchCommand(
            com.averycorp.prismtask.data.remote.api.BatchParseRequest(
                commandText = commandText,
                userContext = enrichedCtx
            )
        )
        return BatchParseOutcome(
            response = response,
            committedMedicationIds = committed.values.toSet()
        )
    }

    private fun expandMatchResult(
        result: MedicationNameMatcher.MatchResult
    ): Pair<Map<String, String>, List<MedicationNameMatcher.AmbiguousPhrase>> = when (result) {
        MedicationNameMatcher.MatchResult.NoMatch -> emptyMap<String, String>() to emptyList()
        is MedicationNameMatcher.MatchResult.Unambiguous -> result.matches to emptyList()
        is MedicationNameMatcher.MatchResult.Ambiguous -> emptyMap<String, String>() to result.phrases
        is MedicationNameMatcher.MatchResult.Mixed -> result.unambiguous to result.ambiguous
    }

    /**
     * Resolve current local medication rows for [ids]. The preview screen
     * calls this when the AI flagged ambiguous medication candidates so
     * the disambiguation picker can show name + display_label without
     * a second API round-trip. Archived / missing rows are filtered out
     * because the picker shouldn't offer choices the user has already
     * removed.
     */
    suspend fun getMedicationsByIds(
        ids: List<Long>
    ): List<com.averycorp.prismtask.data.local.entity.MedicationEntity> {
        if (ids.isEmpty()) return emptyList()
        return ids.mapNotNull { medicationDao.getByIdOnce(it) }.filter { !it.isArchived }
    }

    /**
     * Resolve current tag names for [taskIds]. The preview screen calls
     * this so a TAG_CHANGE row can render a before/after diff (kept tags
     * neutral, additions green, removals red) without round-tripping
     * tag info through the AI response. One `getAllTagsOnce` plus one
     * `getTagIdsForTaskOnce` per id — fine for the ≤25-mutation cap on
     * a batch.
     */
    suspend fun getTagNamesForTasks(taskIds: List<Long>): Map<Long, List<String>> {
        if (taskIds.isEmpty()) return emptyMap()
        val idToName = tagDao.getAllTagsOnce().associate { it.id to it.name }
        val out = mutableMapOf<Long, List<String>>()
        for (id in taskIds) {
            val tagIds = tagDao.getTagIdsForTaskOnce(id)
            out[id] = tagIds.mapNotNull { idToName[it] }.sorted()
        }
        return out
    }

    /**
     * Apply [mutations] in a single Room transaction. Each affected
     * entity gets its pre-mutation state snapshotted to `batch_undo_log`
     * before the mutation runs. Returns the shared `batch_id`.
     *
     * Mutations that don't match any entity (stale id, deleted entity)
     * or aren't supported in this PR are silently skipped — the snapshot
     * for skipped mutations is also skipped, so the undo log stays clean.
     */
    suspend fun applyBatch(
        commandText: String,
        mutations: List<ProposedMutationResponse>
    ): BatchApplyResult {
        val now = System.currentTimeMillis()
        val expiresAt = now + UNDO_WINDOW_MILLIS
        val batchId = UUID.randomUUID().toString()
        val applied = mutableListOf<ProposedMutationResponse>()
        val skipped = mutableListOf<SkippedMutation>()

        database.withTransaction {
            for (mutation in mutations) {
                val entityType = parseEntityType(mutation.entityType)
                val mutationType = parseMutationType(mutation.mutationType)
                if (entityType == null || mutationType == null) {
                    skipped += SkippedMutation(mutation, "unrecognized type")
                    continue
                }
                val entityId = mutation.entityId.toLongOrNull()
                if (entityId == null) {
                    skipped += SkippedMutation(mutation, "non-numeric entity id")
                    continue
                }

                val outcome = applyOne(
                    batchId = batchId,
                    commandText = commandText,
                    expiresAt = expiresAt,
                    now = now,
                    entityType = entityType,
                    mutationType = mutationType,
                    entityId = entityId,
                    mutation = mutation
                )
                if (outcome.applied) {
                    applied += mutation
                } else {
                    skipped += SkippedMutation(mutation, outcome.reason ?: "unknown")
                }
            }
        }

        return BatchApplyResult(
            batchId = batchId,
            commandText = commandText,
            appliedCount = applied.size,
            skipped = skipped
        )
    }

    private suspend fun applyOne(
        batchId: String,
        commandText: String,
        expiresAt: Long,
        now: Long,
        entityType: BatchEntityType,
        mutationType: BatchMutationType,
        entityId: Long,
        mutation: ProposedMutationResponse
    ): MutationOutcome = when (entityType) {
        BatchEntityType.TASK -> applyTaskMutation(
            batchId,
            commandText,
            expiresAt,
            now,
            mutationType,
            entityId,
            mutation
        )
        BatchEntityType.HABIT -> applyHabitMutation(
            batchId,
            commandText,
            expiresAt,
            now,
            mutationType,
            entityId,
            mutation
        )
        BatchEntityType.PROJECT -> applyProjectMutation(
            batchId,
            commandText,
            expiresAt,
            now,
            mutationType,
            entityId,
            mutation
        )
        BatchEntityType.MEDICATION -> applyMedicationMutation(
            batchId,
            commandText,
            expiresAt,
            now,
            mutationType,
            entityId,
            mutation
        )
    }

    private suspend fun applyTaskMutation(
        batchId: String,
        commandText: String,
        expiresAt: Long,
        now: Long,
        mutationType: BatchMutationType,
        entityId: Long,
        mutation: ProposedMutationResponse
    ): MutationOutcome {
        val task = taskDao.getTaskByIdOnce(entityId)
            ?: return MutationOutcome(false, "task not found")

        val updatedNow = task.copy(updatedAt = now)
        return when (mutationType) {
            BatchMutationType.RESCHEDULE -> {
                val newDueIso = mutation.proposedNewValues["due_date"] as? String
                val newDueMillis = newDueIso?.let { parseIsoDateToMillis(it) }
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.TASK, mutationType,
                    entityId, task.cloudId,
                    snapshot = mapOf(
                        "due_date" to task.dueDate,
                        "scheduled_start_time" to task.scheduledStartTime
                    )
                )
                taskDao.update(updatedNow.copy(dueDate = newDueMillis))
                MutationOutcome(true)
            }
            BatchMutationType.DELETE -> {
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.TASK, mutationType,
                    entityId, task.cloudId,
                    snapshot = mapOf("archived_at" to task.archivedAt)
                )
                taskDao.update(updatedNow.copy(archivedAt = now))
                MutationOutcome(true)
            }
            BatchMutationType.COMPLETE -> {
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.TASK, mutationType,
                    entityId, task.cloudId,
                    snapshot = mapOf(
                        "is_completed" to task.isCompleted,
                        "completed_at" to task.completedAt
                    )
                )
                taskDao.update(updatedNow.copy(isCompleted = true, completedAt = now))
                MutationOutcome(true)
            }
            BatchMutationType.PRIORITY_CHANGE -> {
                val newPriority = (mutation.proposedNewValues["priority"] as? Number)?.toInt()
                    ?: return MutationOutcome(false, "missing priority value")
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.TASK, mutationType,
                    entityId, task.cloudId,
                    snapshot = mapOf("priority" to task.priority)
                )
                taskDao.update(updatedNow.copy(priority = newPriority))
                MutationOutcome(true)
            }
            BatchMutationType.TAG_CHANGE -> {
                val priorTagIds = tagDao.getTagIdsForTaskOnce(entityId)
                val toAdd = (mutation.proposedNewValues["tags_added"] as? List<*>)
                    ?.mapNotNull { it as? String }.orEmpty()
                val toRemove = (mutation.proposedNewValues["tags_removed"] as? List<*>)
                    ?.mapNotNull { it as? String }.orEmpty()
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.TASK, mutationType,
                    entityId, task.cloudId,
                    snapshot = mapOf("tag_ids" to priorTagIds)
                )
                applyTagDelta(entityId, toAdd, toRemove, now)
                taskDao.update(updatedNow)
                MutationOutcome(true)
            }
            BatchMutationType.PROJECT_MOVE -> {
                val newProjectId = (mutation.proposedNewValues["project_id"] as? String)?.toLongOrNull()
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.TASK, mutationType,
                    entityId, task.cloudId,
                    snapshot = mapOf("project_id" to task.projectId)
                )
                taskDao.update(updatedNow.copy(projectId = newProjectId))
                MutationOutcome(true)
            }
            else -> MutationOutcome(false, "unsupported task mutation: $mutationType")
        }
    }

    private suspend fun applyHabitMutation(
        batchId: String,
        commandText: String,
        expiresAt: Long,
        now: Long,
        mutationType: BatchMutationType,
        entityId: Long,
        mutation: ProposedMutationResponse
    ): MutationOutcome {
        val habit = habitDao.getHabitByIdOnce(entityId)
            ?: return MutationOutcome(false, "habit not found")

        return when (mutationType) {
            BatchMutationType.COMPLETE -> {
                val dateIso = mutation.proposedNewValues["date"] as? String
                    ?: LocalDate.now().format(isoDate)
                val dateMillis = parseIsoDateToMillis(dateIso) ?: now
                // Snapshot the EXISTENCE of a prior completion row for this
                // (habit, date). Undo deletes the row we insert here.
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.HABIT, mutationType,
                    entityId, habit.cloudId,
                    snapshot = mapOf("completed_date_local" to dateIso)
                )
                habitCompletionDao.insert(
                    HabitCompletionEntity(
                        habitId = entityId,
                        completedDate = dateMillis,
                        completedDateLocal = dateIso,
                        notes = null,
                        completedAt = now
                    )
                )
                MutationOutcome(true)
            }
            BatchMutationType.SKIP -> {
                val dateIso = mutation.proposedNewValues["date"] as? String
                    ?: LocalDate.now().format(isoDate)
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.HABIT, mutationType,
                    entityId, habit.cloudId,
                    snapshot = mapOf("completed_date_local" to dateIso)
                )
                habitCompletionDao.deleteByHabitAndDateLocal(entityId, dateIso)
                MutationOutcome(true)
            }
            BatchMutationType.ARCHIVE -> {
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.HABIT, mutationType,
                    entityId, habit.cloudId,
                    snapshot = mapOf("is_archived" to habit.isArchived)
                )
                habitDao.update(habit.copy(isArchived = true, updatedAt = now))
                MutationOutcome(true)
            }
            BatchMutationType.DELETE -> {
                // Habit DELETE means archive — we don't drop habit history
                // even via batch ops. Same shape as the in-app habit menu.
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.HABIT, mutationType,
                    entityId, habit.cloudId,
                    snapshot = mapOf("is_archived" to habit.isArchived)
                )
                habitDao.update(habit.copy(isArchived = true, updatedAt = now))
                MutationOutcome(true)
            }
            else -> MutationOutcome(false, "unsupported habit mutation: $mutationType")
        }
    }

    @Suppress("LongParameterList")
    private suspend fun applyProjectMutation(
        batchId: String,
        commandText: String,
        expiresAt: Long,
        now: Long,
        mutationType: BatchMutationType,
        entityId: Long,
        @Suppress("UNUSED_PARAMETER") mutation: ProposedMutationResponse
    ): MutationOutcome {
        val project = projectDao.getProjectByIdOnce(entityId)
            ?: return MutationOutcome(false, "project not found")

        return when (mutationType) {
            BatchMutationType.ARCHIVE -> {
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.PROJECT, mutationType,
                    entityId, project.cloudId,
                    snapshot = mapOf("status" to project.status, "archived_at" to project.archivedAt)
                )
                projectDao.update(
                    project.copy(status = "ARCHIVED", archivedAt = now, updatedAt = now)
                )
                MutationOutcome(true)
            }
            BatchMutationType.DELETE -> {
                // Same as ARCHIVE — never hard-delete a project from a batch.
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.PROJECT, mutationType,
                    entityId, project.cloudId,
                    snapshot = mapOf("status" to project.status, "archived_at" to project.archivedAt)
                )
                projectDao.update(
                    project.copy(status = "ARCHIVED", archivedAt = now, updatedAt = now)
                )
                MutationOutcome(true)
            }
            else -> MutationOutcome(false, "unsupported project mutation: $mutationType")
        }
    }

    /**
     * Apply a medication-targeting mutation. The Haiku schema emits four
     * verbs against MEDICATION: COMPLETE (insert real dose), SKIP (insert
     * synthetic-skip dose so the interval-mode rescheduler re-anchors,
     * plus tier-state="skipped"), DELETE (remove the matching real dose),
     * and STATE_CHANGE (manual tier override with `tier_source="user_set"`).
     *
     * `slot_key` from the AI plan is matched case-insensitively against
     * `MedicationSlotEntity.name`. SKIP gracefully proceeds without a
     * tier-state write if the slot can't be resolved (the dose still
     * lands); STATE_CHANGE skips the mutation entirely if the slot is
     * missing because there's no row to write without a slot_id.
     */
    @Suppress("LongParameterList")
    private suspend fun applyMedicationMutation(
        batchId: String,
        commandText: String,
        expiresAt: Long,
        now: Long,
        mutationType: BatchMutationType,
        entityId: Long,
        mutation: ProposedMutationResponse
    ): MutationOutcome {
        val medication = medicationDao.getByIdOnce(entityId)
            ?: return MutationOutcome(false, "medication not found")
        val slotKey = mutation.proposedNewValues["slot_key"] as? String
        val date = mutation.proposedNewValues["date"] as? String
            ?: LocalDate.now().format(isoDate)

        return when (mutationType) {
            BatchMutationType.COMPLETE -> {
                if (slotKey == null) return MutationOutcome(false, "missing slot_key")
                // `taken_at` from the mutation wins over the date-derived
                // midnight fallback so tier-button taps record the actual
                // wall-clock instant, not 00:00. Haiku batch path leaves it
                // unset and keeps the legacy midnight default.
                val takenAt = (mutation.proposedNewValues["taken_at"] as? Number)?.toLong()
                    ?: parseIsoDateToMillis(date)
                    ?: now
                val doseId = medicationDoseDao.insert(
                    MedicationDoseEntity(
                        medicationId = entityId,
                        slotKey = slotKey,
                        takenAt = takenAt,
                        takenDateLocal = date,
                        isSyntheticSkip = false,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.MEDICATION, mutationType,
                    entityId, medication.cloudId,
                    snapshot = mapOf("dose_id" to doseId)
                )
                MutationOutcome(true)
            }
            BatchMutationType.SKIP -> {
                if (slotKey == null) return MutationOutcome(false, "missing slot_key")
                // Delete real (non-synthetic) doses for (med, slot, date) before
                // recording the skip. This is the bulk-mark "Skip wins" semantic
                // — a skipped slot shouldn't keep a stale "taken" row from earlier
                // in the day. Snapshot deleted rows so undo restores them.
                val priorRealDoses = medicationDoseDao.getAllForMedOnce(entityId).filter {
                    it.slotKey == slotKey && it.takenDateLocal == date && !it.isSyntheticSkip
                }
                priorRealDoses.forEach { medicationDoseDao.deleteById(it.id) }
                val takenAt = parseIsoDateToMillis(date) ?: now
                val doseId = medicationDoseDao.insert(
                    MedicationDoseEntity(
                        medicationId = entityId,
                        slotKey = slotKey,
                        takenAt = takenAt,
                        takenDateLocal = date,
                        isSyntheticSkip = true,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                val snap = mutableMapOf<String, Any?>(
                    "dose_id" to doseId,
                    "deleted_real_doses" to priorRealDoses.map { d ->
                        mapOf(
                            "id" to d.id,
                            "cloud_id" to d.cloudId,
                            "slot_key" to d.slotKey,
                            "taken_at" to d.takenAt,
                            "taken_date_local" to d.takenDateLocal,
                            "note" to d.note,
                            "is_synthetic_skip" to d.isSyntheticSkip,
                            "created_at" to d.createdAt
                        )
                    }
                )
                val slot = resolveSlotByKey(slotKey)
                if (slot != null) {
                    val prior = medicationTierStateDao.getForTripleOnce(entityId, date, slot.id)
                    if (prior != null) {
                        snap["tier_state_id"] = prior.id
                        snap["prior_tier"] = prior.tier
                        snap["prior_tier_source"] = prior.tierSource
                        medicationTierStateDao.update(
                            prior.copy(tier = "skipped", tierSource = "user_set", updatedAt = now)
                        )
                    } else {
                        val newId = medicationTierStateDao.insert(
                            MedicationTierStateEntity(
                                medicationId = entityId,
                                slotId = slot.id,
                                logDate = date,
                                tier = "skipped",
                                tierSource = "user_set",
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                        snap["tier_state_id"] = newId
                    }
                }
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.MEDICATION, mutationType,
                    entityId, medication.cloudId,
                    snapshot = snap
                )
                MutationOutcome(true)
            }
            BatchMutationType.DELETE -> {
                if (slotKey == null) return MutationOutcome(false, "missing slot_key")
                val match = medicationDoseDao.getAllForMedOnce(entityId).firstOrNull {
                    it.slotKey == slotKey && it.takenDateLocal == date && !it.isSyntheticSkip
                } ?: return MutationOutcome(false, "no matching dose")
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.MEDICATION, mutationType,
                    entityId, medication.cloudId,
                    snapshot = mapOf(
                        "dose_id" to match.id,
                        "cloud_id" to match.cloudId,
                        "slot_key" to match.slotKey,
                        "taken_at" to match.takenAt,
                        "taken_date_local" to match.takenDateLocal,
                        "note" to match.note,
                        "is_synthetic_skip" to match.isSyntheticSkip,
                        "created_at" to match.createdAt
                    )
                )
                medicationDoseDao.deleteById(match.id)
                MutationOutcome(true)
            }
            BatchMutationType.STATE_CHANGE -> {
                if (slotKey == null) return MutationOutcome(false, "missing slot_key")
                val tier = mutation.proposedNewValues["tier"] as? String
                    ?: return MutationOutcome(false, "missing tier")
                val slot = resolveSlotByKey(slotKey)
                    ?: return MutationOutcome(false, "slot not found")
                val prior = medicationTierStateDao.getForTripleOnce(entityId, date, slot.id)
                val snap = mutableMapOf<String, Any?>()
                if (prior != null) {
                    snap["tier_state_id"] = prior.id
                    snap["prior_tier"] = prior.tier
                    snap["prior_tier_source"] = prior.tierSource
                    medicationTierStateDao.update(
                        prior.copy(tier = tier, tierSource = "user_set", updatedAt = now)
                    )
                } else {
                    val newId = medicationTierStateDao.insert(
                        MedicationTierStateEntity(
                            medicationId = entityId,
                            slotId = slot.id,
                            logDate = date,
                            tier = tier,
                            tierSource = "user_set",
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                    snap["tier_state_id"] = newId
                }
                snapshot(
                    batchId, commandText, expiresAt, now, BatchEntityType.MEDICATION, mutationType,
                    entityId, medication.cloudId,
                    snapshot = snap
                )
                MutationOutcome(true)
            }
            else -> MutationOutcome(false, "unsupported medication mutation: $mutationType")
        }
    }

    private suspend fun resolveSlotByKey(slotKey: String): MedicationSlotEntity? {
        val target = slotKey.trim()
        if (target.isEmpty()) return null
        // The in-app bulk-mark path passes slot.id.toString(); the AI Haiku
        // batch path passes slot.name. Try id lookup first, fall back to name.
        target.toLongOrNull()?.let { id ->
            medicationSlotDao.getByIdOnce(id)?.let { return it }
        }
        val lower = target.lowercase()
        return medicationSlotDao.getAllOnce().firstOrNull {
            it.name.trim().lowercase() == lower
        }
    }

    /**
     * Reverse every entry under [batchId] using its saved `pre_state_json`.
     * Marks each row's `undone_at = now` on success. Returns per-mutation
     * results so the caller can surface partial failures.
     */
    suspend fun undoBatch(batchId: String): BatchUndoResult {
        val entries = batchUndoLogDao.getEntriesForBatchOnce(batchId)
        if (entries.isEmpty()) {
            return BatchUndoResult(batchId, restored = 0, failed = emptyList())
        }
        val now = System.currentTimeMillis()
        val failures = mutableListOf<UndoFailure>()
        var restored = 0

        database.withTransaction {
            // Reverse entries in reverse insertion order — TAG_CHANGE
            // before PROJECT_MOVE if both touched the same task, etc.
            for (entry in entries.reversed()) {
                if (entry.undoneAt != null) continue // already reversed
                val ok = try {
                    reverseOne(entry, now)
                } catch (e: Exception) {
                    failures += UndoFailure(entry.id, e.message ?: e::class.java.simpleName)
                    false
                }
                if (ok) restored += 1
            }
            batchUndoLogDao.markBatchUndone(batchId, now)
        }
        return BatchUndoResult(batchId, restored, failures)
    }

    private suspend fun reverseOne(entry: BatchUndoLogEntry, now: Long): Boolean {
        val entityType = parseEntityType(entry.entityType) ?: return false
        val mutationType = parseMutationType(entry.mutationType) ?: return false
        val snapshot: Map<String, Any?> = decodeSnapshot(entry.preStateJson)
        val entityId = entry.entityId ?: return false

        return when (entityType) {
            BatchEntityType.TASK -> reverseTask(entityId, mutationType, snapshot, now)
            BatchEntityType.HABIT -> reverseHabit(entityId, mutationType, snapshot, now)
            BatchEntityType.PROJECT -> reverseProject(entityId, mutationType, snapshot, now)
            BatchEntityType.MEDICATION -> reverseMedication(entityId, mutationType, snapshot, now)
        }
    }

    private suspend fun reverseTask(
        entityId: Long,
        mutationType: BatchMutationType,
        snapshot: Map<String, Any?>,
        now: Long
    ): Boolean {
        val task = taskDao.getTaskByIdOnce(entityId) ?: return false
        val withTime = task.copy(updatedAt = now)
        when (mutationType) {
            BatchMutationType.RESCHEDULE -> taskDao.update(
                withTime.copy(
                    dueDate = (snapshot["due_date"] as? Number)?.toLong(),
                    scheduledStartTime = (snapshot["scheduled_start_time"] as? Number)?.toLong()
                )
            )
            BatchMutationType.DELETE -> taskDao.update(
                withTime.copy(archivedAt = (snapshot["archived_at"] as? Number)?.toLong())
            )
            BatchMutationType.COMPLETE -> taskDao.update(
                withTime.copy(
                    isCompleted = snapshot["is_completed"] as? Boolean ?: false,
                    completedAt = (snapshot["completed_at"] as? Number)?.toLong()
                )
            )
            BatchMutationType.PRIORITY_CHANGE -> taskDao.update(
                withTime.copy(priority = (snapshot["priority"] as? Number)?.toInt() ?: 0)
            )
            BatchMutationType.TAG_CHANGE -> {
                val priorTagIds = (snapshot["tag_ids"] as? List<*>)
                    ?.mapNotNull { (it as? Number)?.toLong() }.orEmpty()
                tagDao.removeAllTagsFromTask(entityId)
                priorTagIds.forEach { tagId ->
                    tagDao.addTagToTask(
                        com.averycorp.prismtask.data.local.entity.TaskTagCrossRef(
                            taskId = entityId,
                            tagId = tagId
                        )
                    )
                }
                taskDao.update(withTime)
            }
            BatchMutationType.PROJECT_MOVE -> taskDao.update(
                withTime.copy(projectId = (snapshot["project_id"] as? Number)?.toLong())
            )
            else -> return false
        }
        return true
    }

    private suspend fun reverseHabit(
        entityId: Long,
        mutationType: BatchMutationType,
        snapshot: Map<String, Any?>,
        now: Long
    ): Boolean {
        return when (mutationType) {
            BatchMutationType.COMPLETE -> {
                val dateIso = snapshot["completed_date_local"] as? String ?: return false
                habitCompletionDao.deleteLatestByHabitAndDateLocal(entityId, dateIso)
                true
            }
            BatchMutationType.SKIP -> {
                // We can't reconstruct the deleted completion's exact millis;
                // re-create with the date midnight as a best-effort restore.
                val dateIso = snapshot["completed_date_local"] as? String ?: return false
                val millis = parseIsoDateToMillis(dateIso) ?: return false
                habitCompletionDao.insert(
                    HabitCompletionEntity(
                        habitId = entityId,
                        completedDate = millis,
                        completedDateLocal = dateIso,
                        notes = null,
                        completedAt = now
                    )
                )
                true
            }
            BatchMutationType.ARCHIVE, BatchMutationType.DELETE -> {
                val habit = habitDao.getHabitByIdOnce(entityId) ?: return false
                habitDao.update(
                    habit.copy(
                        isArchived = snapshot["is_archived"] as? Boolean ?: false,
                        updatedAt = now
                    )
                )
                true
            }
            else -> false
        }
    }

    private suspend fun reverseProject(
        entityId: Long,
        mutationType: BatchMutationType,
        snapshot: Map<String, Any?>,
        now: Long
    ): Boolean {
        val project = projectDao.getProjectByIdOnce(entityId) ?: return false
        return when (mutationType) {
            BatchMutationType.ARCHIVE, BatchMutationType.DELETE -> {
                projectDao.update(
                    project.copy(
                        status = snapshot["status"] as? String ?: "ACTIVE",
                        archivedAt = (snapshot["archived_at"] as? Number)?.toLong(),
                        updatedAt = now
                    )
                )
                true
            }
            else -> false
        }
    }

    private suspend fun reverseMedication(
        entityId: Long,
        mutationType: BatchMutationType,
        snapshot: Map<String, Any?>,
        now: Long
    ): Boolean {
        return when (mutationType) {
            BatchMutationType.COMPLETE -> {
                val doseId = (snapshot["dose_id"] as? Number)?.toLong() ?: return false
                medicationDoseDao.deleteById(doseId)
                true
            }
            BatchMutationType.SKIP -> {
                val doseId = (snapshot["dose_id"] as? Number)?.toLong() ?: return false
                medicationDoseDao.deleteById(doseId)
                val tierStateId = (snapshot["tier_state_id"] as? Number)?.toLong()
                if (tierStateId != null) {
                    val priorTier = snapshot["prior_tier"] as? String
                    if (priorTier != null) {
                        val ts = medicationTierStateDao.getByIdOnce(tierStateId)
                        if (ts != null) {
                            medicationTierStateDao.update(
                                ts.copy(
                                    tier = priorTier,
                                    tierSource = (snapshot["prior_tier_source"] as? String)
                                        ?: "computed",
                                    updatedAt = now
                                )
                            )
                        }
                    } else {
                        medicationTierStateDao.deleteById(tierStateId)
                    }
                }
                // Restore the real doses that the SKIP apply path deleted.
                @Suppress("UNCHECKED_CAST")
                val deletedRealDoses = snapshot["deleted_real_doses"] as? List<Map<String, Any?>>
                deletedRealDoses?.forEach { d ->
                    medicationDoseDao.insert(
                        MedicationDoseEntity(
                            medicationId = entityId,
                            cloudId = d["cloud_id"] as? String,
                            slotKey = d["slot_key"] as? String ?: return@forEach,
                            takenAt = (d["taken_at"] as? Number)?.toLong() ?: return@forEach,
                            takenDateLocal = d["taken_date_local"] as? String ?: return@forEach,
                            note = d["note"] as? String ?: "",
                            isSyntheticSkip = d["is_synthetic_skip"] as? Boolean ?: false,
                            createdAt = (d["created_at"] as? Number)?.toLong() ?: now,
                            updatedAt = now
                        )
                    )
                }
                true
            }
            BatchMutationType.DELETE -> {
                val cloudId = snapshot["cloud_id"] as? String
                val slotKey = snapshot["slot_key"] as? String ?: return false
                val takenAt = (snapshot["taken_at"] as? Number)?.toLong() ?: return false
                val takenDateLocal = snapshot["taken_date_local"] as? String ?: return false
                val note = snapshot["note"] as? String ?: ""
                val isSynthetic = snapshot["is_synthetic_skip"] as? Boolean ?: false
                val createdAt = (snapshot["created_at"] as? Number)?.toLong() ?: now
                medicationDoseDao.insert(
                    MedicationDoseEntity(
                        medicationId = entityId,
                        cloudId = cloudId,
                        slotKey = slotKey,
                        takenAt = takenAt,
                        takenDateLocal = takenDateLocal,
                        note = note,
                        isSyntheticSkip = isSynthetic,
                        createdAt = createdAt,
                        updatedAt = now
                    )
                )
                true
            }
            BatchMutationType.STATE_CHANGE -> {
                val tierStateId = (snapshot["tier_state_id"] as? Number)?.toLong() ?: return false
                val priorTier = snapshot["prior_tier"] as? String
                if (priorTier != null) {
                    val ts = medicationTierStateDao.getByIdOnce(tierStateId) ?: return false
                    medicationTierStateDao.update(
                        ts.copy(
                            tier = priorTier,
                            tierSource = (snapshot["prior_tier_source"] as? String) ?: "computed",
                            updatedAt = now
                        )
                    )
                } else {
                    medicationTierStateDao.deleteById(tierStateId)
                }
                true
            }
            else -> false
        }
    }

    private suspend fun applyTagDelta(
        taskId: Long,
        addTagNames: List<String>,
        removeTagNames: List<String>,
        now: Long
    ) {
        // Resolve add: existing tag id or auto-create.
        val allTags = tagDao.getAllTagsOnce()
        val nameToId = allTags.associate { it.name.lowercase() to it.id }

        for (name in addTagNames) {
            val existing = nameToId[name.lowercase()]
            val tagId = existing ?: tagDao.insert(
                com.averycorp.prismtask.data.local.entity.TagEntity(
                    name = name,
                    createdAt = now
                )
            )
            tagDao.addTagToTask(
                com.averycorp.prismtask.data.local.entity.TaskTagCrossRef(
                    taskId = taskId,
                    tagId = tagId
                )
            )
        }
        for (name in removeTagNames) {
            val existingId = nameToId[name.lowercase()] ?: continue
            tagDao.removeTagFromTask(taskId, existingId)
        }
    }

    private suspend fun snapshot(
        batchId: String,
        commandText: String,
        expiresAt: Long,
        now: Long,
        entityType: BatchEntityType,
        mutationType: BatchMutationType,
        entityId: Long,
        cloudId: String?,
        snapshot: Map<String, Any?>
    ) {
        batchUndoLogDao.insert(
            BatchUndoLogEntry(
                batchId = batchId,
                batchCommandText = commandText,
                entityType = entityType.name,
                entityId = entityId,
                entityCloudId = cloudId,
                preStateJson = gson.toJson(snapshot),
                mutationType = mutationType.name,
                createdAt = now,
                undoneAt = null,
                expiresAt = expiresAt
            )
        )
    }

    private fun decodeSnapshot(json: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (gson.fromJson(json, Map::class.java) as? Map<String, Any?>).orEmpty()
    }

    private fun parseEntityType(raw: String): BatchEntityType? = runCatching {
        BatchEntityType.valueOf(raw)
    }.getOrNull()

    private fun parseMutationType(raw: String): BatchMutationType? = runCatching {
        BatchMutationType.valueOf(raw)
    }.getOrNull()

    /** ISO `YYYY-MM-DD` -> midnight-UTC millis. Null on parse failure. */
    private fun parseIsoDateToMillis(iso: String): Long? = runCatching {
        LocalDate.parse(iso, isoDate)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    data class BatchParseOutcome(
        val response: BatchParseResponse,
        /**
         * entity_ids the local [MedicationNameMatcher] resolved unambiguously
         * for this command. The ViewModel uses this set to skip its
         * auto-strip safeguard when Haiku flagged a committed med as
         * ambiguous (which shouldn't happen if the backend honors the hint,
         * but the override is the belt-and-suspenders second line of
         * defense — same shape as PR #1034).
         */
        val committedMedicationIds: Set<String>
    )

    data class BatchApplyResult(val batchId: String, val commandText: String, val appliedCount: Int, val skipped: List<SkippedMutation>)

    data class SkippedMutation(val mutation: ProposedMutationResponse, val reason: String)

    data class BatchUndoResult(val batchId: String, val restored: Int, val failed: List<UndoFailure>)

    data class UndoFailure(val entryId: Long, val reason: String)

    private data class MutationOutcome(val applied: Boolean, val reason: String? = null)

    companion object {
        const val UNDO_WINDOW_MILLIS = 24L * 60 * 60 * 1000
    }
}
