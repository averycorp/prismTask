package com.averycorp.prismtask.data.repository

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationDoseDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotDao
import com.averycorp.prismtask.data.local.dao.MedicationTierStateDao
import com.averycorp.prismtask.data.local.dao.SelfCareDao
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.MedicationTierStateEntity
import com.averycorp.prismtask.data.local.entity.SelfCareLogEntity
import com.averycorp.prismtask.data.local.entity.SelfCareStepEntity
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.MedicationPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.model.SelfCareRoutines
import com.averycorp.prismtask.notifications.ExactAlarmHelper
import com.averycorp.prismtask.notifications.MedStepReminderReceiver
import com.averycorp.prismtask.util.DayBoundary
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class MedStepLog(
    val id: String,
    val note: String = "",
    val at: Long = System.currentTimeMillis(),
    /**
     * Time-of-day block this log belongs to (e.g. "morning", "night").
     * Blank string means legacy log that applies to every time block the
     * step is scheduled for.
     */
    val timeOfDay: String = ""
)

/**
 * Pure helper that reconciles medication logs after a tier-for-time change.
 *
 * Given the full tiers-by-time map (already updated to reflect the tap),
 * the block the user just touched, and the existing log list, this returns
 * the new log list. Pulled out of [SelfCareRepository.setTierForTime] so
 * the logic can be unit-tested without DAO/Alarm dependencies.
 *
 * Behavior:
 * - Blocks listed in `tiersByTime` are "managed"; the touched block is also
 *   considered managed even when its tier is being cleared (so deselecting
 *   correctly drops its explicit logs).
 * - Explicit (non-blank) logs in unmanaged blocks are preserved (they may
 *   represent manual toggles in other sections the user hasn't edited).
 * - Legacy logs with a blank time-of-day are preserved as-is when *none* of
 *   the step's scheduled blocks overlap any managed block. When there IS
 *   overlap, the legacy log is resolved into explicit per-block logs only
 *   for the blocks that are actually target-pairs for the current tier
 *   selection — never for unrelated blocks (this prevents the "logging
 *   night also marks evening as done" bug).
 */
internal fun reconcileMedLogsForTierChange(
    steps: List<SelfCareStepEntity>,
    existingLogs: List<MedStepLog>,
    tiersByTime: Map<String, String>,
    touchedTod: String,
    now: Long
): List<MedStepLog> {
    val routineType = "medication"
    val tierOrder = SelfCareRoutines.getTierOrder(routineType)

    // Compute (stepId, timeOfDay) pairs that should be logged based on the
    // full tiers-by-time map. Each pair represents one medication-in-block.
    val targetPairs = mutableSetOf<Pair<String, String>>()
    for ((tod, t) in tiersByTime) {
        val visibleForTier = steps.filter {
            SelfCareRoutines.tierIncludes(tierOrder, t, it.tier)
        }
        for (step in visibleForTier) {
            if (tod in SelfCareRoutines.parseTimeOfDay(step.timeOfDay)) {
                targetPairs.add(step.stepId to tod)
            }
        }
    }

    // The block being touched is always considered "managed" for this call,
    // even if the tier is being cleared — otherwise deselecting a tier would
    // orphan its explicit logs.
    val managedTods = tiersByTime.keys + touchedTod
    val resultLogs = mutableListOf<MedStepLog>()

    for (log in existingLogs) {
        val step = steps.firstOrNull { it.stepId == log.id }
        if (step == null) {
            resultLogs.add(log)
            continue
        }
        val stepTods = SelfCareRoutines.parseTimeOfDay(step.timeOfDay)
        val logBlock = log.timeOfDay
        if (logBlock.isBlank()) {
            // Legacy log (no explicit time-of-day). If none of this step's
            // blocks overlap the currently-managed blocks, leave the legacy
            // log untouched so its implicit "done" state is preserved for
            // views the user isn't currently editing. Otherwise, resolve the
            // legacy log into explicit entries ONLY for blocks in
            // targetPairs — do not materialize logs for blocks the user
            // didn't pick, which would falsely mark unrelated times as done.
            val anyTodManaged = stepTods.any { it in managedTods }
            if (!anyTodManaged) {
                resultLogs.add(log)
            } else {
                for (tod in stepTods) {
                    if ((log.id to tod) in targetPairs) {
                        resultLogs.add(log.copy(timeOfDay = tod))
                    }
                }
            }
        } else {
            if ((log.id to logBlock) in targetPairs) {
                resultLogs.add(log)
            } else if (logBlock !in managedTods) {
                // Manual toggle on an unmanaged block — leave alone.
                resultLogs.add(log)
            }
        }
    }

    // Add newly-targeted pairs that aren't already present.
    val presentPairs = resultLogs.map { it.id to it.timeOfDay }.toMutableSet()
    for (pair in targetPairs) {
        if (pair !in presentPairs) {
            resultLogs.add(MedStepLog(id = pair.first, note = "", at = now, timeOfDay = pair.second))
            presentPairs.add(pair)
        }
    }

    return resultLogs
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SelfCareRepository
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val selfCareDao: SelfCareDao,
    private val habitDao: HabitDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val medicationPreferences: MedicationPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val gson: Gson,
    private val syncTracker: SyncTracker,
    private val medicationDao: MedicationDao,
    private val medicationDoseDao: MedicationDoseDao,
    /**
     * Item 8 dual-write (D8 override, 2026-05-10): on every legacy
     * `tiersByTime` write we also upsert a row in `medication_tier_states`
     * for `(med, DEFAULT slot, today, max-tier)`. Mirrors migration 59→60
     * semantics — DEFAULT slot only, computed tier source. The legacy JSON
     * column remains source of truth until per-block-slot mapping ships.
     */
    private val medicationSlotDao: MedicationSlotDao,
    private val medicationTierStateDao: MedicationTierStateDao,
    private val advancedTuningPreferences: AdvancedTuningPreferences
) {
    private suspend fun startOfToday(): Long =
        DayBoundary.startOfCurrentDay(taskBehaviorPreferences.getDayStartHour().first())

    /**
     * Resolves the starting `selectedTier` for a freshly inserted
     * [SelfCareLogEntity] when no log exists yet today. Mirrors the read-side
     * precedence used by `SelfCareViewModel.getSelectedTier` and
     * `DailyEssentialsUseCase.resolveSelectedTier`: user-configured default
     * → penultimate-of-order. Without this, fresh logs created by
     * `toggleStep` / `setTierForTime` would carry the schema-level "solid"
     * default (which is also structurally invalid for medication and
     * housework tier orders), silently overriding the user's preference for
     * the rest of the day.
     */
    private suspend fun resolveStartingTier(routineType: String): String {
        val order = SelfCareRoutines.getTierOrder(routineType)
        val configured = advancedTuningPreferences.getSelfCareTierDefaults().first()
            .forRoutine(routineType)
        if (configured != null && configured in order) return configured
        return order.getOrNull(order.size - 2) ?: order.firstOrNull() ?: ""
    }

    private suspend fun todayLocalString(): String =
        DayBoundary.currentLocalDateString(taskBehaviorPreferences.getDayStartHour().first())

    fun getTodayLog(routineType: String): Flow<SelfCareLogEntity?> =
        taskBehaviorPreferences.getDayStartHour().flatMapLatest { hour ->
            selfCareDao.getLogForDate(routineType, DayBoundary.startOfCurrentDay(hour))
        }

    fun getLogsForRoutine(routineType: String): Flow<List<SelfCareLogEntity>> =
        selfCareDao.getLogsForRoutine(routineType)

    fun getSteps(routineType: String): Flow<List<SelfCareStepEntity>> =
        selfCareDao.getStepsForRoutine(routineType)

    suspend fun ensureDefaultStepsSeeded() {
        if (selfCareDao.getStepCount() > 0) {
            ensureHouseworkStepsSeeded()
            return
        }
        val morningEntities = SelfCareRoutines.morningSteps.mapIndexed { i, step ->
            SelfCareStepEntity(
                stepId = step.id,
                routineType = "morning",
                label = step.label,
                duration = step.duration,
                tier = step.tier,
                note = step.note,
                phase = step.phase,
                sortOrder = i
            )
        }
        val bedtimeEntities = SelfCareRoutines.bedtimeSteps.mapIndexed { i, step ->
            SelfCareStepEntity(
                stepId = step.id,
                routineType = "bedtime",
                label = step.label,
                duration = step.duration,
                tier = step.tier,
                note = step.note,
                phase = step.phase,
                sortOrder = i
            )
        }
        val houseworkEntities = SelfCareRoutines.houseworkSteps.mapIndexed { i, step ->
            SelfCareStepEntity(
                stepId = step.id,
                routineType = "housework",
                label = step.label,
                duration = step.duration,
                tier = step.tier,
                note = step.note,
                phase = step.phase,
                sortOrder = i
            )
        }
        selfCareDao.insertSteps(morningEntities + bedtimeEntities + houseworkEntities)
    }

    private suspend fun ensureHouseworkStepsSeeded() {
        val existing = selfCareDao.getStepsForRoutineOnce("housework")
        if (existing.isNotEmpty()) return
        val houseworkEntities = SelfCareRoutines.houseworkSteps.mapIndexed { i, step ->
            SelfCareStepEntity(
                stepId = step.id,
                routineType = "housework",
                label = step.label,
                duration = step.duration,
                tier = step.tier,
                note = step.note,
                phase = step.phase,
                sortOrder = i
            )
        }
        selfCareDao.insertSteps(houseworkEntities)
    }

    /**
     * Seed every default step included in [tier] for the given [routineType],
     * using the cumulative tier semantics from [SelfCareRoutines.tierIncludes]
     * (picking "solid" also seeds "survival" steps). The matching habit row
     * is created if missing. Idempotent: existing stepIds are skipped and
     * the method is safe to call repeatedly.
     *
     * Accepted [routineType] values: "morning", "bedtime", "housework".
     * "medication" has no default steps and is a no-op.
     */
    suspend fun seedSelfCareTier(routineType: String, tier: String) {
        val source = SelfCareRoutines.getSteps(routineType)
        if (source.isEmpty()) return
        val tierOrder = SelfCareRoutines.getTierOrder(routineType)
        if (tier !in tierOrder) return
        val stepIds = source
            .filter { SelfCareRoutines.tierIncludes(tierOrder, tier, it.tier) }
            .map { it.id }
        seedSelfCareSteps(routineType, stepIds)
    }

    /**
     * Seed a specific subset of default steps for [routineType] by their
     * canonical [stepIds] (as defined in [SelfCareRoutines]). Steps already
     * present in the database are skipped. The matching habit row is
     * created if missing. Unknown ids are silently ignored so the caller
     * can safely pass a mixed list.
     */
    suspend fun seedSelfCareSteps(routineType: String, stepIds: List<String>) {
        if (stepIds.isEmpty()) return
        val source = SelfCareRoutines.getSteps(routineType)
        if (source.isEmpty()) return
        val existingIds = selfCareDao
            .getStepsForRoutineOnce(routineType)
            .map { it.stepId }
            .toSet()
        val requested = stepIds.toSet()
        val toInsert = source.filter { it.id in requested && it.id !in existingIds }
        if (toInsert.isEmpty()) {
            getOrCreateHabit(routineType)
            return
        }
        val baseSort = selfCareDao.getMaxSortOrder(routineType) + 1
        val entities = toInsert.mapIndexed { i, step ->
            SelfCareStepEntity(
                stepId = step.id,
                routineType = routineType,
                label = step.label,
                duration = step.duration,
                tier = step.tier,
                note = step.note,
                phase = step.phase,
                sortOrder = baseSort + i
            )
        }
        selfCareDao.insertSteps(entities)
        getOrCreateHabit(routineType)
    }

    /**
     * Debug-only escape hatch invoked by the Settings screen long-press on the
     * app version label. For the three categories being expanded in v1.4.0
     * (Self-Care / Housework / Medication), deletes only the steps whose
     * `stepId` comes from [SelfCareRoutines] — so manually-added steps (with
     * `custom_<uuid>` ids) survive — then re-seeds from the current
     * [SelfCareRoutines] source lists.
     */
    suspend fun reseedBuiltInDefaults() {
        val routineTypes = listOf("morning", "housework", "medication")
        for (routineType in routineTypes) {
            val seededIds = SelfCareRoutines.getSteps(routineType).map { it.id }
            if (seededIds.isNotEmpty()) {
                selfCareDao.deleteStepsByStepIds(routineType, seededIds)
            }
            val sourceIds = SelfCareRoutines.getSteps(routineType).map { it.id }
            if (sourceIds.isNotEmpty()) {
                seedSelfCareSteps(routineType, sourceIds)
            }
        }
    }

    suspend fun addStep(
        routineType: String,
        label: String,
        duration: String,
        tier: String,
        note: String,
        phase: String,
        reminderDelayMillis: Long? = null,
        timeOfDay: String = "morning"
    ) {
        val nextOrder = selfCareDao.getMaxSortOrder(routineType) + 1
        val stepId = "custom_${UUID.randomUUID().toString().take(8)}"
        val id = selfCareDao.insertStep(
            SelfCareStepEntity(
                stepId = stepId,
                routineType = routineType,
                label = label,
                duration = duration,
                tier = tier,
                note = note,
                phase = phase,
                sortOrder = nextOrder,
                reminderDelayMillis = reminderDelayMillis,
                timeOfDay = timeOfDay,
                updatedAt = System.currentTimeMillis()
            )
        )
        syncTracker.trackCreate(id, "self_care_step")
    }

    suspend fun updateStep(step: SelfCareStepEntity) {
        selfCareDao.updateStep(step.copy(updatedAt = System.currentTimeMillis()))
        syncTracker.trackUpdate(step.id, "self_care_step")
    }

    suspend fun deleteStep(step: SelfCareStepEntity) {
        syncTracker.trackDelete(step.id, "self_care_step")
        selfCareDao.deleteStep(step)
    }

    suspend fun moveStep(step: SelfCareStepEntity, direction: Int) {
        val allSteps = selfCareDao.getStepsForRoutineOnce(step.routineType)
        val index = allSteps.indexOfFirst { it.id == step.id }
        if (index < 0) return
        val targetIndex = index + direction
        if (targetIndex < 0 || targetIndex >= allSteps.size) return

        val current = allSteps[index]
        val target = allSteps[targetIndex]
        val now = System.currentTimeMillis()
        selfCareDao.updateSteps(
            listOf(
                current.copy(sortOrder = target.sortOrder, updatedAt = now),
                target.copy(sortOrder = current.sortOrder, updatedAt = now)
            )
        )
        syncTracker.trackUpdate(current.id, "self_care_step")
        syncTracker.trackUpdate(target.id, "self_care_step")
    }

    fun getVisibleStepsFromEntities(
        steps: List<SelfCareStepEntity>,
        tier: String,
        routineType: String
    ): List<SelfCareStepEntity> {
        val tierOrder = SelfCareRoutines.getTierOrder(routineType)
        return steps.filter { SelfCareRoutines.tierIncludes(tierOrder, tier, it.tier) }
    }

    fun getPhaseGroupedSteps(
        steps: List<SelfCareStepEntity>,
        routineType: String
    ): List<Pair<String, List<SelfCareStepEntity>>> {
        if (routineType == "medication") {
            return if (steps.isEmpty()) emptyList() else listOf("Medications" to steps)
        }
        val phaseOrder = when (routineType) {
            "morning" -> listOf("Skincare", "Hygiene", "Grooming")
            "housework" -> listOf("Kitchen", "Living Areas", "Bathroom", "Laundry")
            else -> listOf("Wash", "Skincare", "Hygiene", "Sleep")
        }
        val grouped = steps.groupBy { it.phase }
        val result = mutableListOf<Pair<String, List<SelfCareStepEntity>>>()
        for (phase in phaseOrder) {
            grouped[phase]?.let { result.add(phase to it) }
        }
        // Include any custom phases not in the predefined order
        for ((phase, stepsInPhase) in grouped) {
            if (phase !in phaseOrder) {
                result.add(phase to stepsInPhase)
            }
        }
        return result
    }

    suspend fun setTier(routineType: String, tier: String) {
        val today = startOfToday()
        val existing = selfCareDao.getLogForDateOnce(routineType, today)
        if (existing != null) {
            if (routineType == "medication") {
                // Medication: don't clear logs when switching tier view
                val logs = parseMedStepLogs(existing.completedSteps)
                val dbSteps = selfCareDao.getStepsForRoutineOnce(routineType)
                val visibleSteps = getVisibleStepsFromEntities(dbSteps, tier, routineType)
                val allDone = allMedsFullyLogged(logs, visibleSteps)
                selfCareDao.updateLog(existing.copy(selectedTier = tier, isComplete = allDone, updatedAt = System.currentTimeMillis()))
                syncTracker.trackUpdate(existing.id, "self_care_log")
                syncHabitCompletion(routineType, allDone)
            } else {
                selfCareDao.updateLog(
                    existing.copy(
                        selectedTier = tier,
                        isComplete = false,
                        startedAt = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                syncTracker.trackUpdate(existing.id, "self_care_log")
                syncHabitCompletion(routineType, false)
            }
        } else {
            val id = selfCareDao.insertLog(
                SelfCareLogEntity(
                    routineType = routineType,
                    date = today,
                    selectedTier = tier,
                    updatedAt = System.currentTimeMillis()
                )
            )
            syncTracker.trackCreate(id, "self_care_log")
        }
    }

    suspend fun logTier(tier: String, note: String) {
        val routineType = "medication"
        val today = startOfToday()
        var existing = selfCareDao.getLogForDateOnce(routineType, today)
        if (existing == null) {
            selfCareDao.insertLog(
                SelfCareLogEntity(
                    routineType = routineType,
                    date = today,
                    selectedTier = tier,
                    startedAt = System.currentTimeMillis()
                )
            )
            existing = selfCareDao.getLogForDateOnce(routineType, today)
                ?: return
        }

        val logs = parseMedStepLogs(existing.completedSteps).toMutableList()
        val loggedIds = logs.map { it.id }.toSet()
        val dbSteps = selfCareDao.getStepsForRoutineOnce(routineType)
        val visibleSteps = getVisibleStepsFromEntities(dbSteps, tier, routineType)
        val now = System.currentTimeMillis()
        val trimmedNote = note.trim()

        for (step in visibleSteps) {
            if (step.stepId !in loggedIds) {
                logs.add(MedStepLog(id = step.stepId, note = trimmedNote, at = now))
            }
        }

        val activeTier = existing.selectedTier
        val activeVisible = getVisibleStepsFromEntities(dbSteps, activeTier, routineType)
        val allDone = allMedsFullyLogged(logs, activeVisible)

        selfCareDao.updateLog(
            existing.copy(
                completedSteps = serializeMedStepLogs(logs),
                isComplete = allDone,
                startedAt = existing.startedAt ?: now,
                updatedAt = now
            )
        )
        syncTracker.trackUpdate(existing.id, "self_care_log")
        syncHabitCompletion(routineType, allDone)

        // Schedule global medication reminder
        val intervalMinutes = medicationPreferences.getReminderIntervalMinutesOnce()
        if (intervalMinutes > 0) {
            scheduleMedicationReminder(now, intervalMinutes.toLong() * 60_000L)
        }
    }

    suspend fun unlogTier(tier: String) {
        val routineType = "medication"
        val today = startOfToday()
        val existing = selfCareDao.getLogForDateOnce(routineType, today) ?: return

        val logs = parseMedStepLogs(existing.completedSteps).toMutableList()
        val dbSteps = selfCareDao.getStepsForRoutineOnce(routineType)
        // Only remove steps that belong exactly to this tier (not cumulative)
        val tierStepIds = dbSteps.filter { it.tier == tier }.map { it.stepId }.toSet()

        val removed = logs.filter { it.id in tierStepIds }
        removed.forEach { cancelMedStepReminder(it.id) }
        logs.removeAll { it.id in tierStepIds }

        val activeTier = existing.selectedTier
        val activeVisible = getVisibleStepsFromEntities(dbSteps, activeTier, routineType)
        val allDone = allMedsFullyLogged(logs, activeVisible)

        selfCareDao.updateLog(
            existing.copy(
                completedSteps = serializeMedStepLogs(logs),
                isComplete = allDone,
                updatedAt = System.currentTimeMillis()
            )
        )
        syncTracker.trackUpdate(existing.id, "self_care_log")
        syncHabitCompletion(routineType, allDone)
    }

    suspend fun toggleStep(
        routineType: String,
        stepId: String,
        note: String? = null,
        timeOfDay: String = ""
    ) {
        val today = startOfToday()
        var existing = selfCareDao.getLogForDateOnce(routineType, today)
        if (existing == null) {
            selfCareDao.insertLog(
                SelfCareLogEntity(
                    routineType = routineType,
                    date = today,
                    selectedTier = resolveStartingTier(routineType),
                    startedAt = System.currentTimeMillis()
                )
            )
            existing = selfCareDao.getLogForDateOnce(routineType, today)
                ?: return
        }

        val isMedication = routineType == "medication"
        val completedStepsJson: String

        if (isMedication) {
            val logs = parseMedStepLogs(existing.completedSteps).toMutableList()
            val dbSteps = selfCareDao.getStepsForRoutineOnce(routineType)
            val step = dbSteps.firstOrNull { it.stepId == stepId }
            val stepTods = step?.let { SelfCareRoutines.parseTimeOfDay(it.timeOfDay) } ?: emptySet()
            val block = timeOfDay.trim()
            val wasCompleted = if (block.isEmpty()) {
                logs.any { it.id == stepId }
            } else {
                isMedLoggedAt(logs, stepId, block)
            }

            if (wasCompleted) {
                if (block.isEmpty()) {
                    // Legacy / ungrouped toggle: remove every log for this step.
                    logs.removeAll { it.id == stepId }
                } else {
                    // Expand any legacy (blank timeOfDay) log into explicit
                    // per-block logs for the step's other time blocks, then
                    // drop any log that targets the block being unchecked.
                    val legacyForStep = logs.filter { it.id == stepId && it.timeOfDay.isBlank() }
                    if (legacyForStep.isNotEmpty()) {
                        val template = legacyForStep.first()
                        logs.removeAll { it.id == stepId && it.timeOfDay.isBlank() }
                        for (tod in stepTods) {
                            if (tod == block) continue
                            if (!isMedLoggedAt(logs, stepId, tod)) {
                                logs.add(template.copy(timeOfDay = tod))
                            }
                        }
                    }
                    logs.removeAll { it.id == stepId && it.timeOfDay == block }
                }
            } else {
                val now = System.currentTimeMillis()
                logs.add(
                    MedStepLog(
                        id = stepId,
                        note = note?.trim() ?: "",
                        at = now,
                        timeOfDay = block
                    )
                )
            }

            val visibleSteps = getVisibleStepsFromEntities(dbSteps, existing.selectedTier, routineType)
            val allDone = allMedsFullyLogged(logs, visibleSteps)
            completedStepsJson = serializeMedStepLogs(logs)

            val updated = existing.copy(
                completedSteps = completedStepsJson,
                isComplete = allDone,
                startedAt = existing.startedAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            selfCareDao.updateLog(updated)
            syncTracker.trackUpdate(existing.id, "self_care_log")
            syncHabitCompletion(routineType, allDone)
        } else {
            // Non-medication routines: plain string ID list
            val steps = parseSteps(existing.completedSteps).toMutableSet()
            if (steps.contains(stepId)) {
                steps.remove(stepId)
            } else {
                steps.add(stepId)
            }

            val dbSteps = selfCareDao.getStepsForRoutineOnce(routineType)
            val visibleSteps = getVisibleStepsFromEntities(dbSteps, existing.selectedTier, routineType)
            val allDone = visibleSteps.isNotEmpty() && visibleSteps.all { it.stepId in steps }

            val updated = existing.copy(
                completedSteps = gson.toJson(steps.toList()),
                isComplete = allDone,
                startedAt = existing.startedAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            selfCareDao.updateLog(updated)
            syncTracker.trackUpdate(existing.id, "self_care_log")
            syncHabitCompletion(routineType, allDone)
        }
    }

    suspend fun resetToday(routineType: String) {
        val today = startOfToday()
        val existing = selfCareDao.getLogForDateOnce(routineType, today) ?: return
        if (routineType == "medication") {
            cancelMedicationReminder()
        }
        val updated = existing.copy(
            completedSteps = "[]",
            tiersByTime = if (routineType == "medication") "{}" else existing.tiersByTime,
            isComplete = false,
            startedAt = null,
            updatedAt = System.currentTimeMillis()
        )
        selfCareDao.updateLog(updated)
        syncTracker.trackUpdate(existing.id, "self_care_log")
        syncHabitCompletion(routineType, false)
    }

    private fun parseSteps(json: String): Set<String> = try {
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson<List<String>>(json, type).toSet()
    } catch (_: Exception) {
        emptySet()
    }

    fun parseMedStepLogs(json: String): List<MedStepLog> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val array = gson.fromJson(json, JsonArray::class.java)
            array.mapNotNull { element ->
                if (element.isJsonPrimitive) {
                    // Legacy format: plain string step ID
                    MedStepLog(id = element.asString)
                } else if (element.isJsonObject) {
                    val obj = element.asJsonObject
                    MedStepLog(
                        id = obj.get("id")?.asString ?: return@mapNotNull null,
                        note = obj.get("note")?.asString ?: "",
                        at = obj.get("at")?.asLong ?: 0L,
                        timeOfDay = obj.get("timeOfDay")?.asString ?: ""
                    )
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * True if there is a log entry for this step in the given time-of-day.
     * A legacy log (blank timeOfDay) counts as logged for every block the
     * step is scheduled for.
     */
    fun isMedLoggedAt(
        logs: List<MedStepLog>,
        stepId: String,
        timeOfDay: String
    ): Boolean = logs.any { it.id == stepId && (it.timeOfDay == timeOfDay || it.timeOfDay.isBlank()) }

    /**
     * True if every visible medication is logged for every one of its
     * scheduled time-of-day blocks.
     */
    private fun allMedsFullyLogged(
        logs: List<MedStepLog>,
        visibleSteps: List<SelfCareStepEntity>
    ): Boolean {
        if (visibleSteps.isEmpty()) return false
        for (step in visibleSteps) {
            val tods = SelfCareRoutines.parseTimeOfDay(step.timeOfDay)
            if (tods.isEmpty()) {
                if (logs.none { it.id == step.stepId }) return false
            } else {
                for (tod in tods) {
                    if (!isMedLoggedAt(logs, step.stepId, tod)) return false
                }
            }
        }
        return true
    }

    fun parseTiersByTime(json: String): Map<String, String> {
        if (json.isBlank() || json == "{}") return emptyMap()
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java) ?: return emptyMap()
            obj.entrySet().associate { (k, v) -> k to v.asString }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun serializeTiersByTime(map: Map<String, String>): String {
        val obj = JsonObject()
        for ((k, v) in map) obj.addProperty(k, v)
        return gson.toJson(obj)
    }

    /**
     * D8 Item 8 dual-write helper. After the legacy `tiers_by_time` JSON has
     * been persisted, mirror the resulting state into `medication_tier_states`
     * for forward consumers.
     *
     * Per-block (D8 v1.7 schema, migration 77→78):
     *  - One row per `(active_med, DEFAULT slot, today, time_of_day, tier)`
     *    is upserted for every entry in [updatedTiersByTime]. The
     *    `time_of_day` column carries the block identity that legacy rows
     *    (NULL) lost in MIGRATION_59_60.
     *  - Blocks the user CLEARED (present in the previous map but not in
     *    the updated map) are deleted from the table — otherwise stale
     *    per-block rows would inflate the reader's completed-block count.
     *  - Existing per-block rows update in place (LWW); legacy NULL rows
     *    remain untouched (they're the migration-59-60 backfill).
     *
     * Tolerant by design: caller wraps in `runCatching {}`. Missing
     * DEFAULT slot or zero active meds returns silently.
     */
    private suspend fun dualWriteMedicationTierStates(updatedTiersByTime: Map<String, String>) {
        val tierOrder = SelfCareRoutines.getTierOrder("medication")
        val maxTier = updatedTiersByTime.values
            .filter { it in tierOrder }
            .maxByOrNull { tierOrder.indexOf(it) }
            ?: "skipped"
        val defaultSlot = medicationSlotDao.getByNameOnce("Default") ?: return
        val activeMeds = medicationDao.getActiveOnce()
        if (activeMeds.isEmpty()) return
        val date = todayLocalString()
        val now = System.currentTimeMillis()
        // Snapshot every active med's per-block rows for today; entries
        // missing from `updatedTiersByTime` get deleted so cleared blocks
        // disappear from the reader's count.
        val managedBlocks = updatedTiersByTime.keys
        for (med in activeMeds) {
            // 1. Delete per-block rows for blocks no longer in the map.
            val existingForSlotDate = medicationTierStateDao
                .getForSlotDateOnce(defaultSlot.id, date)
                .filter { it.medicationId == med.id && it.timeOfDay != null }
            for (row in existingForSlotDate) {
                if (row.timeOfDay !in managedBlocks) {
                    medicationTierStateDao.deleteById(row.id)
                }
            }
            // 2. Upsert one row per managed block.
            for ((tod, tier) in updatedTiersByTime) {
                if (tier !in tierOrder) continue
                val existing = medicationTierStateDao.getForQuadrupleOnce(
                    medicationId = med.id,
                    date = date,
                    slotId = defaultSlot.id,
                    timeOfDay = tod
                )
                if (existing == null) {
                    medicationTierStateDao.insert(
                        MedicationTierStateEntity(
                            medicationId = med.id,
                            slotId = defaultSlot.id,
                            logDate = date,
                            timeOfDay = tod,
                            tier = tier,
                            tierSource = "computed",
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                } else if (existing.tier != tier) {
                    medicationTierStateDao.update(
                        existing.copy(tier = tier, updatedAt = now)
                    )
                }
            }
        }
    }

    /**
     * Set or clear the medication tier picked for a specific time-of-day.
     * Pass [tier] = null to clear the selection for that time-of-day.
     *
     * Logging behavior: any med whose time_of_day includes [timeOfDay] AND whose
     * tier is included in the selected tier (cumulative) becomes logged. Meds in
     * managed time-of-days that don't satisfy any selection are unlogged. Logs
     * for meds in time-of-days that aren't currently managed are preserved.
     */
    suspend fun setTierForTime(timeOfDay: String, tier: String?) {
        val routineType = "medication"
        val today = startOfToday()
        var existing = selfCareDao.getLogForDateOnce(routineType, today)
        if (existing == null) {
            selfCareDao.insertLog(
                SelfCareLogEntity(
                    routineType = routineType,
                    date = today,
                    selectedTier = resolveStartingTier(routineType),
                    startedAt = System.currentTimeMillis()
                )
            )
            existing = selfCareDao.getLogForDateOnce(routineType, today)
                ?: return
        }

        val tiersByTime = parseTiersByTime(existing.tiersByTime).toMutableMap()
        if (tier.isNullOrBlank()) {
            tiersByTime.remove(timeOfDay)
        } else {
            tiersByTime[timeOfDay] = tier
        }

        val dbSteps = selfCareDao.getStepsForRoutineOnce(routineType)
        val existingLogs = parseMedStepLogs(existing.completedSteps)
        val now = System.currentTimeMillis()

        val resultLogs = reconcileMedLogsForTierChange(
            steps = dbSteps,
            existingLogs = existingLogs,
            tiersByTime = tiersByTime,
            touchedTod = timeOfDay,
            now = now
        )

        // "Done" mirrors the MedicationScreen: every time-of-day block that has
        // any meds scheduled must have a tier selected. Using the per-block
        // tier selection here keeps the Today chip, the medication screen
        // header, and the habit completion in lockstep — otherwise the habit
        // could be logged as "not done" even though the user had checked off
        // every block in the UI.
        val timeGroupsWithMeds = SelfCareRoutines.timesOfDay
            .map { it.id }
            .filter { tod -> dbSteps.any { step -> tod in SelfCareRoutines.parseTimeOfDay(step.timeOfDay) } }
        val allDone = timeGroupsWithMeds.isNotEmpty() &&
            timeGroupsWithMeds.all { it in tiersByTime.keys }

        selfCareDao.updateLog(
            existing.copy(
                tiersByTime = serializeTiersByTime(tiersByTime),
                completedSteps = serializeMedStepLogs(resultLogs),
                isComplete = allDone,
                startedAt = existing.startedAt ?: now,
                updatedAt = now
            )
        )
        syncTracker.trackUpdate(existing.id, "self_care_log")
        syncHabitCompletion(routineType, allDone)

        // D8 Item 8 dual-write: mirror the just-persisted JSON state into
        // `medication_tier_states` for forward consumers. Failure here must
        // never block the legacy write, so any DAO miss / FK violation is
        // swallowed — JSON column remains source of truth.
        runCatching { dualWriteMedicationTierStates(tiersByTime) }

        val intervalMinutes = medicationPreferences.getReminderIntervalMinutesOnce()
        if (!tier.isNullOrBlank() && intervalMinutes > 0) {
            scheduleMedicationReminder(now, intervalMinutes.toLong() * 60_000L)
        }
    }

    private fun serializeMedStepLogs(logs: List<MedStepLog>): String {
        val array = JsonArray()
        for (log in logs) {
            val obj = JsonObject()
            obj.addProperty("id", log.id)
            obj.addProperty("note", log.note)
            obj.addProperty("at", log.at)
            obj.addProperty("timeOfDay", log.timeOfDay)
            array.add(obj)
        }
        return gson.toJson(array)
    }

    // Kept for future per-step med reminder wiring; callers currently use
    // MedicationReminderScheduler instead. Suppressed until that wiring lands.
    @Suppress("UnusedPrivateMember")
    private fun scheduleMedStepReminder(step: SelfCareStepEntity, loggedAt: Long) {
        val delay = step.reminderDelayMillis ?: return
        val triggerTime = loggedAt + delay
        val intent = Intent(context, MedStepReminderReceiver::class.java).apply {
            putExtra("stepId", step.stepId)
            putExtra("medName", step.label)
            putExtra("medNote", step.note)
        }
        val requestCode = step.stepId.hashCode() + 400_000
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        ExactAlarmHelper.scheduleExact(context, triggerTime, pendingIntent)
    }

    private fun cancelMedStepReminder(stepId: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, MedStepReminderReceiver::class.java)
        val requestCode = stepId.hashCode() + 400_000
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun scheduleMedicationReminder(loggedAt: Long, intervalMillis: Long) {
        val triggerTime = maxOf(loggedAt + intervalMillis, System.currentTimeMillis() + 1000)
        val intent = Intent(context, MedStepReminderReceiver::class.java).apply {
            putExtra("stepId", "medication_global")
            putExtra("medName", "Medication Reminder")
            putExtra("medNote", "Time to take your next medications")
        }
        val requestCode = GLOBAL_MED_REMINDER_REQUEST_CODE
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        ExactAlarmHelper.scheduleExact(context, triggerTime, pendingIntent)
    }

    fun cancelMedicationReminder() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, MedStepReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            GLOBAL_MED_REMINDER_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    suspend fun ensureHabitsExist() {
        getOrCreateHabit("morning")
        getOrCreateHabit("bedtime")
        // Medication is no longer auto-seeded as a built-in habit — v1.4's
        // medication-top-level refactor moved it to its own entity
        // (MedicationEntity). Existing users still have the "Medication"
        // habit row in their DB; it stays until Phase 2 cleanup.
        getOrCreateHabit("housework")
    }

    private suspend fun getOrCreateHabit(routineType: String): HabitEntity {
        val name = when (routineType) {
            "morning" -> MORNING_HABIT_NAME
            "medication" -> MEDICATION_HABIT_NAME
            "housework" -> HOUSEWORK_HABIT_NAME
            else -> BEDTIME_HABIT_NAME
        }
        val existing = habitDao.getHabitByName(name)
        if (existing != null) return existing

        val icon = when (routineType) {
            "morning" -> "\u2600\uFE0F"
            "medication" -> "\uD83D\uDC8A"
            "housework" -> "\uD83C\uDFE0"
            else -> "\uD83C\uDF19"
        }
        val color = when (routineType) {
            "morning" -> "#F59E0B"
            "medication" -> "#EF4444"
            "housework" -> "#10B981"
            else -> "#8B5CF6"
        }
        val category = when (routineType) {
            "medication" -> "Medication"
            "housework" -> "Housework"
            else -> "Self-Care"
        }
        val desc = when (routineType) {
            "morning" -> "Complete morning self-care routine"
            "medication" -> "Take all daily medications"
            "housework" -> "Complete daily housework routine"
            else -> "Complete bedtime self-care routine"
        }

        val templateKey = when (routineType) {
            "morning" -> "builtin_morning_selfcare"
            "medication" -> "builtin_medication"
            "housework" -> "builtin_housework"
            else -> "builtin_bedtime_selfcare"
        }
        val id = habitDao.insert(
            HabitEntity(
                name = name,
                description = desc,
                icon = icon,
                color = color,
                category = category,
                targetFrequency = 1,
                frequencyPeriod = "daily",
                isBuiltIn = true,
                templateKey = templateKey,
                showStreak = true
            )
        )
        return habitDao.getHabitByIdOnce(id)
            ?: error("Habit not found after insert")
    }

    private suspend fun syncHabitCompletion(routineType: String, allDone: Boolean) {
        val habit = getOrCreateHabit(routineType)
        val today = startOfToday()
        val todayLocal = todayLocalString()
        val alreadyCompleted = habitCompletionDao.isCompletedOnDateLocalOnce(habit.id, todayLocal)

        if (allDone && !alreadyCompleted) {
            habitCompletionDao.insert(
                HabitCompletionEntity(
                    habitId = habit.id,
                    completedDate = today,
                    completedAt = System.currentTimeMillis(),
                    completedDateLocal = todayLocal
                )
            )
        } else if (!allDone && alreadyCompleted) {
            habitCompletionDao.deleteByHabitAndDateLocal(habit.id, todayLocal)
        }
    }

    suspend fun sortStepsByPhaseOrder(routineType: String, phaseOrder: List<String>) {
        val steps = selfCareDao.getStepsForRoutineOnce(routineType)
        val sorted = steps.sortedWith(
            compareBy<SelfCareStepEntity> {
                val idx = phaseOrder.indexOf(it.phase)
                if (idx >= 0) idx else phaseOrder.size
            }.thenBy { it.sortOrder }
        )

        val now = System.currentTimeMillis()
        val updated = sorted.mapIndexed { i, step -> step.copy(sortOrder = i, updatedAt = now) }
        selfCareDao.updateSteps(updated)
        updated.forEach { syncTracker.trackUpdate(it.id, "self_care_step") }
    }

    fun computeTierTimes(
        steps: List<SelfCareStepEntity>,
        routineType: String
    ): Map<String, String> {
        val tierOrder = SelfCareRoutines.getTierOrder(routineType)
        return tierOrder.associateWith { tier ->
            val visible = steps.filter { SelfCareRoutines.tierIncludes(tierOrder, tier, it.tier) }
            val totalSeconds = visible.sumOf { parseDurationSeconds(it.duration) }
            formatDuration(totalSeconds)
        }
    }

    companion object {
        const val MORNING_HABIT_NAME = "Morning Self-Care"
        const val BEDTIME_HABIT_NAME = "Bedtime Self-Care"
        const val MEDICATION_HABIT_NAME = "Medication"
        const val HOUSEWORK_HABIT_NAME = "Housework"
        private const val GLOBAL_MED_REMINDER_REQUEST_CODE = 500_000

        private fun parseDurationSeconds(duration: String): Int {
            val cleaned = duration
                .replace("~", "")
                .replace("+", "")
                .trim()
                .lowercase()
            val minMatch = Regex("""(\d+)\s*min""").find(cleaned)
            val secMatch = Regex("""(\d+)\s*sec""").find(cleaned)
            val mins = minMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val secs = secMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return mins * 60 + secs
        }

        private fun formatDuration(totalSeconds: Int): String = when {
            totalSeconds >= 60 -> "~${totalSeconds / 60} min"
            totalSeconds > 0 -> "~$totalSeconds sec"
            else -> "0 min"
        }
    }
}
