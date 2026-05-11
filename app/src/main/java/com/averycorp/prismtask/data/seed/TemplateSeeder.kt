package com.averycorp.prismtask.data.seed

import com.averycorp.prismtask.data.local.dao.TaskTemplateDao
import com.averycorp.prismtask.data.local.entity.TaskTemplateEntity
import com.averycorp.prismtask.data.preferences.TemplatePreferences
import com.averycorp.prismtask.domain.model.RecurrenceRule
import com.averycorp.prismtask.domain.model.RecurrenceType
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [TemplateSeeder.SeededFlagStore] that reads and writes the
 * `templates_seeded` flag via [TemplatePreferences]. Kept as a separate
 * class (rather than an object) so Hilt can inject it as a Singleton.
 */
@Singleton
class TemplatePreferencesSeededFlagStore
@Inject
constructor(private val templatePreferences: TemplatePreferences) :
    TemplateSeeder.SeededFlagStore {
    override suspend fun isSeeded(): Boolean = templatePreferences.isSeeded()

    override suspend fun setSeeded(seeded: Boolean) =
        templatePreferences.setSeeded(seeded)
}

/**
 * Seeds the app's built-in starter templates on first launch.
 *
 * Seeding is gated by the `templates_seeded` flag in [TemplatePreferences] —
 * once the flag is set we never re-insert the built-ins, even if the user has
 * deleted some of them. This means users can confidently remove defaults they
 * don't care about without them reappearing on every app start.
 *
 * Built-in templates are inserted with `isBuiltIn = true`. The bit is flipped
 * back to `false` by [com.averycorp.prismtask.data.repository.TaskTemplateRepository]
 * the first time a user edits one, so "customized" defaults are visually no
 * different from user-created templates.
 */
@Singleton
class TemplateSeeder
@Inject
constructor(private val templateDao: TaskTemplateDao, private val seededFlag: SeededFlagStore) {
    /**
     * Minimal view of the "seeded" flag storage the seeder depends on.
     * Pulled into its own interface so unit tests can supply an in-memory
     * fake without needing a real DataStore + Context.
     *
     * The production binding in [com.averycorp.prismtask.di.DatabaseModule]
     * delegates to [TemplatePreferences.isSeeded] / [TemplatePreferences.setSeeded].
     */
    interface SeededFlagStore {
        suspend fun isSeeded(): Boolean

        suspend fun setSeeded(seeded: Boolean)
    }

    /**
     * Seeds all [BUILT_IN_TEMPLATES] if seeding hasn't run before. Safe to
     * call on every app start — the flag check short-circuits the insert path.
     */
    suspend fun seedIfNeeded() {
        if (seededFlag.isSeeded()) return
        // Also guard against the (rare) case where the flag was cleared but the
        // DB already has templates — we don't want to double up.
        if (templateDao.countTemplates() > 0) {
            seededFlag.setSeeded(true)
            return
        }
        val now = System.currentTimeMillis()
        BUILT_IN_TEMPLATES.forEach { spec ->
            templateDao.insertTemplate(spec.toEntity(now))
        }
        seededFlag.setSeeded(true)
    }

    /**
     * Debug-only escape hatch invoked by the Settings screen long-press on the
     * app version label. Deletes any templates still flagged `isBuiltIn` and
     * re-inserts the current [BUILT_IN_TEMPLATES] list. User-edited templates
     * (which have their `isBuiltIn` flipped to `false` by
     * [com.averycorp.prismtask.data.repository.TaskTemplateRepository.updateTemplate])
     * and purely user-created templates are left untouched.
     */
    suspend fun reseedBuiltIns() {
        templateDao.deleteAllBuiltIn()
        val now = System.currentTimeMillis()
        BUILT_IN_TEMPLATES.forEach { spec ->
            templateDao.insertTemplate(spec.toEntity(now))
        }
        seededFlag.setSeeded(true)
    }

    companion object {
        private val gson = Gson()

        /**
         * Data class capturing the shape of a single built-in template so the
         * list below reads declaratively. Kept internal to the seeder since
         * nothing else in the app consumes it — everywhere else uses the fully
         * hydrated [TaskTemplateEntity].
         */
        data class BuiltInTemplateSpec(
            val name: String,
            val templateKey: String,
            val icon: String,
            val category: String,
            val templateTitle: String,
            val templatePriority: Int,
            val templateSubtasks: List<String>,
            val templateRecurrence: RecurrenceRule? = null,
            val templateDuration: Int? = null
        ) {
            fun toEntity(now: Long): TaskTemplateEntity = TaskTemplateEntity(
                name = name,
                icon = icon,
                category = category,
                templateTitle = templateTitle,
                templatePriority = templatePriority,
                templateRecurrenceJson = templateRecurrence?.let { gson.toJson(it) },
                templateDuration = templateDuration,
                templateSubtasksJson = gson.toJson(templateSubtasks),
                isBuiltIn = true,
                templateKey = templateKey,
                createdAt = now,
                updatedAt = now
            )
        }

        /**
         * The canonical list of built-in templates. Ordered the way we want
         * them to appear in the list right after first launch (before the user
         * starts using them and the "most used" sort kicks in).
         *
         * v1.4.0 default-template expansion: "Assignment" (School), "Deep Clean"
         * (Housework), and "Morning Routine" (Self-Care) were removed from this
         * seed source. Housework and Self-Care are now seeded as flat
         * `SelfCareStepEntity` rows in [com.averycorp.prismtask.domain.model.SelfCareRoutines];
         * School and Leisure remain here as parent-with-subtasks templates.
         */
        val BUILT_IN_TEMPLATES: List<BuiltInTemplateSpec> = listOf(
            BuiltInTemplateSpec(
                name = "Weekly Review",
                templateKey = "builtin_weekly_review",
                // 📊
                icon = "\uD83D\uDCCA",
                category = "Routines",
                templateTitle = "Weekly Review",
                templatePriority = 2,
                templateSubtasks = listOf(
                    "Review Completed Tasks",
                    "Check Outstanding Items",
                    "Plan Next Week's Priorities",
                    "Clean Up Inbox",
                    "Update Project Statuses"
                ),
                // Weekly on Sunday — daysOfWeek uses 1=Mon..7=Sun
                templateRecurrence = RecurrenceRule(
                    type = RecurrenceType.WEEKLY,
                    daysOfWeek = listOf(7)
                ),
                templateDuration = 45
            ),
            BuiltInTemplateSpec(
                name = "Meeting Prep",
                templateKey = "builtin_meeting_prep",
                // 🤝
                icon = "\uD83E\uDD1D",
                category = "Work",
                templateTitle = "Prepare for Meeting",
                templatePriority = 3,
                templateSubtasks = listOf(
                    "Review Agenda",
                    "Prepare Talking Points",
                    "Gather Relevant Docs",
                    "Test Video/Audio Setup"
                ),
                templateDuration = 20
            ),
            BuiltInTemplateSpec(
                name = "Grocery Run",
                templateKey = "builtin_grocery_run",
                // 🛒
                icon = "\uD83D\uDED2",
                category = "Personal",
                templateTitle = "Grocery Shopping",
                templatePriority = 1,
                templateSubtasks = listOf(
                    "Check Pantry & Fridge",
                    "Write Shopping List",
                    "Check Coupons/Deals",
                    "Go to Store"
                ),
                templateDuration = 60
            ),
            BuiltInTemplateSpec(
                name = "School Daily",
                templateKey = "builtin_school_daily",
                // 📚
                icon = "\uD83D\uDCDA",
                category = "School",
                templateTitle = "School Daily",
                templatePriority = 3,
                templateSubtasks = listOf(
                    "Review Today's Lecture Notes",
                    "Check Course Portal For New Assignments",
                    "Read Assigned Chapter",
                    "Work On In-Progress Assignment",
                    "Study Session (Pick One Topic)",
                    "Submit Due Assignments",
                    "Office Hours Or Study Group",
                    "Plan Next Week's Schedule"
                ),
                templateRecurrence = RecurrenceRule(type = RecurrenceType.DAILY),
                templateDuration = 120
            ),
            BuiltInTemplateSpec(
                name = "Leisure Time",
                templateKey = "builtin_leisure_time",
                // 🎮
                icon = "\uD83C\uDFAE",
                category = "Personal",
                templateTitle = "Leisure Time",
                templatePriority = 1,
                templateSubtasks = listOf(
                    "Read 20 Pages",
                    "Watch An Episode",
                    "Play A Game (30 Minutes)",
                    "Listen To An Album Or Podcast",
                    "Practice Instrument",
                    "Journal Or Creative Writing",
                    "Call Or Message A Friend"
                ),
                templateDuration = 60
            )
        )
    }
}
