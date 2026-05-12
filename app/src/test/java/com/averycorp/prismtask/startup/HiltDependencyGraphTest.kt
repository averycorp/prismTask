package com.averycorp.prismtask.startup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates Hilt dependency graph consistency for startup safety.
 *
 * When a DAO is declared in PrismTaskDatabase but not provided in
 * DatabaseModule (or vice versa), Hilt fails at compile time. However,
 * if a @Provides method references a DAO method that doesn't exist on
 * the database class, the error only shows up at KSP processing time
 * which can be obscure in CI logs.
 *
 * These tests scan the source files and verify structural consistency
 * between DatabaseModule and PrismTaskDatabase, catching mismatches
 * before they cause cryptic build failures.
 */
class HiltDependencyGraphTest {
    private val srcMainDir = File("app/src/main/java/com/averycorp/prismtask")

    /**
     * All DAO accessor method names declared in PrismTaskDatabase.
     * Must match the abstract fun declarations exactly.
     */
    private val expectedDaoAccessors = listOf(
        "taskDao",
        "projectDao",
        "tagDao",
        "attachmentDao",
        "usageLogDao",
        "syncMetadataDao",
        "calendarSyncDao",
        "habitDao",
        "habitCompletionDao",
        "habitLogDao",
        "leisureActivityDao",
        "leisureSessionDao",
        "schoolworkDao",
        "selfCareDao",
        "taskTemplateDao",
        "nlpShortcutDao",
        "savedFilterDao",
        "notificationProfileDao",
        "customSoundDao",
        "projectTemplateDao",
        "habitTemplateDao",
        "moodEnergyLogDao",
        "medicationRefillDao",
        "boundaryRuleDao",
        "checkInLogDao",
        "weeklyReviewDao",
        "taskCompletionDao",
        "focusReleaseLogDao"
    )

    @Test
    fun `DatabaseModule provides all DAOs declared in PrismTaskDatabase`() {
        if (!srcMainDir.exists()) return

        val moduleFile = File(srcMainDir, "di/DatabaseModule.kt")
        val dbFile = File(srcMainDir, "data/local/database/PrismTaskDatabase.kt")
        if (!moduleFile.exists() || !dbFile.exists()) return

        val moduleContent = moduleFile.readText()
        val dbContent = dbFile.readText()

        for (accessor in expectedDaoAccessors) {
            // Check that the accessor exists in PrismTaskDatabase
            assertTrue(
                "PrismTaskDatabase should declare abstract fun $accessor()",
                dbContent.contains("fun $accessor()")
            )

            // Check that DatabaseModule has a @Provides method calling it
            assertTrue(
                "DatabaseModule should have a @Provides method calling database.$accessor()",
                moduleContent.contains("database.$accessor()")
            )
        }
    }

    @Test
    fun `DatabaseModule does not provide DAOs absent from PrismTaskDatabase`() {
        if (!srcMainDir.exists()) return

        val moduleFile = File(srcMainDir, "di/DatabaseModule.kt")
        if (!moduleFile.exists()) return

        val moduleContent = moduleFile.readText()

        // Extract all "database.xxxDao()" calls from DatabaseModule
        val daoCallPattern = Regex("""database\.(\w+Dao)\(\)""")
        val moduleDaoCalls = daoCallPattern
            .findAll(moduleContent)
            .map { it.groupValues[1] }
            .toSet()

        // Every DAO call in the module should be in our expected list
        for (call in moduleDaoCalls) {
            assertTrue(
                "DatabaseModule calls database.$call() but it's not in expectedDaoAccessors. " +
                    "Either add it to the test or remove the provider.",
                expectedDaoAccessors.contains(call)
            )
        }
    }

    @Test
    fun `all preference classes referenced in Application are injectable`() {
        // PrismTaskApplication @Inject fields must correspond to classes with
        // either @Inject constructor or a @Provides method in a Hilt module.
        // This test checks that the injected types exist.
        if (!srcMainDir.exists()) return

        val appFile = File(srcMainDir, "PrismTaskApplication.kt")
        if (!appFile.exists()) return

        val content = appFile.readText()

        // These are the types injected in PrismTaskApplication
        val injectedTypes = listOf(
            "HiltWorkerFactory",
            "SchoolworkRepository",
            "LeisureBudgetRepository",
            "SelfCareRepository",
            "TaskBehaviorPreferences",
            "TemplateSeeder"
        )

        for (type in injectedTypes) {
            assertTrue(
                "PrismTaskApplication should have @Inject lateinit var for $type",
                content.contains(type)
            )
        }
    }

    @Test
    fun `all injected fields in MainActivity exist`() {
        if (!srcMainDir.exists()) return

        val file = File(srcMainDir, "MainActivity.kt")
        if (!file.exists()) return

        val content = file.readText()

        // These are the types injected in MainActivity
        val injectedTypes = listOf(
            "ThemePreferences",
            "TabPreferences",
            "SyncService",
            "OnboardingPreferences",
            "BillingManager",
            "A11yPreferences",
            "UserPreferencesDataStore",
            "DiagnosticLogger"
        )

        for (type in injectedTypes) {
            assertTrue(
                "MainActivity should reference $type as an injected field",
                content.contains(type)
            )
        }
    }

    @Test
    fun `WorkManager initializer is disabled in manifest`() {
        // Without this removal, the default WorkManagerInitializer races
        // with Hilt's HiltWorkerFactory, causing startup crashes.
        val manifest = File("app/src/main/AndroidManifest.xml")
        if (!manifest.exists()) return

        val content = manifest.readText()

        assertTrue(
            "AndroidManifest must disable WorkManagerInitializer via " +
                "tools:node=\"remove\" to prevent startup race condition",
            content.contains("WorkManagerInitializer") &&
                content.contains("tools:node=\"remove\"")
        )
    }

    @Test
    fun `Application implements Configuration Provider for WorkManager`() {
        if (!srcMainDir.exists()) return

        val appFile = File(srcMainDir, "PrismTaskApplication.kt")
        if (!appFile.exists()) return

        val content = appFile.readText()

        assertTrue(
            "PrismTaskApplication must implement Configuration.Provider " +
                "for WorkManager on-demand initialization",
            content.contains("Configuration.Provider")
        )

        assertTrue(
            "PrismTaskApplication must override workManagerConfiguration",
            content.contains("workManagerConfiguration")
        )

        assertTrue(
            "workManagerConfiguration must use HiltWorkerFactory",
            content.contains("workerFactory") || content.contains("HiltWorkerFactory")
        )
    }
}
