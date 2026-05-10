package com.averycorp.prismtask.data.remote

import androidx.annotation.VisibleForTesting
import com.averycorp.prismtask.data.local.dao.AttachmentDao
import com.averycorp.prismtask.data.local.dao.AutomationRuleDao
import com.averycorp.prismtask.data.local.dao.BoundaryRuleDao
import com.averycorp.prismtask.data.local.dao.CheckInLogDao
import com.averycorp.prismtask.data.local.dao.CustomSoundDao
import com.averycorp.prismtask.data.local.dao.DailyEssentialSlotCompletionDao
import com.averycorp.prismtask.data.local.dao.ExternalAnchorDao
import com.averycorp.prismtask.data.local.dao.FocusReleaseLogDao
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.HabitLogDao
import com.averycorp.prismtask.data.local.dao.HabitTemplateDao
import com.averycorp.prismtask.data.local.dao.LeisureDao
import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationDoseDao
import com.averycorp.prismtask.data.local.dao.MedicationRefillDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotOverrideDao
import com.averycorp.prismtask.data.local.dao.MedicationTierStateDao
import com.averycorp.prismtask.data.local.dao.MilestoneDao
import com.averycorp.prismtask.data.local.dao.MoodEnergyLogDao
import com.averycorp.prismtask.data.local.dao.NlpShortcutDao
import com.averycorp.prismtask.data.local.dao.NotificationProfileDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.ProjectPhaseDao
import com.averycorp.prismtask.data.local.dao.ProjectRiskDao
import com.averycorp.prismtask.data.local.dao.ProjectTemplateDao
import com.averycorp.prismtask.data.local.dao.SavedFilterDao
import com.averycorp.prismtask.data.local.dao.SchoolworkDao
import com.averycorp.prismtask.data.local.dao.SelfCareDao
import com.averycorp.prismtask.data.local.dao.SyncMetadataDao
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskCompletionDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.dao.TaskDependencyDao
import com.averycorp.prismtask.data.local.dao.TaskTemplateDao
import com.averycorp.prismtask.data.local.dao.TaskTimingDao
import com.averycorp.prismtask.data.local.dao.WeeklyReviewDao
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import com.averycorp.prismtask.data.preferences.BuiltInSyncPreferences
import com.averycorp.prismtask.data.remote.mapper.MedicationSyncMapper
import com.averycorp.prismtask.data.remote.mapper.SyncMapper
import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import com.averycorp.prismtask.data.remote.sync.ReactiveSyncDriver
import com.averycorp.prismtask.data.remote.sync.SyncStateRepository
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

// See docs/audits/SYNCSERVICE_GOD_CLASS_REFACTOR_AUDIT.md for the surface-axis
// refactor plan (operator-confirmed May 4, 2026; Phase 1 + Slice 0 shipped via
// PRs #1118 + #1122; Phase 2 sub-PR 7a — Firestore constructor injection —
// shipped via this PR).
// TODO(sync-refactor): split SyncService — separate push, pull, listener,
// and initial-upload surfaces. Each PR that touches this file widens the
// file further; the next refactor should land before the next feature.
@Suppress("LargeClass")
@Singleton
class SyncService
@Inject
constructor(
    private val authManager: AuthManager,
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val habitDao: HabitDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val habitLogDao: HabitLogDao,
    private val taskTemplateDao: TaskTemplateDao,
    private val milestoneDao: MilestoneDao,
    private val taskCompletionDao: TaskCompletionDao,
    private val taskTimingDao: TaskTimingDao,
    private val proFeatureGate: ProFeatureGate,
    private val logger: PrismSyncLogger,
    private val syncStateRepository: SyncStateRepository,
    private val builtInHabitReconciler: BuiltInHabitReconciler,
    private val builtInTaskTemplateReconciler: BuiltInTaskTemplateReconciler,
    private val builtInTaskTemplateBackfiller: BuiltInTaskTemplateBackfiller,
    private val builtInUpdateDetector: com.averycorp.prismtask.domain.usecase.BuiltInUpdateDetector,
    private val cloudIdOrphanHealer: CloudIdOrphanHealer,
    private val builtInMedicationReconciler: BuiltInMedicationReconciler,
    private val medicationDao: MedicationDao,
    private val medicationDoseDao: MedicationDoseDao,
    private val medicationSlotDao: MedicationSlotDao,
    private val medicationSlotOverrideDao: MedicationSlotOverrideDao,
    private val medicationTierStateDao: MedicationTierStateDao,
    private val medicationMigrationPreferences: com.averycorp.prismtask.data.preferences.MedicationMigrationPreferences,
    private val sortPreferencesSyncService: SortPreferencesSyncService,
    private val schoolworkDao: SchoolworkDao,
    private val leisureDao: LeisureDao,
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
    private val builtInSyncPreferences: BuiltInSyncPreferences,
    private val database: com.averycorp.prismtask.data.local.database.PrismTaskDatabase,
    private val projectPhaseDao: ProjectPhaseDao,
    private val projectRiskDao: ProjectRiskDao,
    private val taskDependencyDao: TaskDependencyDao,
    private val externalAnchorDao: ExternalAnchorDao,
    private val firestore: FirebaseFirestore,
    /**
     * D8 Item 7 Strangler Fig 7c — realtime listener surface.
     */
    private val listenerManager: SyncListenerManager,
    /**
     * D8 Item 7 Strangler Fig 7b — push surface (orchestrator slice).
     * Owns the sort / iterate / log / retry loop. The 36-branch
     * pushCreate / pushUpdate / pushDelete dispatch remains on
     * SyncService for now; the orchestrator routes through a single
     * dispatch lambda passed in from [pushLocalChanges].
     */
    private val pushOrchestrator: SyncPushOrchestrator,
    /**
     * D8 Item 7 Strangler Fig 7d — initial-upload surface (orchestrator
     * slice). Owns the one-shot guard, isSyncing lifecycle, completion
     * telemetry, and post-release pull. `doInitialUpload` body remains
     * on SyncService and is passed in as a lambda.
     */
    private val initialUploadOrchestrator: SyncInitialUploadOrchestrator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var isSyncing = false

    private fun userCollection(collection: String) =
        authManager.userId?.let { firestore.collection("users").document(it).collection(collection) }

    suspend fun initialUpload() {
        if (authManager.userId == null) return
        // D8 Item 7 7d — orchestration extracted to
        // [SyncInitialUploadOrchestrator]. The dispatch (`doInitialUpload`)
        // and the post-release pull (`pullRemoteChanges`) remain on
        // SyncService and are passed in as lambdas; once those surfaces
        // extract in follow-on PRs the lambdas become direct method
        // references with no signature change.
        initialUploadOrchestrator.runIfNeeded(
            isSyncing = { isSyncing },
            setSyncing = { isSyncing = it },
            doUpload = { doInitialUpload() },
            postReleasePull = {
                val pullSummary = pullRemoteChanges()
                logger.debug(
                    operation = "initialUpload.post_release_pull",
                    status = "success",
                    detail = "applied=${pullSummary.applied} permanently_failed=${pullSummary.skippedPermanent}"
                )
            }
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun doInitialUpload() {
        val projects = projectDao.getAllProjectsOnce()
        logger.debug("upload.projects", status = "begin", detail = "count=${projects.size}")
        for (project in projects) {
            try {
                // Fix C — skip rows that already have a cloud mapping. On
                // fresh-install after a fullSync pull, every local row already
                // has a sync_metadata entry from the pull path; without this
                // guard initialUpload would re-upload all of them as new
                // auto-ID docs and duplicate the cloud state.
                if (syncMetadataDao.getCloudId(project.id, "project") != null) continue
                val docRef = userCollection("projects")?.document() ?: continue
                docRef.set(SyncMapper.projectToMap(project)).await()
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = project.id,
                        entityType = "project",
                        cloudId = docRef.id,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.project",
                    entity = "project",
                    id = project.id.toString(),
                    detail = project.name,
                    throwable = e
                )
            }
        }

        // Upload milestones (v1.4.0 Projects Phase 5). Must come AFTER the
        // projects block so the cloud IDs for each project are registered
        // in sync_metadata and we can attach the milestone to its parent.
        logger.debug("upload.milestones", status = "begin")
        for (project in projects) {
            val projectCloudId = syncMetadataDao.getCloudId(project.id, "project") ?: continue
            val milestones = milestoneDao.getMilestonesOnce(project.id)
            for (milestone in milestones) {
                try {
                    if (syncMetadataDao.getCloudId(milestone.id, "milestone") != null) continue
                    val docRef = userCollection("milestones")?.document() ?: continue
                    docRef.set(SyncMapper.milestoneToMap(milestone, projectCloudId)).await()
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = milestone.id,
                            entityType = "milestone",
                            cloudId = docRef.id,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(
                        operation = "upload.milestone",
                        entity = "milestone",
                        id = milestone.id.toString(),
                        detail = milestone.title,
                        throwable = e
                    )
                }
            }
        }

        // Upload project phases + risks + external anchors
        // (PrismTask-timeline-class scope, PR-1 + PR-3). Same
        // parent-cloud-id-must-exist contract as milestones.
        logger.debug("upload.project_phases", status = "begin")
        for (project in projects) {
            val projectCloudId = syncMetadataDao.getCloudId(project.id, "project") ?: continue
            for (phase in projectPhaseDao.getPhasesOnce(project.id)) {
                try {
                    if (syncMetadataDao.getCloudId(phase.id, "project_phase") != null) continue
                    val docRef = userCollection("project_phases")?.document() ?: continue
                    docRef.set(SyncMapper.projectPhaseToMap(phase, projectCloudId)).await()
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = phase.id,
                            entityType = "project_phase",
                            cloudId = docRef.id,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(
                        operation = "upload.project_phase",
                        entity = "project_phase",
                        id = phase.id.toString(),
                        detail = phase.title,
                        throwable = e
                    )
                }
            }
            for (risk in projectRiskDao.getRisksOnce(project.id)) {
                try {
                    if (syncMetadataDao.getCloudId(risk.id, "project_risk") != null) continue
                    val docRef = userCollection("project_risks")?.document() ?: continue
                    docRef.set(SyncMapper.projectRiskToMap(risk, projectCloudId)).await()
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = risk.id,
                            entityType = "project_risk",
                            cloudId = docRef.id,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(
                        operation = "upload.project_risk",
                        entity = "project_risk",
                        id = risk.id.toString(),
                        detail = risk.title,
                        throwable = e
                    )
                }
            }
            for (anchor in externalAnchorDao.getAnchorsOnce(project.id)) {
                try {
                    if (syncMetadataDao.getCloudId(anchor.id, "external_anchor") != null) continue
                    val phaseCloudId = anchor.phaseId?.let {
                        syncMetadataDao.getCloudId(it, "project_phase")
                    }
                    val docRef = userCollection("external_anchors")?.document() ?: continue
                    docRef.set(
                        SyncMapper.externalAnchorToMap(anchor, projectCloudId, phaseCloudId)
                    ).await()
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = anchor.id,
                            entityType = "external_anchor",
                            cloudId = docRef.id,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(
                        operation = "upload.external_anchor",
                        entity = "external_anchor",
                        id = anchor.id.toString(),
                        detail = anchor.label,
                        throwable = e
                    )
                }
            }
        }

        val tags = tagDao.getAllTagsOnce()
        logger.debug("upload.tags", status = "begin", detail = "count=${tags.size}")
        for (tag in tags) {
            try {
                if (syncMetadataDao.getCloudId(tag.id, "tag") != null) continue
                val docRef = userCollection("tags")?.document() ?: continue
                docRef.set(SyncMapper.tagToMap(tag)).await()
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = tag.id,
                        entityType = "tag",
                        cloudId = docRef.id,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.tag",
                    entity = "tag",
                    id = tag.id.toString(),
                    detail = tag.name,
                    throwable = e
                )
            }
        }

        val habits = habitDao.getActiveHabitsOnce()
        logger.debug("upload.habits", status = "begin", detail = "count=${habits.size}")
        for (habit in habits) {
            try {
                if (syncMetadataDao.getCloudId(habit.id, "habit") != null) continue
                val docRef = userCollection("habits")?.document() ?: continue
                docRef.set(SyncMapper.habitToMap(habit)).await()
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = habit.id,
                        entityType = "habit",
                        cloudId = docRef.id,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.habit",
                    entity = "habit",
                    id = habit.id.toString(),
                    detail = habit.name,
                    throwable = e
                )
            }
        }

        logger.debug("upload.habit_completions", status = "begin")
        for (habit in habits) {
            val completions = habitCompletionDao.getCompletionsForHabitOnce(habit.id)
            val habitCloudId = syncMetadataDao.getCloudId(habit.id, "habit") ?: continue
            for (completion in completions) {
                try {
                    if (syncMetadataDao.getCloudId(completion.id, "habit_completion") != null) continue
                    val docRef = userCollection("habit_completions")?.document() ?: continue
                    docRef.set(SyncMapper.habitCompletionToMap(completion, habitCloudId)).await()
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = completion.id,
                            entityType = "habit_completion",
                            cloudId = docRef.id,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(
                        operation = "upload.habit_completion",
                        entity = "habit_completion",
                        id = completion.id.toString(),
                        throwable = e
                    )
                }
            }
        }

        logger.debug("upload.habit_logs", status = "begin")
        for (habit in habits) {
            val logs = habitLogDao.getAllLogsOnce().filter { it.habitId == habit.id }
            val habitCloudId = syncMetadataDao.getCloudId(habit.id, "habit") ?: continue
            for (log in logs) {
                try {
                    if (syncMetadataDao.getCloudId(log.id, "habit_log") != null) continue
                    val docRef = userCollection("habit_logs")?.document() ?: continue
                    docRef.set(SyncMapper.habitLogToMap(log, habitCloudId)).await()
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = log.id,
                            entityType = "habit_log",
                            cloudId = docRef.id,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(
                        operation = "upload.habit_log",
                        entity = "habit_log",
                        id = log.id.toString(),
                        throwable = e
                    )
                }
            }
        }

        val tasks = taskDao.getAllTasksOnce()
        logger.debug("upload.tasks", status = "begin", detail = "count=${tasks.size}")
        for (task in tasks) {
            try {
                if (syncMetadataDao.getCloudId(task.id, "task") != null) continue
                val tagIds = tagDao.getTagIdsForTaskOnce(task.id).mapNotNull { tagId ->
                    syncMetadataDao.getCloudId(tagId, "tag")
                }
                val projectCloudId = task.projectId?.let { syncMetadataDao.getCloudId(it, "project") }
                val parentTaskCloudId = task.parentTaskId?.let { syncMetadataDao.getCloudId(it, "task") }
                val sourceHabitCloudId = task.sourceHabitId?.let { syncMetadataDao.getCloudId(it, "habit") }
                val phaseCloudId = task.phaseId?.let { syncMetadataDao.getCloudId(it, "project_phase") }
                val docRef = userCollection("tasks")?.document() ?: continue
                docRef.set(
                    SyncMapper.taskToMap(
                        task,
                        tagIds,
                        projectCloudId,
                        parentTaskCloudId,
                        sourceHabitCloudId,
                        phaseCloudId
                    )
                ).await()
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = task.id,
                        entityType = "task",
                        cloudId = docRef.id,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.task",
                    entity = "task",
                    id = task.id.toString(),
                    detail = task.title,
                    throwable = e
                )
            }
        }

        // task_dependencies after tasks so both endpoint cloud IDs are
        // available for FK serialization (PrismTask-timeline-class scope, PR-2).
        val taskDependencies = taskDependencyDao.getAllOnce()
        logger.debug(
            "upload.task_dependencies",
            status = "begin",
            detail = "count=${taskDependencies.size}"
        )
        for (dep in taskDependencies) {
            try {
                if (syncMetadataDao.getCloudId(dep.id, "task_dependency") != null) continue
                val blockerCloudId =
                    syncMetadataDao.getCloudId(dep.blockerTaskId, "task") ?: continue
                val blockedCloudId =
                    syncMetadataDao.getCloudId(dep.blockedTaskId, "task") ?: continue
                val docRef = userCollection("task_dependencies")?.document() ?: continue
                docRef.set(
                    SyncMapper.taskDependencyToMap(dep, blockerCloudId, blockedCloudId)
                ).await()
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = dep.id,
                        entityType = "task_dependency",
                        cloudId = docRef.id,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.task_dependency",
                    entity = "task_dependency",
                    id = dep.id.toString(),
                    throwable = e
                )
            }
        }

        // task_completions after tasks so task cloud IDs are available for FK serialization.
        val taskCompletions = taskCompletionDao.getAllCompletionsOnce()
        logger.debug("upload.task_completions", status = "begin", detail = "count=${taskCompletions.size}")
        for (completion in taskCompletions) {
            try {
                if (syncMetadataDao.getCloudId(completion.id, "task_completion") != null) continue
                val taskCloudId = completion.taskId?.let { syncMetadataDao.getCloudId(it, "task") }
                val projectCloudId = completion.projectId?.let { syncMetadataDao.getCloudId(it, "project") }
                val docRef = userCollection("task_completions")?.document() ?: continue
                docRef.set(SyncMapper.taskCompletionToMap(completion, taskCloudId, projectCloudId)).await()
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = completion.id,
                        entityType = "task_completion",
                        cloudId = docRef.id,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.task_completion",
                    entity = "task_completion",
                    id = completion.id.toString(),
                    throwable = e
                )
            }
        }

        // task_timings after tasks so task cloud IDs are available for FK serialization.
        val taskTimings = taskTimingDao.getAllOnce()
        logger.debug("upload.task_timings", status = "begin", detail = "count=${taskTimings.size}")
        for (timing in taskTimings) {
            try {
                if (syncMetadataDao.getCloudId(timing.id, "task_timing") != null) continue
                val taskCloudId = syncMetadataDao.getCloudId(timing.taskId, "task")
                val docRef = userCollection("task_timings")?.document() ?: continue
                docRef.set(SyncMapper.taskTimingToMap(timing, taskCloudId)).await()
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = timing.id,
                        entityType = "task_timing",
                        cloudId = docRef.id,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.task_timing",
                    entity = "task_timing",
                    id = timing.id.toString(),
                    throwable = e
                )
            }
        }

        val templates = taskTemplateDao.getAllTemplatesOnce()
        logger.debug("upload.task_templates", status = "begin", detail = "count=${templates.size}")
        for (template in templates) {
            try {
                if (syncMetadataDao.getCloudId(template.id, "task_template") != null) continue
                val templateProjectCloudId = template.templateProjectId?.let { syncMetadataDao.getCloudId(it, "project") }
                val docRef = userCollection("task_templates")?.document() ?: continue
                docRef.set(SyncMapper.taskTemplateToMap(template, templateProjectCloudId)).await()
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = template.id,
                        entityType = "task_template",
                        cloudId = docRef.id,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.task_template",
                    entity = "task_template",
                    id = template.id.toString(),
                    detail = template.name,
                    throwable = e
                )
            }
        }

        uploadRoomConfigFamily(
            entityType = "notification_profile",
            collection = "notification_profiles",
            rows = notificationProfileDao.getAllOnce(),
            rowId = { it.id },
            toMap = { SyncMapper.notificationProfileToMap(it) }
        )
        uploadRoomConfigFamily(
            entityType = "custom_sound",
            collection = "custom_sounds",
            rows = customSoundDao.getAllOnce(),
            rowId = { it.id },
            toMap = { SyncMapper.customSoundToMap(it) }
        )
        uploadRoomConfigFamily(
            entityType = "saved_filter",
            collection = "saved_filters",
            rows = savedFilterDao.getAllOnce(),
            rowId = { it.id },
            toMap = { SyncMapper.savedFilterToMap(it) }
        )
        uploadRoomConfigFamily(
            entityType = "nlp_shortcut",
            collection = "nlp_shortcuts",
            rows = nlpShortcutDao.getAllOnce(),
            rowId = { it.id },
            toMap = { SyncMapper.nlpShortcutToMap(it) }
        )
        uploadRoomConfigFamily(
            entityType = "habit_template",
            collection = "habit_templates",
            rows = habitTemplateDao.getAllOnce(),
            rowId = { it.id },
            toMap = { SyncMapper.habitTemplateToMap(it) }
        )
        uploadRoomConfigFamily(
            entityType = "project_template",
            collection = "project_templates",
            rows = projectTemplateDao.getAllOnce(),
            rowId = { it.id },
            toMap = { SyncMapper.projectTemplateToMap(it) }
        )
        uploadRoomConfigFamily(
            entityType = "boundary_rule",
            collection = "boundary_rules",
            rows = boundaryRuleDao.getAllOnce(),
            rowId = { it.id },
            toMap = { SyncMapper.boundaryRuleToMap(it) }
        )
        uploadRoomConfigFamily(
            entityType = "automation_rule",
            collection = "automation_rules",
            rows = automationRuleDao.getAllOnce(),
            rowId = { it.id },
            toMap = { SyncMapper.automationRuleToMap(it) }
        )

        // --- v1.4.38 content families (FK-free) ---
        uploadRoomConfigFamily(
            entityType = "check_in_log",
            collection = "check_in_logs",
            rows = checkInLogDao.getAllOnce(),
            rowId = { it.id },
            toMap = { SyncMapper.checkInLogToMap(it) }
        )
        uploadRoomConfigFamily(
            entityType = "mood_energy_log",
            collection = "mood_energy_logs",
            rows = moodEnergyLogDao.getAllOnce(),
            rowId = { it.id },
            toMap = { SyncMapper.moodEnergyLogToMap(it) }
        )
        uploadRoomConfigFamily(
            entityType = "medication_refill",
            collection = "medication_refills",
            rows = medicationRefillDao.getAllOnce(),
            rowId = { it.id },
            toMap = { SyncMapper.medicationRefillToMap(it) }
        )
        uploadRoomConfigFamily(
            entityType = "weekly_review",
            collection = "weekly_reviews",
            rows = weeklyReviewDao.getAllOnce(),
            rowId = { it.id },
            toMap = { SyncMapper.weeklyReviewToMap(it) }
        )
        uploadRoomConfigFamily(
            entityType = "daily_essential_slot_completion",
            collection = "daily_essential_slot_completions",
            rows = dailyEssentialSlotCompletionDao.getAllOnce(),
            rowId = { it.id },
            toMap = { SyncMapper.dailyEssentialSlotCompletionToMap(it) }
        )

        // --- v1.4.38 content families (FK-bearing) ---
        // focus_release_log.taskId, assignment.courseId, attachment.taskId,
        // study_log.coursePick + .assignmentPick need local→cloud translation
        // at push time. If a parent row isn't synced yet (no cloud_id), we
        // skip the child and let the next upload pass retry.
        uploadFocusReleaseLogs()
        uploadAssignments()
        uploadAttachments()
        uploadStudyLogs()

        maybeRunEntityBackfill()
    }

    private suspend fun uploadFocusReleaseLogs() {
        val rows = focusReleaseLogDao.getAllOnce()
        logger.debug("upload.focus_release_logs", status = "begin", detail = "count=${rows.size}")
        for (row in rows) {
            try {
                if (syncMetadataDao.getCloudId(row.id, "focus_release_log") != null) continue
                val taskCloudId = row.taskId?.let { syncMetadataDao.getCloudId(it, "task") }
                val docRef = userCollection("focus_release_logs")?.document() ?: continue
                docRef.set(SyncMapper.focusReleaseLogToMap(row, taskCloudId)).await()
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = row.id,
                        entityType = "focus_release_log",
                        cloudId = docRef.id,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.focus_release_log",
                    entity = "focus_release_log",
                    id = row.id.toString(),
                    throwable = e
                )
            }
        }
    }

    private suspend fun uploadAssignments() {
        val rows = schoolworkDao.getAllAssignmentsOnce()
        logger.debug("upload.assignments", status = "begin", detail = "count=${rows.size}")
        for (row in rows) {
            try {
                if (syncMetadataDao.getCloudId(row.id, "assignment") != null) continue
                val courseCloudId = syncMetadataDao.getCloudId(row.courseId, "course") ?: continue
                val docRef = userCollection("assignments")?.document() ?: continue
                docRef.set(SyncMapper.assignmentToMap(row, courseCloudId)).await()
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = row.id,
                        entityType = "assignment",
                        cloudId = docRef.id,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.assignment",
                    entity = "assignment",
                    id = row.id.toString(),
                    throwable = e
                )
            }
        }
    }

    private suspend fun uploadAttachments() {
        val rows = attachmentDao.getAllOnce()
        logger.debug("upload.attachments", status = "begin", detail = "count=${rows.size}")
        for (row in rows) {
            try {
                if (syncMetadataDao.getCloudId(row.id, "attachment") != null) continue
                val taskCloudId = syncMetadataDao.getCloudId(row.taskId, "task") ?: continue
                val docRef = userCollection("attachments")?.document() ?: continue
                docRef.set(SyncMapper.attachmentToMap(row, taskCloudId)).await()
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = row.id,
                        entityType = "attachment",
                        cloudId = docRef.id,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.attachment",
                    entity = "attachment",
                    id = row.id.toString(),
                    throwable = e
                )
            }
        }
    }

    private suspend fun uploadStudyLogs() {
        val rows = schoolworkDao.getAllStudyLogsOnce()
        logger.debug("upload.study_logs", status = "begin", detail = "count=${rows.size}")
        for (row in rows) {
            try {
                if (syncMetadataDao.getCloudId(row.id, "study_log") != null) continue
                val coursePickCloudId = row.coursePick?.let { syncMetadataDao.getCloudId(it, "course") }
                val assignmentPickCloudId = row.assignmentPick?.let {
                    syncMetadataDao.getCloudId(it, "assignment")
                }
                val docRef = userCollection("study_logs")?.document() ?: continue
                docRef.set(SyncMapper.studyLogToMap(row, coursePickCloudId, assignmentPickCloudId)).await()
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = row.id,
                        entityType = "study_log",
                        cloudId = docRef.id,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.study_log",
                    entity = "study_log",
                    id = row.id.toString(),
                    throwable = e
                )
            }
        }
    }

    /**
     * Upload helper for the v1.4.37 Room-entity config families
     * (`notification_profile`, `custom_sound`, `saved_filter`, `nlp_shortcut`,
     * `habit_template`, `project_template`, `boundary_rule`). Each row is
     * skipped if it already has a cloud_id in sync_metadata, and each
     * failure is logged + swallowed so one bad row doesn't block the rest.
     */
    private suspend fun <T> uploadRoomConfigFamily(
        entityType: String,
        collection: String,
        rows: List<T>,
        rowId: (T) -> Long,
        toMap: (T) -> Map<String, Any?>
    ) {
        logger.debug("upload.$collection", status = "begin", detail = "count=${rows.size}")
        for (row in rows) {
            val id = rowId(row)
            try {
                if (syncMetadataDao.getCloudId(id, entityType) != null) continue
                val docRef = userCollection(collection)?.document() ?: continue
                docRef.set(toMap(row)).await()
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = id,
                        entityType = entityType,
                        cloudId = docRef.id,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.$entityType",
                    entity = entityType,
                    id = id.toString(),
                    throwable = e
                )
            }
        }
    }

    /**
     * Per-family backfill for the v1.4 "new entity" upload families
     * (`courses`, `course_completions`, `leisure_logs`, `self_care_steps`,
     * `self_care_logs`). Each family is guarded by its own DataStore flag
     * in [BuiltInSyncPreferences] with a legacy fallback to
     * [BuiltInSyncPreferences.isNewEntitiesBackfillDone], so users who
     * already ran the old single-flag master backfill don't re-run any
     * family's loop after upgrade. Each family's flag flips to true only
     * on successful completion of its own loop — a family that partially
     * failed stays retryable on the next app start independently of the
     * other families' success.
     *
     * Called from [doInitialUpload] (sign-in path) AND from [startAutoSync]
     * (already-signed-in path) so devices authenticated before this code
     * shipped are not silently skipped.
     *
     * Per-row guards (`if (getCloudId(row.id, type) != null) continue`)
     * make re-running a family's loop idempotent against already-synced
     * rows — resetting a per-family flag for targeted re-upload after an
     * out-of-band Firestore wipe is safe.
     *
     * The master flag ([BuiltInSyncPreferences.setNewEntitiesBackfillDone])
     * is still flipped to true when all families succeed, preserving
     * backwards-compat signaling for code paths that still check it.
     */
    private suspend fun maybeRunEntityBackfill() {
        val coursesOk = runCoursesBackfillIfNeeded()
        val courseCompletionsOk = runCourseCompletionsBackfillIfNeeded()
        val leisureLogsOk = runLeisureLogsBackfillIfNeeded()
        val selfCareStepsOk = runSelfCareStepsBackfillIfNeeded()
        val selfCareLogsOk = runSelfCareLogsBackfillIfNeeded()
        // medications BEFORE medication_doses so the dose helper can
        // resolve cloud_ids for the parents. Each has its own one-shot
        // flag in MedicationMigrationPreferences.
        val medicationsOk = runMedicationsBackfillIfNeeded()
        val medicationDosesOk = runMedicationDosesBackfillIfNeeded()

        val allSucceeded = coursesOk && courseCompletionsOk && leisureLogsOk &&
            selfCareStepsOk && selfCareLogsOk &&
            medicationsOk && medicationDosesOk
        if (allSucceeded) {
            builtInSyncPreferences.setNewEntitiesBackfillDone(true)
            logger.info("upload.new_entities_backfill", status = "success")
        }
    }

    /**
     * Runs the courses upload loop if its per-family flag is false.
     * Returns true if the family is complete (either was already done
     * or completed successfully in this pass), false if it ran but
     * encountered an exception. The flag flips only on clean completion.
     */
    private suspend fun runCoursesBackfillIfNeeded(): Boolean {
        if (builtInSyncPreferences.isCoursesBackfillDone()) return true
        return try {
            val courses = schoolworkDao.getAllCoursesOnce()
            logger.debug("upload.courses", status = "begin", detail = "count=${courses.size}")
            for (course in courses) {
                try {
                    if (syncMetadataDao.getCloudId(course.id, "course") != null) continue
                    val docRef = userCollection("courses")?.document() ?: continue
                    docRef.set(SyncMapper.courseToMap(course)).await()
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = course.id,
                            entityType = "course",
                            cloudId = docRef.id,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(
                        operation = "upload.course",
                        entity = "course",
                        id = course.id.toString(),
                        detail = course.name,
                        throwable = e
                    )
                }
            }
            builtInSyncPreferences.setCoursesBackfillDone(true)
            true
        } catch (e: Exception) {
            logger.error(operation = "upload.courses", throwable = e)
            false
        }
    }

    private suspend fun runCourseCompletionsBackfillIfNeeded(): Boolean {
        if (builtInSyncPreferences.isCourseCompletionsBackfillDone()) return true
        return try {
            // course_completions AFTER courses so course cloud IDs are in sync_metadata.
            val courseCompletions = schoolworkDao.getAllCompletionsOnce()
            logger.debug("upload.course_completions", status = "begin", detail = "count=${courseCompletions.size}")
            for (completion in courseCompletions) {
                try {
                    if (syncMetadataDao.getCloudId(completion.id, "course_completion") != null) continue
                    val courseCloudId = syncMetadataDao.getCloudId(completion.courseId, "course") ?: continue
                    val docRef = userCollection("course_completions")?.document() ?: continue
                    docRef.set(SyncMapper.courseCompletionToMap(completion, courseCloudId)).await()
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = completion.id,
                            entityType = "course_completion",
                            cloudId = docRef.id,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(
                        operation = "upload.course_completion",
                        entity = "course_completion",
                        id = completion.id.toString(),
                        throwable = e
                    )
                }
            }
            builtInSyncPreferences.setCourseCompletionsBackfillDone(true)
            true
        } catch (e: Exception) {
            logger.error(operation = "upload.course_completions", throwable = e)
            false
        }
    }

    private suspend fun runLeisureLogsBackfillIfNeeded(): Boolean {
        if (builtInSyncPreferences.isLeisureLogsBackfillDone()) return true
        return try {
            val leisureLogs = leisureDao.getAllLogsOnce()
            logger.debug("upload.leisure_logs", status = "begin", detail = "count=${leisureLogs.size}")
            for (log in leisureLogs) {
                try {
                    if (syncMetadataDao.getCloudId(log.id, "leisure_log") != null) continue
                    val docRef = userCollection("leisure_logs")?.document() ?: continue
                    docRef.set(SyncMapper.leisureLogToMap(log)).await()
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = log.id,
                            entityType = "leisure_log",
                            cloudId = docRef.id,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(operation = "upload.leisure_log", entity = "leisure_log", id = log.id.toString(), throwable = e)
                }
            }
            builtInSyncPreferences.setLeisureLogsBackfillDone(true)
            true
        } catch (e: Exception) {
            logger.error(operation = "upload.leisure_logs", throwable = e)
            false
        }
    }

    private suspend fun runSelfCareStepsBackfillIfNeeded(): Boolean {
        if (builtInSyncPreferences.isSelfCareStepsBackfillDone()) return true
        return try {
            val selfCareSteps = selfCareDao.getAllStepsOnce()
            logger.debug("upload.self_care_steps", status = "begin", detail = "count=${selfCareSteps.size}")
            for (step in selfCareSteps) {
                try {
                    if (syncMetadataDao.getCloudId(step.id, "self_care_step") != null) continue
                    val docRef = userCollection("self_care_steps")?.document() ?: continue
                    docRef.set(SyncMapper.selfCareStepToMap(step)).await()
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = step.id,
                            entityType = "self_care_step",
                            cloudId = docRef.id,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(operation = "upload.self_care_step", entity = "self_care_step", id = step.id.toString(), throwable = e)
                }
            }
            builtInSyncPreferences.setSelfCareStepsBackfillDone(true)
            true
        } catch (e: Exception) {
            logger.error(operation = "upload.self_care_steps", throwable = e)
            false
        }
    }

    private suspend fun runSelfCareLogsBackfillIfNeeded(): Boolean {
        if (builtInSyncPreferences.isSelfCareLogsBackfillDone()) return true
        return try {
            // self_care_logs AFTER self_care_steps (logical dependency).
            val selfCareLogs = selfCareDao.getAllLogsOnce()
            logger.debug("upload.self_care_logs", status = "begin", detail = "count=${selfCareLogs.size}")
            for (log in selfCareLogs) {
                try {
                    if (syncMetadataDao.getCloudId(log.id, "self_care_log") != null) continue
                    val docRef = userCollection("self_care_logs")?.document() ?: continue
                    docRef.set(SyncMapper.selfCareLogToMap(log)).await()
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = log.id,
                            entityType = "self_care_log",
                            cloudId = docRef.id,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(operation = "upload.self_care_log", entity = "self_care_log", id = log.id.toString(), throwable = e)
                }
            }
            builtInSyncPreferences.setSelfCareLogsBackfillDone(true)
            true
        } catch (e: Exception) {
            logger.error(operation = "upload.self_care_logs", throwable = e)
            false
        }
    }

    /**
     * Uploads every medication row that doesn't yet have a cloud mapping.
     * Must run BEFORE [runMedicationDosesBackfillIfNeeded] so dose rows
     * can resolve their parent medication's cloud_id.
     *
     * One-shot guard flag lives in [MedicationMigrationPreferences] (not
     * [BuiltInSyncPreferences]) because the medication migration owns
     * its own preference store.
     */
    private suspend fun runMedicationsBackfillIfNeeded(): Boolean {
        if (medicationMigrationPreferences.isMigrationPushedToCloud()) return true
        return try {
            val medications = medicationDao.getAllOnce()
            logger.debug("upload.medications", status = "begin", detail = "count=${medications.size}")
            for (med in medications) {
                try {
                    if (syncMetadataDao.getCloudId(med.id, "medication") != null) continue
                    val docRef = userCollection("medications")?.document() ?: continue
                    val slotCloudIds = medicationSlotDao.getSlotIdsForMedicationOnce(med.id)
                        .mapNotNull { syncMetadataDao.getCloudId(it, "medication_slot") }
                    docRef.set(MedicationSyncMapper.medicationToMap(med, slotCloudIds)).await()
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = med.id,
                            entityType = "medication",
                            cloudId = docRef.id,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(
                        operation = "upload.medication",
                        entity = "medication",
                        id = med.id.toString(),
                        detail = med.name,
                        throwable = e
                    )
                }
            }
            true
            // isMigrationPushedToCloud flag is set by the dose helper once
            // BOTH medications + doses are uploaded — setting it too early
            // would skip dose uploads on a partial-success retry.
        } catch (e: Exception) {
            logger.error(operation = "upload.medications", throwable = e)
            false
        }
    }

    private suspend fun runMedicationDosesBackfillIfNeeded(): Boolean {
        if (medicationMigrationPreferences.isMigrationPushedToCloud()) return true
        return try {
            val allDoses = medicationDoseDao.getAllOnce()
            logger.debug("upload.medication_doses", status = "begin", detail = "count=${allDoses.size}")
            for (dose in allDoses) {
                try {
                    if (syncMetadataDao.getCloudId(dose.id, "medication_dose") != null) continue
                    // Custom doses (medicationId == null) sync without a parent
                    // cloud id; tracked-medication doses bail when the parent
                    // hasn't synced yet so we don't push an orphan doc.
                    val medCloudId: String? = if (dose.medicationId == null) {
                        null
                    } else {
                        syncMetadataDao.getCloudId(dose.medicationId, "medication") ?: continue
                    }
                    val docRef = userCollection("medication_doses")?.document() ?: continue
                    docRef.set(MedicationSyncMapper.medicationDoseToMap(dose, medCloudId)).await()
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = dose.id,
                            entityType = "medication_dose",
                            cloudId = docRef.id,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(
                        operation = "upload.medication_dose",
                        entity = "medication_dose",
                        id = dose.id.toString(),
                        throwable = e
                    )
                }
            }
            medicationMigrationPreferences.setMigrationPushedToCloud(true)
            true
        } catch (e: Exception) {
            logger.error(operation = "upload.medication_doses", throwable = e)
            false
        }
    }

    fun launchInitialUpload() {
        scope.launch {
            val start = System.currentTimeMillis()
            try {
                initialUpload()
                logger.info(
                    operation = "upload.initial",
                    status = "success",
                    durationMs = System.currentTimeMillis() - start
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.initial",
                    durationMs = System.currentTimeMillis() - start,
                    throwable = e
                )
            }
        }
    }

    /**
     * Returns the number of pending operations processed (success + failure).
     * Callers use this to populate the "pushed=N" detail in sync completion
     * logs so we can see partial-push ratios at a glance.
     *
     * D8 Item 7 7b — sort / iterate / log / retry orchestration lives in
     * [SyncPushOrchestrator]; the per-entity create/update/delete
     * dispatch remains on SyncService until its own follow-on PR.
     */
    suspend fun pushLocalChanges(): Int =
        pushOrchestrator.pushAllPending { meta ->
            when (meta.pendingAction) {
                "create" -> pushCreate(meta)
                "update" -> pushUpdate(meta)
                "delete" -> pushDelete(meta)
            }
        }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    // Dispatch across every synced entityType — splitting the `when` is not
    // worth the indirection since each branch is only a DAO lookup + mapper
    // call. TODO: refactor pushCreate to reduce early return statements.
    private suspend fun pushCreate(meta: SyncMetadataEntity) {
        val collection = userCollection(collectionNameFor(meta.entityType)) ?: return
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
            "leisure_log" -> {
                val log = leisureDao.getAllLogsOnce().find { it.id == meta.localId } ?: return
                SyncMapper.leisureLogToMap(log)
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
            "daily_essential_slot_completion" -> {
                val row = dailyEssentialSlotCompletionDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.dailyEssentialSlotCompletionToMap(row)
            }
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
    private suspend fun pushUpdate(meta: SyncMetadataEntity) {
        if (meta.cloudId.isEmpty()) {
            pushCreate(meta)
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
            "leisure_log" -> {
                val log = leisureDao.getAllLogsOnce().find { it.id == meta.localId } ?: return
                SyncMapper.leisureLogToMap(log)
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
            "daily_essential_slot_completion" -> {
                val row = dailyEssentialSlotCompletionDao.getByIdOnce(meta.localId) ?: return
                SyncMapper.dailyEssentialSlotCompletionToMap(row)
            }
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

    /**
     * Returns the number of remote documents applied locally across all
     * collections.
     *
     * Pull order is dependency-first so FK resolution always finds a
     * registered cloud→local mapping when it is needed:
     *   projects → tags → habits → tasks → task_completions →
     *   habit_completions → habit_logs → milestones → task_templates
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    suspend fun pullRemoteChanges(): PullSummary {
        var applied = 0
        var skipped = 0
        // P0 sync audit PR-D. Tracks the subset of skips that came from
        // a thrown exception (SQLiteConstraintException, etc.) — the
        // permanent-data-loss kind that previously hid inside the warn
        // emit at pull.summary. Surfaced separately to fullSync so
        // sync.completed can flip status when non-zero.
        var skippedPermanent = 0

        val projectsResult = pullCollection("projects") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "project")
            if (localId == null) {
                val project = SyncMapper.mapToProject(data, cloudId = cloudId)
                val newId = projectDao.insert(project)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "project",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
                // Resolve any sort preference that was stashed while waiting for this project.
                sortPreferencesSyncService.notifyProjectSynced(cloudId)
            } else {
                val localProject = projectDao.getProjectByIdOnce(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localProject == null || remoteUpdatedAt > localProject.updatedAt) {
                    projectDao.update(SyncMapper.mapToProject(data, localId, cloudId = cloudId))
                    syncMetadataDao.clearPendingAction(localId, "project")
                }
            }
            true
        }
        applied += projectsResult.applied
        skipped += projectsResult.skipped
        skippedPermanent += projectsResult.skippedPermanent

        val tagsResult = pullCollection("tags") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "tag")
            if (localId == null) {
                val tag = SyncMapper.mapToTag(data, cloudId = cloudId)
                val newId = tagDao.insert(tag)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "tag",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val tag = SyncMapper.mapToTag(data, localId, cloudId = cloudId)
                tagDao.update(tag)
                syncMetadataDao.clearPendingAction(localId, "tag")
            }
            true
        }
        applied += tagsResult.applied
        skipped += tagsResult.skipped
        skippedPermanent += tagsResult.skippedPermanent

        // Habits before tasks: tasks may reference habits via sourceHabitId.
        val habitsResult = pullCollection("habits") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "habit")
            if (localId == null) {
                val habit = SyncMapper.mapToHabit(data, cloudId = cloudId)
                val newId = habitDao.insert(habit)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "habit",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val localHabit = habitDao.getHabitByIdOnce(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localHabit == null || remoteUpdatedAt > localHabit.updatedAt) {
                    habitDao.update(SyncMapper.mapToHabit(data, localId, cloudId = cloudId))
                    syncMetadataDao.clearPendingAction(localId, "habit")
                }
            }
            true
        }
        applied += habitsResult.applied
        skipped += habitsResult.skipped
        skippedPermanent += habitsResult.skippedPermanent

        val tasksResult = pullCollection("tasks") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "task")
            val projectCloudId = data["projectId"] as? String
            val projectLocalId = projectCloudId?.let { syncMetadataDao.getLocalId(it, "project") }
            // parentTaskId is a self-reference; parent may not be landed yet on first pull — accept null.
            val parentTaskCloudId = data["parentTaskId"] as? String
            val parentTaskLocalId = parentTaskCloudId?.let { syncMetadataDao.getLocalId(it, "task") }
            val sourceHabitCloudId = data["sourceHabitId"] as? String
            val sourceHabitLocalId = sourceHabitCloudId?.let { syncMetadataDao.getLocalId(it, "habit") }
            val phaseCloudId = data["phaseId"] as? String
            val phaseLocalId = phaseCloudId?.let { syncMetadataDao.getLocalId(it, "project_phase") }
            if (localId == null) {
                val task = SyncMapper.mapToTask(
                    data,
                    0,
                    projectLocalId,
                    parentTaskLocalId,
                    sourceHabitLocalId,
                    cloudId = cloudId,
                    phaseLocalId = phaseLocalId
                )
                val newId = taskDao.insert(task)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "task",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
                @Suppress("UNCHECKED_CAST")
                val cloudTagIds = data["tags"] as? List<String> ?: emptyList()
                for (cloudTagId in cloudTagIds) {
                    val tagLocalId = syncMetadataDao.getLocalId(cloudTagId, "tag") ?: continue
                    tagDao.addTagToTask(TaskTagCrossRef(taskId = newId, tagId = tagLocalId))
                }
            } else {
                val localTask = taskDao.getTaskByIdOnce(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localTask == null || remoteUpdatedAt > localTask.updatedAt) {
                    taskDao.update(
                        SyncMapper.mapToTask(
                            data,
                            localId,
                            projectLocalId,
                            parentTaskLocalId,
                            sourceHabitLocalId,
                            cloudId = cloudId,
                            phaseLocalId = phaseLocalId
                        )
                    )
                    syncMetadataDao.clearPendingAction(localId, "task")
                }
            }
            true
        }
        applied += tasksResult.applied
        skipped += tasksResult.skipped
        skippedPermanent += tasksResult.skippedPermanent

        // task_completions after tasks and projects so FK cloud IDs can be resolved.
        val taskCompletionsResult = pullCollection("task_completions") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "task_completion")
            val taskCloudId = data["taskId"] as? String
            val taskLocalId = taskCloudId?.let { syncMetadataDao.getLocalId(it, "task") }
            val projectCloudId = data["projectId"] as? String
            val projectLocalId = projectCloudId?.let { syncMetadataDao.getLocalId(it, "project") }
            if (localId == null) {
                val completion = SyncMapper.mapToTaskCompletion(data, 0, taskLocalId, projectLocalId, cloudId = cloudId)
                val newId = taskCompletionDao.insert(completion)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "task_completion",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            }
            true
        }
        applied += taskCompletionsResult.applied
        skipped += taskCompletionsResult.skipped
        skippedPermanent += taskCompletionsResult.skippedPermanent

        // task_timings after tasks so FK cloud IDs can be resolved.
        val taskTimingsResult = pullCollection("task_timings") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "task_timing")
            val taskCloudId = data["taskId"] as? String ?: return@pullCollection false
            val taskLocalId = syncMetadataDao.getLocalId(taskCloudId, "task") ?: return@pullCollection false
            if (localId == null) {
                val timing = SyncMapper.mapToTaskTiming(data, 0, taskLocalId, cloudId = cloudId)
                val newId = taskTimingDao.insert(timing)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "task_timing",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            }
            true
        }
        applied += taskTimingsResult.applied
        skipped += taskTimingsResult.skipped
        skippedPermanent += taskTimingsResult.skippedPermanent

        val habitCompletionsResult = pullCollection("habit_completions") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "habit_completion")
            val habitCloudId = data["habitCloudId"] as? String
                ?: return@pullCollection false
            val habitLocalId = syncMetadataDao.getLocalId(habitCloudId, "habit")
                ?: return@pullCollection false
            // P0 sync audit PR-A. Defensive guard for stale sync_metadata:
            // HabitRepository.deleteHabit and BackendSyncService.applyHabitChanges
            // (delete branch) both call habitDao.deleteById without removing the
            // sync_metadata row first, leaving cloud_id → local_id mappings that
            // resolve to a now-gone habits.id. Subsequent habit_completion pulls
            // for that cloudId resolve a non-null habitLocalId here, then
            // habitCompletionDao.insert below throws SQLiteConstraintException:
            // FOREIGN KEY constraint failed (Test 3, Session 1, 2026-04-27).
            //
            // Treat missing parent as a transient skip — the eventual pushDelete
            // will tombstone the Firestore doc, and the next pull's
            // processRemoteDeletions will reap the orphan completion uniformly
            // across devices. Do NOT delete the stale sync_metadata here: the
            // pending_action='delete' row is what pushDelete needs to find the
            // Firestore target. Architectural sweep (pair every deleteById with
            // syncMetadataDao.delete on the receive side) is PR-A2.
            if (habitDao.getHabitByIdOnce(habitLocalId) == null) {
                logger.warn(
                    operation = "pull.apply",
                    entity = "habit_completions",
                    id = cloudId,
                    status = "skipped_stale_parent",
                    detail = "habit local_id=$habitLocalId is gone; metadata pending eventual pushDelete"
                )
                return@pullCollection false
            }
            if (localId == null) {
                // mapToHabitCompletion always produces a non-null completedDateLocal
                // (either from the Firestore doc or derived from the epoch for
                // legacy docs), so no post-hoc re-normalization is needed.
                val completion = SyncMapper.mapToHabitCompletion(data, habitLocalId = habitLocalId, cloudId = cloudId)
                // Dedup by natural key (habitId, completedDateLocal) to avoid
                // duplicating completions seeded locally on both devices before sign-in.
                val existingByNaturalKey = completion.completedDateLocal?.let {
                    habitCompletionDao.getByHabitAndDateLocal(habitLocalId, it)
                }
                if (existingByNaturalKey != null) {
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = existingByNaturalKey.id,
                            entityType = "habit_completion",
                            cloudId = cloudId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    val newId = habitCompletionDao.insert(completion)
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = newId,
                            entityType = "habit_completion",
                            cloudId = cloudId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            true
        }
        applied += habitCompletionsResult.applied
        skipped += habitCompletionsResult.skipped
        skippedPermanent += habitCompletionsResult.skippedPermanent

        val habitLogsResult = pullCollection("habit_logs") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "habit_log")
            val habitCloudId = data["habitCloudId"] as? String
                ?: return@pullCollection false
            val habitLocalId = syncMetadataDao.getLocalId(habitCloudId, "habit")
                ?: return@pullCollection false
            // P0 sync audit PR-A — same defensive guard as habit_completions
            // above. habit_logs.habit_id has the same FK CASCADE shape, so
            // stale sync_metadata after a local habit delete causes an
            // identical SQLiteConstraintException on insert.
            if (habitDao.getHabitByIdOnce(habitLocalId) == null) {
                logger.warn(
                    operation = "pull.apply",
                    entity = "habit_logs",
                    id = cloudId,
                    status = "skipped_stale_parent",
                    detail = "habit local_id=$habitLocalId is gone; metadata pending eventual pushDelete"
                )
                return@pullCollection false
            }
            if (localId == null) {
                val log = SyncMapper.mapToHabitLog(data, habitLocalId = habitLocalId, cloudId = cloudId)
                val newId = habitLogDao.insertLog(log)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "habit_log",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            }
            true
        }
        applied += habitLogsResult.applied
        skipped += habitLogsResult.skipped
        skippedPermanent += habitLogsResult.skippedPermanent

        // Milestones after projects: projectCloudId must already be in sync_metadata.
        val milestonesResult = pullCollection("milestones") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "milestone")
            val projectCloudId = data["projectCloudId"] as? String
                ?: return@pullCollection false
            val projectLocalId = syncMetadataDao.getLocalId(projectCloudId, "project")
                ?: return@pullCollection false
            if (localId == null) {
                val milestone = SyncMapper.mapToMilestone(data, projectLocalId, cloudId = cloudId)
                val newId = milestoneDao.insert(milestone)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "milestone",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val localMilestone = milestoneDao.getByIdOnce(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localMilestone == null || remoteUpdatedAt > localMilestone.updatedAt) {
                    milestoneDao.update(SyncMapper.mapToMilestone(data, projectLocalId, localId, cloudId = cloudId))
                    syncMetadataDao.clearPendingAction(localId, "milestone")
                }
            }
            true
        }
        applied += milestonesResult.applied
        skipped += milestonesResult.skipped
        skippedPermanent += milestonesResult.skippedPermanent

        // Project phases (PrismTask-timeline-class scope, PR-1). Must come
        // after projects so projectCloudId resolves; same shape as milestones.
        val projectPhasesResult = pullCollection("project_phases") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "project_phase")
            val projectCloudId = data["projectCloudId"] as? String
                ?: return@pullCollection false
            val projectLocalId = syncMetadataDao.getLocalId(projectCloudId, "project")
                ?: return@pullCollection false
            if (localId == null) {
                val phase = SyncMapper.mapToProjectPhase(data, projectLocalId, cloudId = cloudId)
                val newId = projectPhaseDao.insert(phase)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "project_phase",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val localPhase = projectPhaseDao.getByIdOnce(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localPhase == null || remoteUpdatedAt > localPhase.updatedAt) {
                    projectPhaseDao.update(
                        SyncMapper.mapToProjectPhase(data, projectLocalId, localId, cloudId = cloudId)
                    )
                    syncMetadataDao.clearPendingAction(localId, "project_phase")
                }
            }
            true
        }
        applied += projectPhasesResult.applied
        skipped += projectPhasesResult.skipped
        skippedPermanent += projectPhasesResult.skippedPermanent

        val projectRisksResult = pullCollection("project_risks") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "project_risk")
            val projectCloudId = data["projectCloudId"] as? String
                ?: return@pullCollection false
            val projectLocalId = syncMetadataDao.getLocalId(projectCloudId, "project")
                ?: return@pullCollection false
            if (localId == null) {
                val risk = SyncMapper.mapToProjectRisk(data, projectLocalId, cloudId = cloudId)
                val newId = projectRiskDao.insert(risk)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "project_risk",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val localRisk = projectRiskDao.getByIdOnce(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localRisk == null || remoteUpdatedAt > localRisk.updatedAt) {
                    projectRiskDao.update(
                        SyncMapper.mapToProjectRisk(data, projectLocalId, localId, cloudId = cloudId)
                    )
                    syncMetadataDao.clearPendingAction(localId, "project_risk")
                }
            }
            true
        }
        applied += projectRisksResult.applied
        skipped += projectRisksResult.skipped
        skippedPermanent += projectRisksResult.skippedPermanent

        // Task dependencies (PrismTask-timeline-class scope, PR-2). Must
        // come after tasks so both endpoint cloud IDs resolve.
        val taskDepsResult = pullCollection("task_dependencies") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "task_dependency")
            val blockerCloudId = data["blockerTaskCloudId"] as? String
                ?: return@pullCollection false
            val blockedCloudId = data["blockedTaskCloudId"] as? String
                ?: return@pullCollection false
            val blockerLocalId = syncMetadataDao.getLocalId(blockerCloudId, "task")
                ?: return@pullCollection false
            val blockedLocalId = syncMetadataDao.getLocalId(blockedCloudId, "task")
                ?: return@pullCollection false
            if (localId == null) {
                val dep = SyncMapper.mapToTaskDependency(
                    data,
                    blockerTaskLocalId = blockerLocalId,
                    blockedTaskLocalId = blockedLocalId,
                    cloudId = cloudId
                )
                val newId = taskDependencyDao.insert(dep)
                // OnConflictStrategy.IGNORE returns -1 when the unique
                // index already holds the (blocker, blocked) pair —
                // skip metadata write in that case so the orphan
                // healer's enumeration doesn't see a phantom row.
                if (newId > 0) {
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = newId,
                            entityType = "task_dependency",
                            cloudId = cloudId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            // Dependencies are immutable once created — no update branch.
            true
        }
        applied += taskDepsResult.applied
        skipped += taskDepsResult.skipped
        skippedPermanent += taskDepsResult.skippedPermanent

        // External anchors (PrismTask-timeline-class scope, PR-3). Must
        // come after projects + project_phases so the FK columns
        // resolve. `phase_id` is nullable — anchors may be project-only.
        val externalAnchorsResult = pullCollection("external_anchors") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "external_anchor")
            val projectCloudId = data["projectCloudId"] as? String
                ?: return@pullCollection false
            val projectLocalId = syncMetadataDao.getLocalId(projectCloudId, "project")
                ?: return@pullCollection false
            val phaseCloudId = data["phaseCloudId"] as? String
            val phaseLocalId = phaseCloudId?.let { syncMetadataDao.getLocalId(it, "project_phase") }
            if (localId == null) {
                val anchor = SyncMapper.mapToExternalAnchor(
                    data,
                    projectLocalId,
                    phaseLocalId,
                    cloudId = cloudId
                )
                val newId = externalAnchorDao.insert(anchor)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "external_anchor",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val localRow = externalAnchorDao.getByIdOnce(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localRow == null || remoteUpdatedAt > localRow.updatedAt) {
                    externalAnchorDao.update(
                        SyncMapper.mapToExternalAnchor(
                            data,
                            projectLocalId,
                            phaseLocalId,
                            localId,
                            cloudId = cloudId
                        )
                    )
                    syncMetadataDao.clearPendingAction(localId, "external_anchor")
                }
            }
            true
        }
        applied += externalAnchorsResult.applied
        skipped += externalAnchorsResult.skipped
        skippedPermanent += externalAnchorsResult.skippedPermanent

        val taskTemplatesResult = pullCollection("task_templates") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "task_template")
            val templateProjectCloudId = data["templateProjectId"] as? String
            val templateProjectLocalId = templateProjectCloudId?.let { syncMetadataDao.getLocalId(it, "project") }
            if (localId == null) {
                val template = SyncMapper.mapToTaskTemplate(data, 0, templateProjectLocalId, cloudId = cloudId)
                val newId = taskTemplateDao.insertTemplate(template)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "task_template",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val localTemplate = taskTemplateDao.getTemplateById(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localTemplate == null || remoteUpdatedAt > localTemplate.updatedAt) {
                    taskTemplateDao.updateTemplate(SyncMapper.mapToTaskTemplate(data, localId, templateProjectLocalId, cloudId = cloudId))
                    syncMetadataDao.clearPendingAction(localId, "task_template")
                }
            }
            true
        }
        applied += taskTemplatesResult.applied
        skipped += taskTemplatesResult.skipped
        skippedPermanent += taskTemplatesResult.skippedPermanent

        // Courses before course_completions so courseCloudId FK can be resolved.
        val coursesResult = pullCollection("courses") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "course")
            if (localId == null) {
                val course = SyncMapper.mapToCourse(data, cloudId = cloudId)
                val newId = schoolworkDao.insertCourse(course)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "course",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val localCourse = schoolworkDao.getCourseById(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localCourse == null || remoteUpdatedAt > localCourse.updatedAt) {
                    schoolworkDao.updateCourse(SyncMapper.mapToCourse(data, localId, cloudId = cloudId))
                    syncMetadataDao.clearPendingAction(localId, "course")
                }
            }
            true
        }
        applied += coursesResult.applied
        skipped += coursesResult.skipped
        skippedPermanent += coursesResult.skippedPermanent

        val courseCompletionsResult = pullCollection("course_completions") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "course_completion")
            val courseCloudId = data["courseCloudId"] as? String
                ?: return@pullCollection false
            val courseLocalId = syncMetadataDao.getLocalId(courseCloudId, "course")
                ?: return@pullCollection false
            if (localId == null) {
                val completion = SyncMapper.mapToCourseCompletion(data, courseLocalId = courseLocalId, cloudId = cloudId)
                val newId = schoolworkDao.insertCompletion(completion)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "course_completion",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val localCompletion = schoolworkDao.getCompletionById(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localCompletion == null || remoteUpdatedAt > localCompletion.updatedAt) {
                    schoolworkDao.updateCompletion(SyncMapper.mapToCourseCompletion(data, localId, courseLocalId, cloudId = cloudId))
                    syncMetadataDao.clearPendingAction(localId, "course_completion")
                }
            }
            true
        }
        applied += courseCompletionsResult.applied
        skipped += courseCompletionsResult.skipped
        skippedPermanent += courseCompletionsResult.skippedPermanent

        val leisureLogsResult = pullCollection("leisure_logs") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "leisure_log")
            if (localId == null) {
                val log = SyncMapper.mapToLeisureLog(data, cloudId = cloudId)
                val newId = leisureDao.insertLog(log)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "leisure_log",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val localLog = leisureDao.getLogById(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localLog == null || remoteUpdatedAt > localLog.updatedAt) {
                    leisureDao.updateLog(SyncMapper.mapToLeisureLog(data, localId, cloudId = cloudId))
                    syncMetadataDao.clearPendingAction(localId, "leisure_log")
                }
            }
            true
        }
        applied += leisureLogsResult.applied
        skipped += leisureLogsResult.skipped
        skippedPermanent += leisureLogsResult.skippedPermanent

        // self_care_steps before self_care_logs (logical dependency).
        // Dedup by stepId+routineType to avoid duplicating built-in default steps
        // that are seeded locally on both devices before sign-in.
        val selfCareStepsResult = pullCollection("self_care_steps") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "self_care_step")
            if (localId == null) {
                val stepId = data["stepId"] as? String
                val routineType = data["routineType"] as? String
                val existingByStepId = if (stepId != null && routineType != null) {
                    selfCareDao.getStepByStepIdOnce(stepId, routineType)
                } else {
                    null
                }
                if (existingByStepId != null) {
                    // Link existing local step to this cloud doc instead of duplicating.
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = existingByStepId.id,
                            entityType = "self_care_step",
                            cloudId = cloudId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    val step = SyncMapper.mapToSelfCareStep(data, cloudId = cloudId)
                    val newId = selfCareDao.insertStep(step)
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = newId,
                            entityType = "self_care_step",
                            cloudId = cloudId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                }
            } else {
                val localStep = selfCareDao.getStepById(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localStep == null || remoteUpdatedAt > localStep.updatedAt) {
                    selfCareDao.updateStep(SyncMapper.mapToSelfCareStep(data, localId, cloudId = cloudId))
                    syncMetadataDao.clearPendingAction(localId, "self_care_step")
                }
            }
            true
        }
        applied += selfCareStepsResult.applied
        skipped += selfCareStepsResult.skipped
        skippedPermanent += selfCareStepsResult.skippedPermanent

        val selfCareLogsResult = pullCollection("self_care_logs") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "self_care_log")
            if (localId == null) {
                val log = SyncMapper.mapToSelfCareLog(data, cloudId = cloudId)
                val newId = selfCareDao.insertLog(log)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "self_care_log",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val localLog = selfCareDao.getLogById(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localLog == null || remoteUpdatedAt > localLog.updatedAt) {
                    selfCareDao.updateLog(SyncMapper.mapToSelfCareLog(data, localId, cloudId = cloudId))
                    syncMetadataDao.clearPendingAction(localId, "self_care_log")
                }
            }
            true
        }
        applied += selfCareLogsResult.applied
        skipped += selfCareLogsResult.skipped
        skippedPermanent += selfCareLogsResult.skippedPermanent

        // medication_slots BEFORE medications so the junction rebuild lands
        // cleanly (medication pull embeds slotCloudIds that need local IDs).
        val medicationSlotsResult = pullCollection("medication_slots") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "medication_slot")
            if (localId == null) {
                val incoming = MedicationSyncMapper.mapToMedicationSlot(data, cloudId = cloudId)
                // Natural-key dedup before INSERT (medication sync audit PR-A).
                // medication_slots.name is not UNIQUE, so a fresh INSERT won't
                // throw — but it produces visible duplicate slots ("Morning",
                // "Morning") when both devices seeded the built-in slots
                // independently and then pulled each other's docs. Adopt the
                // existing same-name local row instead; bind its cloud_id and
                // apply last-write-wins. Pattern mirrors medications-by-name
                // dedup at lines 2103–2142 below.
                val existingByName = medicationSlotDao.getByNameOnce(incoming.name)
                if (existingByName != null) {
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = existingByName.id,
                            entityType = "medication_slot",
                            cloudId = cloudId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                    val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                    if (remoteUpdatedAt > existingByName.updatedAt) {
                        medicationSlotDao.update(incoming.copy(id = existingByName.id))
                    }
                } else {
                    val newId = medicationSlotDao.insert(incoming)
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = newId,
                            entityType = "medication_slot",
                            cloudId = cloudId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                }
            } else {
                val local = medicationSlotDao.getByIdOnce(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (local == null || remoteUpdatedAt > local.updatedAt) {
                    medicationSlotDao.update(
                        MedicationSyncMapper.mapToMedicationSlot(data, localId, cloudId = cloudId)
                    )
                    syncMetadataDao.clearPendingAction(localId, "medication_slot")
                }
            }
            true
        }
        applied += medicationSlotsResult.applied
        skipped += medicationSlotsResult.skipped
        skippedPermanent += medicationSlotsResult.skippedPermanent

        // medications BEFORE medication_doses so the FK resolution lands.
        // Junction rebuild: after every medication pull, replace its
        // `medication_medication_slots` row set with the slot cloud-ids
        // embedded on the Firestore doc (resolved via sync_metadata).
        val medicationsResult = pullCollection("medications") { data, cloudId ->
            val localId = syncMetadataDao.getLocalId(cloudId, "medication")
            val resolvedLocalId = if (localId == null) {
                val incoming = MedicationSyncMapper.mapToMedication(data, cloudId = cloudId)
                // Natural-key dedup before INSERT (P0 sync audit PR-B).
                // medications.name carries a UNIQUE index, so a plain INSERT
                // throws SQLiteConstraintException when both devices ran the
                // v53→v54 backfill independently and pulled each other's
                // cloud_ids. Adopt the existing same-name local row instead;
                // bind its cloud_id and apply last-write-wins. Pattern mirrors
                // the habit_completions natural-key dedup at lines 1682–1693.
                val existingByName = medicationDao.getByNameOnce(incoming.name)
                if (existingByName != null) {
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = existingByName.id,
                            entityType = "medication",
                            cloudId = cloudId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                    val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                    if (remoteUpdatedAt > existingByName.updatedAt) {
                        medicationDao.update(
                            incoming.copy(id = existingByName.id)
                        )
                    }
                    existingByName.id
                } else {
                    val newId = medicationDao.insert(incoming)
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = newId,
                            entityType = "medication",
                            cloudId = cloudId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                    newId
                }
            } else {
                val localMed = medicationDao.getByIdOnce(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (localMed == null || remoteUpdatedAt > localMed.updatedAt) {
                    medicationDao.update(
                        MedicationSyncMapper.mapToMedication(data, localId, cloudId = cloudId)
                    )
                    syncMetadataDao.clearPendingAction(localId, "medication")
                }
                localId
            }
            // Always rebuild junction from the embedded list — even on a
            // seen-update skip, the other device may have added / removed a
            // slot link without bumping `updatedAt` in a way we observed.
            val slotCloudIds = MedicationSyncMapper.extractSlotCloudIds(data)
            val slotLocalIds = slotCloudIds.mapNotNull {
                syncMetadataDao.getLocalId(it, "medication_slot")
            }
            medicationSlotDao.deleteLinksForMedication(resolvedLocalId)
            if (slotLocalIds.isNotEmpty()) {
                medicationSlotDao.insertLinks(
                    slotLocalIds.distinct().map {
                        com.averycorp.prismtask.data.local.entity.MedicationSlotCrossRef(
                            medicationId = resolvedLocalId,
                            slotId = it
                        )
                    }
                )
            }
            true
        }
        applied += medicationsResult.applied
        skipped += medicationsResult.skipped
        skippedPermanent += medicationsResult.skippedPermanent

        val medicationDosesResult = pullCollection("medication_doses") { data, cloudId ->
            // Custom doses (no parent medication) come down with a null
            // medicationCloudId — accept those and insert with medicationId=null.
            // Tracked-medication doses still require the FK to resolve
            // locally; if the parent hasn't pulled yet, skip and retry next cycle.
            val medCloudId = data["medicationCloudId"] as? String
            val medLocalId = medCloudId?.let { syncMetadataDao.getLocalId(it, "medication") }
            if (medCloudId != null && medLocalId == null) return@pullCollection false
            val localId = syncMetadataDao.getLocalId(cloudId, "medication_dose")
            if (localId == null) {
                val dose = MedicationSyncMapper.mapToMedicationDose(
                    data,
                    medicationLocalId = medLocalId,
                    cloudId = cloudId
                )
                val newId = medicationDoseDao.insert(dose)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "medication_dose",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                // Doses are append-only — no updates. If the same
                // cloudId arrives again, it's a no-op duplicate push
                // and the existing row wins.
                syncMetadataDao.clearPendingAction(localId, "medication_dose")
            }
            true
        }
        applied += medicationDosesResult.applied
        skipped += medicationDosesResult.skipped
        skippedPermanent += medicationDosesResult.skippedPermanent

        val medicationSlotOverridesResult = pullCollection("medication_slot_overrides") { data, cloudId ->
            val medCloudId = data["medicationCloudId"] as? String ?: return@pullCollection false
            val slotCloudId = data["slotCloudId"] as? String ?: return@pullCollection false
            val medLocalId = syncMetadataDao.getLocalId(medCloudId, "medication")
                ?: return@pullCollection false
            val slotLocalId = syncMetadataDao.getLocalId(slotCloudId, "medication_slot")
                ?: return@pullCollection false
            val localId = syncMetadataDao.getLocalId(cloudId, "medication_slot_override")
            if (localId == null) {
                val incoming = MedicationSyncMapper.mapToMedicationSlotOverride(
                    data,
                    medicationLocalId = medLocalId,
                    slotLocalId = slotLocalId,
                    cloudId = cloudId
                )
                // Natural-key dedup before INSERT (medication sync audit PR-A).
                // medication_slot_overrides has UNIQUE(medication_id, slot_id),
                // so a fresh INSERT throws SQLiteConstraintException when both
                // devices created an override for the same (med, slot) pair
                // and pulled each other's docs. Adopt the existing local row;
                // bind its cloud_id and apply last-write-wins. Pattern mirrors
                // medications-by-name dedup at the medications block above.
                val existingByPair = medicationSlotOverrideDao.getForPairOnce(medLocalId, slotLocalId)
                if (existingByPair != null) {
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = existingByPair.id,
                            entityType = "medication_slot_override",
                            cloudId = cloudId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                    val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                    if (remoteUpdatedAt > existingByPair.updatedAt) {
                        medicationSlotOverrideDao.update(incoming.copy(id = existingByPair.id))
                    }
                } else {
                    val newId = medicationSlotOverrideDao.insert(incoming)
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = newId,
                            entityType = "medication_slot_override",
                            cloudId = cloudId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                }
            } else {
                val local = medicationSlotOverrideDao.getByIdOnce(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (local == null || remoteUpdatedAt > local.updatedAt) {
                    medicationSlotOverrideDao.update(
                        MedicationSyncMapper.mapToMedicationSlotOverride(
                            data,
                            localId = localId,
                            medicationLocalId = medLocalId,
                            slotLocalId = slotLocalId,
                            cloudId = cloudId
                        )
                    )
                    syncMetadataDao.clearPendingAction(localId, "medication_slot_override")
                }
            }
            true
        }
        applied += medicationSlotOverridesResult.applied
        skipped += medicationSlotOverridesResult.skipped
        skippedPermanent += medicationSlotOverridesResult.skippedPermanent

        val medicationTierStatesResult = pullCollection("medication_tier_states") { data, cloudId ->
            val medCloudId = data["medicationCloudId"] as? String ?: return@pullCollection false
            val slotCloudId = data["slotCloudId"] as? String ?: return@pullCollection false
            val medLocalId = syncMetadataDao.getLocalId(medCloudId, "medication")
                ?: return@pullCollection false
            val slotLocalId = syncMetadataDao.getLocalId(slotCloudId, "medication_slot")
                ?: return@pullCollection false
            val localId = syncMetadataDao.getLocalId(cloudId, "medication_tier_state")
            if (localId == null) {
                val incoming = MedicationSyncMapper.mapToMedicationTierState(
                    data,
                    medicationLocalId = medLocalId,
                    slotLocalId = slotLocalId,
                    cloudId = cloudId
                )
                // Natural-key dedup before INSERT (medication sync audit PR-A).
                // medication_tier_states has UNIQUE(medication_id, log_date,
                // slot_id), so a fresh INSERT throws SQLiteConstraintException
                // when both devices logged the same (med, slot, day) tier and
                // pulled each other's docs. Adopt the existing local row;
                // bind its cloud_id and apply last-write-wins. Pattern mirrors
                // medications-by-name dedup at the medications block above.
                val existingByTriple = medicationTierStateDao.getForTripleOnce(
                    medicationId = medLocalId,
                    date = incoming.logDate,
                    slotId = slotLocalId
                )
                if (existingByTriple != null) {
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = existingByTriple.id,
                            entityType = "medication_tier_state",
                            cloudId = cloudId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                    val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                    if (remoteUpdatedAt > existingByTriple.updatedAt) {
                        medicationTierStateDao.update(incoming.copy(id = existingByTriple.id))
                    }
                } else {
                    val newId = medicationTierStateDao.insert(incoming)
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = newId,
                            entityType = "medication_tier_state",
                            cloudId = cloudId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                }
            } else {
                val local = medicationTierStateDao.getByIdOnce(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (local == null || remoteUpdatedAt > local.updatedAt) {
                    medicationTierStateDao.update(
                        MedicationSyncMapper.mapToMedicationTierState(
                            data,
                            localId = localId,
                            medicationLocalId = medLocalId,
                            slotLocalId = slotLocalId,
                            cloudId = cloudId
                        )
                    )
                    syncMetadataDao.clearPendingAction(localId, "medication_tier_state")
                }
            }
            true
        }
        applied += medicationTierStatesResult.applied
        skipped += medicationTierStatesResult.skipped
        skippedPermanent += medicationTierStatesResult.skippedPermanent

        // v1.4.37 Room config families — last-write-wins per-row using updatedAt.
        val notificationProfilesResult = pullRoomConfigFamily(
            collection = "notification_profiles",
            entityType = "notification_profile",
            getLocalUpdatedAt = { notificationProfileDao.getById(it)?.updatedAt },
            insert = { data, cloudId ->
                notificationProfileDao.insert(SyncMapper.mapToNotificationProfile(data, cloudId = cloudId))
            },
            update = { data, localId, cloudId ->
                notificationProfileDao.update(SyncMapper.mapToNotificationProfile(data, localId, cloudId))
            }
        )
        applied += notificationProfilesResult.applied
        skipped += notificationProfilesResult.skipped
        skippedPermanent += notificationProfilesResult.skippedPermanent

        val customSoundsResult = pullRoomConfigFamily(
            collection = "custom_sounds",
            entityType = "custom_sound",
            getLocalUpdatedAt = { customSoundDao.getById(it)?.updatedAt },
            insert = { data, cloudId ->
                customSoundDao.insert(SyncMapper.mapToCustomSound(data, cloudId = cloudId))
            },
            update = { data, localId, cloudId ->
                customSoundDao.update(SyncMapper.mapToCustomSound(data, localId, cloudId))
            }
        )
        applied += customSoundsResult.applied
        skipped += customSoundsResult.skipped
        skippedPermanent += customSoundsResult.skippedPermanent

        val savedFiltersResult = pullRoomConfigFamily(
            collection = "saved_filters",
            entityType = "saved_filter",
            getLocalUpdatedAt = { savedFilterDao.getByIdOnce(it)?.updatedAt },
            insert = { data, cloudId ->
                savedFilterDao.insert(SyncMapper.mapToSavedFilter(data, cloudId = cloudId))
            },
            update = { data, localId, cloudId ->
                savedFilterDao.update(SyncMapper.mapToSavedFilter(data, localId, cloudId))
            }
        )
        applied += savedFiltersResult.applied
        skipped += savedFiltersResult.skipped
        skippedPermanent += savedFiltersResult.skippedPermanent

        val nlpShortcutsResult = pullRoomConfigFamily(
            collection = "nlp_shortcuts",
            entityType = "nlp_shortcut",
            getLocalUpdatedAt = { nlpShortcutDao.getByIdOnce(it)?.updatedAt },
            insert = { data, cloudId ->
                nlpShortcutDao.insert(SyncMapper.mapToNlpShortcut(data, cloudId = cloudId))
            },
            update = { data, localId, cloudId ->
                nlpShortcutDao.update(SyncMapper.mapToNlpShortcut(data, localId, cloudId))
            },
            // P0 sync audit PR-C: nlp_shortcuts.trigger is UNIQUE; same-trigger
            // built-in shortcuts created independently on each device must dedup
            // on pull, not throw.
            naturalKeyLookup = { data ->
                val trigger = data["trigger"] as? String
                trigger?.let { nlpShortcutDao.getByTrigger(it)?.id }
            }
        )
        applied += nlpShortcutsResult.applied
        skipped += nlpShortcutsResult.skipped
        skippedPermanent += nlpShortcutsResult.skippedPermanent

        val habitTemplatesResult = pullRoomConfigFamily(
            collection = "habit_templates",
            entityType = "habit_template",
            getLocalUpdatedAt = { habitTemplateDao.getById(it)?.updatedAt },
            insert = { data, cloudId ->
                habitTemplateDao.insert(SyncMapper.mapToHabitTemplate(data, cloudId = cloudId))
            },
            update = { data, localId, cloudId ->
                habitTemplateDao.update(SyncMapper.mapToHabitTemplate(data, localId, cloudId))
            }
        )
        applied += habitTemplatesResult.applied
        skipped += habitTemplatesResult.skipped
        skippedPermanent += habitTemplatesResult.skippedPermanent

        val projectTemplatesResult = pullRoomConfigFamily(
            collection = "project_templates",
            entityType = "project_template",
            getLocalUpdatedAt = { projectTemplateDao.getById(it)?.updatedAt },
            insert = { data, cloudId ->
                projectTemplateDao.insert(SyncMapper.mapToProjectTemplate(data, cloudId = cloudId))
            },
            update = { data, localId, cloudId ->
                projectTemplateDao.update(SyncMapper.mapToProjectTemplate(data, localId, cloudId))
            }
        )
        applied += projectTemplatesResult.applied
        skipped += projectTemplatesResult.skipped
        skippedPermanent += projectTemplatesResult.skippedPermanent

        val boundaryRulesResult = pullRoomConfigFamily(
            collection = "boundary_rules",
            entityType = "boundary_rule",
            getLocalUpdatedAt = { boundaryRuleDao.getByIdOnce(it)?.updatedAt },
            insert = { data, cloudId ->
                boundaryRuleDao.insert(SyncMapper.mapToBoundaryRule(data, cloudId = cloudId))
            },
            update = { data, localId, cloudId ->
                boundaryRuleDao.update(SyncMapper.mapToBoundaryRule(data, localId, cloudId))
            }
        )
        applied += boundaryRulesResult.applied
        skipped += boundaryRulesResult.skipped
        skippedPermanent += boundaryRulesResult.skippedPermanent

        val automationRulesResult = pullRoomConfigFamily(
            collection = "automation_rules",
            entityType = "automation_rule",
            getLocalUpdatedAt = { automationRuleDao.getByIdOnce(it)?.updatedAt },
            insert = { data, cloudId ->
                automationRuleDao.insert(SyncMapper.mapToAutomationRule(data, cloudId = cloudId))
            },
            update = { data, localId, cloudId ->
                automationRuleDao.update(SyncMapper.mapToAutomationRule(data, localId, cloudId))
            },
            // Same-template-imported-on-two-devices dedup: when a peer's
            // import of template X arrives, adopt the existing local row
            // whose template_key already matches instead of creating a
            // second row. Skipped when templateKey is null (user-authored
            // rules carry no template identity and must not collapse).
            naturalKeyLookup = { data ->
                (data["templateKey"] as? String)?.let { key ->
                    automationRuleDao.getByTemplateKeyOnce(key)?.id
                }
            }
        )
        applied += automationRulesResult.applied
        skipped += automationRulesResult.skipped
        skippedPermanent += automationRulesResult.skippedPermanent

        // v1.4.38 content families (FK-free) — same LWW semantics as above.
        val checkInLogsResult = pullRoomConfigFamily(
            collection = "check_in_logs",
            entityType = "check_in_log",
            getLocalUpdatedAt = { checkInLogDao.getByIdOnce(it)?.updatedAt },
            insert = { data, cloudId ->
                checkInLogDao.upsert(SyncMapper.mapToCheckInLog(data, cloudId = cloudId))
            },
            update = { data, localId, cloudId ->
                checkInLogDao.upsert(SyncMapper.mapToCheckInLog(data, localId, cloudId))
            },
            // P0 sync audit PR-C: check_in_logs.date is UNIQUE; same-day
            // logs created on both devices offline must dedup on pull.
            naturalKeyLookup = { data ->
                val date = (data["date"] as? Number)?.toLong()
                date?.let { checkInLogDao.getByDate(it)?.id }
            }
        )
        applied += checkInLogsResult.applied
        skipped += checkInLogsResult.skipped
        skippedPermanent += checkInLogsResult.skippedPermanent

        val moodEnergyLogsResult = pullRoomConfigFamily(
            collection = "mood_energy_logs",
            entityType = "mood_energy_log",
            getLocalUpdatedAt = { moodEnergyLogDao.getByIdOnce(it)?.updatedAt },
            insert = { data, cloudId ->
                moodEnergyLogDao.insert(SyncMapper.mapToMoodEnergyLog(data, cloudId = cloudId))
            },
            update = { data, localId, cloudId ->
                moodEnergyLogDao.update(SyncMapper.mapToMoodEnergyLog(data, localId, cloudId))
            },
            // P0 sync audit PR-C: mood_energy_logs.(date, time_of_day) is
            // UNIQUE; same date+slot logs created on both devices offline
            // must dedup on pull.
            naturalKeyLookup = { data ->
                val date = (data["date"] as? Number)?.toLong()
                val timeOfDay = data["timeOfDay"] as? String
                if (date != null && timeOfDay != null) {
                    moodEnergyLogDao.getByDateAndTimeOfDayOnce(date, timeOfDay)?.id
                } else {
                    null
                }
            }
        )
        applied += moodEnergyLogsResult.applied
        skipped += moodEnergyLogsResult.skipped
        skippedPermanent += moodEnergyLogsResult.skippedPermanent

        val medicationRefillsResult = pullRoomConfigFamily(
            collection = "medication_refills",
            entityType = "medication_refill",
            getLocalUpdatedAt = { medicationRefillDao.getByIdOnce(it)?.updatedAt },
            insert = { data, cloudId ->
                medicationRefillDao.upsert(SyncMapper.mapToMedicationRefill(data, cloudId = cloudId))
            },
            update = { data, localId, cloudId ->
                medicationRefillDao.update(SyncMapper.mapToMedicationRefill(data, localId, cloudId))
            },
            // P0 sync audit PR-C (RED): medication_refills.medication_name
            // is UNIQUE. Same migration-backfill shape as PR-B's
            // medications.name fix — both devices' v53→v54 backfills can
            // produce same-name refill rows that must dedup on pull.
            naturalKeyLookup = { data ->
                val name = data["medicationName"] as? String
                name?.let { medicationRefillDao.getByName(it)?.id }
            }
        )
        applied += medicationRefillsResult.applied
        skipped += medicationRefillsResult.skipped
        skippedPermanent += medicationRefillsResult.skippedPermanent

        val weeklyReviewsResult = pullRoomConfigFamily(
            collection = "weekly_reviews",
            entityType = "weekly_review",
            getLocalUpdatedAt = { weeklyReviewDao.getByIdOnce(it)?.updatedAt },
            insert = { data, cloudId ->
                weeklyReviewDao.upsert(SyncMapper.mapToWeeklyReview(data, cloudId = cloudId))
            },
            update = { data, localId, cloudId ->
                weeklyReviewDao.upsert(SyncMapper.mapToWeeklyReview(data, localId, cloudId))
            },
            // P0 sync audit PR-C: weekly_reviews.week_start_date is UNIQUE;
            // same-week reviews drafted on both devices offline must dedup
            // on pull.
            naturalKeyLookup = { data ->
                val weekStart = (data["weekStartDate"] as? Number)?.toLong()
                weekStart?.let { weeklyReviewDao.getByWeek(it)?.id }
            }
        )
        applied += weeklyReviewsResult.applied
        skipped += weeklyReviewsResult.skipped
        skippedPermanent += weeklyReviewsResult.skippedPermanent

        val dailyEssentialSlotResult = pullRoomConfigFamily(
            collection = "daily_essential_slot_completions",
            entityType = "daily_essential_slot_completion",
            getLocalUpdatedAt = { dailyEssentialSlotCompletionDao.getByIdOnce(it)?.updatedAt },
            insert = { data, cloudId ->
                dailyEssentialSlotCompletionDao.upsert(
                    SyncMapper.mapToDailyEssentialSlotCompletion(data, cloudId = cloudId)
                )
            },
            update = { data, localId, cloudId ->
                dailyEssentialSlotCompletionDao.upsert(
                    SyncMapper.mapToDailyEssentialSlotCompletion(data, localId, cloudId)
                )
            },
            // P0 sync audit PR-C: daily_essential_slot_completions
            // (date, slot_key) is UNIQUE; same slot completions toggled on
            // both devices offline must dedup on pull.
            naturalKeyLookup = { data ->
                val date = (data["date"] as? Number)?.toLong()
                val slotKey = data["slotKey"] as? String
                if (date != null && slotKey != null) {
                    dailyEssentialSlotCompletionDao.getBySlotOnce(date, slotKey)?.id
                } else {
                    null
                }
            }
        )
        applied += dailyEssentialSlotResult.applied
        skipped += dailyEssentialSlotResult.skipped
        skippedPermanent += dailyEssentialSlotResult.skippedPermanent

        // v1.4.38 content families with FK translation.
        val focusReleaseLogsResult = pullCollection("focus_release_logs") { data, cloudId ->
            val taskCloudId = data["taskId"] as? String
            val taskLocalId = taskCloudId?.let { syncMetadataDao.getLocalId(it, "task") }
            val localId = syncMetadataDao.getLocalId(cloudId, "focus_release_log")
            if (localId == null) {
                val log = SyncMapper.mapToFocusReleaseLog(data, 0, taskLocalId, cloudId)
                val newId = focusReleaseLogDao.insert(log)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "focus_release_log",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                // Focus-release logs are append-only; same cloudId arriving again is a no-op.
                syncMetadataDao.clearPendingAction(localId, "focus_release_log")
            }
            true
        }
        applied += focusReleaseLogsResult.applied
        skipped += focusReleaseLogsResult.skipped
        skippedPermanent += focusReleaseLogsResult.skippedPermanent

        val assignmentsResult = pullCollection("assignments") { data, cloudId ->
            val courseCloudId = data["courseId"] as? String ?: return@pullCollection false
            val courseLocalId = syncMetadataDao.getLocalId(courseCloudId, "course")
                ?: return@pullCollection false
            val localId = syncMetadataDao.getLocalId(cloudId, "assignment")
            if (localId == null) {
                val assignment = SyncMapper.mapToAssignment(data, 0, courseLocalId, cloudId)
                val newId = schoolworkDao.insertAssignment(assignment)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "assignment",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val local = schoolworkDao.getAssignmentById(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (local == null || remoteUpdatedAt > local.updatedAt) {
                    schoolworkDao.updateAssignment(
                        SyncMapper.mapToAssignment(data, localId, courseLocalId, cloudId)
                    )
                    syncMetadataDao.clearPendingAction(localId, "assignment")
                }
            }
            true
        }
        applied += assignmentsResult.applied
        skipped += assignmentsResult.skipped
        skippedPermanent += assignmentsResult.skippedPermanent

        val attachmentsResult = pullCollection("attachments") { data, cloudId ->
            val taskCloudId = data["taskId"] as? String ?: return@pullCollection false
            val taskLocalId = syncMetadataDao.getLocalId(taskCloudId, "task")
                ?: return@pullCollection false
            val localId = syncMetadataDao.getLocalId(cloudId, "attachment")
            if (localId == null) {
                val attachment = SyncMapper.mapToAttachment(data, 0, taskLocalId, cloudId)
                val newId = attachmentDao.insert(attachment)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "attachment",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                // Attachments are effectively immutable after insert — same cloudId
                // arriving again is a no-op. Clearing pending_action keeps the
                // sync_metadata tidy.
                syncMetadataDao.clearPendingAction(localId, "attachment")
            }
            true
        }
        applied += attachmentsResult.applied
        skipped += attachmentsResult.skipped
        skippedPermanent += attachmentsResult.skippedPermanent

        val studyLogsResult = pullCollection("study_logs") { data, cloudId ->
            val coursePickCloudId = data["coursePick"] as? String
            val assignmentPickCloudId = data["assignmentPick"] as? String
            val coursePickLocalId = coursePickCloudId?.let { syncMetadataDao.getLocalId(it, "course") }
            val assignmentPickLocalId = assignmentPickCloudId?.let {
                syncMetadataDao.getLocalId(it, "assignment")
            }
            val localId = syncMetadataDao.getLocalId(cloudId, "study_log")
            if (localId == null) {
                val log = SyncMapper.mapToStudyLog(
                    data,
                    0,
                    coursePickLocalId,
                    assignmentPickLocalId,
                    cloudId
                )
                val newId = schoolworkDao.insertLog(log)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = "study_log",
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val local = schoolworkDao.getStudyLogByIdOnce(localId)
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (local == null || remoteUpdatedAt > local.updatedAt) {
                    schoolworkDao.updateLog(
                        SyncMapper.mapToStudyLog(
                            data,
                            localId,
                            coursePickLocalId,
                            assignmentPickLocalId,
                            cloudId
                        )
                    )
                    syncMetadataDao.clearPendingAction(localId, "study_log")
                }
            }
            true
        }
        applied += studyLogsResult.applied
        skipped += studyLogsResult.skipped
        skippedPermanent += studyLogsResult.skippedPermanent

        // P0 sync audit PR-D. Promote pull.summary to status=error only
        // when at least one doc threw an exception during apply (the
        // permanent-data-loss kind). Routine transient skips (handler
        // returned false because a parent FK hadn't been pulled yet)
        // stay at status=warning since they self-heal on the next pull.
        when {
            skippedPermanent > 0 -> {
                logger.error(
                    operation = "pull.summary",
                    entity = "all",
                    status = "error",
                    detail = "applied=$applied skipped=$skipped permanent=$skippedPermanent — " +
                        "check pull.apply status=failed logs for details"
                )
            }
            skipped > 0 -> {
                logger.warn(
                    operation = "pull.summary",
                    entity = "all",
                    status = "warning",
                    detail = "applied=$applied skipped=$skipped (all transient — child waiting on parent) — see pull.apply for details"
                )
            }
            else -> {
                logger.info(
                    operation = "pull.summary",
                    entity = "all",
                    status = "success",
                    detail = "applied=$applied skipped=0"
                )
            }
        }
        return PullSummary(applied = applied, skippedPermanent = skippedPermanent)
    }

    /**
     * Result of a [pullRemoteChanges] cycle. P0 sync audit PR-D — exposes
     * `skippedPermanent` so [fullSync] can plumb it into
     * `markSyncCompleted` and flip `sync.completed` to a data-loss
     * status when any doc threw during apply.
     */
    data class PullSummary(val applied: Int, val skippedPermanent: Int)

    /**
     * Handler returns `true` if the document was applied, `false` if it was
     * intentionally skipped (e.g. missing FK reference — handler chose to
     * defer; will be retried on next pull). Exceptions are caught and
     * counted as PERMANENT skips (data loss for that doc until external
     * intervention).
     *
     * P0 sync audit PR-D. Pre-fix this method collapsed both outcomes into
     * a single `skipped` counter, and `pull.summary` then emitted
     * `status=warning` for any non-zero skip count. Tooling that watched
     * for `status=warning` was implicitly tuned to ignore it (since
     * transient FK skips on first-ever pull are routine), masking real
     * SQLiteConstraintException data loss. The split lets `pull.summary`
     * promote to `status=error` only when at least one doc threw — a
     * signal the user should actually see.
     */
    private suspend fun pullCollection(
        name: String,
        handler: suspend (Map<String, Any?>, String) -> Boolean
    ): PullResult {
        val snapshot = userCollection(name)?.get()?.await() ?: return PullResult(0, 0, 0)
        var applied = 0
        var skippedTransient = 0
        var skippedPermanent = 0
        for (doc in snapshot.documents) {
            val data = doc.data ?: continue
            try {
                if (handler(data, doc.id)) applied++ else skippedTransient++
            } catch (e: Exception) {
                skippedPermanent++
                logger.error(
                    operation = "pull.apply",
                    entity = name,
                    id = doc.id,
                    throwable = e
                )
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
        }
        return PullResult(applied, skippedTransient, skippedPermanent)
    }

    private data class PullResult(
        val applied: Int,
        val skippedTransient: Int,
        val skippedPermanent: Int
    ) {
        val skipped: Int get() = skippedTransient + skippedPermanent
    }

    /**
     * Pull helper for the v1.4.37 Room-entity config families. Identical
     * upsert semantics across all 7: insert-if-missing, else apply remote
     * only when `remoteUpdatedAt > localUpdatedAt` (last-write-wins).
     *
     * P0 sync audit PR-C: optional [naturalKeyLookup] handles entities
     * whose schema carries a non-`cloud_id` UNIQUE index (e.g.
     * `medication_refills.medication_name`,
     * `mood_energy_logs.(date, time_of_day)`,
     * `nlp_shortcuts.trigger`). Without this, a plain INSERT on the
     * `localId == null` branch throws SQLiteConstraintException whenever
     * both devices created a same-natural-key row offline before sync.
     * When supplied, the lookup runs first; on hit, bind the incoming
     * cloud_id to the existing local row and apply last-write-wins
     * against the existing `updatedAt`. INSERT only on a true miss
     * (neither cloud_id nor natural key matches).
     *
     * Pattern mirrors the inline habit_completions natural-key dedup at
     * `SyncService.kt:1682–1693` and the medications dedup added in PR-B.
     */
    private suspend fun pullRoomConfigFamily(
        collection: String,
        entityType: String,
        getLocalUpdatedAt: suspend (Long) -> Long?,
        insert: suspend (Map<String, Any?>, String) -> Long,
        update: suspend (Map<String, Any?>, Long, String) -> Unit,
        naturalKeyLookup: (suspend (Map<String, Any?>) -> Long?)? = null
    ): PullResult = pullCollection(collection) { data, cloudId ->
        val localId = syncMetadataDao.getLocalId(cloudId, entityType)
        if (localId == null) {
            val existingLocalId = naturalKeyLookup?.invoke(data)
            if (existingLocalId != null) {
                // Adopt: bind the incoming cloud_id to the existing
                // local row. Apply last-write-wins against the local
                // updatedAt before binding metadata so the user's view
                // reflects whichever side wrote most recently.
                val localUpdatedAt = getLocalUpdatedAt(existingLocalId) ?: 0L
                val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                if (remoteUpdatedAt > localUpdatedAt) {
                    update(data, existingLocalId, cloudId)
                }
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = existingLocalId,
                        entityType = entityType,
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val newId = insert(data, cloudId)
                syncMetadataDao.upsert(
                    SyncMetadataEntity(
                        localId = newId,
                        entityType = entityType,
                        cloudId = cloudId,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            }
        } else {
            val localUpdatedAt = getLocalUpdatedAt(localId) ?: 0L
            val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
            if (remoteUpdatedAt > localUpdatedAt) {
                update(data, localId, cloudId)
                syncMetadataDao.clearPendingAction(localId, entityType)
            }
        }
        true
    }

    suspend fun fullSync(trigger: String = "manual") {
        if (isSyncing) {
            logger.debug(
                operation = "sync.skipped",
                entity = "service",
                id = "firebase",
                status = "already_running",
                detail = "trigger=$trigger"
            )
            return
        }
        isSyncing = true
        val start = System.currentTimeMillis()
        syncStateRepository.markSyncStarted(source = SOURCE_FIREBASE, trigger = trigger)
        var pushed = 0
        var pulled = 0
        var permanentlyFailed = 0
        try {
            pushed = pushLocalChanges()
            val pullSummary = pullRemoteChanges()
            pulled = pullSummary.applied
            permanentlyFailed = pullSummary.skippedPermanent
            // Re-queue pushes for any local row with a cloud_id that no
            // longer has a matching Firestore doc. See
            // [CloudIdOrphanHealer] — covers post-Fix-D out-of-band wipe.
            try {
                cloudIdOrphanHealer.healOrphans()
            } catch (e: Exception) {
                logger.error(operation = "healer.error", throwable = e)
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
            builtInHabitReconciler.reconcileAfterSyncIfNeeded()
            builtInTaskTemplateReconciler.reconcileAfterSyncIfNeeded()
            builtInMedicationReconciler.reconcileAfterSyncIfNeeded()
            try {
                builtInUpdateDetector.refreshPendingUpdates()
            } catch (e: Exception) {
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
            syncStateRepository.markSyncCompleted(
                source = SOURCE_FIREBASE,
                success = true,
                durationMs = System.currentTimeMillis() - start,
                pushed = pushed,
                pulled = pulled,
                permanentlyFailed = permanentlyFailed
            )
        } catch (e: Exception) {
            syncStateRepository.markSyncCompleted(
                source = SOURCE_FIREBASE,
                success = false,
                durationMs = System.currentTimeMillis() - start,
                pushed = pushed,
                pulled = pulled,
                permanentlyFailed = permanentlyFailed,
                throwable = e
            )
            throw e
        } finally {
            isSyncing = false
        }
    }

    /**
     * Phase 2.5 one-shot restore. Migration_51_52 backfilled
     * `cloud_id` on every syncable entity table from `sync_metadata` at
     * upgrade time, but every subsequent `pullRemoteChanges` then NULLed
     * the column because `SyncMapper.mapToX` didn't yet accept a `cloudId`
     * parameter. This patch fixes the mapper AND runs this restore once
     * to re-populate the column on rows that were pre-existing when the
     * patch landed. Gated by [BuiltInSyncPreferences.isCloudIdRestoreDone]
     * so it runs exactly once; flag only flips to true on success.
     *
     * Uses `UPDATE OR IGNORE` so any row that would collide with the
     * unique index on `cloud_id` (if two local rows still point at the
     * same cloud doc via `sync_metadata`) is silently skipped — a belt-
     * and-suspenders for collision cases beyond what Migration_51_52
     * already resolved. Collisions are logged per-table as the `updated`
     * count being less than the null-cloud_id row count; the skipped
     * row keeps `cloud_id = NULL` and a later sync cycle can mend it.
     *
     * Does NOT mutate `sync_metadata` (read-only) and does NOT write to
     * Firestore.
     */
    private suspend fun restoreCloudIdFromMetadata() {
        if (builtInSyncPreferences.isCloudIdRestoreDone()) return

        // Mirrors [Migration_51_52.syncableTables] — the two must stay in
        // sync. If a new syncable entity is added in a future release,
        // extend both lists.
        val syncableTables = listOf(
            "tasks" to "task",
            "projects" to "project",
            "tags" to "tag",
            "habits" to "habit",
            "habit_completions" to "habit_completion",
            "habit_logs" to "habit_log",
            "task_completions" to "task_completion",
            "task_timings" to "task_timing",
            "task_templates" to "task_template",
            "milestones" to "milestone",
            "project_phases" to "project_phase",
            "project_risks" to "project_risk",
            "external_anchors" to "external_anchor",
            "task_dependencies" to "task_dependency",
            "courses" to "course",
            "course_completions" to "course_completion",
            "leisure_logs" to "leisure_log",
            "self_care_steps" to "self_care_step",
            "self_care_logs" to "self_care_log"
        )

        val db = database.openHelper.writableDatabase
        var totalUpdated = 0
        try {
            for ((table, entityType) in syncableTables) {
                val sql = """
                    UPDATE OR IGNORE `$table` SET `cloud_id` = (
                        SELECT NULLIF(sm.cloud_id, '')
                        FROM sync_metadata sm
                        WHERE sm.local_id = `$table`.id
                          AND sm.entity_type = '$entityType'
                    )
                    WHERE cloud_id IS NULL
                """.trimIndent()
                val updated = db.compileStatement(sql).use { it.executeUpdateDelete() }
                totalUpdated += updated
                logger.info(
                    operation = "cloudId.restore",
                    entity = entityType,
                    status = "success",
                    detail = "table=$table updated=$updated"
                )
            }
            builtInSyncPreferences.setCloudIdRestoreDone(true)
            logger.info(
                operation = "cloudId.restore",
                status = "success",
                detail = "total_updated=$totalUpdated"
            )
        } catch (e: Throwable) {
            logger.error(operation = "cloudId.restore", throwable = e)
            // Flag intentionally NOT set — retry on next boot.
        }
    }

    fun startAutoSync() {
        if (authManager.userId == null) return
        startRealtimeListeners()
        scope.launch {
            // Phase 2.5 — re-populate `cloud_id` on pre-existing rows
            // before any pull activity. Runs once; see [restoreCloudIdFromMetadata].
            try {
                restoreCloudIdFromMetadata()
            } catch (e: Exception) {
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
            // Per-family backfill guards mean maybeRunEntityBackfill is now
            // safe and cheap to call unconditionally — each family's internal
            // flag short-circuits the loop when already done. Previously
            // guarded by [isNewEntitiesBackfillDone]; that master flag is
            // still written on full success and remains the legacy-user
            // fallback inside each per-family accessor.
            try {
                maybeRunEntityBackfill()
            } catch (e: Exception) {
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
            // Heal pre-template_key task_templates rows before the first
            // fullSync so the reconciler sees a correctly-shaped dataset on
            // the same cycle. See [BuiltInTaskTemplateBackfiller].
            try {
                builtInTaskTemplateBackfiller.runBackfillIfNeeded()
            } catch (e: Exception) {
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
            try {
                fullSync(trigger = "startAutoSync")
            } catch (e: Exception) {
                // Error already logged by fullSync / markSyncCompleted.
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
        }
        scope.launch {
            syncMetadataDao.observePending()
                .debounce(500L)
                .collect { entries ->
                    if (entries.isEmpty()) {
                        logger.debug(operation = "reactive.push.skipped", detail = "reason=queue_empty")
                        return@collect
                    }
                    if (isSyncing) {
                        logger.debug(operation = "reactive.push.skipped", detail = "reason=already_syncing")
                        return@collect
                    }
                    if (!syncStateRepository.isOnline.value) {
                        logger.debug(operation = "reactive.push.skipped", detail = "reason=offline")
                        return@collect
                    }
                    if (authManager.userId == null) {
                        logger.debug(operation = "reactive.push.skipped", detail = "reason=not_signed_in")
                        return@collect
                    }
                    isSyncing = true
                    logger.info(operation = "reactive.push.triggered", detail = "pendingCount=${entries.size}")
                    try {
                        pushLocalChanges()
                        logger.info(operation = "reactive.push.completed", detail = "pendingCount=${entries.size}")
                    } catch (e: Exception) {
                        logger.error(operation = "reactive.push.error", throwable = e)
                        try {
                            com.google.firebase.crashlytics.FirebaseCrashlytics
                                .getInstance()
                                .recordException(e)
                        } catch (_: Exception) {
                        }
                    } finally {
                        isSyncing = false
                    }
                }
        }
        // Backstop syncs:
        //   1. Reactive — fire fullSync when isOnline flips false→true so
        //      pending writes made offline don't sit in the queue until the
        //      next local edit. observePending above only re-emits on queue
        //      changes, so a network return alone never re-triggers it.
        //   2. Periodic — bound worst-case staleness while online, even if
        //      a connectivity callback is missed. fullSync's own isSyncing
        //      guard makes this safe to fire concurrently with manual /
        //      reactive runs.
        ReactiveSyncDriver(
            isOnline = syncStateRepository.isOnline,
            isSignedIn = { authManager.userId != null },
            periodMs = PERIODIC_SYNC_INTERVAL_MS,
            onTrigger = { trigger ->
                try {
                    fullSync(trigger = trigger)
                } catch (e: Exception) {
                    // fullSync logs its own failures via markSyncCompleted.
                    try {
                        com.google.firebase.crashlytics.FirebaseCrashlytics
                            .getInstance()
                            .recordException(e)
                    } catch (_: Exception) {
                    }
                }
            }
        ).start(scope)
    }

    fun startRealtimeListeners() {
        // D8 Item 7 7c — listener surface extracted to SyncListenerManager.
        // The orchestrator passes the still-inlined pull + remote-delete
        // surfaces as lambdas; once those land in their own classes
        // (sub-PRs 7b + 7d) the lambdas become direct method references.
        listenerManager.start(
            scope = scope,
            isSyncing = { isSyncing },
            onRemoteDeletes = ::processRemoteDeletions,
            pull = ::pullRemoteChanges
        )
    }

    private suspend fun processRemoteDeletions(collection: String, cloudIds: List<String>) {
        val entityType = entityTypeForCollectionName(collection) ?: return
        var deleted = 0
        for (cloudId in cloudIds) {
            val localId = syncMetadataDao.getLocalId(cloudId, entityType) ?: continue
            try {
                when (entityType) {
                    "task" -> taskDao.deleteById(localId)
                    "project" -> projectDao.deleteById(localId)
                    "tag" -> tagDao.getTagByIdOnce(localId)?.let { tagDao.delete(it) }
                    "habit" -> habitDao.deleteById(localId)
                    "habit_completion" -> habitCompletionDao.deleteById(localId)
                    "habit_log" -> { /* HabitLogDao has no by-ID delete; metadata is still cleaned up below */ }
                    "task_completion" -> taskCompletionDao.deleteById(localId)
                    "task_timing" -> taskTimingDao.deleteById(localId)
                    "milestone" -> milestoneDao.deleteById(localId)
                    "project_phase" -> projectPhaseDao.deleteById(localId)
                    "project_risk" -> projectRiskDao.deleteById(localId)
                    "external_anchor" -> externalAnchorDao.deleteById(localId)
                    "task_dependency" -> taskDependencyDao.deleteById(localId)
                    "task_template" -> taskTemplateDao.deleteTemplate(localId)
                    "course" -> schoolworkDao.deleteCourse(localId)
                    "course_completion" -> schoolworkDao.deleteCompletionById(localId)
                    "leisure_log" -> leisureDao.deleteLogById(localId)
                    "self_care_step" -> selfCareDao.deleteStepById(localId)
                    "self_care_log" -> selfCareDao.deleteLogById(localId)
                    "medication" -> medicationDao.deleteById(localId)
                    "medication_dose" -> medicationDoseDao.deleteById(localId)
                    "medication_slot" -> medicationSlotDao.deleteById(localId)
                    "medication_slot_override" -> medicationSlotOverrideDao.deleteById(localId)
                    "medication_tier_state" -> medicationTierStateDao.deleteById(localId)
                    "notification_profile" ->
                        notificationProfileDao.getById(localId)?.let { notificationProfileDao.delete(it) }
                    "custom_sound" -> customSoundDao.deleteById(localId)
                    "saved_filter" -> savedFilterDao.deleteById(localId)
                    "nlp_shortcut" -> nlpShortcutDao.deleteById(localId)
                    "habit_template" -> habitTemplateDao.deleteById(localId)
                    "project_template" -> projectTemplateDao.deleteById(localId)
                    "boundary_rule" -> boundaryRuleDao.delete(localId)
                    "automation_rule" -> automationRuleDao.deleteById(localId)
                    "check_in_log" -> checkInLogDao.deleteById(localId)
                    "mood_energy_log" -> moodEnergyLogDao.deleteById(localId)
                    "focus_release_log" -> focusReleaseLogDao.deleteById(localId)
                    "medication_refill" -> medicationRefillDao.deleteById(localId)
                    "weekly_review" -> weeklyReviewDao.deleteById(localId)
                    "daily_essential_slot_completion" -> dailyEssentialSlotCompletionDao.deleteById(localId)
                    "assignment" -> schoolworkDao.deleteAssignment(localId)
                    "attachment" -> attachmentDao.deleteById(localId)
                    "study_log" -> schoolworkDao.deleteStudyLogById(localId)
                }
                syncMetadataDao.delete(localId, entityType)
                logger.info(
                    operation = "pull.delete",
                    entity = entityType,
                    id = cloudId,
                    status = "success"
                )
                deleted++
            } catch (e: Exception) {
                logger.error(
                    operation = "pull.delete",
                    entity = entityType,
                    id = cloudId,
                    throwable = e
                )
            }
        }
        logger.info(
            operation = "pull.delete.summary",
            entity = entityType,
            status = "success",
            detail = "deleted=$deleted"
        )
    }

    fun stopRealtimeListeners() {
        listenerManager.stop()
    }

    companion object {
        const val SOURCE_FIREBASE: String = "firebase"

        // 30 s — user-requested maximum interval between syncs while
        // online + signed in. Acts as a safety net for any reactive
        // trigger that doesn't fire (e.g. ConnectivityManager callback
        // missed because no collector was active).
        private const val PERIODIC_SYNC_INTERVAL_MS: Long = 30_000L

        // D8 Item 7 Strangler Fig 7e — pure dispatch tables now live in
        // [SyncDispatchTables]; these companion-object members delegate so
        // existing call-sites + [SyncServiceDispatchTest] keep working
        // unchanged. Behaviour-identical pass-throughs.

        @VisibleForTesting
        internal fun collectionNameFor(entityType: String): String =
            SyncDispatchTables.collectionNameFor(entityType)

        @VisibleForTesting
        internal fun entityTypeForCollectionName(collection: String): String? =
            SyncDispatchTables.entityTypeForCollectionName(collection)

        @VisibleForTesting
        internal fun pushOrderPriorityOf(entityType: String): Int =
            SyncDispatchTables.pushOrderPriorityOf(entityType)
    }
}
