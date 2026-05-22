package com.averycorp.prismtask.data.remote.sync

import com.averycorp.prismtask.data.local.dao.*
import com.averycorp.prismtask.data.local.entity.*
import com.averycorp.prismtask.data.remote.mapper.*
import com.averycorp.prismtask.data.remote.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncPusher @Inject constructor(
    private val authManager: AuthManager,
    private val firestore: FirebaseFirestore,
    private val pushOrchestrator: SyncPushOrchestrator,
    private val syncMetadataDao: SyncMetadataDao,
    private val logger: PrismSyncLogger,
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val habitDao: HabitDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val habitLogDao: HabitLogDao,
    private val taskTemplateDao: TaskTemplateDao,
    private val milestoneDao: MilestoneDao,
    private val taskCompletionDao: TaskCompletionDao,
    private val taskTimingDao: TaskTimingDao,
    private val medicationDao: MedicationDao,
    private val medicationDoseDao: MedicationDoseDao,
    private val medicationSlotDao: MedicationSlotDao,
    private val medicationSlotOverrideDao: MedicationSlotOverrideDao,
    private val medicationTierStateDao: MedicationTierStateDao,
    private val schoolworkDao: SchoolworkDao,
    private val selfCareDao: SelfCareDao,
    private val notificationProfileDao: NotificationProfileDao,
    private val customSoundDao: CustomSoundDao,
    private val savedFilterDao: SavedFilterDao,
    private val nlpShortcutDao: NlpShortcutDao,
    private val habitTemplateDao: HabitTemplateDao,
    private val projectTemplateDao: ProjectTemplateDao,
    private val boundaryRuleDao: BoundaryRuleDao,
    private val automationRuleDao: AutomationRuleDao,
    private val checkInLogDao: CheckInLogDao,
    private val moodEnergyLogDao: MoodEnergyLogDao,
    private val focusReleaseLogDao: FocusReleaseLogDao,
    private val medicationRefillDao: MedicationRefillDao,
    private val weeklyReviewDao: WeeklyReviewDao,
    private val dailyEssentialSlotCompletionDao: DailyEssentialSlotCompletionDao,
    private val attachmentDao: AttachmentDao,
    private val projectPhaseDao: ProjectPhaseDao,
    private val projectRiskDao: ProjectRiskDao,
    private val taskDependencyDao: TaskDependencyDao,
    private val externalAnchorDao: ExternalAnchorDao
) {
    private fun userCollection(collection: String) =
        authManager.userId?.let { firestore.collection("users").document(it).collection(collection) }

    private fun collectionNameFor(entityType: String): String =
        SyncDispatchTables.collectionNameFor(entityType)

    private fun tierStateDeterministicDocId(
        medCloudId: String,
        logDate: String,
        slotCloudId: String
    ): String = "${medCloudId}__${logDate}__${slotCloudId}"

    private fun courseCompletionDeterministicDocId(
        courseCloudId: String,
        date: Long
    ): String = "${courseCloudId}__$date"

    suspend fun pushLocalChanges(processRemoteDeletions: suspend (String, List<String>) -> Unit): Int =
        pushOrchestrator.pushAllPending { meta ->
            when (meta.pendingAction) {
                "create" -> pushCreate(meta, processRemoteDeletions)
                "update" -> pushUpdate(meta, processRemoteDeletions)
                "delete" -> pushDelete(meta)
            }
        }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    // Dispatch across every synced entityType — splitting the `when` is not
    // worth the indirection since each branch is only a DAO lookup + mapper
    // call. TODO: refactor pushCreate to reduce early return statements.
    private suspend fun pushCreate(meta: SyncMetadataEntity, processRemoteDeletions: suspend (String, List<String>) -> Unit) {
        val collection = userCollection(collectionNameFor(meta.entityType)) ?: return
        // Deterministic doc id for medication_tier_state (parity Batch 5
        // PR-8, decision D-E3). Two devices toggling the same
        // (medication, slot, day) collapse into one doc rather than
        // racing on `collection.document()` auto-ids. The id shape is
        // `${medCloudId}__${logDate}__${slotCloudId}` — read order:
        // 1. fetch state row → resolve med + slot cloud ids
        // 2. mint the doc ref by id (not auto)
        // 3. setDoc(merge=true) so retries are idempotent
        // No Room migration; the cloud_id column already exists.
        if (meta.entityType == "medication_tier_state") {
            val state = medicationTierStateDao.getByIdOnce(meta.localId) ?: return
            val medCloudId = syncMetadataDao.getCloudId(state.medicationId, "medication") ?: return
            val slotCloudId = syncMetadataDao.getCloudId(state.slotId, "medication_slot") ?: return
            val detId = tierStateDeterministicDocId(medCloudId, state.logDate, slotCloudId)
            val payload = MedicationSyncMapper.medicationTierStateToMap(state, medCloudId, slotCloudId)
            val tierDoc = collection.document(detId)
            tierDoc.set(payload, SetOptions.merge()).await()
            syncMetadataDao.upsert(meta.copy(cloudId = tierDoc.id, pendingAction = null, lastSyncedAt = System.currentTimeMillis()))
            return
        }
        // Deterministic doc id for course_completions — mirrors web's
        // `${courseCloudId}__${date}` shape (see
        // `web/src/api/firestore/courseCompletions.ts`) so two devices
        // toggling the same (course, day) pair converge on one Firestore
        // doc instead of racing on an auto-id and producing duplicates
        // (which the pull-side `(courseId, date)` UNIQUE index would
        // then collide on).
        if (meta.entityType == "course_completion") {
            val completion = schoolworkDao.getAllCompletionsOnce().find { it.id == meta.localId } ?: return
            val courseCloudId = syncMetadataDao.getCloudId(completion.courseId, "course") ?: return
            val detId = courseCompletionDeterministicDocId(courseCloudId, completion.date)
            val payload = SyncMapper.courseCompletionToMap(completion, courseCloudId)
            val completionDoc = collection.document(detId)
            completionDoc.set(payload, SetOptions.merge()).await()
            syncMetadataDao.upsert(
                meta.copy(
                    cloudId = completionDoc.id,
                    pendingAction = null,
                    lastSyncedAt = System.currentTimeMillis()
                )
            )
            return
        }
        val docRef = collection.document()
        val data = when (meta.entityType) {
            "task" -> {
                val task = taskDao.getTaskByIdOnce(meta.localId) ?: return
                val tagIds = tagDao.getTagIdsForTaskOnce(task.id).mapNotNull { syncMetadataDao.getCloudId(it, "tag") }
                val projectCloudId = task.projectId?.let { syncMetadataDao.getCloudId(it, "project") }
                val parentTaskCloudId = task.parentTaskId?.let { syncMetadataDao.getCloudId(it, "task") }
                val sourceHabitCloudId = task.sourceHabitId?.let { syncMetadataDao.getCloudId(it, "habit") }
                val phaseCloudId = task.phaseId?.let { syncMetadataDao.getCloudId(it, "project_phase") }
                SyncMapper.taskToMap(task, tagIds, projectCloudId, parentTaskCloudId, sourceHabitCloudId, phaseCloudId)
            }
            "project" -> {
                val project = projectDao.getProjectByIdOnce(meta.localId) ?: return
                SyncMapper.projectToMap(project)
            }
            "tag" -> {
                val tag = tagDao.getTagByIdOnce(meta.localId) ?: return
                SyncMapper.tagToMap(tag)
            }
            "habit" -> {
                val habit = habitDao.getHabitByIdOnce(meta.localId) ?: return
                SyncMapper.habitToMap(habit)
            }
            "habit_completion" -> {
                val completion = habitCompletionDao.getAllCompletionsOnce().find { it.id == meta.localId }
                if (completion == null) {
                    logger.error(
                        operation = "push.create",
                        entity = "habit_completion",
                        id = meta.localId.toString(),
                        status = "error",
                        detail = "completion not found for localId=${meta.localId}"
                    )
                    return
                }
                val habitCloudId = syncMetadataDao.getCloudId(completion.habitId, "habit") ?: return
                SyncMapper.habitCompletionToMap(completion, habitCloudId)
            }
            "habit_log" -> {
                val logs = habitLogDao.getAllLogsOnce()
                val log = logs.find { it.id == meta.localId } ?: return
                val habitCloudId = syncMetadataDao.getCloudId(log.habitId, "habit") ?: return
                SyncMapper.habitLogToMap(log, habitCloudId)
            }
            "task_completion" -> {
                val completion = taskCompletionDao.getAllCompletionsOnce().find { it.id == meta.localId }
                    ?: return
                val taskCloudId = completion.taskId?.let { syncMetadataDao.getCloudId(it, "task") }
                val projectCloudId = completion.projectId?.let { syncMetadataDao.getCloudId(it, "project") }
                SyncMapper.taskCompletionToMap(completion, taskCloudId, projectCloudId)
            }
            "task_timing" -> {
                val timing = taskTimingDao.getByIdOnce(meta.localId) ?: return
                val taskCloudId = syncMetadataDao.getCloudId(timing.taskId, "task")
                SyncMapper.taskTimingToMap(timing, taskCloudId)
            }
            "task_template" -> {
                val template = taskTemplateDao.getTemplateById(meta.localId) ?: return
                val templateProjectCloudId = template.templateProjectId?.let { syncMetadataDao.getCloudId(it, "project") }
                SyncMapper.taskTemplateToMap(template, templateProjectCloudId)
            }
            "course" -> {
                val course = schoolworkDao.getCourseById(meta.localId) ?: return
                SyncMapper.courseToMap(course)
            }
            "course_completion" -> {
                val completion = schoolworkDao.getAllCompletionsOnce().find { it.id == meta.localId } ?: return
                val courseCloudId = syncMetadataDao.getCloudId(completion.courseId, "course") ?: return
                SyncMapper.courseCompletionToMap(completion, courseCloudId)
            }
            "self_care_step" -> {
                val step = selfCareDao.getAllStepsOnce().find { it.id == meta.localId } ?: return
                SyncMapper.selfCareStepToMap(step)
            }
            "self_care_log" -> {
                val log = selfCareDao.getAllLogsOnce().find { it.id == meta.localId } ?: return
                SyncMapper.selfCareLogToMap(log)
            }
            "medication" -> {
                val med = medicationDao.getByIdOnce(meta.localId) ?: return
                val slotCloudIds = medicationSlotDao.getSlotIdsForMedicationOnce(med.id)
                    .mapNotNull { syncMetadataDao.getCloudId(it, "medication_slot") }
                MedicationSyncMapper.medicationToMap(med, slotCloudIds)
            }
            "medication_dose" -> {
                val dose = medicationDoseDao.getAllOnce().find { it.id == meta.localId } ?: return
                val medCloudId = if (dose.medicationId == null) {
                    null
                } else {
                    syncMetadataDao.getCloudId(dose.medicationId, "medication") ?: return
                }
                MedicationSyncMapper.medicationDoseToMap(dose, medCloudId)
            }
            "medication_slot" -> {
                val slot = medicationSlotDao.getByIdOnce(meta.localId) ?: return
                MedicationSyncMapper.medicationSlotToMap(slot)
            }
            "medication_slot_override" -> {
                val override = medicationSlotOverrideDao.getByIdOnce(meta.localId) ?: return
                val medCloudId = syncMetadataDao.getCloudId(override.medicationId, "medication") ?: return
                val slotCloudId = syncMetadataDao.getCloudId(override.slotId, "medication_slot") ?: return
                MedicationSyncMapper.medicationSlotOverrideToMap(override, medCloudId, slotCloudId)
            }
            "medication_tier_state" -> {
                val state = medicationTierStateDao.getByIdOnce(meta.localId) ?: return
                val medCloudId = syncMetadataDao.getCloudId(state.medicationId, "medication") ?: return
                val slotCloudId = syncMetadataDao.getCloudId(state.slotId, "medication_slot") ?: return
                MedicationSyncMapper.medicationTierStateToMap(state, medCloudId, slotCloudId)
            }
            "notification_profile" -> {
                val profile = notificationProfileDao.getById(meta.localId) ?: return
                SyncMapper.notificationProfileToMap(profile)
            }
            "custom_sound" -> {
                val sound = customSoundDao.getById(meta.localId) ?: return
                SyncMapper.customSoundToMap(sound)
            }
            "saved_filter" -> {
                val filter = savedFilterDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.savedFilterToMap(filter)
            }
            "nlp_shortcut" -> {
                val shortcut = nlpShortcutDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.nlpShortcutToMap(shortcut)
            }
            "habit_template" -> {
                val template = habitTemplateDao.getById(meta.localId) ?: return
                SyncMapper.habitTemplateToMap(template)
            }
            "project_template" -> {
                val template = projectTemplateDao.getById(meta.localId) ?: return
                SyncMapper.projectTemplateToMap(template)
            }
            "boundary_rule" -> {
                val rule = boundaryRuleDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.boundaryRuleToMap(rule)
            }
            "automation_rule" -> {
                val rule = automationRuleDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.automationRuleToMap(rule)
            }
            "check_in_log" -> {
                val log = checkInLogDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.checkInLogToMap(log)
            }
            "mood_energy_log" -> {
                val log = moodEnergyLogDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.moodEnergyLogToMap(log)
            }
            "focus_release_log" -> {
                val log = focusReleaseLogDao.getByIdOnce(meta.localId) ?: return
                val taskCloudId = log.taskId?.let { syncMetadataDao.getCloudId(it, "task") }
                SyncMapper.focusReleaseLogToMap(log, taskCloudId)
            }
            "medication_refill" -> {
                val refill = medicationRefillDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.medicationRefillToMap(refill)
            }
            "weekly_review" -> {
                val review = weeklyReviewDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.weeklyReviewToMap(review)
            }
            // `daily_essential_slot_completion` Firestore push removed
            // in parity Batch 5 PR-9 (decision D-E4). BackendSyncService
            // is authoritative.
            "assignment" -> {
                val assignment = schoolworkDao.getAssignmentById(meta.localId) ?: return
                val courseCloudId = syncMetadataDao.getCloudId(assignment.courseId, "course")
                    ?: return // course not yet synced — retry on next pass
                SyncMapper.assignmentToMap(assignment, courseCloudId)
            }
            "attachment" -> {
                val attachment = attachmentDao.getByIdOnce(meta.localId) ?: return
                val taskCloudId = syncMetadataDao.getCloudId(attachment.taskId, "task")
                    ?: return // parent task not yet synced — retry on next pass
                SyncMapper.attachmentToMap(attachment, taskCloudId)
            }
            "study_log" -> {
                val log = schoolworkDao.getStudyLogByIdOnce(meta.localId) ?: return
                val coursePickCloudId = log.coursePick?.let { syncMetadataDao.getCloudId(it, "course") }
                val assignmentPickCloudId = log.assignmentPick?.let {
                    syncMetadataDao.getCloudId(it, "assignment")
                }
                SyncMapper.studyLogToMap(log, coursePickCloudId, assignmentPickCloudId)
            }
            "project_phase" -> {
                val phase = projectPhaseDao.getByIdOnce(meta.localId) ?: return
                val projectCloudId = syncMetadataDao.getCloudId(phase.projectId, "project")
                    ?: return // parent project not yet synced — retry on next pass
                SyncMapper.projectPhaseToMap(phase, projectCloudId)
            }
            "project_risk" -> {
                val risk = projectRiskDao.getByIdOnce(meta.localId) ?: return
                val projectCloudId = syncMetadataDao.getCloudId(risk.projectId, "project")
                    ?: return
                SyncMapper.projectRiskToMap(risk, projectCloudId)
            }
            "task_dependency" -> {
                val dep = taskDependencyDao.getByIdOnce(meta.localId) ?: return
                val blockerCloudId =
                    syncMetadataDao.getCloudId(dep.blockerTaskId, "task") ?: return
                val blockedCloudId =
                    syncMetadataDao.getCloudId(dep.blockedTaskId, "task") ?: return
                SyncMapper.taskDependencyToMap(dep, blockerCloudId, blockedCloudId)
            }
            "external_anchor" -> {
                val anchor = externalAnchorDao.getByIdOnce(meta.localId) ?: return
                val projectCloudId = syncMetadataDao.getCloudId(anchor.projectId, "project")
                    ?: return
                val phaseCloudId = anchor.phaseId?.let {
                    syncMetadataDao.getCloudId(it, "project_phase")
                }
                SyncMapper.externalAnchorToMap(anchor, projectCloudId, phaseCloudId)
            }
            else -> return
        }
        docRef.set(data).await()
        syncMetadataDao.upsert(meta.copy(cloudId = docRef.id, pendingAction = null, lastSyncedAt = System.currentTimeMillis()))
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    // Dispatch across every synced entityType — see pushCreate for the same
    // trade-off. TODO: refactor pushUpdate to reduce early return statements.
    private suspend fun pushUpdate(meta: SyncMetadataEntity, processRemoteDeletions: suspend (String, List<String>) -> Unit) {
        if (meta.cloudId.isEmpty()) {
            pushCreate(meta, processRemoteDeletions)
            return
        }
        val docRef = userCollection(collectionNameFor(meta.entityType))?.document(meta.cloudId) ?: return
        val data = when (meta.entityType) {
            "task" -> {
                val task = taskDao.getTaskByIdOnce(meta.localId) ?: return
                val tagIds = tagDao.getTagIdsForTaskOnce(task.id).mapNotNull { syncMetadataDao.getCloudId(it, "tag") }
                val projectCloudId = task.projectId?.let { syncMetadataDao.getCloudId(it, "project") }
                val parentTaskCloudId = task.parentTaskId?.let { syncMetadataDao.getCloudId(it, "task") }
                val sourceHabitCloudId = task.sourceHabitId?.let { syncMetadataDao.getCloudId(it, "habit") }
                val phaseCloudId = task.phaseId?.let { syncMetadataDao.getCloudId(it, "project_phase") }
                SyncMapper.taskToMap(task, tagIds, projectCloudId, parentTaskCloudId, sourceHabitCloudId, phaseCloudId)
            }
            "project" -> {
                val project = projectDao.getProjectByIdOnce(meta.localId) ?: return
                SyncMapper.projectToMap(project)
            }
            "tag" -> {
                val tag = tagDao.getTagByIdOnce(meta.localId) ?: return
                SyncMapper.tagToMap(tag)
            }
            "habit" -> {
                val habit = habitDao.getHabitByIdOnce(meta.localId) ?: return
                SyncMapper.habitToMap(habit)
            }
            "task_template" -> {
                val template = taskTemplateDao.getTemplateById(meta.localId) ?: return
                val templateProjectCloudId = template.templateProjectId?.let { syncMetadataDao.getCloudId(it, "project") }
                SyncMapper.taskTemplateToMap(template, templateProjectCloudId)
            }
            "course" -> {
                val course = schoolworkDao.getCourseById(meta.localId) ?: return
                SyncMapper.courseToMap(course)
            }
            "course_completion" -> {
                val completion = schoolworkDao.getAllCompletionsOnce().find { it.id == meta.localId } ?: return
                val courseCloudId = syncMetadataDao.getCloudId(completion.courseId, "course") ?: return
                SyncMapper.courseCompletionToMap(completion, courseCloudId)
            }
            "self_care_step" -> {
                val step = selfCareDao.getAllStepsOnce().find { it.id == meta.localId } ?: return
                SyncMapper.selfCareStepToMap(step)
            }
            "self_care_log" -> {
                val log = selfCareDao.getAllLogsOnce().find { it.id == meta.localId } ?: return
                SyncMapper.selfCareLogToMap(log)
            }
            "medication" -> {
                val med = medicationDao.getByIdOnce(meta.localId) ?: return
                val slotCloudIds = medicationSlotDao.getSlotIdsForMedicationOnce(med.id)
                    .mapNotNull { syncMetadataDao.getCloudId(it, "medication_slot") }
                MedicationSyncMapper.medicationToMap(med, slotCloudIds)
            }
            "medication_dose" -> {
                val dose = medicationDoseDao.getAllOnce().find { it.id == meta.localId } ?: return
                val medCloudId = if (dose.medicationId == null) {
                    null
                } else {
                    syncMetadataDao.getCloudId(dose.medicationId, "medication") ?: return
                }
                MedicationSyncMapper.medicationDoseToMap(dose, medCloudId)
            }
            "medication_slot" -> {
                val slot = medicationSlotDao.getByIdOnce(meta.localId) ?: return
                MedicationSyncMapper.medicationSlotToMap(slot)
            }
            "medication_slot_override" -> {
                val override = medicationSlotOverrideDao.getByIdOnce(meta.localId) ?: return
                val medCloudId = syncMetadataDao.getCloudId(override.medicationId, "medication") ?: return
                val slotCloudId = syncMetadataDao.getCloudId(override.slotId, "medication_slot") ?: return
                MedicationSyncMapper.medicationSlotOverrideToMap(override, medCloudId, slotCloudId)
            }
            "medication_tier_state" -> {
                val state = medicationTierStateDao.getByIdOnce(meta.localId) ?: return
                val medCloudId = syncMetadataDao.getCloudId(state.medicationId, "medication") ?: return
                val slotCloudId = syncMetadataDao.getCloudId(state.slotId, "medication_slot") ?: return
                MedicationSyncMapper.medicationTierStateToMap(state, medCloudId, slotCloudId)
            }
            "notification_profile" -> {
                val profile = notificationProfileDao.getById(meta.localId) ?: return
                SyncMapper.notificationProfileToMap(profile)
            }
            "custom_sound" -> {
                val sound = customSoundDao.getById(meta.localId) ?: return
                SyncMapper.customSoundToMap(sound)
            }
            "saved_filter" -> {
                val filter = savedFilterDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.savedFilterToMap(filter)
            }
            "nlp_shortcut" -> {
                val shortcut = nlpShortcutDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.nlpShortcutToMap(shortcut)
            }
            "habit_template" -> {
                val template = habitTemplateDao.getById(meta.localId) ?: return
                SyncMapper.habitTemplateToMap(template)
            }
            "project_template" -> {
                val template = projectTemplateDao.getById(meta.localId) ?: return
                SyncMapper.projectTemplateToMap(template)
            }
            "boundary_rule" -> {
                val rule = boundaryRuleDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.boundaryRuleToMap(rule)
            }
            "automation_rule" -> {
                val rule = automationRuleDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.automationRuleToMap(rule)
            }
            "check_in_log" -> {
                val log = checkInLogDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.checkInLogToMap(log)
            }
            "mood_energy_log" -> {
                val log = moodEnergyLogDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.moodEnergyLogToMap(log)
            }
            "focus_release_log" -> {
                val log = focusReleaseLogDao.getByIdOnce(meta.localId) ?: return
                val taskCloudId = log.taskId?.let { syncMetadataDao.getCloudId(it, "task") }
                SyncMapper.focusReleaseLogToMap(log, taskCloudId)
            }
            "medication_refill" -> {
                val refill = medicationRefillDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.medicationRefillToMap(refill)
            }
            "weekly_review" -> {
                val review = weeklyReviewDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.weeklyReviewToMap(review)
            }
            // `daily_essential_slot_completion` Firestore push removed
            // in parity Batch 5 PR-9 (decision D-E4). BackendSyncService
            // is authoritative.
            "assignment" -> {
                val assignment = schoolworkDao.getAssignmentById(meta.localId) ?: return
                val courseCloudId = syncMetadataDao.getCloudId(assignment.courseId, "course")
                    ?: return
                SyncMapper.assignmentToMap(assignment, courseCloudId)
            }
            "attachment" -> {
                val attachment = attachmentDao.getByIdOnce(meta.localId) ?: return
                val taskCloudId = syncMetadataDao.getCloudId(attachment.taskId, "task")
                    ?: return
                SyncMapper.attachmentToMap(attachment, taskCloudId)
            }
            "study_log" -> {
                val log = schoolworkDao.getStudyLogByIdOnce(meta.localId) ?: return
                val coursePickCloudId = log.coursePick?.let { syncMetadataDao.getCloudId(it, "course") }
                val assignmentPickCloudId = log.assignmentPick?.let {
                    syncMetadataDao.getCloudId(it, "assignment")
                }
                SyncMapper.studyLogToMap(log, coursePickCloudId, assignmentPickCloudId)
            }
            "project_phase" -> {
                val phase = projectPhaseDao.getByIdOnce(meta.localId) ?: return
                val projectCloudId = syncMetadataDao.getCloudId(phase.projectId, "project")
                    ?: return
                SyncMapper.projectPhaseToMap(phase, projectCloudId)
            }
            "project_risk" -> {
                val risk = projectRiskDao.getByIdOnce(meta.localId) ?: return
                val projectCloudId = syncMetadataDao.getCloudId(risk.projectId, "project")
                    ?: return
                SyncMapper.projectRiskToMap(risk, projectCloudId)
            }
            "task_dependency" -> {
                val dep = taskDependencyDao.getByIdOnce(meta.localId) ?: return
                val blockerCloudId =
                    syncMetadataDao.getCloudId(dep.blockerTaskId, "task") ?: return
                val blockedCloudId =
                    syncMetadataDao.getCloudId(dep.blockedTaskId, "task") ?: return
                SyncMapper.taskDependencyToMap(dep, blockerCloudId, blockedCloudId)
            }
            "external_anchor" -> {
                val anchor = externalAnchorDao.getByIdOnce(meta.localId) ?: return
                val projectCloudId = syncMetadataDao.getCloudId(anchor.projectId, "project")
                    ?: return
                val phaseCloudId = anchor.phaseId?.let {
                    syncMetadataDao.getCloudId(it, "project_phase")
                }
                SyncMapper.externalAnchorToMap(anchor, projectCloudId, phaseCloudId)
            }
            else -> return
        }
        // Delete-wins contract: use `docRef.update(...)` rather than `docRef.set(...)`.
        // `set` on a non-existent path silently creates the doc, which would
        // resurrect a row that another device already deleted — exactly the
        // bug Test 10 flagged. `update` throws with code NOT_FOUND (SDK 24+)
        // or FAILED_PRECONDITION (older) when the doc is missing; we treat
        // that as "remote deleted, propagate to local" and reuse the same
        // cleanup path the realtime listener takes in `processRemoteDeletions`.
        try {
            @Suppress("UNCHECKED_CAST")
            docRef.update(data as Map<String, Any>).await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.NOT_FOUND ||
                e.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION
            ) {
                logger.info(
                    operation = "push.update.remoteDeleted",
                    entity = meta.entityType,
                    id = meta.cloudId,
                    status = "cleanup",
                    detail = "Remote doc missing — delete wins; removing local row."
                )
                processRemoteDeletions(
                    collectionNameFor(meta.entityType),
                    listOf(meta.cloudId)
                )
                return
            }
            throw e
        }
    }

    private suspend fun pushDelete(meta: SyncMetadataEntity) {
        if (meta.cloudId.isNotEmpty()) {
            userCollection(collectionNameFor(meta.entityType))?.document(meta.cloudId)?.delete()?.await()
        }
        syncMetadataDao.delete(meta.localId, meta.entityType)
    }
}
