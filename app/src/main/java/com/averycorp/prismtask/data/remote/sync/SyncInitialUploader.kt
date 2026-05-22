package com.averycorp.prismtask.data.remote.sync

import com.google.firebase.firestore.FirebaseFirestoreException
import com.averycorp.prismtask.data.local.dao.*
import com.averycorp.prismtask.data.local.entity.*
import com.averycorp.prismtask.data.remote.mapper.*
import com.averycorp.prismtask.data.remote.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncInitialUploader @Inject constructor(
    private val builtInSyncPreferences: com.averycorp.prismtask.data.preferences.BuiltInSyncPreferences,
    private val medicationMigrationPreferences: com.averycorp.prismtask.data.preferences.MedicationMigrationPreferences,
    private val authManager: AuthManager,
    private val firestore: FirebaseFirestore,
    private val projectDao: ProjectDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val tagDao: TagDao,
    private val habitDao: HabitDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val habitLogDao: HabitLogDao,
    private val taskTemplateDao: TaskTemplateDao,
    private val milestoneDao: MilestoneDao,
    private val taskDao: TaskDao,
    private val taskCompletionDao: TaskCompletionDao,
    private val taskTimingDao: TaskTimingDao,
    private val logger: PrismSyncLogger,
    private val builtInHabitReconciler: BuiltInHabitReconciler,
    private val builtInTaskTemplateReconciler: BuiltInTaskTemplateReconciler,
    private val builtInTaskTemplateBackfiller: BuiltInTaskTemplateBackfiller,
    private val builtInMedicationReconciler: BuiltInMedicationReconciler,
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

    private fun tierStateDeterministicDocId(
        medCloudId: String,
        logDate: String,
        slotCloudId: String
    ): String = "${medCloudId}__${logDate}__${slotCloudId}"

    private fun courseCompletionDeterministicDocId(
        courseCloudId: String,
        date: Long
    ): String = "${courseCloudId}__$date"

    suspend fun doInitialUpload() {
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
        // `daily_essential_slot_completion` Firestore upload removed in
        // parity Batch 5 PR-9 (decision D-E4). `BackendSyncService` is
        // now the single authoritative writer; the row continues to
        // mirror into Room via `BackendSyncMappers.kt:170` on pull.

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
    suspend fun maybeRunEntityBackfill() {
        val coursesOk = runCoursesBackfillIfNeeded()
        val courseCompletionsOk = runCourseCompletionsBackfillIfNeeded()
        // Leisure Budget v2.0: v1.x leisure_logs backfill retired in v82
        // migration. Mark the flag done so we never come back here looking
        // for v1.x data.
        if (!builtInSyncPreferences.isLeisureLogsBackfillDone()) {
            builtInSyncPreferences.setLeisureLogsBackfillDone(true)
        }
        val selfCareStepsOk = runSelfCareStepsBackfillIfNeeded()
        val selfCareLogsOk = runSelfCareLogsBackfillIfNeeded()
        // medications BEFORE medication_doses so the dose helper can
        // resolve cloud_ids for the parents. Each has its own one-shot
        // flag in MedicationMigrationPreferences.
        val medicationsOk = runMedicationsBackfillIfNeeded()
        val medicationDosesOk = runMedicationDosesBackfillIfNeeded()
        // Parity Batch 5 PR-8 — rewrite existing tier-state docs to
        // deterministic id form. Runs AFTER the medications + doses
        // pushes so syncMetadataDao has populated cloud_ids for the
        // referenced medications + slots. Its own one-shot flag means
        // it no-ops after the first success regardless of the other
        // family flags.
        val tierStateIdsOk = runTierStateDocIdBackfillIfNeeded()

        val allSucceeded = coursesOk && courseCompletionsOk &&
            selfCareStepsOk && selfCareLogsOk &&
            medicationsOk && medicationDosesOk &&
            tierStateIdsOk
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
                    val detId = courseCompletionDeterministicDocId(courseCloudId, completion.date)
                    val docRef = userCollection("course_completions")?.document(detId) ?: continue
                    docRef.set(
                        SyncMapper.courseCompletionToMap(completion, courseCloudId),
                        SetOptions.merge()
                    ).await()
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

    // Leisure Budget v2.0: v1.x runLeisureLogsBackfillIfNeeded was
    // retired when migration 81→82 dropped the leisure_logs table.
    // The new v2.0 leisure_activities / leisure_sessions tables sync
    // via the BackendSyncMappers path (extensions added in the v2.0
    // bundle PR) rather than this Firestore-specific path.

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

    /**
     * One-time backfill of `medication_tier_states` Firestore docs to
     * the deterministic id form `${medCloudId}__${logDate}__${slotCloudId}`
     * (parity Batch 5 PR-8, decision D-E3).
     *
     * Iterates every local tier-state row, computes the deterministic
     * id, writes via `setDoc(merge=true)` at the new path, then deletes
     * the old auto-id doc (if any) and updates the local `cloud_id` in
     * `sync_metadata`. **No Room migration** — the `cloud_id` column
     * already exists; we just rewrite its value.
     *
     * Safety:
     * - Guarded by [MedicationMigrationPreferences.isTierStateDocIdBackfillDone];
     *   re-runs are no-ops after success.
     * - `setDoc(merge = true)` makes each write idempotent so a partial
     *   failure stays retryable.
     * - The old auto-id doc is only deleted AFTER the deterministic
     *   write lands; a crash between the two leaves duplicate docs that
     *   the next sync resolves naturally (deterministic write is the
     *   one with the newest `updatedAt`).
     * - Skips rows that already point at a deterministic-form id —
     *   `cloud_id == detId` is a no-op.
     */
    private suspend fun runTierStateDocIdBackfillIfNeeded(): Boolean {
        if (medicationMigrationPreferences.isTierStateDocIdBackfillDone()) return true
        if (authManager.userId == null) return false
        return try {
            val rows = medicationTierStateDao.getAllOnce()
            logger.debug(
                "backfill.tier_state_doc_id",
                status = "begin",
                detail = "count=${rows.size}"
            )
            val collection = userCollection("medication_tier_states") ?: return false
            for (row in rows) {
                try {
                    val medCloudId = syncMetadataDao.getCloudId(row.medicationId, "medication")
                        ?: continue
                    val slotCloudId = syncMetadataDao.getCloudId(row.slotId, "medication_slot")
                        ?: continue
                    val detId = tierStateDeterministicDocId(medCloudId, row.logDate, slotCloudId)
                    val existingCloudId = syncMetadataDao.getCloudId(row.id, "medication_tier_state")
                    if (existingCloudId == detId) continue
                    val payload = MedicationSyncMapper.medicationTierStateToMap(row, medCloudId, slotCloudId)
                    // Write deterministic doc first (idempotent merge).
                    collection.document(detId).set(payload, SetOptions.merge()).await()
                    // Delete the old auto-id doc if it differs. Swallow
                    // NOT_FOUND — a previous partial-success retry may
                    // have already deleted it.
                    if (existingCloudId != null && existingCloudId.isNotEmpty() && existingCloudId != detId) {
                        try {
                            collection.document(existingCloudId).delete().await()
                        } catch (e: FirebaseFirestoreException) {
                            if (e.code != FirebaseFirestoreException.Code.NOT_FOUND) throw e
                        }
                    }
                    // Update local sync_metadata to the new id so future
                    // pushUpdate paths target the deterministic doc.
                    syncMetadataDao.upsert(
                        SyncMetadataEntity(
                            localId = row.id,
                            entityType = "medication_tier_state",
                            cloudId = detId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error(
                        operation = "backfill.tier_state_doc_id",
                        entity = "medication_tier_state",
                        id = row.id.toString(),
                        throwable = e
                    )
                }
            }
            medicationMigrationPreferences.setTierStateDocIdBackfillDone(true)
            logger.info(
                operation = "backfill.tier_state_doc_id",
                status = "success",
                detail = "rows=${rows.size}"
            )
            true
        } catch (e: Exception) {
            logger.error(operation = "backfill.tier_state_doc_id", throwable = e)
            false
        }
    }
}
