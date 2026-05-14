package com.averycorp.prismtask.data.remote.sync

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
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.SortPreferencesSyncService
import com.averycorp.prismtask.data.remote.SyncService
import com.averycorp.prismtask.data.remote.mapper.MedicationSyncMapper
import com.averycorp.prismtask.data.remote.mapper.SyncMapper
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D8 Item 7 Strangler Fig — pull surface (orchestrator slice). Refactor
 * Tier 1 Slice 1: extracts the ~1,400 LOC `pullRemoteChanges` body and
 * its two private helpers (`pullCollection`,
 * `pullRoomConfigFamily`) out of [SyncService]. Returned `PullSummary`
 * remains a nested type on [SyncService] so that listener/full-sync
 * callers (notably `SyncListenerManager.start(pull = ...)`) keep
 * compiling unchanged.
 *
 * Behavior is byte-identical to the pre-extraction body — no log
 * messages, no FK order, and no dedup paths changed. The
 * [SyncServiceDispatchTest] suite is the pin test; this slice does not
 * touch the three dispatch tables and therefore must not regress it.
 *
 * Dependencies scoped narrowly to the DAOs / mappers the pull path
 * actually touches (not all 49 DAOs on [SyncService]). `authManager` +
 * `firestore` are injected so `userCollection(...)` can resolve the
 * per-user Firestore root.
 */
@Suppress("LargeClass", "LongParameterList")
@Singleton
class SyncPullOrchestrator
@Inject
constructor(
    private val authManager: AuthManager,
    private val firestore: FirebaseFirestore,
    private val syncMetadataDao: SyncMetadataDao,
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
    private val externalAnchorDao: ExternalAnchorDao,
    private val sortPreferencesSyncService: SortPreferencesSyncService,
    private val logger: PrismSyncLogger
) {
    private fun userCollection(collection: String) =
        authManager.userId?.let { firestore.collection("users").document(it).collection(collection) }

    /**
     * Pull every per-user Firestore collection into Room. Behavior is
     * the pre-extraction body of [SyncService.pullRemoteChanges] —
     * collection order, FK resolution, natural-key dedup, and the
     * `pull.summary` log line are all preserved verbatim.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    suspend fun pull(): SyncService.PullSummary {
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

        // Leisure Budget v2.0: v1.x leisure_logs Firestore pull retired in
        // migration 81→82. New v2.0 leisure_activities / leisure_sessions
        // sync via BackendSyncMappers (see SyncDispatchTables wiring).

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

        // `daily_essential_slot_completions` Firestore pull removed in
        // parity Batch 5 PR-9 (decision D-E4). BackendSyncService now
        // mirrors this entity into Room directly via
        // `BackendSyncMappers.kt:170`. Old clients may still write to
        // the legacy Firestore collection — those rows are now read-
        // noise and don't pull into Room here.

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
        return SyncService.PullSummary(applied = applied, skippedPermanent = skippedPermanent)
    }

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
}
