package com.averycorp.prismtask.smoke

import android.content.Context
import androidx.room.Room
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.di.DatabaseModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DatabaseModule::class]
)
object TestDatabaseModule {
    @Provides
    @Singleton
    fun provideTestDatabase(
        @ApplicationContext context: Context
    ): PrismTaskDatabase =
        Room
            .inMemoryDatabaseBuilder(context, PrismTaskDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @Provides
    fun provideTaskDao(database: PrismTaskDatabase) = database.taskDao()

    @Provides
    fun provideProjectDao(database: PrismTaskDatabase) = database.projectDao()

    @Provides
    fun provideTagDao(database: PrismTaskDatabase) = database.tagDao()

    @Provides
    fun provideAttachmentDao(database: PrismTaskDatabase) = database.attachmentDao()

    @Provides
    fun provideUsageLogDao(database: PrismTaskDatabase) = database.usageLogDao()

    @Provides
    fun provideSyncMetadataDao(database: PrismTaskDatabase) = database.syncMetadataDao()

    @Provides
    fun provideCalendarSyncDao(database: PrismTaskDatabase) = database.calendarSyncDao()

    @Provides
    fun provideHabitDao(database: PrismTaskDatabase) = database.habitDao()

    @Provides
    fun provideHabitCompletionDao(database: PrismTaskDatabase) = database.habitCompletionDao()

    @Provides
    fun provideLeisureDao(database: PrismTaskDatabase) = database.leisureDao()

    @Provides
    fun provideSchoolworkDao(database: PrismTaskDatabase) = database.schoolworkDao()

    @Provides
    fun provideSelfCareDao(database: PrismTaskDatabase) = database.selfCareDao()

    @Provides
    fun provideTaskTemplateDao(database: PrismTaskDatabase) = database.taskTemplateDao()

    // The DAOs below were missing from this test module when the
    // corresponding production entities shipped (tier-2 WLB, milestones,
    // notifications, daily-essentials). Without them, Hilt fails to build
    // the androidTest component graph because DatabaseModule is replaced
    // by this module and any @Inject on one of these DAOs from production
    // code becomes unresolvable.
    @Provides
    fun provideHabitLogDao(database: PrismTaskDatabase) = database.habitLogDao()

    @Provides
    fun provideTaskCompletionDao(database: PrismTaskDatabase) = database.taskCompletionDao()

    @Provides
    fun provideTaskTimingDao(database: PrismTaskDatabase) = database.taskTimingDao()

    @Provides
    fun provideMilestoneDao(database: PrismTaskDatabase) = database.milestoneDao()

    @Provides
    fun provideDailyEssentialSlotCompletionDao(database: PrismTaskDatabase) =
        database.dailyEssentialSlotCompletionDao()

    @Provides
    fun provideNotificationProfileDao(database: PrismTaskDatabase) =
        database.notificationProfileDao()

    @Provides
    fun provideCustomSoundDao(database: PrismTaskDatabase) = database.customSoundDao()

    @Provides
    fun provideMoodEnergyLogDao(database: PrismTaskDatabase) = database.moodEnergyLogDao()

    @Provides
    fun provideMedicationRefillDao(database: PrismTaskDatabase) =
        database.medicationRefillDao()

    @Provides
    fun provideBoundaryRuleDao(database: PrismTaskDatabase) = database.boundaryRuleDao()

    @Provides
    fun provideCheckInLogDao(database: PrismTaskDatabase) = database.checkInLogDao()

    @Provides
    fun provideWeeklyReviewDao(database: PrismTaskDatabase) = database.weeklyReviewDao()

    @Provides
    fun provideFocusReleaseLogDao(database: PrismTaskDatabase) =
        database.focusReleaseLogDao()

    @Provides
    fun provideMedicationDao(database: PrismTaskDatabase) = database.medicationDao()

    @Provides
    fun provideMedicationDoseDao(database: PrismTaskDatabase) = database.medicationDoseDao()

    @Provides
    fun provideSavedFilterDao(database: PrismTaskDatabase) = database.savedFilterDao()

    @Provides
    fun provideNlpShortcutDao(database: PrismTaskDatabase) = database.nlpShortcutDao()

    @Provides
    fun provideHabitTemplateDao(database: PrismTaskDatabase) = database.habitTemplateDao()

    @Provides
    fun provideProjectTemplateDao(database: PrismTaskDatabase) = database.projectTemplateDao()

    @Provides
    fun provideBatchUndoLogDao(database: PrismTaskDatabase) = database.batchUndoLogDao()

    @Provides
    fun provideMedicationSlotDao(database: PrismTaskDatabase) = database.medicationSlotDao()

    @Provides
    fun provideMedicationSlotOverrideDao(database: PrismTaskDatabase) =
        database.medicationSlotOverrideDao()

    @Provides
    fun provideMedicationTierStateDao(database: PrismTaskDatabase) =
        database.medicationTierStateDao()

    @Provides
    fun provideAutomationRuleDao(database: PrismTaskDatabase) = database.automationRuleDao()

    @Provides
    fun provideAutomationLogDao(database: PrismTaskDatabase) = database.automationLogDao()

    // PrismTask-timeline-class scope (PR-1/2/3): production DatabaseModule
    // gained the four new DAOs below; mirror them here so the
    // @TestInstallIn replacement of DatabaseModule keeps the androidTest
    // Hilt graph buildable. Without these, every instrumentation test
    // that injects ProjectRepository / TaskDependencyRepository /
    // ExternalAnchorRepository fails at hiltJavaCompileDebugAndroidTest
    // with "Dagger/MissingBinding".
    @Provides
    fun provideProjectPhaseDao(database: PrismTaskDatabase) = database.projectPhaseDao()

    @Provides
    fun provideProjectRiskDao(database: PrismTaskDatabase) = database.projectRiskDao()

    @Provides
    fun provideTaskDependencyDao(database: PrismTaskDatabase) = database.taskDependencyDao()

    @Provides
    fun provideExternalAnchorDao(database: PrismTaskDatabase) = database.externalAnchorDao()

    // D11 E.3 — production DatabaseModule gained `chatMessageDao()`
    // when chat conversation persistence shipped. Mirror it here per
    // memory #17/#21 so compileDebugAndroidTestKotlin keeps the
    // androidTest Hilt graph buildable.
    @Provides
    fun provideChatMessageDao(database: PrismTaskDatabase) = database.chatMessageDao()

    @Provides
    @Singleton
    fun provideGson(): com.google.gson.Gson = com.google.gson.Gson()
}
