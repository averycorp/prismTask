package com.averycorp.prismtask.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.averycorp.prismtask.data.local.dao.AttachmentDao
import com.averycorp.prismtask.data.local.dao.AutomationLogDao
import com.averycorp.prismtask.data.local.dao.AutomationRuleDao
import com.averycorp.prismtask.data.local.dao.BatchUndoLogDao
import com.averycorp.prismtask.data.local.dao.BoundaryRuleDao
import com.averycorp.prismtask.data.local.dao.CalendarSyncDao
import com.averycorp.prismtask.data.local.dao.ChatMessageDao
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
import com.averycorp.prismtask.data.local.dao.UsageLogDao
import com.averycorp.prismtask.data.local.dao.WeeklyReviewDao
import com.averycorp.prismtask.data.local.entity.AssignmentEntity
import com.averycorp.prismtask.data.local.entity.AttachmentEntity
import com.averycorp.prismtask.data.local.entity.AutomationLogEntity
import com.averycorp.prismtask.data.local.entity.AutomationRuleEntity
import com.averycorp.prismtask.data.local.entity.BatchUndoLogEntry
import com.averycorp.prismtask.data.local.entity.BoundaryRuleEntity
import com.averycorp.prismtask.data.local.entity.CalendarSyncEntity
import com.averycorp.prismtask.data.local.entity.ChatMessageEntity
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
import com.averycorp.prismtask.data.local.entity.LeisureLogEntity
import com.averycorp.prismtask.data.local.entity.MedicationDoseEntity
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.local.entity.MedicationRefillEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotCrossRef
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotOverrideEntity
import com.averycorp.prismtask.data.local.entity.MedicationTierStateEntity
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
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskCompletionEntity
import com.averycorp.prismtask.data.local.entity.TaskDependencyEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import com.averycorp.prismtask.data.local.entity.TaskTemplateEntity
import com.averycorp.prismtask.data.local.entity.TaskTimingEntity
import com.averycorp.prismtask.data.local.entity.UsageLogEntity
import com.averycorp.prismtask.data.local.entity.WeeklyReviewEntity

@Database(
    entities = [
        TaskEntity::class,
        ProjectEntity::class,
        TagEntity::class,
        TaskTagCrossRef::class,
        AttachmentEntity::class,
        UsageLogEntity::class,
        SyncMetadataEntity::class,
        CalendarSyncEntity::class,
        HabitEntity::class,
        HabitCompletionEntity::class,
        HabitLogEntity::class,
        LeisureLogEntity::class,
        CourseEntity::class,
        AssignmentEntity::class,
        CourseCompletionEntity::class,
        SelfCareLogEntity::class,
        SelfCareStepEntity::class,
        StudyLogEntity::class,
        TaskTemplateEntity::class,
        NlpShortcutEntity::class,
        SavedFilterEntity::class,
        NotificationProfileEntity::class,
        CustomSoundEntity::class,
        ProjectTemplateEntity::class,
        HabitTemplateEntity::class,
        MoodEnergyLogEntity::class,
        MedicationRefillEntity::class,
        BoundaryRuleEntity::class,
        CheckInLogEntity::class,
        WeeklyReviewEntity::class,
        TaskCompletionEntity::class,
        FocusReleaseLogEntity::class,
        DailyEssentialSlotCompletionEntity::class,
        MilestoneEntity::class,
        MedicationEntity::class,
        MedicationDoseEntity::class,
        BatchUndoLogEntry::class,
        MedicationSlotEntity::class,
        MedicationSlotOverrideEntity::class,
        MedicationSlotCrossRef::class,
        MedicationTierStateEntity::class,
        TaskTimingEntity::class,
        AutomationRuleEntity::class,
        AutomationLogEntity::class,
        ProjectPhaseEntity::class,
        ProjectRiskEntity::class,
        TaskDependencyEntity::class,
        ExternalAnchorEntity::class,
        ChatMessageEntity::class
    ],
    version = CURRENT_DB_VERSION,
    exportSchema = false
)
abstract class PrismTaskDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    abstract fun projectDao(): ProjectDao

    abstract fun tagDao(): TagDao

    abstract fun attachmentDao(): AttachmentDao

    abstract fun usageLogDao(): UsageLogDao

    abstract fun syncMetadataDao(): SyncMetadataDao

    abstract fun calendarSyncDao(): CalendarSyncDao

    abstract fun habitDao(): HabitDao

    abstract fun habitCompletionDao(): HabitCompletionDao

    abstract fun habitLogDao(): HabitLogDao

    abstract fun leisureDao(): LeisureDao

    abstract fun schoolworkDao(): SchoolworkDao

    abstract fun selfCareDao(): SelfCareDao

    abstract fun taskTemplateDao(): TaskTemplateDao

    abstract fun nlpShortcutDao(): NlpShortcutDao

    abstract fun savedFilterDao(): SavedFilterDao

    abstract fun notificationProfileDao(): NotificationProfileDao

    abstract fun customSoundDao(): CustomSoundDao

    abstract fun projectTemplateDao(): ProjectTemplateDao

    abstract fun habitTemplateDao(): HabitTemplateDao

    abstract fun moodEnergyLogDao(): MoodEnergyLogDao

    abstract fun medicationRefillDao(): MedicationRefillDao

    abstract fun boundaryRuleDao(): BoundaryRuleDao

    abstract fun checkInLogDao(): CheckInLogDao

    abstract fun weeklyReviewDao(): WeeklyReviewDao

    abstract fun taskCompletionDao(): TaskCompletionDao

    abstract fun focusReleaseLogDao(): FocusReleaseLogDao

    abstract fun dailyEssentialSlotCompletionDao(): DailyEssentialSlotCompletionDao

    abstract fun milestoneDao(): MilestoneDao

    abstract fun medicationDao(): MedicationDao

    abstract fun medicationDoseDao(): MedicationDoseDao

    abstract fun batchUndoLogDao(): BatchUndoLogDao

    abstract fun medicationSlotDao(): MedicationSlotDao

    abstract fun medicationSlotOverrideDao(): MedicationSlotOverrideDao

    abstract fun medicationTierStateDao(): MedicationTierStateDao

    abstract fun taskTimingDao(): TaskTimingDao

    abstract fun automationRuleDao(): AutomationRuleDao

    abstract fun automationLogDao(): AutomationLogDao

    abstract fun projectPhaseDao(): ProjectPhaseDao

    abstract fun projectRiskDao(): ProjectRiskDao

    abstract fun taskDependencyDao(): TaskDependencyDao

    abstract fun externalAnchorDao(): ExternalAnchorDao

    abstract fun chatMessageDao(): ChatMessageDao
}
