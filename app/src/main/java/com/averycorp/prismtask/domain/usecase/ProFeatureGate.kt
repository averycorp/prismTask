package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.billing.BillingManager
import com.averycorp.prismtask.data.billing.UserTier
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI features used across the app. `aiModelForFeature` maps each feature to
 * the Claude model that should power it.
 */
enum class AiFeature {
    NLP,
    EISENHOWER,
    POMODORO,
    DAILY_BRIEFING,
    COACHING,
    TASK_BREAKDOWN,
    DAILY_PLANNING,
    REENGAGEMENT,
    TIME_BLOCKING,
    EVENING_SUMMARY,
    CONVERSATIONAL_CHAT,
    WEEKLY_PLANNER,
    MONTHLY_REVIEW,
    TASK_EXTRACTION
}

/**
 * Returns the Claude model ID that should serve the given feature. Weekly
 * planner and monthly review get Sonnet for higher-quality output; all other
 * AI features run on Haiku.
 */
fun aiModelForFeature(feature: AiFeature): String = when (feature) {
    AiFeature.WEEKLY_PLANNER, AiFeature.MONTHLY_REVIEW -> "claude-sonnet-4-20250514"
    else -> "claude-haiku-4-5-20251001"
}

@Singleton
class ProFeatureGate
@Inject
constructor(private val billingManager: BillingManager) {
    val userTier: StateFlow<UserTier> = billingManager.userTier

    fun isPro(): Boolean = userTier.value == UserTier.PRO

    fun hasAccess(feature: String): Boolean = when (requiredTier(feature)) {
        UserTier.FREE -> true
        UserTier.PRO -> isPro()
    }

    fun requiredTier(feature: String): UserTier = when (feature) {
        TEMPLATE_SYNC, AI_EISENHOWER, AI_POMODORO,
        AI_NLP,
        AI_EVENING_SUMMARY, AI_COACHING, AI_TASK_BREAKDOWN,
        AI_BRIEFING, AI_WEEKLY_PLAN, AI_WEEKLY_REVIEW, AI_TIME_BLOCK,
        AI_CONVERSATIONAL,
        AI_REENGAGEMENT, AI_DAILY_PLANNING,
        AI_BATCH_OPS,
        CLOUD_SYNC,
        ANALYTICS_FULL, ANALYTICS_CORRELATIONS,
        SYLLABUS_IMPORT -> UserTier.PRO

        else -> UserTier.FREE
    }

    companion object {
        const val CLOUD_SYNC = "cloud_sync"
        const val TEMPLATE_SYNC = "template_sync"
        const val AI_EISENHOWER = "ai_eisenhower"
        const val AI_POMODORO = "ai_pomodoro"
        const val AI_NLP = "ai_nlp"
        const val AI_EVENING_SUMMARY = "ai_evening_summary"
        const val AI_COACHING = "ai_coaching"
        const val AI_TASK_BREAKDOWN = "ai_task_breakdown"
        const val AI_BRIEFING = "ai_briefing"
        const val AI_WEEKLY_PLAN = "ai_weekly_plan"
        const val AI_WEEKLY_REVIEW = "ai_weekly_review"
        const val AI_TIME_BLOCK = "ai_time_block"
        const val AI_CONVERSATIONAL = "ai_conversational"
        const val AI_DAILY_PLANNING = "ai_daily_planning"
        const val AI_REENGAGEMENT = "ai_reengagement"
        const val AI_BATCH_OPS = "ai_batch_ops"
        const val SYLLABUS_IMPORT = "syllabus_import"

        /**
         * Full analytics dashboard (productivity score area chart,
         * time-tracking bars, summary tiles). Free tier sees the basic
         * task-completion stats; Pro unlocks the score chart + time chart.
         */
        const val ANALYTICS_FULL = "analytics_full"

        /**
         * Habit correlation analysis (calls `/analytics/habit-correlations`,
         * Anthropic-backed). Pro-only. Currently deferred to a follow-up
         * audit — constant is reserved here for forward compatibility.
         */
        const val ANALYTICS_CORRELATIONS = "analytics_correlations"
    }
}
