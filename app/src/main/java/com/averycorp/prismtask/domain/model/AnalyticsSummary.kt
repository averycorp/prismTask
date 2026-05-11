package com.averycorp.prismtask.domain.model

/**
 * Mirrors the web `/analytics/summary` response shape (web PR #715), but
 * computed client-side from Room because Android syncs to Firestore rather
 * than to the FastAPI/Postgres backend the web app talks to.
 *
 * Phase F port (audit `ANALYTICS_PR715_PORT_AUDIT.md`, Subset C — slice 1).
 */
data class AnalyticsSummary(val today: TodaySummary, val thisWeek: WeekSummary, val streaks: StreakSummary, val habits: HabitSummaryBucket)

data class TodaySummary(val completed: Int, val remaining: Int)

enum class WeekTrend { IMPROVING, STABLE, DECLINING }

data class WeekSummary(val completed: Int, val previousWeekCompleted: Int, val trend: WeekTrend)

data class StreakSummary(val currentDays: Int, val longestDays: Int)

data class HabitSummaryBucket(
    /** 0.0-1.0 — completions / (active habits × 7), capped at 1.0. */
    val completionRate7d: Double,
    /** 0.0-1.0 — completions / (active habits × 30), capped at 1.0. */
    val completionRate30d: Double,
    val activeHabits: Int
)
