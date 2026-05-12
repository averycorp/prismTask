package com.averycorp.prismtask.data.remote.mapper

import com.averycorp.prismtask.data.local.entity.AssignmentEntity
import com.averycorp.prismtask.data.local.entity.AttachmentEntity
import com.averycorp.prismtask.data.local.entity.AutomationRuleEntity
import com.averycorp.prismtask.data.local.entity.BoundaryRuleEntity
import com.averycorp.prismtask.data.local.entity.CheckInLogEntity
import com.averycorp.prismtask.data.local.entity.CourseCompletionEntity
import com.averycorp.prismtask.data.local.entity.CourseEntity
import com.averycorp.prismtask.data.local.entity.CustomSoundEntity
import com.averycorp.prismtask.data.local.entity.DailyEssentialSlotCompletionEntity
import com.averycorp.prismtask.data.local.entity.ExternalAnchorEntity
import com.averycorp.prismtask.data.local.entity.FocusReleaseLogEntity
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.HabitLogEntity
import com.averycorp.prismtask.data.local.entity.HabitTemplateEntity
import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import com.averycorp.prismtask.data.local.entity.LeisureSessionEntity
import com.averycorp.prismtask.data.local.entity.MedicationRefillEntity
import com.averycorp.prismtask.data.local.entity.MilestoneEntity
import com.averycorp.prismtask.data.local.entity.MoodEnergyLogEntity
import com.averycorp.prismtask.data.local.entity.NlpShortcutEntity
import com.averycorp.prismtask.data.local.entity.NotificationProfileEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.ProjectPhaseEntity
import com.averycorp.prismtask.data.local.entity.ProjectRiskEntity
import com.averycorp.prismtask.data.local.entity.ProjectTemplateEntity
import com.averycorp.prismtask.data.local.entity.SavedFilterEntity
import com.averycorp.prismtask.data.local.entity.SelfCareLogEntity
import com.averycorp.prismtask.data.local.entity.SelfCareStepEntity
import com.averycorp.prismtask.data.local.entity.StudyLogEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskCompletionEntity
import com.averycorp.prismtask.data.local.entity.TaskDependencyEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTemplateEntity
import com.averycorp.prismtask.data.local.entity.TaskTimingEntity
import com.averycorp.prismtask.data.local.entity.WeeklyReviewEntity

@Suppress("TooManyFunctions") // Cohesive sync-surface object — one entityToMap + one mapToEntity per synced Room table.
object SyncMapper {
    fun taskToMap(
        task: TaskEntity,
        tagIds: List<String> = emptyList(),
        projectCloudId: String? = null,
        parentTaskCloudId: String? = null,
        sourceHabitCloudId: String? = null,
        phaseCloudId: String? = null
    ): Map<String, Any?> = mapOf(
        "localId" to task.id,
        "title" to task.title,
        "description" to task.description,
        "dueDate" to task.dueDate,
        "dueTime" to task.dueTime,
        "priority" to task.priority,
        "isCompleted" to task.isCompleted,
        "projectId" to projectCloudId,
        "parentTaskId" to parentTaskCloudId,
        "recurrenceRule" to task.recurrenceRule,
        "reminderOffset" to task.reminderOffset,
        "tags" to tagIds,
        "plannedDate" to task.plannedDate,
        "estimatedDuration" to task.estimatedDuration,
        "scheduledStartTime" to task.scheduledStartTime,
        "sourceHabitId" to sourceHabitCloudId,
        "notes" to task.notes,
        "eisenhowerQuadrant" to task.eisenhowerQuadrant,
        "eisenhowerUpdatedAt" to task.eisenhowerUpdatedAt,
        "eisenhowerReason" to task.eisenhowerReason,
        "userOverrodeQuadrant" to task.userOverrodeQuadrant,
        "sortOrder" to task.sortOrder,
        "isFlagged" to task.isFlagged,
        "lifeCategory" to task.lifeCategory,
        "taskMode" to task.taskMode,
        "cognitiveLoad" to task.cognitiveLoad,
        "goodEnoughMinutesOverride" to task.goodEnoughMinutesOverride,
        "maxRevisionsOverride" to task.maxRevisionsOverride,
        "revisionCount" to task.revisionCount,
        "revisionLocked" to task.revisionLocked,
        "cumulativeEditMinutes" to task.cumulativeEditMinutes,
        "phaseId" to phaseCloudId,
        "progressPercent" to task.progressPercent,
        "createdAt" to task.createdAt,
        "updatedAt" to task.updatedAt,
        "completedAt" to task.completedAt,
        "archivedAt" to task.archivedAt
    )

    fun mapToTask(
        data: Map<String, Any?>,
        localId: Long = 0,
        projectLocalId: Long? = null,
        parentTaskLocalId: Long? = null,
        sourceHabitLocalId: Long? = null,
        cloudId: String? = null,
        phaseLocalId: Long? = null
    ): TaskEntity = TaskEntity(
        id = localId,
        cloudId = cloudId,
        title = data["title"] as? String ?: "",
        description = data["description"] as? String,
        dueDate = (data["dueDate"] as? Number)?.toLong(),
        dueTime = (data["dueTime"] as? Number)?.toLong(),
        priority = (data["priority"] as? Number)?.toInt() ?: 0,
        isCompleted = data["isCompleted"] as? Boolean ?: false,
        projectId = projectLocalId,
        parentTaskId = parentTaskLocalId,
        recurrenceRule = data["recurrenceRule"] as? String,
        reminderOffset = (data["reminderOffset"] as? Number)?.toLong(),
        plannedDate = (data["plannedDate"] as? Number)?.toLong(),
        estimatedDuration = (data["estimatedDuration"] as? Number)?.toInt(),
        scheduledStartTime = (data["scheduledStartTime"] as? Number)?.toLong(),
        sourceHabitId = sourceHabitLocalId,
        notes = data["notes"] as? String,
        eisenhowerQuadrant = data["eisenhowerQuadrant"] as? String,
        eisenhowerUpdatedAt = (data["eisenhowerUpdatedAt"] as? Number)?.toLong(),
        eisenhowerReason = data["eisenhowerReason"] as? String,
        userOverrodeQuadrant = data["userOverrodeQuadrant"] as? Boolean ?: false,
        sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0,
        isFlagged = data["isFlagged"] as? Boolean ?: false,
        lifeCategory = data["lifeCategory"] as? String,
        taskMode = data["taskMode"] as? String,
        cognitiveLoad = data["cognitiveLoad"] as? String,
        goodEnoughMinutesOverride = (data["goodEnoughMinutesOverride"] as? Number)?.toInt(),
        maxRevisionsOverride = (data["maxRevisionsOverride"] as? Number)?.toInt(),
        revisionCount = (data["revisionCount"] as? Number)?.toInt() ?: 0,
        revisionLocked = data["revisionLocked"] as? Boolean ?: false,
        cumulativeEditMinutes = (data["cumulativeEditMinutes"] as? Number)?.toInt() ?: 0,
        phaseId = phaseLocalId,
        progressPercent = (data["progressPercent"] as? Number)?.toInt(),
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        completedAt = (data["completedAt"] as? Number)?.toLong(),
        archivedAt = (data["archivedAt"] as? Number)?.toLong()
    )

    fun projectToMap(project: ProjectEntity): Map<String, Any?> = mapOf(
        "localId" to project.id,
        "name" to project.name,
        "description" to project.description,
        "color" to project.color,
        "icon" to project.icon,
        "themeColorKey" to project.themeColorKey,
        "status" to project.status,
        "startDate" to project.startDate,
        "endDate" to project.endDate,
        "completedAt" to project.completedAt,
        "archivedAt" to project.archivedAt,
        "createdAt" to project.createdAt,
        "updatedAt" to project.updatedAt
    )

    fun mapToProject(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): ProjectEntity = ProjectEntity(
        id = localId,
        cloudId = cloudId,
        name = data["name"] as? String ?: "",
        description = data["description"] as? String,
        color = data["color"] as? String ?: "#4A90D9",
        icon = data["icon"] as? String ?: "\uD83D\uDCC1",
        themeColorKey = data["themeColorKey"] as? String,
        // v1.3 projects synced before the Phase 1 migration landed won't have
        // a status field — default to ACTIVE so the post-pull row is a
        // well-formed v1.4 project.
        status = (data["status"] as? String) ?: "ACTIVE",
        startDate = (data["startDate"] as? Number)?.toLong(),
        endDate = (data["endDate"] as? Number)?.toLong(),
        completedAt = (data["completedAt"] as? Number)?.toLong(),
        archivedAt = (data["archivedAt"] as? Number)?.toLong(),
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
    )

    /**
     * Milestones are a child collection under a project (mirrors how habit
     * completions reference their parent habit's cloud ID). [projectCloudId]
     * is the Firestore document ID from `sync_metadata`.
     */
    fun milestoneToMap(milestone: MilestoneEntity, projectCloudId: String): Map<String, Any?> = mapOf(
        "localId" to milestone.id,
        "projectCloudId" to projectCloudId,
        "title" to milestone.title,
        "isCompleted" to milestone.isCompleted,
        "completedAt" to milestone.completedAt,
        "orderIndex" to milestone.orderIndex,
        "createdAt" to milestone.createdAt,
        "updatedAt" to milestone.updatedAt
    )

    fun mapToMilestone(
        data: Map<String, Any?>,
        projectLocalId: Long,
        localId: Long = 0,
        cloudId: String? = null
    ): MilestoneEntity =
        MilestoneEntity(
            id = localId,
            cloudId = cloudId,
            projectId = projectLocalId,
            title = data["title"] as? String ?: "",
            isCompleted = data["isCompleted"] as? Boolean ?: false,
            completedAt = (data["completedAt"] as? Number)?.toLong(),
            orderIndex = (data["orderIndex"] as? Number)?.toInt() ?: 0,
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )

    /**
     * Project phases live as a child subcollection under a project,
     * mirroring milestones (P11 audit recommendation: subcollection from
     * day 1 to avoid the 1 MiB embedded-array cliff).
     */
    fun projectPhaseToMap(
        phase: ProjectPhaseEntity,
        projectCloudId: String
    ): Map<String, Any?> = mapOf(
        "localId" to phase.id,
        "projectCloudId" to projectCloudId,
        "title" to phase.title,
        "description" to phase.description,
        "colorKey" to phase.colorKey,
        "startDate" to phase.startDate,
        "endDate" to phase.endDate,
        "versionAnchor" to phase.versionAnchor,
        "versionNote" to phase.versionNote,
        "orderIndex" to phase.orderIndex,
        "completedAt" to phase.completedAt,
        "createdAt" to phase.createdAt,
        "updatedAt" to phase.updatedAt
    )

    fun mapToProjectPhase(
        data: Map<String, Any?>,
        projectLocalId: Long,
        localId: Long = 0,
        cloudId: String? = null
    ): ProjectPhaseEntity =
        ProjectPhaseEntity(
            id = localId,
            cloudId = cloudId,
            projectId = projectLocalId,
            title = data["title"] as? String ?: "",
            description = data["description"] as? String,
            colorKey = data["colorKey"] as? String,
            startDate = (data["startDate"] as? Number)?.toLong(),
            endDate = (data["endDate"] as? Number)?.toLong(),
            versionAnchor = data["versionAnchor"] as? String,
            versionNote = data["versionNote"] as? String,
            orderIndex = (data["orderIndex"] as? Number)?.toInt() ?: 0,
            completedAt = (data["completedAt"] as? Number)?.toLong(),
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )

    /**
     * Project risks live as a child subcollection under a project. Same
     * shape rationale as phases.
     */
    fun projectRiskToMap(
        risk: ProjectRiskEntity,
        projectCloudId: String
    ): Map<String, Any?> = mapOf(
        "localId" to risk.id,
        "projectCloudId" to projectCloudId,
        "title" to risk.title,
        "level" to risk.level,
        "mitigation" to risk.mitigation,
        "resolvedAt" to risk.resolvedAt,
        "createdAt" to risk.createdAt,
        "updatedAt" to risk.updatedAt
    )

    fun mapToProjectRisk(
        data: Map<String, Any?>,
        projectLocalId: Long,
        localId: Long = 0,
        cloudId: String? = null
    ): ProjectRiskEntity =
        ProjectRiskEntity(
            id = localId,
            cloudId = cloudId,
            projectId = projectLocalId,
            title = data["title"] as? String ?: "",
            level = data["level"] as? String ?: "MEDIUM",
            mitigation = data["mitigation"] as? String,
            resolvedAt = (data["resolvedAt"] as? Number)?.toLong(),
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )

    /**
     * External anchors live as a child subcollection under a project.
     * `phaseCloudId` is optional — anchors may be project-scoped only.
     * The polymorphic [com.averycorp.prismtask.domain.model.ExternalAnchor]
     * payload is already JSON-serialized in `anchor_json`; this mapper
     * just round-trips the column verbatim.
     */
    fun externalAnchorToMap(
        anchor: ExternalAnchorEntity,
        projectCloudId: String,
        phaseCloudId: String? = null
    ): Map<String, Any?> = mapOf(
        "localId" to anchor.id,
        "projectCloudId" to projectCloudId,
        "phaseCloudId" to phaseCloudId,
        "label" to anchor.label,
        "anchorJson" to anchor.anchorJson,
        "createdAt" to anchor.createdAt,
        "updatedAt" to anchor.updatedAt
    )

    fun mapToExternalAnchor(
        data: Map<String, Any?>,
        projectLocalId: Long,
        phaseLocalId: Long? = null,
        localId: Long = 0,
        cloudId: String? = null
    ): ExternalAnchorEntity =
        ExternalAnchorEntity(
            id = localId,
            cloudId = cloudId,
            projectId = projectLocalId,
            phaseId = phaseLocalId,
            label = data["label"] as? String ?: "",
            anchorJson = data["anchorJson"] as? String ?: "",
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )

    /**
     * Task dependency edges round-trip both task cloud ids; the
     * SyncService callers resolve `(blocker, blocked)` cloud ↔ local at
     * push and pull time, mirroring the project_phase / milestone
     * pattern.
     */
    fun taskDependencyToMap(
        dependency: TaskDependencyEntity,
        blockerTaskCloudId: String,
        blockedTaskCloudId: String
    ): Map<String, Any?> = mapOf(
        "localId" to dependency.id,
        "blockerTaskCloudId" to blockerTaskCloudId,
        "blockedTaskCloudId" to blockedTaskCloudId,
        "createdAt" to dependency.createdAt
    )

    fun mapToTaskDependency(
        data: Map<String, Any?>,
        blockerTaskLocalId: Long,
        blockedTaskLocalId: Long,
        localId: Long = 0,
        cloudId: String? = null
    ): TaskDependencyEntity =
        TaskDependencyEntity(
            id = localId,
            cloudId = cloudId,
            blockerTaskId = blockerTaskLocalId,
            blockedTaskId = blockedTaskLocalId,
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )

    fun tagToMap(tag: TagEntity): Map<String, Any?> = mapOf(
        "localId" to tag.id,
        "name" to tag.name,
        "color" to tag.color,
        "createdAt" to tag.createdAt
    )

    fun mapToTag(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): TagEntity = TagEntity(
        id = localId,
        cloudId = cloudId,
        name = data["name"] as? String ?: "",
        color = data["color"] as? String ?: "#6B7280",
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
    )

    fun habitToMap(habit: HabitEntity): Map<String, Any?> = mapOf(
        "localId" to habit.id,
        "name" to habit.name,
        "description" to habit.description,
        "targetFrequency" to habit.targetFrequency,
        "frequencyPeriod" to habit.frequencyPeriod,
        "activeDays" to habit.activeDays,
        "color" to habit.color,
        "icon" to habit.icon,
        "reminderTime" to habit.reminderTime,
        "sortOrder" to habit.sortOrder,
        "isArchived" to habit.isArchived,
        "category" to habit.category,
        "createDailyTask" to habit.createDailyTask,
        "reminderIntervalMillis" to habit.reminderIntervalMillis,
        "reminderTimesPerDay" to habit.reminderTimesPerDay,
        "hasLogging" to habit.hasLogging,
        "trackBooking" to habit.trackBooking,
        "trackPreviousPeriod" to habit.trackPreviousPeriod,
        "isBookable" to habit.isBookable,
        "isBooked" to habit.isBooked,
        "bookedDate" to habit.bookedDate,
        "bookedNote" to habit.bookedNote,
        "showStreak" to habit.showStreak,
        "nagSuppressionOverrideEnabled" to habit.nagSuppressionOverrideEnabled,
        "nagSuppressionDaysOverride" to habit.nagSuppressionDaysOverride,
        "todaySkipAfterCompleteDays" to habit.todaySkipAfterCompleteDays,
        "todaySkipBeforeScheduleDays" to habit.todaySkipBeforeScheduleDays,
        "isBuiltIn" to habit.isBuiltIn,
        "templateKey" to habit.templateKey,
        "sourceVersion" to habit.sourceVersion,
        "isUserModified" to habit.isUserModified,
        "isDetachedFromTemplate" to habit.isDetachedFromTemplate,
        "createdAt" to habit.createdAt,
        "updatedAt" to habit.updatedAt
    )

    fun mapToHabit(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): HabitEntity {
        val templateKey = data["templateKey"] as? String
        val isBuiltIn = (data["isBuiltIn"] as? Boolean) ?: (templateKey != null)
        return HabitEntity(
            id = localId,
            cloudId = cloudId,
            name = data["name"] as? String ?: "",
            description = data["description"] as? String,
            targetFrequency = (data["targetFrequency"] as? Number)?.toInt() ?: 1,
            frequencyPeriod = data["frequencyPeriod"] as? String ?: "daily",
            activeDays = data["activeDays"] as? String,
            color = data["color"] as? String ?: "#4A90D9",
            icon = data["icon"] as? String ?: "\u2B50",
            reminderTime = (data["reminderTime"] as? Number)?.toLong(),
            sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0,
            isArchived = data["isArchived"] as? Boolean ?: false,
            category = data["category"] as? String,
            createDailyTask = data["createDailyTask"] as? Boolean ?: false,
            reminderIntervalMillis = (data["reminderIntervalMillis"] as? Number)?.toLong(),
            reminderTimesPerDay = (data["reminderTimesPerDay"] as? Number)?.toInt() ?: 1,
            hasLogging = data["hasLogging"] as? Boolean ?: false,
            trackBooking = data["trackBooking"] as? Boolean ?: false,
            trackPreviousPeriod = data["trackPreviousPeriod"] as? Boolean ?: false,
            isBookable = data["isBookable"] as? Boolean ?: false,
            isBooked = data["isBooked"] as? Boolean ?: false,
            bookedDate = (data["bookedDate"] as? Number)?.toLong(),
            bookedNote = data["bookedNote"] as? String,
            showStreak = data["showStreak"] as? Boolean ?: false,
            nagSuppressionOverrideEnabled = data["nagSuppressionOverrideEnabled"] as? Boolean ?: false,
            nagSuppressionDaysOverride = (data["nagSuppressionDaysOverride"] as? Number)?.toInt() ?: -1,
            todaySkipAfterCompleteDays = (data["todaySkipAfterCompleteDays"] as? Number)?.toInt() ?: -1,
            todaySkipBeforeScheduleDays = (data["todaySkipBeforeScheduleDays"] as? Number)?.toInt() ?: -1,
            isBuiltIn = isBuiltIn,
            templateKey = templateKey,
            sourceVersion = (data["sourceVersion"] as? Number)?.toInt() ?: 0,
            isUserModified = data["isUserModified"] as? Boolean ?: false,
            isDetachedFromTemplate = data["isDetachedFromTemplate"] as? Boolean ?: false,
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )
    }

    fun habitCompletionToMap(completion: HabitCompletionEntity, habitCloudId: String): Map<String, Any?> = mapOf(
        "localId" to completion.id,
        "habitCloudId" to habitCloudId,
        "completedDate" to completion.completedDate,
        "completedDateLocal" to (
            completion.completedDateLocal
                ?: epochToLocalDateString(completion.completedDate)
            ),
        "completedAt" to completion.completedAt,
        "notes" to completion.notes
    )

    fun mapToHabitCompletion(
        data: Map<String, Any?>,
        localId: Long = 0,
        habitLocalId: Long = 0,
        cloudId: String? = null
    ): HabitCompletionEntity {
        val completedDate = (data["completedDate"] as? Number)?.toLong() ?: 0
        // Prefer the timezone-neutral string field. Fall back to epoch-derived
        // LocalDate in the pulling device's zone for legacy Firestore docs
        // written before the completedDateLocal field existed — see PR body for
        // cross-timezone caveats.
        val completedDateLocal = (data["completedDateLocal"] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: epochToLocalDateString(completedDate)
        return HabitCompletionEntity(
            id = localId,
            cloudId = cloudId,
            habitId = habitLocalId,
            completedDate = completedDate,
            completedAt = (data["completedAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            notes = data["notes"] as? String,
            completedDateLocal = completedDateLocal
        )
    }

    private fun epochToLocalDateString(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()

    fun habitLogToMap(log: HabitLogEntity, habitCloudId: String): Map<String, Any?> = mapOf(
        "localId" to log.id,
        "habitCloudId" to habitCloudId,
        "date" to log.date,
        "notes" to log.notes,
        "createdAt" to log.createdAt
    )

    fun mapToHabitLog(
        data: Map<String, Any?>,
        localId: Long = 0,
        habitLocalId: Long = 0,
        cloudId: String? = null
    ): HabitLogEntity =
        HabitLogEntity(
            id = localId,
            cloudId = cloudId,
            habitId = habitLocalId,
            date = (data["date"] as? Number)?.toLong() ?: 0,
            notes = data["notes"] as? String,
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )

    fun taskTemplateToMap(
        template: TaskTemplateEntity,
        templateProjectCloudId: String? = null
    ): Map<String, Any?> = mapOf(
        "localId" to template.id,
        "userId" to template.userId,
        "remoteId" to template.remoteId,
        "name" to template.name,
        "description" to template.description,
        "icon" to template.icon,
        "category" to template.category,
        "templateTitle" to template.templateTitle,
        "templateDescription" to template.templateDescription,
        "templatePriority" to template.templatePriority,
        "templateProjectId" to templateProjectCloudId,
        "templateTagsJson" to template.templateTagsJson,
        "templateRecurrenceJson" to template.templateRecurrenceJson,
        "templateDuration" to template.templateDuration,
        "templateSubtasksJson" to template.templateSubtasksJson,
        "isBuiltIn" to template.isBuiltIn,
        "templateKey" to template.templateKey,
        "usageCount" to template.usageCount,
        "lastUsedAt" to template.lastUsedAt,
        "createdAt" to template.createdAt,
        "updatedAt" to template.updatedAt
    )

    fun taskCompletionToMap(
        completion: TaskCompletionEntity,
        taskCloudId: String? = null,
        projectCloudId: String? = null
    ): Map<String, Any?> = mapOf(
        "localId" to completion.id,
        "taskId" to taskCloudId,
        "projectId" to projectCloudId,
        "completedDate" to completion.completedDate,
        "completedAtTime" to completion.completedAtTime,
        "priority" to completion.priority,
        "wasOverdue" to completion.wasOverdue,
        "daysToComplete" to completion.daysToComplete,
        "tags" to completion.tags,
        // Synced because the spawned task itself syncs via clientId — when
        // a peer device pulls this completion + the spawned task together,
        // local toggle-uncomplete on that peer can still find and roll
        // back the spawn. NULL on legacy rows is fine.
        "spawnedRecurrenceId" to completion.spawnedRecurrenceId
    )

    fun taskTimingToMap(
        timing: TaskTimingEntity,
        taskCloudId: String?
    ): Map<String, Any?> = mapOf(
        "localId" to timing.id,
        "taskId" to taskCloudId,
        "startedAt" to timing.startedAt,
        "endedAt" to timing.endedAt,
        "durationMinutes" to timing.durationMinutes,
        "source" to timing.source,
        "notes" to timing.notes,
        "createdAt" to timing.createdAt
    )

    fun mapToTaskTiming(
        data: Map<String, Any?>,
        localId: Long = 0,
        taskLocalId: Long,
        cloudId: String? = null
    ): TaskTimingEntity = TaskTimingEntity(
        id = localId,
        cloudId = cloudId,
        taskId = taskLocalId,
        startedAt = (data["startedAt"] as? Number)?.toLong(),
        endedAt = (data["endedAt"] as? Number)?.toLong(),
        durationMinutes = (data["durationMinutes"] as? Number)?.toInt() ?: 0,
        source = data["source"] as? String ?: TaskTimingEntity.SOURCE_MANUAL,
        notes = data["notes"] as? String,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
    )

    fun mapToTaskCompletion(
        data: Map<String, Any?>,
        localId: Long = 0,
        taskLocalId: Long? = null,
        projectLocalId: Long? = null,
        cloudId: String? = null
    ): TaskCompletionEntity =
        TaskCompletionEntity(
            id = localId,
            cloudId = cloudId,
            taskId = taskLocalId,
            projectId = projectLocalId,
            completedDate = (data["completedDate"] as? Number)?.toLong() ?: 0,
            completedAtTime = (data["completedAtTime"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            priority = (data["priority"] as? Number)?.toInt() ?: 0,
            wasOverdue = data["wasOverdue"] as? Boolean ?: false,
            daysToComplete = (data["daysToComplete"] as? Number)?.toInt(),
            tags = data["tags"] as? String,
            spawnedRecurrenceId = (data["spawnedRecurrenceId"] as? Number)?.toLong()
        )

    fun mapToTaskTemplate(
        data: Map<String, Any?>,
        localId: Long = 0,
        templateProjectLocalId: Long? = null,
        cloudId: String? = null
    ): TaskTemplateEntity = TaskTemplateEntity(
        id = localId,
        cloudId = cloudId,
        userId = data["userId"] as? String,
        remoteId = (data["remoteId"] as? Number)?.toInt(),
        name = data["name"] as? String ?: "",
        description = data["description"] as? String,
        icon = data["icon"] as? String,
        category = data["category"] as? String,
        templateTitle = data["templateTitle"] as? String,
        templateDescription = data["templateDescription"] as? String,
        templatePriority = (data["templatePriority"] as? Number)?.toInt(),
        templateProjectId = templateProjectLocalId,
        templateTagsJson = data["templateTagsJson"] as? String,
        templateRecurrenceJson = data["templateRecurrenceJson"] as? String,
        templateDuration = (data["templateDuration"] as? Number)?.toInt(),
        templateSubtasksJson = data["templateSubtasksJson"] as? String,
        isBuiltIn = data["isBuiltIn"] as? Boolean ?: false,
        templateKey = data["templateKey"] as? String,
        usageCount = (data["usageCount"] as? Number)?.toInt() ?: 0,
        lastUsedAt = (data["lastUsedAt"] as? Number)?.toLong(),
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
    )

    // ── Courses ───────────────────────────────────────────────────────────────

    fun courseToMap(course: CourseEntity): Map<String, Any?> = mapOf(
        "localId" to course.id,
        "name" to course.name,
        "code" to course.code,
        "color" to course.color,
        "icon" to course.icon,
        "active" to course.active,
        "sortOrder" to course.sortOrder,
        "createdAt" to course.createdAt,
        "updatedAt" to course.updatedAt,
        "createDailyTask" to course.createDailyTask,
        "dailyTaskId" to course.dailyTaskId
    )

    fun mapToCourse(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): CourseEntity = CourseEntity(
        id = localId,
        cloudId = cloudId,
        name = data["name"] as? String ?: "",
        code = data["code"] as? String ?: "",
        color = (data["color"] as? Number)?.toInt() ?: 0,
        icon = data["icon"] as? String ?: "\uD83D\uDCDA",
        active = data["active"] as? Boolean ?: true,
        sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L,
        createDailyTask = data["createDailyTask"] as? Boolean ?: false,
        // The daily_task_id is a per-device local FK and intentionally
        // not round-tripped from the cloud payload \u2014 each device manages
        // its own copy of the spawned TaskEntity locally.
        dailyTaskId = null
    )

    // [courseCloudId] is the Firestore document ID of the parent CourseEntity from sync_metadata.
    fun courseCompletionToMap(
        completion: CourseCompletionEntity,
        courseCloudId: String
    ): Map<String, Any?> = mapOf(
        "localId" to completion.id,
        "courseCloudId" to courseCloudId,
        "date" to completion.date,
        "completed" to completion.completed,
        "completedAt" to completion.completedAt,
        "createdAt" to completion.createdAt,
        "updatedAt" to completion.updatedAt
    )

    fun mapToCourseCompletion(
        data: Map<String, Any?>,
        localId: Long = 0,
        courseLocalId: Long,
        cloudId: String? = null
    ): CourseCompletionEntity = CourseCompletionEntity(
        id = localId,
        cloudId = cloudId,
        date = (data["date"] as? Number)?.toLong() ?: 0L,
        courseId = courseLocalId,
        completed = data["completed"] as? Boolean ?: false,
        completedAt = (data["completedAt"] as? Number)?.toLong(),
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Leisure Budget v2.0 (Items 7 + 8) ─────────────────────────────────────

    fun leisureActivityToMap(activity: LeisureActivityEntity): Map<String, Any?> = mapOf(
        "localId" to activity.id,
        "name" to activity.name,
        "category" to activity.category,
        "defaultDurationMinutes" to activity.defaultDurationMinutes,
        "enabled" to activity.enabled,
        "createdAt" to activity.createdAt,
        "updatedAt" to activity.updatedAt,
        "lastCompletedAt" to activity.lastCompletedAt
    )

    fun mapToLeisureActivity(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): LeisureActivityEntity = LeisureActivityEntity(
        id = localId,
        cloudId = cloudId,
        name = data["name"] as? String ?: "",
        category = data["category"] as? String ?: "PASSIVE",
        defaultDurationMinutes = (data["defaultDurationMinutes"] as? Number)?.toInt(),
        enabled = data["enabled"] as? Boolean ?: true,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L,
        lastCompletedAt = (data["lastCompletedAt"] as? Number)?.toLong()
    )

    fun leisureSessionToMap(session: LeisureSessionEntity): Map<String, Any?> = mapOf(
        "localId" to session.id,
        "activityId" to session.activityId,
        "category" to session.category,
        "durationMinutes" to session.durationMinutes,
        "loggedAt" to session.loggedAt,
        "source" to session.source,
        "createdAt" to session.createdAt
    )

    fun mapToLeisureSession(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): LeisureSessionEntity = LeisureSessionEntity(
        id = localId,
        cloudId = cloudId,
        activityId = (data["activityId"] as? Number)?.toLong(),
        category = data["category"] as? String ?: "PASSIVE",
        durationMinutes = (data["durationMinutes"] as? Number)?.toInt() ?: 0,
        loggedAt = (data["loggedAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        source = data["source"] as? String ?: "MANUAL",
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
    )

    // ── Self-care steps ───────────────────────────────────────────────────────

    fun selfCareStepToMap(step: SelfCareStepEntity): Map<String, Any?> = mapOf(
        "localId" to step.id,
        "stepId" to step.stepId,
        "routineType" to step.routineType,
        "label" to step.label,
        "duration" to step.duration,
        "tier" to step.tier,
        "note" to step.note,
        "phase" to step.phase,
        "sortOrder" to step.sortOrder,
        "reminderDelayMillis" to step.reminderDelayMillis,
        "timeOfDay" to step.timeOfDay,
        "medicationName" to step.medicationName,
        "sourceVersion" to step.sourceVersion,
        "updatedAt" to step.updatedAt
    )

    fun mapToSelfCareStep(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): SelfCareStepEntity = SelfCareStepEntity(
        id = localId,
        cloudId = cloudId,
        stepId = data["stepId"] as? String ?: "",
        routineType = data["routineType"] as? String ?: "",
        label = data["label"] as? String ?: "",
        duration = data["duration"] as? String ?: "",
        tier = data["tier"] as? String ?: "",
        note = data["note"] as? String ?: "",
        phase = data["phase"] as? String ?: "",
        sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0,
        reminderDelayMillis = (data["reminderDelayMillis"] as? Number)?.toLong(),
        timeOfDay = data["timeOfDay"] as? String ?: "morning",
        medicationName = data["medicationName"] as? String,
        sourceVersion = (data["sourceVersion"] as? Number)?.toInt() ?: 0,
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Self-care logs ────────────────────────────────────────────────────────

    fun selfCareLogToMap(log: SelfCareLogEntity): Map<String, Any?> = mapOf(
        "localId" to log.id,
        "routineType" to log.routineType,
        "date" to log.date,
        "selectedTier" to log.selectedTier,
        "completedSteps" to log.completedSteps,
        "tiersByTime" to log.tiersByTime,
        "isComplete" to log.isComplete,
        "startedAt" to log.startedAt,
        "createdAt" to log.createdAt,
        "updatedAt" to log.updatedAt
    )

    fun mapToSelfCareLog(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): SelfCareLogEntity = SelfCareLogEntity(
        id = localId,
        cloudId = cloudId,
        routineType = data["routineType"] as? String ?: "",
        date = (data["date"] as? Number)?.toLong() ?: 0L,
        selectedTier = data["selectedTier"] as? String ?: "solid",
        completedSteps = data["completedSteps"] as? String ?: "[]",
        tiersByTime = data["tiersByTime"] as? String ?: "{}",
        isComplete = data["isComplete"] as? Boolean ?: false,
        startedAt = (data["startedAt"] as? Number)?.toLong(),
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Notification Profiles ────────────────────────────────────────────────

    fun notificationProfileToMap(profile: NotificationProfileEntity): Map<String, Any?> = mapOf(
        "localId" to profile.id,
        "name" to profile.name,
        "offsetsCsv" to profile.offsetsCsv,
        "escalation" to profile.escalation,
        "escalationIntervalMinutes" to profile.escalationIntervalMinutes,
        "isBuiltIn" to profile.isBuiltIn,
        "urgencyTierKey" to profile.urgencyTierKey,
        "soundId" to profile.soundId,
        "soundVolumePercent" to profile.soundVolumePercent,
        "soundFadeInMs" to profile.soundFadeInMs,
        "soundFadeOutMs" to profile.soundFadeOutMs,
        "silent" to profile.silent,
        "vibrationPresetKey" to profile.vibrationPresetKey,
        "vibrationIntensityKey" to profile.vibrationIntensityKey,
        "vibrationRepeatCount" to profile.vibrationRepeatCount,
        "vibrationContinuous" to profile.vibrationContinuous,
        "customVibrationPatternCsv" to profile.customVibrationPatternCsv,
        "displayModeKey" to profile.displayModeKey,
        "lockScreenVisibilityKey" to profile.lockScreenVisibilityKey,
        "accentColorHex" to profile.accentColorHex,
        "badgeModeKey" to profile.badgeModeKey,
        "toastPositionKey" to profile.toastPositionKey,
        "escalationChainJson" to profile.escalationChainJson,
        "quietHoursJson" to profile.quietHoursJson,
        "snoozeDurationsCsv" to profile.snoozeDurationsCsv,
        "reAlertIntervalMinutes" to profile.reAlertIntervalMinutes,
        "reAlertMaxAttempts" to profile.reAlertMaxAttempts,
        "watchSyncModeKey" to profile.watchSyncModeKey,
        "watchHapticPresetKey" to profile.watchHapticPresetKey,
        "autoSwitchRulesJson" to profile.autoSwitchRulesJson,
        "volumeOverride" to profile.volumeOverride,
        "createdAt" to profile.createdAt,
        "updatedAt" to profile.updatedAt
    )

    fun mapToNotificationProfile(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): NotificationProfileEntity = NotificationProfileEntity(
        id = localId,
        cloudId = cloudId,
        name = data["name"] as? String ?: "",
        offsetsCsv = data["offsetsCsv"] as? String ?: "",
        escalation = data["escalation"] as? Boolean ?: false,
        escalationIntervalMinutes = (data["escalationIntervalMinutes"] as? Number)?.toInt(),
        isBuiltIn = data["isBuiltIn"] as? Boolean ?: false,
        urgencyTierKey = data["urgencyTierKey"] as? String ?: "medium",
        soundId = data["soundId"] as? String ?: "system_default",
        soundVolumePercent = (data["soundVolumePercent"] as? Number)?.toInt() ?: 70,
        soundFadeInMs = (data["soundFadeInMs"] as? Number)?.toInt() ?: 0,
        soundFadeOutMs = (data["soundFadeOutMs"] as? Number)?.toInt() ?: 0,
        silent = data["silent"] as? Boolean ?: false,
        vibrationPresetKey = data["vibrationPresetKey"] as? String ?: "single",
        vibrationIntensityKey = data["vibrationIntensityKey"] as? String ?: "medium",
        vibrationRepeatCount = (data["vibrationRepeatCount"] as? Number)?.toInt() ?: 1,
        vibrationContinuous = data["vibrationContinuous"] as? Boolean ?: false,
        customVibrationPatternCsv = data["customVibrationPatternCsv"] as? String,
        displayModeKey = data["displayModeKey"] as? String ?: "standard",
        lockScreenVisibilityKey = data["lockScreenVisibilityKey"] as? String ?: "app_name",
        accentColorHex = data["accentColorHex"] as? String,
        badgeModeKey = data["badgeModeKey"] as? String ?: "total",
        toastPositionKey = data["toastPositionKey"] as? String ?: "top_right",
        escalationChainJson = data["escalationChainJson"] as? String,
        quietHoursJson = data["quietHoursJson"] as? String,
        snoozeDurationsCsv = data["snoozeDurationsCsv"] as? String ?: "5,15,30,60",
        reAlertIntervalMinutes = (data["reAlertIntervalMinutes"] as? Number)?.toInt() ?: 5,
        reAlertMaxAttempts = (data["reAlertMaxAttempts"] as? Number)?.toInt() ?: 3,
        watchSyncModeKey = data["watchSyncModeKey"] as? String ?: "mirror",
        watchHapticPresetKey = data["watchHapticPresetKey"] as? String ?: "single",
        autoSwitchRulesJson = data["autoSwitchRulesJson"] as? String,
        volumeOverride = data["volumeOverride"] as? Boolean ?: false,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Custom Sounds ────────────────────────────────────────────────────────
    //
    // `uri` is a local sandbox path (file:///data/user/0/.../sounds/<file>).
    // Syncing the URI lets other devices see *that a user-added sound exists
    // under this name* but the audio file itself is per-device — playing it
    // on a sibling device falls back to the system default. Uploading the
    // actual sound blob is deferred; see docs.

    fun customSoundToMap(sound: CustomSoundEntity): Map<String, Any?> = mapOf(
        "localId" to sound.id,
        "name" to sound.name,
        "originalFilename" to sound.originalFilename,
        "uri" to sound.uri,
        "format" to sound.format,
        "sizeBytes" to sound.sizeBytes,
        "durationMs" to sound.durationMs,
        "createdAt" to sound.createdAt,
        "updatedAt" to sound.updatedAt
    )

    fun mapToCustomSound(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): CustomSoundEntity = CustomSoundEntity(
        id = localId,
        cloudId = cloudId,
        name = data["name"] as? String ?: "",
        originalFilename = data["originalFilename"] as? String ?: "",
        uri = data["uri"] as? String ?: "",
        format = data["format"] as? String ?: "",
        sizeBytes = (data["sizeBytes"] as? Number)?.toLong() ?: 0L,
        durationMs = (data["durationMs"] as? Number)?.toLong() ?: 0L,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Saved Filters ────────────────────────────────────────────────────────

    fun savedFilterToMap(filter: SavedFilterEntity): Map<String, Any?> = mapOf(
        "localId" to filter.id,
        "name" to filter.name,
        "filterJson" to filter.filterJson,
        "iconEmoji" to filter.iconEmoji,
        "sortOrder" to filter.sortOrder,
        "createdAt" to filter.createdAt,
        "updatedAt" to filter.updatedAt
    )

    fun mapToSavedFilter(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): SavedFilterEntity = SavedFilterEntity(
        id = localId,
        cloudId = cloudId,
        name = data["name"] as? String ?: "",
        filterJson = data["filterJson"] as? String ?: "",
        iconEmoji = data["iconEmoji"] as? String,
        sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── NLP Shortcuts ────────────────────────────────────────────────────────

    fun nlpShortcutToMap(shortcut: NlpShortcutEntity): Map<String, Any?> = mapOf(
        "localId" to shortcut.id,
        "trigger" to shortcut.trigger,
        "expansion" to shortcut.expansion,
        "sortOrder" to shortcut.sortOrder,
        "createdAt" to shortcut.createdAt,
        "updatedAt" to shortcut.updatedAt
    )

    fun mapToNlpShortcut(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): NlpShortcutEntity = NlpShortcutEntity(
        id = localId,
        cloudId = cloudId,
        trigger = data["trigger"] as? String ?: "",
        expansion = data["expansion"] as? String ?: "",
        sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Habit Templates ──────────────────────────────────────────────────────

    fun habitTemplateToMap(template: HabitTemplateEntity): Map<String, Any?> = mapOf(
        "localId" to template.id,
        "name" to template.name,
        "description" to template.description,
        "iconEmoji" to template.iconEmoji,
        "color" to template.color,
        "category" to template.category,
        "frequency" to template.frequency,
        "targetCount" to template.targetCount,
        "activeDaysCsv" to template.activeDaysCsv,
        "isBuiltIn" to template.isBuiltIn,
        "usageCount" to template.usageCount,
        "lastUsedAt" to template.lastUsedAt,
        "createdAt" to template.createdAt,
        "updatedAt" to template.updatedAt
    )

    fun mapToHabitTemplate(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): HabitTemplateEntity = HabitTemplateEntity(
        id = localId,
        cloudId = cloudId,
        name = data["name"] as? String ?: "",
        description = data["description"] as? String,
        iconEmoji = data["iconEmoji"] as? String,
        color = data["color"] as? String,
        category = data["category"] as? String,
        frequency = data["frequency"] as? String ?: "DAILY",
        targetCount = (data["targetCount"] as? Number)?.toInt() ?: 1,
        activeDaysCsv = data["activeDaysCsv"] as? String ?: "",
        isBuiltIn = data["isBuiltIn"] as? Boolean ?: false,
        usageCount = (data["usageCount"] as? Number)?.toInt() ?: 0,
        lastUsedAt = (data["lastUsedAt"] as? Number)?.toLong(),
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Project Templates ────────────────────────────────────────────────────

    fun projectTemplateToMap(template: ProjectTemplateEntity): Map<String, Any?> = mapOf(
        "localId" to template.id,
        "name" to template.name,
        "description" to template.description,
        "color" to template.color,
        "iconEmoji" to template.iconEmoji,
        "category" to template.category,
        "taskTemplatesJson" to template.taskTemplatesJson,
        "isBuiltIn" to template.isBuiltIn,
        "usageCount" to template.usageCount,
        "lastUsedAt" to template.lastUsedAt,
        "createdAt" to template.createdAt,
        "updatedAt" to template.updatedAt
    )

    fun mapToProjectTemplate(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): ProjectTemplateEntity = ProjectTemplateEntity(
        id = localId,
        cloudId = cloudId,
        name = data["name"] as? String ?: "",
        description = data["description"] as? String,
        color = data["color"] as? String,
        iconEmoji = data["iconEmoji"] as? String,
        category = data["category"] as? String,
        taskTemplatesJson = data["taskTemplatesJson"] as? String ?: "[]",
        isBuiltIn = data["isBuiltIn"] as? Boolean ?: false,
        usageCount = (data["usageCount"] as? Number)?.toInt() ?: 0,
        lastUsedAt = (data["lastUsedAt"] as? Number)?.toLong(),
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Boundary Rules ───────────────────────────────────────────────────────

    fun boundaryRuleToMap(rule: BoundaryRuleEntity): Map<String, Any?> = mapOf(
        "localId" to rule.id,
        "name" to rule.name,
        "ruleType" to rule.ruleType,
        "category" to rule.category,
        "startTime" to rule.startTime,
        "endTime" to rule.endTime,
        "activeDaysCsv" to rule.activeDaysCsv,
        "isEnabled" to rule.isEnabled,
        "isBuiltIn" to rule.isBuiltIn,
        "createdAt" to rule.createdAt,
        "updatedAt" to rule.updatedAt
    )

    fun mapToBoundaryRule(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): BoundaryRuleEntity = BoundaryRuleEntity(
        id = localId,
        cloudId = cloudId,
        name = data["name"] as? String ?: "",
        ruleType = data["ruleType"] as? String ?: "",
        category = data["category"] as? String ?: "",
        startTime = data["startTime"] as? String ?: "00:00",
        endTime = data["endTime"] as? String ?: "23:59",
        activeDaysCsv = data["activeDaysCsv"] as? String ?: "",
        isEnabled = data["isEnabled"] as? Boolean ?: true,
        isBuiltIn = data["isBuiltIn"] as? Boolean ?: false,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── CheckIn Logs ─────────────────────────────────────────────────────────

    fun checkInLogToMap(log: CheckInLogEntity): Map<String, Any?> = mapOf(
        "localId" to log.id,
        "date" to log.date,
        "stepsCompletedCsv" to log.stepsCompletedCsv,
        "medicationsConfirmed" to log.medicationsConfirmed,
        "tasksReviewed" to log.tasksReviewed,
        "habitsCompleted" to log.habitsCompleted,
        "createdAt" to log.createdAt,
        "updatedAt" to log.updatedAt
    )

    fun mapToCheckInLog(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): CheckInLogEntity = CheckInLogEntity(
        id = localId,
        cloudId = cloudId,
        date = (data["date"] as? Number)?.toLong() ?: 0L,
        stepsCompletedCsv = data["stepsCompletedCsv"] as? String ?: "",
        medicationsConfirmed = (data["medicationsConfirmed"] as? Number)?.toInt() ?: 0,
        tasksReviewed = (data["tasksReviewed"] as? Number)?.toInt() ?: 0,
        habitsCompleted = (data["habitsCompleted"] as? Number)?.toInt() ?: 0,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Mood/Energy Logs ─────────────────────────────────────────────────────

    fun moodEnergyLogToMap(log: MoodEnergyLogEntity): Map<String, Any?> = mapOf(
        "localId" to log.id,
        "date" to log.date,
        "mood" to log.mood,
        "energy" to log.energy,
        "notes" to log.notes,
        "timeOfDay" to log.timeOfDay,
        "createdAt" to log.createdAt,
        "updatedAt" to log.updatedAt
    )

    fun mapToMoodEnergyLog(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): MoodEnergyLogEntity = MoodEnergyLogEntity(
        id = localId,
        cloudId = cloudId,
        date = (data["date"] as? Number)?.toLong() ?: 0L,
        mood = (data["mood"] as? Number)?.toInt() ?: 3,
        energy = (data["energy"] as? Number)?.toInt() ?: 3,
        notes = data["notes"] as? String,
        timeOfDay = data["timeOfDay"] as? String ?: "morning",
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Focus-Release Logs ───────────────────────────────────────────────────
    //
    // `taskId` is a Long FK that must be translated at the call-site:
    //   push: local id → cloud id via syncMetadataDao.getCloudId(id, "task")
    //   pull: cloud id → local id via syncMetadataDao.getLocalId(id, "task")

    fun focusReleaseLogToMap(
        log: FocusReleaseLogEntity,
        taskCloudId: String? = null
    ): Map<String, Any?> = mapOf(
        "localId" to log.id,
        "eventType" to log.eventType,
        "taskId" to taskCloudId,
        "context" to log.context,
        "createdAt" to log.createdAt,
        "updatedAt" to log.updatedAt
    )

    fun mapToFocusReleaseLog(
        data: Map<String, Any?>,
        localId: Long = 0,
        taskLocalId: Long? = null,
        cloudId: String? = null
    ): FocusReleaseLogEntity = FocusReleaseLogEntity(
        id = localId,
        cloudId = cloudId,
        eventType = data["eventType"] as? String ?: "",
        taskId = taskLocalId,
        context = data["context"] as? String,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Medication Refills ───────────────────────────────────────────────────

    fun medicationRefillToMap(refill: MedicationRefillEntity): Map<String, Any?> = mapOf(
        "localId" to refill.id,
        "medicationName" to refill.medicationName,
        "pillCount" to refill.pillCount,
        "pillsPerDose" to refill.pillsPerDose,
        "dosesPerDay" to refill.dosesPerDay,
        "lastRefillDate" to refill.lastRefillDate,
        "pharmacyName" to refill.pharmacyName,
        "pharmacyPhone" to refill.pharmacyPhone,
        "reminderDaysBefore" to refill.reminderDaysBefore,
        "createdAt" to refill.createdAt,
        "updatedAt" to refill.updatedAt
    )

    fun mapToMedicationRefill(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): MedicationRefillEntity = MedicationRefillEntity(
        id = localId,
        cloudId = cloudId,
        medicationName = data["medicationName"] as? String ?: "",
        pillCount = (data["pillCount"] as? Number)?.toInt() ?: 0,
        pillsPerDose = (data["pillsPerDose"] as? Number)?.toInt() ?: 1,
        dosesPerDay = (data["dosesPerDay"] as? Number)?.toInt() ?: 1,
        lastRefillDate = (data["lastRefillDate"] as? Number)?.toLong(),
        pharmacyName = data["pharmacyName"] as? String,
        pharmacyPhone = data["pharmacyPhone"] as? String,
        reminderDaysBefore = (data["reminderDaysBefore"] as? Number)?.toInt() ?: 3,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
    )

    // ── Weekly Reviews ───────────────────────────────────────────────────────

    fun weeklyReviewToMap(review: WeeklyReviewEntity): Map<String, Any?> = mapOf(
        "localId" to review.id,
        "weekStartDate" to review.weekStartDate,
        "metricsJson" to review.metricsJson,
        "aiInsightsJson" to review.aiInsightsJson,
        "createdAt" to review.createdAt,
        "updatedAt" to review.updatedAt
    )

    fun mapToWeeklyReview(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): WeeklyReviewEntity = WeeklyReviewEntity(
        id = localId,
        cloudId = cloudId,
        weekStartDate = (data["weekStartDate"] as? Number)?.toLong() ?: 0L,
        metricsJson = data["metricsJson"] as? String ?: "{}",
        aiInsightsJson = data["aiInsightsJson"] as? String,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Daily Essential Slot Completions ─────────────────────────────────────
    //
    // `medIdsJson` is a JSON array of synthetic dose keys — no FK translation
    // needed; the keys survive cross-device lookup via name matching.

    fun dailyEssentialSlotCompletionToMap(
        row: DailyEssentialSlotCompletionEntity
    ): Map<String, Any?> = mapOf(
        "localId" to row.id,
        "date" to row.date,
        "slotKey" to row.slotKey,
        "medIdsJson" to row.medIdsJson,
        "takenAt" to row.takenAt,
        "createdAt" to row.createdAt,
        "updatedAt" to row.updatedAt
    )

    fun mapToDailyEssentialSlotCompletion(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): DailyEssentialSlotCompletionEntity = DailyEssentialSlotCompletionEntity(
        id = localId,
        cloudId = cloudId,
        date = (data["date"] as? Number)?.toLong() ?: 0L,
        slotKey = data["slotKey"] as? String ?: "",
        medIdsJson = data["medIdsJson"] as? String ?: "[]",
        takenAt = (data["takenAt"] as? Number)?.toLong(),
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
    )

    // ── Assignments ──────────────────────────────────────────────────────────
    //
    // `courseId` is a required Long FK; the caller must resolve cloud ↔ local
    // before calling these. A missing courseCloudId at push time means the
    // parent course hasn't synced yet — skip the row and retry on the next
    // pass rather than inserting a dangling FK.

    fun assignmentToMap(
        assignment: AssignmentEntity,
        courseCloudId: String
    ): Map<String, Any?> = mapOf(
        "localId" to assignment.id,
        "courseId" to courseCloudId,
        "title" to assignment.title,
        "dueDate" to assignment.dueDate,
        "completed" to assignment.completed,
        "completedAt" to assignment.completedAt,
        "notes" to assignment.notes,
        "createdAt" to assignment.createdAt,
        "updatedAt" to assignment.updatedAt
    )

    fun mapToAssignment(
        data: Map<String, Any?>,
        localId: Long = 0,
        courseLocalId: Long,
        cloudId: String? = null
    ): AssignmentEntity = AssignmentEntity(
        id = localId,
        cloudId = cloudId,
        courseId = courseLocalId,
        title = data["title"] as? String ?: "",
        dueDate = (data["dueDate"] as? Number)?.toLong(),
        completed = data["completed"] as? Boolean ?: false,
        completedAt = (data["completedAt"] as? Number)?.toLong(),
        notes = data["notes"] as? String,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Attachments ──────────────────────────────────────────────────────────
    //
    // `uri` is a per-device sandbox path for image attachments; the pointer
    // round-trips but the bytes don't. For link attachments `uri` is the URL
    // and round-trips cleanly.

    fun attachmentToMap(
        attachment: AttachmentEntity,
        taskCloudId: String
    ): Map<String, Any?> = mapOf(
        "localId" to attachment.id,
        "taskId" to taskCloudId,
        "type" to attachment.type,
        "uri" to attachment.uri,
        "fileName" to attachment.fileName,
        "thumbnailUri" to attachment.thumbnailUri,
        "createdAt" to attachment.createdAt,
        "updatedAt" to attachment.updatedAt
    )

    fun mapToAttachment(
        data: Map<String, Any?>,
        localId: Long = 0,
        taskLocalId: Long,
        cloudId: String? = null
    ): AttachmentEntity = AttachmentEntity(
        id = localId,
        cloudId = cloudId,
        taskId = taskLocalId,
        type = data["type"] as? String ?: "link",
        uri = data["uri"] as? String ?: "",
        fileName = data["fileName"] as? String,
        thumbnailUri = data["thumbnailUri"] as? String,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Study Logs ───────────────────────────────────────────────────────────
    //
    // Two nullable FKs: `coursePick` and `assignmentPick`, both SET_NULL on
    // parent delete. Translate each at the call-site; null-to-null is
    // intentional (user hasn't picked yet).

    fun studyLogToMap(
        log: StudyLogEntity,
        coursePickCloudId: String? = null,
        assignmentPickCloudId: String? = null
    ): Map<String, Any?> = mapOf(
        "localId" to log.id,
        "date" to log.date,
        "coursePick" to coursePickCloudId,
        "studyDone" to log.studyDone,
        "assignmentPick" to assignmentPickCloudId,
        "assignmentDone" to log.assignmentDone,
        "startedAt" to log.startedAt,
        "createdAt" to log.createdAt,
        "updatedAt" to log.updatedAt
    )

    fun mapToStudyLog(
        data: Map<String, Any?>,
        localId: Long = 0,
        coursePickLocalId: Long? = null,
        assignmentPickLocalId: Long? = null,
        cloudId: String? = null
    ): StudyLogEntity = StudyLogEntity(
        id = localId,
        cloudId = cloudId,
        date = (data["date"] as? Number)?.toLong() ?: 0L,
        coursePick = coursePickLocalId,
        studyDone = data["studyDone"] as? Boolean ?: false,
        assignmentPick = assignmentPickLocalId,
        assignmentDone = data["assignmentDone"] as? Boolean ?: false,
        startedAt = (data["startedAt"] as? Number)?.toLong(),
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )

    // ── Automation Rules ─────────────────────────────────────────────────────
    //
    // Full-doc LWW (matches the medication pattern, simplest correct
    // behavior). All three structural blobs (`triggerJson`, `conditionJson`,
    // `actionJson`) round-trip as opaque strings — Firestore stores them
    // verbatim, the decoder is local. The metadata cols (enabled, priority,
    // fire counters) sync as plain scalars. Logs are local-only and do
    // NOT round-trip — they're observability, not state.

    fun automationRuleToMap(rule: AutomationRuleEntity): Map<String, Any?> = mapOf(
        "localId" to rule.id,
        "name" to rule.name,
        "description" to rule.description,
        "enabled" to rule.enabled,
        "priority" to rule.priority,
        "isBuiltIn" to rule.isBuiltIn,
        "templateKey" to rule.templateKey,
        "triggerJson" to rule.triggerJson,
        "conditionJson" to rule.conditionJson,
        "actionJson" to rule.actionJson,
        "lastFiredAt" to rule.lastFiredAt,
        "fireCount" to rule.fireCount,
        "dailyFireCount" to rule.dailyFireCount,
        "dailyFireCountDate" to rule.dailyFireCountDate,
        "createdAt" to rule.createdAt,
        "updatedAt" to rule.updatedAt
    )

    fun mapToAutomationRule(
        data: Map<String, Any?>,
        localId: Long = 0,
        cloudId: String? = null
    ): AutomationRuleEntity = AutomationRuleEntity(
        id = localId,
        cloudId = cloudId,
        name = data["name"] as? String ?: "",
        description = data["description"] as? String,
        enabled = data["enabled"] as? Boolean ?: true,
        priority = (data["priority"] as? Number)?.toInt() ?: 0,
        isBuiltIn = data["isBuiltIn"] as? Boolean ?: false,
        templateKey = data["templateKey"] as? String,
        triggerJson = data["triggerJson"] as? String ?: "",
        conditionJson = data["conditionJson"] as? String,
        actionJson = data["actionJson"] as? String ?: "[]",
        lastFiredAt = (data["lastFiredAt"] as? Number)?.toLong(),
        fireCount = (data["fireCount"] as? Number)?.toInt() ?: 0,
        dailyFireCount = (data["dailyFireCount"] as? Number)?.toInt() ?: 0,
        dailyFireCountDate = data["dailyFireCountDate"] as? String,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
    )
}
