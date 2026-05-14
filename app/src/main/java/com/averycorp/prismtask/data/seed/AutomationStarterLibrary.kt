package com.averycorp.prismtask.data.seed

import com.averycorp.prismtask.domain.automation.AutomationAction
import com.averycorp.prismtask.domain.automation.AutomationCondition
import com.averycorp.prismtask.domain.automation.AutomationCondition.Op
import com.averycorp.prismtask.domain.automation.AutomationTrigger

/**
 * Browseable library of starter automation rules. The full inventory and
 * design rationale lives in `docs/audits/AUTOMATION_STARTER_LIBRARY_ARCHITECTURE.md`
 * — 27 rules across 7 goal-first categories. Five carry the original
 * `builtin.*` template keys from PR #1056 so the seeder stays
 * back-compatible; the other 22 carry `starter.<category>.<slug>` keys.
 *
 * Source-of-truth lives here in Kotlin per A7 of the audit (sample_rules.json
 * was a documentation artifact — never parsed). The browse UI reads from
 * [ALL_TEMPLATES]; the seeder reads from [FIRST_INSTALL_SEED_IDS].
 */
enum class AutomationTemplateCategory(val displayName: String) {
    STAY_ON_TOP("Stay On Top Of Work"),
    HABITS("Build Healthy Habits"),
    MEDICATION("Medication Adherence"),
    FOCUS("Focus + Deep Work"),
    FRICTION("Reduce Friction"),
    WELLNESS("Wellness Check-Ins"),
    POWER_USER("Power User")
}

/**
 * One template = one importable rule. [id] doubles as the
 * `automation_rules.template_key` after import so a future "show
 * imported templates" filter can grep on prefix.
 */
data class AutomationTemplate(
    val id: String,
    val category: AutomationTemplateCategory,
    val name: String,
    val description: String,
    val trigger: AutomationTrigger,
    val condition: AutomationCondition?,
    val actions: List<AutomationAction>
) {
    /** True if any action in [actions] egresses to Anthropic. */
    val requiresAi: Boolean
        get() = actions.any {
            it is AutomationAction.AiComplete || it is AutomationAction.AiSummarize
        }
}

object AutomationStarterLibrary {

    // --- Convenience builders to keep the inventory below readable. ----

    private fun notify(title: String, body: String) =
        AutomationAction.Notify(title = title, body = body)

    private fun log(message: String) =
        AutomationAction.LogMessage(message)

    private fun aiSummarize(scope: String, maxItems: Int = 50) =
        AutomationAction.AiSummarize(scope = scope, maxItems = maxItems)

    private fun mutateTask(updates: Map<String, Any?>) =
        AutomationAction.MutateTask(updates = updates)

    private val WEEKEND = setOf("SATURDAY", "SUNDAY")
    private val SUNDAY_ONLY = setOf("SUNDAY")

    private fun timeOfDay(hour: Int, minute: Int = 0) =
        AutomationTrigger.TimeOfDay(hour, minute)

    private fun dayOfWeekTime(days: Set<String>, hour: Int, minute: Int = 0) =
        AutomationTrigger.DayOfWeekTime(daysOfWeek = days, hour = hour, minute = minute)

    private fun entityEvent(kind: String) =
        AutomationTrigger.EntityEvent(eventKind = kind)

    private fun cmpGte(field: String, value: Any) =
        AutomationCondition.Compare(Op.GTE, field, value)

    private fun cmpEq(field: String, value: Any) =
        AutomationCondition.Compare(Op.EQ, field, value)

    private fun cmpContains(field: String, value: Any) =
        AutomationCondition.Compare(Op.CONTAINS, field, value)

    private fun cmpExists(field: String) =
        AutomationCondition.Compare(Op.EXISTS, field)

    private fun and(vararg children: AutomationCondition) =
        AutomationCondition.And(children.toList())

    private fun or(vararg children: AutomationCondition) =
        AutomationCondition.Or(children.toList())

    private fun not(child: AutomationCondition) =
        AutomationCondition.Not(child)

    private val NOW_TOKEN: Map<String, Any?> = mapOf("@now" to null)

    // ----- Stay on top of work (5) ---------------------------------------

    private val notifyOverdueUrgent = AutomationTemplate(
        id = "builtin.notify_overdue_urgent",
        category = AutomationTemplateCategory.STAY_ON_TOP,
        name = "Notify When Overdue Urgent Task",
        description = "Pings you when an urgent task crosses its due date.",
        trigger = entityEvent("TaskUpdated"),
        condition = and(
            cmpGte("task.priority", 3),
            AutomationCondition.Compare(Op.LT, "task.dueDate", NOW_TOKEN),
            not(cmpExists("task.completedAt"))
        ),
        actions = listOf(
            notify(
                title = "Urgent Task From Earlier",
                body = "An urgent task is past its due date — tap to review."
            )
        )
    )

    private val notifyHighPriorityCreated = AutomationTemplate(
        id = "starter.stay_on_top.notify_high_priority_created",
        category = AutomationTemplateCategory.STAY_ON_TOP,
        name = "Notify When High-Priority Task Added",
        description = "Lets you know the moment a task with priority High or Urgent lands in your list.",
        trigger = entityEvent("TaskCreated"),
        condition = cmpGte("task.priority", 3),
        actions = listOf(
            notify(
                title = "High-Priority Task Added",
                body = "A new high-priority task is on your list — block time for it."
            )
        )
    )

    private val morningKickoff = AutomationTemplate(
        id = "builtin.morning_routine",
        category = AutomationTemplateCategory.STAY_ON_TOP,
        name = "Daily Morning Kickoff",
        description = "Posts a morning kickoff notification at 7:00 to help you plan your day.",
        trigger = timeOfDay(7, 0),
        condition = null,
        actions = listOf(
            notify(
                title = "Good Morning, Plan Your Day",
                body = "Your day's plan is ready — tap to review your Today screen."
            ),
            log("Morning routine fired")
        )
    )

    private val eveningReview = AutomationTemplate(
        id = "starter.stay_on_top.evening_review",
        category = AutomationTemplateCategory.STAY_ON_TOP,
        name = "Evening Review Prompt",
        description = "Reminds you at 21:00 to glance at tomorrow's plan before winding down.",
        trigger = timeOfDay(21, 0),
        condition = null,
        actions = listOf(
            notify(
                title = "Review Tomorrow's Plan",
                body = "A 2-minute look at tomorrow's tasks now sets up a calmer morning."
            )
        )
    )

    private val weekendPlanning = AutomationTemplate(
        id = "starter.stay_on_top.weekend_planning",
        category = AutomationTemplateCategory.STAY_ON_TOP,
        name = "Sunday Weekly Planning",
        description = "Sunday at 17:00, prompts you to lay out the upcoming week.",
        trigger = dayOfWeekTime(SUNDAY_ONLY, 17, 0),
        condition = null,
        actions = listOf(
            notify(
                title = "Plan The Week Ahead",
                body = "Take 10 minutes to set priorities for the upcoming week."
            )
        )
    )

    // ----- Build healthy habits (4) --------------------------------------

    private val streak7 = AutomationTemplate(
        id = "builtin.streak_achievement",
        category = AutomationTemplateCategory.HABITS,
        name = "7-Day Streak Celebration",
        description = "Celebrates when a habit hits a 7-day streak.",
        trigger = entityEvent("HabitStreakHit"),
        condition = cmpGte("habit.streakCount", 7),
        actions = listOf(
            notify(
                title = "7-Day Streak Hit",
                body = "7 days in a row — milestone hit."
            ),
            log("Streak achievement fired")
        )
    )

    private val streak30 = AutomationTemplate(
        id = "starter.habits.streak_30",
        category = AutomationTemplateCategory.HABITS,
        name = "30-Day Streak Milestone",
        description = "Celebrates a month-long streak — a meaningful proof of consistency.",
        trigger = entityEvent("HabitStreakHit"),
        condition = cmpGte("habit.streakCount", 30),
        actions = listOf(
            notify(
                title = "30-Day Streak Milestone",
                body = "A full month of consistency — that's habit territory now."
            )
        )
    )

    private val streak100 = AutomationTemplate(
        id = "starter.habits.streak_100",
        category = AutomationTemplateCategory.HABITS,
        name = "100-Day Streak Legend",
        description = "Celebrates a 100-day streak — rare and worth marking.",
        trigger = entityEvent("HabitStreakHit"),
        condition = cmpGte("habit.streakCount", 100),
        actions = listOf(
            notify(
                title = "100-Day Streak Milestone",
                body = "100 days of consistency — that's habit territory."
            )
        )
    )

    private val weeklyHabitReview = AutomationTemplate(
        id = "starter.habits.weekly_habit_review",
        category = AutomationTemplateCategory.HABITS,
        name = "Weekly Habit Review",
        description = "Sunday at 18:00, prompts you to review which habits stuck this week.",
        trigger = dayOfWeekTime(SUNDAY_ONLY, 18, 0),
        condition = null,
        actions = listOf(
            notify(
                title = "Review Your Habits This Week",
                body = "Quick check-in: what's working, what needs a tweak?"
            )
        )
    )

    // ----- Medication adherence (3) --------------------------------------

    private val medMorningReminder = AutomationTemplate(
        id = "starter.med.morning_reminder",
        category = AutomationTemplateCategory.MEDICATION,
        name = "Morning Medication Reminder",
        description = "Daily 8:00 nudge to take your morning medications.",
        trigger = timeOfDay(8, 0),
        condition = null,
        actions = listOf(
            notify(
                title = "Morning Medications",
                body = "Time for your morning meds — open the medication tab to log."
            )
        )
    )

    private val medEveningCheck = AutomationTemplate(
        id = "starter.med.evening_check",
        category = AutomationTemplateCategory.MEDICATION,
        name = "Evening Medication Check",
        description = "Daily 20:00 nudge to take your evening medications.",
        trigger = timeOfDay(20, 0),
        condition = null,
        actions = listOf(
            notify(
                title = "Evening Medications",
                body = "Time for your evening meds — open the medication tab to log."
            )
        )
    )

    private val medWeeklyAiSummary = AutomationTemplate(
        id = "starter.med.weekly_ai_summary",
        category = AutomationTemplateCategory.MEDICATION,
        name = "Weekly Adherence AI Summary",
        description = "Sunday 19:00, asks AI to summarize the past week's medication adherence.",
        trigger = dayOfWeekTime(SUNDAY_ONLY, 19, 0),
        condition = null,
        actions = listOf(
            aiSummarize(scope = "recent_completions", maxItems = 14)
        )
    )

    // ----- Focus + deep work (3) -----------------------------------------

    private val focusAiSummarizeCompletions = AutomationTemplate(
        id = "builtin.ai_summary_completions",
        category = AutomationTemplateCategory.FOCUS,
        name = "AI Summarize Recent Completions",
        description = "Asks AI to summarize your last 50 completed tasks (requires AI features enabled).",
        trigger = entityEvent("TaskCompleted"),
        condition = null,
        actions = listOf(
            aiSummarize(scope = "recent_completions", maxItems = 50)
        )
    )

    private val focusManualKickoff = AutomationTemplate(
        id = "starter.focus.manual_kickoff",
        category = AutomationTemplateCategory.FOCUS,
        name = "Manual Focus-Session Kickoff",
        description = "Tap Run Now when you're starting a focus block — posts a 'phone away' nudge.",
        trigger = AutomationTrigger.Manual,
        condition = null,
        actions = listOf(
            notify(
                title = "Focus Session Started",
                body = "Phone away. Notifications muted in your head. Deep work for the next 25 minutes."
            )
        )
    )

    private val focusMiddayBlock = AutomationTemplate(
        id = "starter.focus.midday_focus_block",
        category = AutomationTemplateCategory.FOCUS,
        name = "Mid-Day Focus Block",
        description = "Daily 14:00 nudge to claim a 25-minute focus block before the afternoon scatters.",
        trigger = timeOfDay(14, 0),
        condition = null,
        actions = listOf(
            notify(
                title = "Time For A 25-Min Focus Block",
                body = "Pick one task and protect 25 minutes for it."
            )
        )
    )

    // ----- Reduce friction (5) -------------------------------------------

    private val frictionAutotagToday = AutomationTemplate(
        id = "builtin.autotag_today",
        category = AutomationTemplateCategory.FRICTION,
        name = "Auto-Tag New Tasks With #today",
        description = "Adds the #today tag to any task as soon as it's created.",
        trigger = entityEvent("TaskCreated"),
        condition = null,
        actions = listOf(
            mutateTask(mapOf("tagsAdd" to listOf("today"))),
            log("Tagged new task as #today")
        )
    )

    private val frictionAutoFlagUrgent = AutomationTemplate(
        id = "starter.friction.auto_flag_urgent",
        category = AutomationTemplateCategory.FRICTION,
        name = "Auto-Flag Urgent Tasks",
        description = "Any task created at priority Urgent gets a flag so it floats to the top.",
        trigger = entityEvent("TaskCreated"),
        condition = cmpGte("task.priority", 4),
        actions = listOf(
            mutateTask(mapOf("isFlagged" to true))
        )
    )

    private val frictionWeekendPersonal = AutomationTemplate(
        id = "starter.friction.weekend_personal_tag",
        category = AutomationTemplateCategory.FRICTION,
        name = "Tag Weekend Tasks As Personal",
        description = "Tasks created on Saturday or Sunday get the #personal tag and the Personal life category.",
        trigger = entityEvent("TaskCreated"),
        condition = or(
            cmpEq("event.dayOfWeek", "SATURDAY"),
            cmpEq("event.dayOfWeek", "SUNDAY")
        ),
        actions = listOf(
            mutateTask(
                mapOf(
                    "tagsAdd" to listOf("personal"),
                    "lifeCategory" to "PERSONAL"
                )
            )
        )
    )

    private val frictionCategorizeHealth = AutomationTemplate(
        id = "starter.friction.categorize_health_keyword",
        category = AutomationTemplateCategory.FRICTION,
        name = "Auto-Categorize Health Tasks",
        description = "Tasks whose title contains 'doctor', 'dentist', or 'appointment' move to the Health category.",
        trigger = entityEvent("TaskCreated"),
        condition = or(
            cmpContains("task.title", "doctor"),
            cmpContains("task.title", "dentist"),
            cmpContains("task.title", "appointment")
        ),
        actions = listOf(
            mutateTask(mapOf("lifeCategory" to "HEALTH"))
        )
    )

    private val frictionCategorizeWork = AutomationTemplate(
        id = "starter.friction.categorize_work_keyword",
        category = AutomationTemplateCategory.FRICTION,
        name = "Auto-Categorize Work Tasks",
        description = "Tasks whose title contains 'meeting', 'sync', or '1:1' move to the Work category.",
        trigger = entityEvent("TaskCreated"),
        condition = or(
            cmpContains("task.title", "meeting"),
            cmpContains("task.title", "sync"),
            cmpContains("task.title", "1:1")
        ),
        actions = listOf(
            mutateTask(mapOf("lifeCategory" to "WORK"))
        )
    )

    // ----- Wellness check-ins (4) ----------------------------------------

    private val wellnessMorningMood = AutomationTemplate(
        id = "starter.wellness.morning_mood_check",
        category = AutomationTemplateCategory.WELLNESS,
        name = "Morning Mood Check",
        description = "Daily 7:30 prompt to log how you're feeling — feeds the Mood Analytics screen.",
        trigger = timeOfDay(7, 30),
        condition = null,
        actions = listOf(
            notify(
                title = "How Are You Feeling This Morning?",
                body = "Take 30 seconds to log mood + energy — patterns emerge over weeks."
            )
        )
    )

    private val wellnessMiddayPause = AutomationTemplate(
        id = "starter.wellness.midday_pause",
        category = AutomationTemplateCategory.WELLNESS,
        name = "Midday Wellness Pause",
        description = "Daily 13:00 nudge to stand up, drink water, and reset.",
        trigger = timeOfDay(13, 0),
        condition = null,
        actions = listOf(
            notify(
                title = "Stand Up, Drink Water, Reset",
                body = "Two-minute body scan: shoulders down, breath slow, eyes off the screen."
            )
        )
    )

    private val wellnessEveningReflection = AutomationTemplate(
        id = "starter.wellness.evening_reflection",
        category = AutomationTemplateCategory.WELLNESS,
        name = "Evening Reflection Prompt",
        description = "Daily 21:30 prompt for a brief journaling moment.",
        trigger = timeOfDay(21, 30),
        condition = null,
        actions = listOf(
            notify(
                title = "Take A Moment To Reflect",
                body = "What went well today? What's worth noting for tomorrow?"
            )
        )
    )

    private val wellnessSundayReview = AutomationTemplate(
        id = "starter.wellness.sunday_review",
        category = AutomationTemplateCategory.WELLNESS,
        name = "Sunday Weekly Review",
        description = "Sunday 17:30, prompts a 5-minute weekly retrospective.",
        trigger = dayOfWeekTime(SUNDAY_ONLY, 17, 30),
        condition = null,
        actions = listOf(
            notify(
                title = "Sunday Weekly Review",
                body = "5 minutes: what worked, what didn't, what changes for next week?"
            )
        )
    )

    // ----- Power user (3) ------------------------------------------------

    private val powerManualAiBriefing = AutomationTemplate(
        id = "starter.power.manual_ai_briefing",
        category = AutomationTemplateCategory.POWER_USER,
        name = "Manual AI Briefing",
        description = "Tap Run Now to get an AI-generated briefing of today's tasks.",
        trigger = AutomationTrigger.Manual,
        condition = null,
        actions = listOf(
            aiSummarize(scope = "today_briefing", maxItems = 20)
        )
    )

    private val powerDailyEodAi = AutomationTemplate(
        id = "starter.power.daily_ai_eod_summary",
        category = AutomationTemplateCategory.POWER_USER,
        name = "Daily End-Of-Day AI Summary",
        description = "Daily 22:00, asks AI to summarize what you completed today.",
        trigger = timeOfDay(22, 0),
        condition = null,
        actions = listOf(
            aiSummarize(scope = "recent_completions", maxItems = 20)
        )
    )

    private val powerWeeklyAiReflection = AutomationTemplate(
        id = "starter.power.weekly_ai_reflection",
        category = AutomationTemplateCategory.POWER_USER,
        name = "Weekly AI Reflection",
        description = "Sunday 20:00, asks AI to reflect on the past week's completions and habits.",
        trigger = dayOfWeekTime(SUNDAY_ONLY, 20, 0),
        condition = null,
        actions = listOf(
            aiSummarize(scope = "weekly_reflection", maxItems = 50)
        )
    )

    // ----- Inventory ------------------------------------------------------

    val ALL_TEMPLATES: List<AutomationTemplate> = listOf(
        notifyOverdueUrgent,
        notifyHighPriorityCreated,
        morningKickoff,
        eveningReview,
        weekendPlanning,
        streak7,
        streak30,
        streak100,
        weeklyHabitReview,
        medMorningReminder,
        medEveningCheck,
        medWeeklyAiSummary,
        focusAiSummarizeCompletions,
        focusManualKickoff,
        focusMiddayBlock,
        frictionAutotagToday,
        frictionAutoFlagUrgent,
        frictionWeekendPersonal,
        frictionCategorizeHealth,
        frictionCategorizeWork,
        wellnessMorningMood,
        wellnessMiddayPause,
        wellnessEveningReflection,
        wellnessSundayReview,
        powerManualAiBriefing,
        powerDailyEodAi,
        powerWeeklyAiReflection
    )

    val TEMPLATES_BY_CATEGORY: Map<AutomationTemplateCategory, List<AutomationTemplate>>
        get() = ALL_TEMPLATES.groupBy { it.category }

    fun findById(id: String): AutomationTemplate? = ALL_TEMPLATES.firstOrNull { it.id == id }

    /**
     * Subset seeded on first install — preserves the original 5 PR #1056
     * sample rules (same template keys) so existing users see no change.
     * Imported via [AutomationSampleRulesSeeder] with `enabled = false`.
     */
    val FIRST_INSTALL_SEED_IDS: Set<String> = setOf(
        notifyOverdueUrgent.id,
        morningKickoff.id,
        streak7.id,
        frictionAutotagToday.id,
        focusAiSummarizeCompletions.id
    )
}
