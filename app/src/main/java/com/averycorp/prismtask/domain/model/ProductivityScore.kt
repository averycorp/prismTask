package com.averycorp.prismtask.domain.model

import java.time.LocalDate

/**
 * Mirrors `backend/app/schemas/analytics.py::ProductivityScoreResponse` so the
 * shape is identical to what web consumes from `/analytics/productivity-score`.
 * Android computes client-side (Subset C / slice 2 of the web PR #715 port —
 * see `docs/audits/ANALYTICS_PR715_PORT_AUDIT.md`).
 */
data class ProductivityScoreResponse(
    val scores: List<DailyScore>,
    val averageScore: Double,
    val trend: ProductivityTrend,
    val bestDay: BestWorstDay?,
    val worstDay: BestWorstDay?
)

data class DailyScore(val date: LocalDate, val score: Double, val breakdown: ScoreBreakdown)

data class ScoreBreakdown(
    /** 0-100 — completed-due / due, 100 when no tasks were due. */
    val taskCompletion: Double,
    /** 0-100 — on-time-completed / total-completed, 100 when nothing completed. */
    val onTime: Double,
    /** 0-100 — habit-completions-on-day / active-habits, 100 when no habits. */
    val habitCompletion: Double,
    /**
     * 0-100 — average accuracy of estimated vs. actual time per task. Android
     * has no `actualDuration` column on `TaskEntity` today, so this defaults
     * to 100 to match the backend's "no data → 100" convention. Replace with
     * real per-task variance when time tracking is wired into Room.
     */
    val estimationAccuracy: Double
)

enum class ProductivityTrend { IMPROVING, DECLINING, STABLE }

data class BestWorstDay(val date: LocalDate, val score: Double)
