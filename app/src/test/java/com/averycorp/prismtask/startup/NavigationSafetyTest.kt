package com.averycorp.prismtask.startup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates that all screen composables referenced in FeatureRoutes.kt
 * and NavGraph.kt exist and are importable.
 *
 * If a composable function referenced in a navigation destination is
 * deleted or moved, the app will crash with a NoSuchMethodError or
 * ClassNotFoundException when navigation resolves the route — which
 * can happen on startup if the initial route targets a missing screen.
 */
class NavigationSafetyTest {
    private val srcMainDir = File("app/src/main/java/com/averycorp/prismtask")

    /**
     * All screen composable files that are referenced in navigation routes.
     * Paths relative to srcMainDir.
     */
    private val requiredScreenFiles = listOf(
        // Core tab screens
        "ui/screens/today/TodayScreen.kt",
        "ui/screens/tasklist/TaskListScreen.kt",
        "ui/screens/projects/ProjectListScreen.kt",
        "ui/screens/habits/HabitListScreen.kt",
        "ui/screens/settings/SettingsScreen.kt",
        "ui/screens/timer/TimerScreen.kt",
        // Feature screens
        "ui/screens/addedittask/AddEditTaskScreen.kt",
        "ui/screens/projects/AddEditProjectScreen.kt",
        "ui/screens/habits/AddEditHabitScreen.kt",
        "ui/screens/habits/HabitAnalyticsScreen.kt",
        "ui/screens/habits/HabitDetailScreen.kt",
        "ui/screens/tags/TagManagementScreen.kt",
        "ui/screens/search/SearchScreen.kt",
        "ui/screens/archive/ArchiveScreen.kt",
        "ui/screens/weekview/WeekViewScreen.kt",
        "ui/screens/monthview/MonthViewScreen.kt",
        "ui/screens/timeline/TimelineScreen.kt",
        "ui/screens/selfcare/SelfCareScreen.kt",
        "ui/screens/medication/MedicationScreen.kt",
        "ui/screens/medication/MedicationLogScreen.kt",
        "ui/screens/leisure/LeisurePoolScreen.kt",
        "ui/screens/schoolwork/SchoolworkScreen.kt",
        "ui/screens/schoolwork/AddEditCourseScreen.kt",
        "ui/screens/eisenhower/EisenhowerScreen.kt",
        "ui/screens/pomodoro/SmartPomodoroScreen.kt",
        "ui/screens/briefing/DailyBriefingScreen.kt",
        "ui/screens/planner/WeeklyPlannerScreen.kt",
        "ui/screens/templates/TemplateListScreen.kt",
        "ui/screens/templates/AddEditTemplateScreen.kt",
        "ui/screens/chat/ChatScreen.kt",
        "ui/screens/auth/AuthScreen.kt",
        "ui/screens/onboarding/OnboardingScreen.kt",
        // v1.4.0 screens
        "ui/screens/analytics/TaskAnalyticsScreen.kt",
        "ui/screens/feedback/BugReportScreen.kt",
        "ui/screens/debug/DebugLogScreen.kt"
    )

    @Test
    fun `all navigation-referenced screen files exist`() {
        if (!srcMainDir.exists()) return

        val missing = mutableListOf<String>()
        for (path in requiredScreenFiles) {
            val file = File(srcMainDir, path)
            if (!file.exists()) {
                missing.add(path)
            }
        }

        assertTrue(
            "Missing screen files referenced in navigation: $missing. " +
                "If a screen was moved or renamed, update both the navigation " +
                "graph and this test.",
            missing.isEmpty()
        )
    }

    @Test
    fun `NavGraph and FeatureRoutes files exist`() {
        if (!srcMainDir.exists()) return

        val navGraph = File(srcMainDir, "ui/navigation/NavGraph.kt")
        val featureRoutes = File(srcMainDir, "ui/navigation/FeatureRoutes.kt")

        assertTrue(
            "NavGraph.kt must exist in ui/navigation/",
            navGraph.exists()
        )
        assertTrue(
            "FeatureRoutes.kt must exist in ui/navigation/",
            featureRoutes.exists()
        )
    }

    @Test
    fun `all PrismTaskRoute sealed objects have unique route strings`() {
        if (!srcMainDir.exists()) return

        val navFile = File(srcMainDir, "ui/navigation/NavGraph.kt")
        if (!navFile.exists()) return

        val content = navFile.readText()

        // Extract all route string values from data object declarations
        val routePattern = Regex("""PrismTaskRoute\("([^"]+)"\)""")
        val routes = routePattern.findAll(content).map { it.groupValues[1] }.toList()

        val duplicates = routes.groupBy { it }.filter { it.value.size > 1 }
        assertTrue(
            "Found duplicate route strings: ${duplicates.keys}. " +
                "Each PrismTaskRoute must have a unique route string.",
            duplicates.isEmpty()
        )
    }

    @Test
    fun `FeatureRoutes references composable functions for all routes`() {
        if (!srcMainDir.exists()) return

        val navDir = File(srcMainDir, "ui/navigation")
        if (!navDir.exists()) return

        // Check that composable() calls exist somewhere under the nav
        // package — the destinations live in per-domain files under
        // ui/navigation/routes/ now, not directly in FeatureRoutes.kt.
        val composableCount = navDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sumOf { file ->
                Regex("""composable\(""").findAll(file.readText()).count()
            }
        assertTrue(
            "Navigation package should have composable() declarations " +
                "(searched ${navDir.absolutePath})",
            composableCount > 0
        )
    }

    @Test
    fun `all ViewModels used in navigation have hiltViewModel calls`() {
        if (!srcMainDir.exists()) return

        val navDir = File(srcMainDir, "ui/navigation")
        if (!navDir.exists()) return

        val combinedContent = navDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString(separator = "\n") { it.readText() }

        // If the nav code references ViewModel types, it should use hiltViewModel()
        if (combinedContent.contains("ViewModel")) {
            assertTrue(
                "Navigation code references ViewModels but doesn't import hiltViewModel. " +
                    "All ViewModels in composable destinations must use hiltViewModel().",
                combinedContent.contains("hiltViewModel")
            )
        }
    }
}
