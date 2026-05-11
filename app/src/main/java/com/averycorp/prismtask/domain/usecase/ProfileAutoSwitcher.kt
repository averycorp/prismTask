package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.domain.model.notifications.ProfileAutoSwitchTrigger
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Evaluates a user's auto-switch rules and picks the active notification
 * profile for a given moment.
 *
 * Rules are evaluated **top-down**: the first rule whose predicate matches
 * wins. A [fallbackProfileId] covers the "no rule matched" case.
 *
 * The rule engine is intentionally pure — [currentTimeProvider] is the
 * single seam for testability.
 */
class ProfileAutoSwitcher {
    /**
     * A single rule. [trigger] tags the rule for UI grouping; [matches]
     * is the opaque predicate the rule evaluates against a [Context].
     */
    data class Rule(val id: String, val profileId: Long, val trigger: ProfileAutoSwitchTrigger, val matches: (Context) -> Boolean)

    data class Context(
        val time: LocalTime,
        val day: DayOfWeek,
        val inOsFocusMode: Boolean = false,
        val hasActiveCalendarEvent: Boolean = false,
        val currentLocationTag: String? = null
    )

    /**
     * Returns the profile id for the first matching rule, or
     * [fallbackProfileId] if no rule matches.
     */
    fun pick(
        rules: List<Rule>,
        context: Context,
        fallbackProfileId: Long
    ): Long = rules.firstOrNull { it.matches(context) }?.profileId ?: fallbackProfileId

    companion object {
        /**
         * Time-of-day rule: active when [start] <= current time < [end]
         * (inclusive/exclusive). Overnight ranges (start > end) wrap.
         */
        fun timeOfDay(
            id: String,
            profileId: Long,
            start: LocalTime,
            end: LocalTime
        ): Rule = Rule(
            id = id,
            profileId = profileId,
            trigger = ProfileAutoSwitchTrigger.TIME_OF_DAY,
            matches = { ctx ->
                val t = ctx.time
                if (start == end) {
                    false
                } else if (start < end) {
                    t >= start && t < end
                } else {
                    t >= start || t < end
                }
            }
        )

        /** Day-of-week rule: active on any of [days]. */
        fun dayOfWeek(
            id: String,
            profileId: Long,
            days: Set<DayOfWeek>
        ): Rule = Rule(
            id = id,
            profileId = profileId,
            trigger = ProfileAutoSwitchTrigger.DAY_OF_WEEK,
            matches = { ctx -> ctx.day in days }
        )

        /** Combined time + day rule. */
        fun timeAndDay(
            id: String,
            profileId: Long,
            start: LocalTime,
            end: LocalTime,
            days: Set<DayOfWeek>
        ): Rule {
            val t = timeOfDay(id, profileId, start, end)
            val d = dayOfWeek(id, profileId, days)
            return Rule(
                id = id,
                profileId = profileId,
                trigger = ProfileAutoSwitchTrigger.TIME_OF_DAY,
                matches = { ctx -> t.matches(ctx) && d.matches(ctx) }
            )
        }

        /** Active when OS Focus / iOS Focus / Android Modes is engaged. */
        fun osFocusMode(id: String, profileId: Long): Rule = Rule(
            id = id,
            profileId = profileId,
            trigger = ProfileAutoSwitchTrigger.OS_FOCUS_MODE,
            matches = { it.inOsFocusMode }
        )

        /** Active while the device calendar reports an ongoing event. */
        fun calendarEvent(id: String, profileId: Long): Rule = Rule(
            id = id,
            profileId = profileId,
            trigger = ProfileAutoSwitchTrigger.CALENDAR_EVENT,
            matches = { it.hasActiveCalendarEvent }
        )

        /** Active when the device is geofenced to [locationTag]. */
        fun location(id: String, profileId: Long, locationTag: String): Rule = Rule(
            id = id,
            profileId = profileId,
            trigger = ProfileAutoSwitchTrigger.LOCATION,
            matches = { it.currentLocationTag == locationTag }
        )
    }
}
