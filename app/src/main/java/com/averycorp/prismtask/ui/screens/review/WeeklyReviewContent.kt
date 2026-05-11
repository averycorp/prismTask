package com.averycorp.prismtask.ui.screens.review

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * UI projection of a persisted [com.averycorp.prismtask.data.local.entity.WeeklyReviewEntity].
 *
 * Two producers currently write `aiInsightsJson`:
 *  - [com.averycorp.prismtask.domain.usecase.WeeklyReviewGenerator] (the
 *    A2 worker) stores a serialized [com.averycorp.prismtask.data.remote.api.WeeklyReviewResponse]
 *    with fields `wins`, `slips`, `patterns`, `next_week_focus`, `narrative`.
 *  - [WeeklyReviewViewModel.persistLocal] (on-demand viewer) stores a
 *    legacy rule-based narrative shape with `wins`, `misses`, `suggestions`.
 *
 * The detail screen needs to render *either* history, so this parser
 * detects the shape by key presence and emits a single unified
 * [WeeklyReviewContent].
 */
data class WeeklyReviewContent(
    val narrative: String,
    val activitySummary: ActivitySummary,
    val patterns: List<String>,
    val nextWeekFocus: List<String>,
    val wins: List<String>,
    val slips: List<String>
) {
    data class ActivitySummary(val completed: Int, val slipped: Int, val rescheduled: Int, val byCategory: Map<String, Int>)

    companion object {
        fun parseMetrics(metricsJson: String?): ActivitySummary {
            if (metricsJson.isNullOrBlank()) return ActivitySummary(0, 0, 0, emptyMap())
            return try {
                val obj = JsonParser.parseString(metricsJson).asJsonObject
                ActivitySummary(
                    completed = obj.optInt("completed"),
                    slipped = obj.optInt("slipped"),
                    rescheduled = obj.optInt("rescheduled"),
                    byCategory = obj.getAsJsonObject("byCategory")?.entrySet()
                        ?.associate { it.key to (it.value?.asInt ?: 0) }
                        ?: emptyMap()
                )
            } catch (_: Exception) {
                ActivitySummary(0, 0, 0, emptyMap())
            }
        }

        fun parseInsights(aiInsightsJson: String?): InsightsBody {
            if (aiInsightsJson.isNullOrBlank()) return InsightsBody.empty()
            return try {
                val obj = JsonParser.parseString(aiInsightsJson).asJsonObject
                // WeeklyReviewResponse shape ships `next_week_focus` /
                // `patterns` / `slips`. The legacy narrative shape ships
                // `misses` + `suggestions`. Take whichever is present.
                val isResponseShape = obj.has("next_week_focus") ||
                    obj.has("patterns") ||
                    obj.has("narrative")
                if (isResponseShape) {
                    InsightsBody(
                        narrative = obj.optString("narrative"),
                        wins = obj.optStringList("wins"),
                        slips = obj.optStringList("slips"),
                        patterns = obj.optStringList("patterns"),
                        nextWeekFocus = obj.optStringList("next_week_focus")
                    )
                } else {
                    InsightsBody(
                        narrative = "",
                        wins = obj.optStringList("wins"),
                        slips = obj.optStringList("misses"),
                        patterns = emptyList(),
                        nextWeekFocus = obj.optStringList("suggestions")
                    )
                }
            } catch (_: Exception) {
                InsightsBody.empty()
            }
        }

        fun of(metricsJson: String?, aiInsightsJson: String?): WeeklyReviewContent {
            val metrics = parseMetrics(metricsJson)
            val insights = parseInsights(aiInsightsJson)
            return WeeklyReviewContent(
                narrative = insights.narrative,
                activitySummary = metrics,
                patterns = insights.patterns,
                nextWeekFocus = insights.nextWeekFocus,
                wins = insights.wins,
                slips = insights.slips
            )
        }
    }

    data class InsightsBody(
        val narrative: String,
        val wins: List<String>,
        val slips: List<String>,
        val patterns: List<String>,
        val nextWeekFocus: List<String>
    ) {
        companion object {
            fun empty(): InsightsBody = InsightsBody("", emptyList(), emptyList(), emptyList(), emptyList())
        }
    }
}

private fun JsonObject.optInt(key: String): Int =
    get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0

private fun JsonObject.optString(key: String): String =
    get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

private fun JsonObject.optStringList(key: String): List<String> =
    getAsJsonArray(key)?.mapNotNull { it?.takeIf { e -> !e.isJsonNull }?.asString } ?: emptyList()
