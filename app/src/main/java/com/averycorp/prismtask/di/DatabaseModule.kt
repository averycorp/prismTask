package com.averycorp.prismtask.di

import android.content.Context
import androidx.room.Room
import com.averycorp.prismtask.data.diagnostics.MigrationInstrumentor
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
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.local.database.instrumentedMigrations
import com.averycorp.prismtask.data.seed.TemplatePreferencesSeededFlagStore
import com.averycorp.prismtask.data.seed.TemplateSeeder
import com.google.gson.Gson
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // DI module: provides functions grow linearly with DAO count.
object DatabaseModule {
    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): PrismTaskDatabase =
        Room
            .databaseBuilder(
                context,
                PrismTaskDatabase::class.java,
                "averytask.db"
            ).addMigrations(*instrumentedMigrations(context))
            .addCallback(MigrationInstrumentor.flushCallback(context))
            .build()

    @Provides
    fun provideTaskDao(database: PrismTaskDatabase): TaskDao = database.taskDao()

    @Provides
    fun provideProjectDao(database: PrismTaskDatabase): ProjectDao = database.projectDao()

    @Provides
    fun provideTagDao(database: PrismTaskDatabase): TagDao = database.tagDao()

    @Provides
    fun provideAttachmentDao(database: PrismTaskDatabase): AttachmentDao = database.attachmentDao()

    @Provides
    fun provideUsageLogDao(database: PrismTaskDatabase): UsageLogDao = database.usageLogDao()

    @Provides
    fun provideSyncMetadataDao(database: PrismTaskDatabase): SyncMetadataDao = database.syncMetadataDao()

    @Provides
    fun provideCalendarSyncDao(database: PrismTaskDatabase): CalendarSyncDao = database.calendarSyncDao()

    @Provides
    fun provideHabitDao(database: PrismTaskDatabase): HabitDao = database.habitDao()

    @Provides
    fun provideHabitCompletionDao(database: PrismTaskDatabase): HabitCompletionDao = database.habitCompletionDao()

    @Provides
    fun provideHabitLogDao(database: PrismTaskDatabase): HabitLogDao = database.habitLogDao()

    @Provides
    fun provideLeisureDao(database: PrismTaskDatabase): LeisureDao = database.leisureDao()

    @Provides
    fun provideSchoolworkDao(database: PrismTaskDatabase): SchoolworkDao = database.schoolworkDao()

    @Provides
    fun provideSelfCareDao(database: PrismTaskDatabase): SelfCareDao = database.selfCareDao()

    @Provides
    fun provideTaskTemplateDao(database: PrismTaskDatabase): TaskTemplateDao = database.taskTemplateDao()

    @Provides
    fun provideNlpShortcutDao(database: PrismTaskDatabase): NlpShortcutDao = database.nlpShortcutDao()

    @Provides
    fun provideSavedFilterDao(database: PrismTaskDatabase): SavedFilterDao = database.savedFilterDao()

    @Provides
    fun provideNotificationProfileDao(database: PrismTaskDatabase): NotificationProfileDao =
        database.notificationProfileDao()

    @Provides
    fun provideCustomSoundDao(database: PrismTaskDatabase): CustomSoundDao = database.customSoundDao()

    @Provides
    fun provideProjectTemplateDao(database: PrismTaskDatabase): ProjectTemplateDao = database.projectTemplateDao()

    @Provides
    fun provideHabitTemplateDao(database: PrismTaskDatabase): HabitTemplateDao = database.habitTemplateDao()

    @Provides
    fun provideMoodEnergyLogDao(database: PrismTaskDatabase): MoodEnergyLogDao = database.moodEnergyLogDao()

    @Provides
    fun provideMedicationRefillDao(database: PrismTaskDatabase): MedicationRefillDao = database.medicationRefillDao()

    @Provides
    fun provideBoundaryRuleDao(database: PrismTaskDatabase): BoundaryRuleDao = database.boundaryRuleDao()

    @Provides
    fun provideCheckInLogDao(database: PrismTaskDatabase): CheckInLogDao = database.checkInLogDao()

    @Provides
    fun provideWeeklyReviewDao(database: PrismTaskDatabase): WeeklyReviewDao = database.weeklyReviewDao()

    @Provides
    fun provideTaskCompletionDao(database: PrismTaskDatabase): TaskCompletionDao = database.taskCompletionDao()

    @Provides
    fun provideTaskTimingDao(database: PrismTaskDatabase): TaskTimingDao = database.taskTimingDao()

    @Provides
    fun provideFocusReleaseLogDao(database: PrismTaskDatabase): FocusReleaseLogDao = database.focusReleaseLogDao()

    @Provides
    fun provideDailyEssentialSlotCompletionDao(
        database: PrismTaskDatabase
    ): DailyEssentialSlotCompletionDao = database.dailyEssentialSlotCompletionDao()

    @Provides
    fun provideMilestoneDao(database: PrismTaskDatabase): MilestoneDao = database.milestoneDao()

    @Provides
    fun provideMedicationDao(database: PrismTaskDatabase): MedicationDao = database.medicationDao()

    @Provides
    fun provideMedicationDoseDao(
        database: PrismTaskDatabase
    ): MedicationDoseDao = database.medicationDoseDao()

    @Provides
    fun provideMedicationSlotDao(database: PrismTaskDatabase): MedicationSlotDao =
        database.medicationSlotDao()

    @Provides
    fun provideMedicationSlotOverrideDao(database: PrismTaskDatabase): MedicationSlotOverrideDao =
        database.medicationSlotOverrideDao()

    @Provides
    fun provideMedicationTierStateDao(database: PrismTaskDatabase): MedicationTierStateDao =
        database.medicationTierStateDao()

    @Provides
    fun provideBatchUndoLogDao(database: PrismTaskDatabase): BatchUndoLogDao = database.batchUndoLogDao()

    @Provides
    fun provideAutomationRuleDao(database: PrismTaskDatabase): AutomationRuleDao = database.automationRuleDao()

    @Provides
    fun provideAutomationLogDao(database: PrismTaskDatabase): AutomationLogDao = database.automationLogDao()

    @Provides
    fun provideProjectPhaseDao(database: PrismTaskDatabase): ProjectPhaseDao = database.projectPhaseDao()

    @Provides
    fun provideProjectRiskDao(database: PrismTaskDatabase): ProjectRiskDao = database.projectRiskDao()

    @Provides
    fun provideTaskDependencyDao(database: PrismTaskDatabase): TaskDependencyDao = database.taskDependencyDao()

    @Provides
    fun provideExternalAnchorDao(database: PrismTaskDatabase): ExternalAnchorDao = database.externalAnchorDao()

    @Provides
    fun provideChatMessageDao(database: PrismTaskDatabase): ChatMessageDao = database.chatMessageDao()
}

/**
 * Binds the production [TemplateSeeder.SeededFlagStore] implementation
 * ([TemplatePreferencesSeededFlagStore]) into the Hilt graph. Split into a
 * separate abstract module because `@Binds` can't live alongside `@Provides`
 * in an `object` module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TemplateSeederModule {
    @Binds
    @Singleton
    abstract fun bindSeededFlagStore(
        impl: TemplatePreferencesSeededFlagStore
    ): TemplateSeeder.SeededFlagStore
}
