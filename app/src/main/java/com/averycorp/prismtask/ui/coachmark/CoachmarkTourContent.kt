package com.averycorp.prismtask.ui.coachmark

import androidx.compose.runtime.Immutable

/**
 * What action the controller should take when the user taps the primary
 * CTA on a step.
 *
 * - [Next]: advance to the next step (no navigation).
 * - [Navigate]: hand off to the host with a route key; host issues the
 *   navigation and the controller persists the step index so the overlay
 *   re-shows after the destination mounts.
 * - [Finish]: terminal step. Marks tour completed.
 */
@Immutable
sealed class CoachmarkAction {
    data object Next : CoachmarkAction()
    data class Navigate(val routeKey: String) : CoachmarkAction()
    data object Finish : CoachmarkAction()
}

/**
 * One step of the post-onboarding coachmark tour.
 *
 * @param anchorId must match an id registered via [Modifier.coachmarkAnchor].
 *                 If unresolvable at show-time, the controller advances to
 *                 the next step after a 250ms grace period.
 * @param title    short headline (≤4 words).
 * @param body     ≤2 sentences. ADHD-forgiving voice; no urgency / guilt.
 * @param action   primary CTA semantics.
 * @param requiresPro pre-condition: step is shown only when Pro is active.
 *                    When Free + this is true, the step is silently skipped.
 */
@Immutable
data class CoachmarkStep(
    val anchorId: String,
    val title: String,
    val body: String,
    val action: CoachmarkAction = CoachmarkAction.Next,
    val requiresPro: Boolean = false
)

/**
 * Anchor ID constants used across the codebase. Keep additive — removing
 * an id without removing its tour step is a runtime advance-on-grace, not
 * a crash, but lint via direct grep should catch drift.
 */
object CoachmarkAnchors {
    const val TODAY_OVERLOAD_BANNER = "today_overload_banner"
    const val TODAY_QUICK_ADD = "today_quick_add"
    const val TODAY_HABIT_CHIPS = "today_habit_chips"
    const val TODAY_AI_TOOLS_CHIP = "today_ai_tools_chip"
    const val NAV_TASKS_TAB = "nav_tasks_tab"
    const val NAV_HABITS_TAB = "nav_habits_tab"
    const val NAV_MEDS_TAB = "nav_meds_tab"
    const val OPEN_TIMER_ENTRY = "open_timer_entry"
    const val AI_COACH_FAB = "ai_coach_fab"
    const val EISENHOWER_QUADRANT_GRID = "eisenhower_quadrant_grid"
    const val SETTINGS_WEEKLY_REVIEW_ENTRY = "settings_weekly_review_entry"
    const val SETTINGS_APPEARANCE_ENTRY = "settings_appearance_entry"
    const val SETTINGS_AUTOMATIONS_ENTRY = "settings_automations_entry"
    const val NAV_SETTINGS_TAB = "nav_settings_tab"
    const val BOTTOM_NAV_ROW = "bottom_nav_row"
}

/**
 * Route keys for cross-screen tour navigation. The controller does not
 * own a NavController; the host translates these to actual nav calls.
 */
object CoachmarkRoutes {
    const val TASKS = "route:tasks"
    const val HABITS = "route:habits"
    const val MEDICATIONS = "route:medications"
    const val TIMER = "route:timer"
    const val EISENHOWER = "route:eisenhower"
    const val SETTINGS_APPEARANCE = "route:settings_appearance"
    const val SETTINGS = "route:settings"
}

/**
 * The post-onboarding coachmark tour. 13 surfaces; voice & tone match
 * existing `GUIDED_TOUR_STEPS` in `today/components/GuidedTourCard.kt`.
 *
 * Pre-conditions and fallbacks documented in
 * `docs/audits/ONBOARDING_TOUR_EXPANSION_AUDIT.md` § B.
 */
val DEFAULT_COACHMARK_TOUR: List<CoachmarkStep> = listOf(
    CoachmarkStep(
        anchorId = CoachmarkAnchors.TODAY_OVERLOAD_BANNER,
        title = "Balance bar",
        body = "When your day tilts heavy, the bar lights up. Tap it to see what's eating your time — no judgement, just data."
    ),
    CoachmarkStep(
        anchorId = CoachmarkAnchors.TODAY_QUICK_ADD,
        title = "Quick add anything",
        body = "Type or speak a task. \"Buy milk tomorrow at 4pm #shopping\" parses dates, tags, and projects from plain words."
    ),
    CoachmarkStep(
        anchorId = CoachmarkAnchors.TODAY_HABIT_CHIPS,
        title = "Habit chips",
        body = "Quick chips for the routines you set up. One tap to mark a habit done — they live here so Today stays Today."
    ),
    CoachmarkStep(
        anchorId = CoachmarkAnchors.TODAY_AI_TOOLS_CHIP,
        title = "AI helpers — optional",
        body = "Priority sort, daily briefing, weekly review. Off by default. Turn on what helps in Settings → AI Coach.",
        requiresPro = false
    ),
    CoachmarkStep(
        anchorId = CoachmarkAnchors.NAV_TASKS_TAB,
        title = "Tasks tab",
        body = "All your tasks. Filters, swipe-to-complete, drag-to-reorder, project view — every cut tunable.",
        action = CoachmarkAction.Navigate(CoachmarkRoutes.TASKS)
    ),
    CoachmarkStep(
        anchorId = CoachmarkAnchors.NAV_HABITS_TAB,
        title = "Habits tab",
        body = "Habits are forgiving. A missed day won't punish you — your streak grace days are tunable in Settings.",
        action = CoachmarkAction.Navigate(CoachmarkRoutes.HABITS)
    ),
    CoachmarkStep(
        anchorId = CoachmarkAnchors.NAV_MEDS_TAB,
        title = "Medications",
        body = "Track meds, doses, and refills. Per-slot reminders that snooze, not nag.",
        action = CoachmarkAction.Navigate(CoachmarkRoutes.MEDICATIONS)
    ),
    CoachmarkStep(
        anchorId = CoachmarkAnchors.OPEN_TIMER_ENTRY,
        title = "Focus timer",
        body = "Pomodoro tuned to your energy. Short or long sessions, breaks the way you want them.",
        action = CoachmarkAction.Navigate(CoachmarkRoutes.TIMER)
    ),
    CoachmarkStep(
        anchorId = CoachmarkAnchors.AI_COACH_FAB,
        title = "AI Coach",
        body = "Chat when you want a nudge. Starter prompts and action chips help you get unstuck — history stays private to you."
    ),
    CoachmarkStep(
        anchorId = CoachmarkAnchors.EISENHOWER_QUADRANT_GRID,
        title = "Eisenhower matrix",
        body = "Drag tasks across quadrants to plan your week. Urgent vs. important — your call, the matrix doesn't lecture.",
        action = CoachmarkAction.Navigate(CoachmarkRoutes.EISENHOWER),
        requiresPro = true
    ),
    CoachmarkStep(
        anchorId = CoachmarkAnchors.SETTINGS_APPEARANCE_ENTRY,
        title = "Appearance & widgets",
        body = "Theme palette, widget appearance, card density — match your home screen, your phone, your mood.",
        action = CoachmarkAction.Navigate(CoachmarkRoutes.SETTINGS_APPEARANCE)
    ),
    CoachmarkStep(
        anchorId = CoachmarkAnchors.NAV_SETTINGS_TAB,
        title = "Settings",
        body = "Backups, integrations, accessibility, debug — anything you didn't see in onboarding lives here.",
        action = CoachmarkAction.Navigate(CoachmarkRoutes.SETTINGS)
    ),
    CoachmarkStep(
        anchorId = CoachmarkAnchors.BOTTOM_NAV_ROW,
        title = "You're set",
        body = "Five tabs, one tap each. Today is your home, the rest are deeper dives — you'll find your favorites.",
        action = CoachmarkAction.Finish
    )
)
