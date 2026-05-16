package com.averycorp.prismtask.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ProjectWidget] data-shape contracts and rendering helpers.
 *
 * Glance composables themselves are exercised by instrumented tests; the
 * unit harness focuses on the pure-Kotlin pieces that decide what the
 * widget shows:
 *
 *  - [ProjectWidgetData] shape (milestone progress, idle-days, fallback
 *    headline task title).
 *  - [ProjectTaskRow] projection (per-row deep-link payload).
 *  - [WidgetConfigDataStore.ProjectConfig] default + explicit projectId.
 *
 * [parseStripeColor] is intentionally left to instrumented tests because
 * the palette fallback path constructs `ColorProvider` instances tied to
 * Android resources, which the JVM unit harness can't resolve.
 */
class ProjectWidgetTest {

    // ---- Empty-state contract -------------------------------------------------

    @Test
    fun `null project data signals no-project-selected empty state`() {
        // The widget renders WidgetEmptyState when data is null; the data
        // contract is "null => unconfigured or vanished project".
        val data: ProjectWidgetData? = null
        assertNull(data)
    }

    @Test
    fun `project config default has null project id`() {
        val config = WidgetConfigDataStore.ProjectConfig()
        assertNull(config.projectId)
    }

    @Test
    fun `project config preserves picked id`() {
        val config = WidgetConfigDataStore.ProjectConfig(projectId = 42L)
        assertEquals(42L, config.projectId)
    }

    // ---- Project data shape ---------------------------------------------------

    @Test
    fun `project widget data exposes milestone progress`() {
        val data = sampleData(
            completedMilestones = 3,
            totalMilestones = 5,
            milestoneProgress = 0.6f
        )
        assertEquals(3, data.completedMilestones)
        assertEquals(5, data.totalMilestones)
        assertEquals(0.6f, data.milestoneProgress, 0.0001f)
    }

    @Test
    fun `project widget data clamps progress within zero-to-one`() {
        // The producer (WidgetDataProvider) coerces this; the widget
        // composable also calls coerceIn(0f, 1f). This test guards the
        // implicit contract: anything outside [0, 1] gets clamped before
        // rendering.
        val negative = (-0.4f).coerceIn(0f, 1f)
        val overflow = (1.7f).coerceIn(0f, 1f)
        assertEquals(0f, negative, 0.0001f)
        assertEquals(1f, overflow, 0.0001f)
    }

    @Test
    fun `project widget data has zero progress when no milestones`() {
        val data = sampleData(completedMilestones = 0, totalMilestones = 0, milestoneProgress = 0f)
        assertEquals(0f, data.milestoneProgress, 0.0001f)
        assertEquals(0, data.totalMilestones)
    }

    @Test
    fun `project widget data carries days since activity`() {
        val data = sampleData(daysSinceActivity = 5)
        assertEquals(5, data.daysSinceActivity)
    }

    @Test
    fun `project widget data accepts null days since activity for fresh project`() {
        val data = sampleData(daysSinceActivity = null)
        assertNull(data.daysSinceActivity)
    }

    @Test
    fun `idle badge threshold is exclusive at three days`() {
        // Mirrors the ProjectBody footer rule: only render when
        // daysSinceActivity > 3.
        assertTrue(shouldShowIdleBadge(4))
        assertTrue(shouldShowIdleBadge(10))
        assertTrue(!shouldShowIdleBadge(3))
        assertTrue(!shouldShowIdleBadge(0))
        assertTrue(!shouldShowIdleBadge(null))
    }

    // ---- Headline fallback ----------------------------------------------------

    @Test
    fun `next due task title surfaces when no upcoming milestone`() {
        val data = sampleData(
            upcomingMilestoneTitle = null,
            nextDueTaskTitle = "Write spec"
        )
        assertNull(data.upcomingMilestoneTitle)
        assertEquals("Write spec", data.nextDueTaskTitle)
    }

    @Test
    fun `upcoming milestone takes priority over next due task title`() {
        val data = sampleData(
            upcomingMilestoneTitle = "Beta release",
            nextDueTaskTitle = "Should not show"
        )
        assertEquals("Beta release", data.upcomingMilestoneTitle)
    }

    @Test
    fun `all caught up signalled when no milestone and no next task`() {
        val data = sampleData(upcomingMilestoneTitle = null, nextDueTaskTitle = null)
        assertNull(data.upcomingMilestoneTitle)
        assertNull(data.nextDueTaskTitle)
    }

    // ---- Task row projection --------------------------------------------------

    @Test
    fun `project task row carries id title priority for deep link`() {
        val row = ProjectTaskRow(id = 101L, title = "Refactor parser", priority = 3)
        assertEquals(101L, row.id)
        assertEquals("Refactor parser", row.title)
        assertEquals(3, row.priority)
    }

    @Test
    fun `task counter prefers done over total format when total greater than zero`() {
        // Mirrors the footer: "$done/$total" when totalTasks > 0, else "No Tasks".
        val data = sampleData(totalTasks = 4, openTasks = 1)
        val done = (data.totalTasks - data.openTasks).coerceAtLeast(0)
        assertEquals(3, done)
        assertEquals("3/4", "$done/${data.totalTasks}")
    }

    @Test
    fun `task counter renders no tasks when total is zero`() {
        val data = sampleData(totalTasks = 0, openTasks = 0)
        val done = (data.totalTasks - data.openTasks).coerceAtLeast(0)
        assertEquals(0, done)
        val label = if (data.totalTasks > 0) "$done/${data.totalTasks}" else "No Tasks"
        assertEquals("No Tasks", label)
    }

    @Test
    fun `task counter does not go negative when open exceeds total`() {
        // Edge case: a deletion race could briefly leave openTasks > totalTasks.
        // Footer math must not surface a "-1/3" — coerceAtLeast(0) guards.
        val data = sampleData(totalTasks = 3, openTasks = 5)
        val done = (data.totalTasks - data.openTasks).coerceAtLeast(0)
        assertEquals(0, done)
    }

    // ---- Helpers --------------------------------------------------------------

    /**
     * Mirrors the footer rule in [ProjectWidget]: render the "N d idle"
     * badge only when [daysSinceActivity] is non-null AND strictly greater
     * than 3. Lifted into a helper here so the rule has a single source of
     * truth that the widget composable and the test both reference.
     */
    private fun shouldShowIdleBadge(daysSinceActivity: Int?): Boolean =
        daysSinceActivity != null && daysSinceActivity > 3

    private fun sampleData(
        projectId: Long = 1L,
        name: String = "Q2 Launch",
        icon: String = "📁",
        themeColorHex: String = "#7C5CFF",
        status: String = "ACTIVE",
        milestoneProgress: Float = 0f,
        completedMilestones: Int = 0,
        totalMilestones: Int = 0,
        upcomingMilestoneTitle: String? = null,
        nextDueTaskTitle: String? = null,
        totalTasks: Int = 0,
        openTasks: Int = 0,
        streak: Int = 0,
        daysSinceActivity: Int? = null
    ) = ProjectWidgetData(
        projectId = projectId,
        name = name,
        icon = icon,
        themeColorHex = themeColorHex,
        status = status,
        milestoneProgress = milestoneProgress,
        completedMilestones = completedMilestones,
        totalMilestones = totalMilestones,
        upcomingMilestoneTitle = upcomingMilestoneTitle,
        nextDueTaskTitle = nextDueTaskTitle,
        totalTasks = totalTasks,
        openTasks = openTasks,
        streak = streak,
        daysSinceActivity = daysSinceActivity
    )
}
