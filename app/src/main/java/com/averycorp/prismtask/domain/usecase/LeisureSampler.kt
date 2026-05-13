package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import com.averycorp.prismtask.domain.model.LeisureCategory
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.random.Random

/**
 * Leisure Budget v2.0 — Item 13. Recency-weighted random pull from the
 * activity pool.
 *
 * Algorithm (per spec):
 *   weight(activity) = max(1, daysSince(lastCompletedAt ?: epoch))
 *
 * Sampling is biased toward activities the user hasn't done lately so
 * the suggestion surface stays fresh. The 3-refresh-per-day cap is
 * enforced upstream by [com.averycorp.prismtask.data.repository.LeisureBudgetRepository] —
 * this sampler is a pure function over its inputs.
 *
 * Q1 lock — the refresh limit is the feature, not a paywall. Free +
 * paid tiers share the same default cap (3/day).
 */
@Singleton
class LeisureSampler
@Inject
constructor() {

    /**
     * Pick one activity from [candidates], weighted by days-since-last-completed.
     *
     * Returns null when there are no candidates. Filters by [enabledCategories]
     * first, then weights by recency.
     */
    fun pick(
        candidates: List<LeisureActivityEntity>,
        enabledCategories: Set<LeisureCategory>,
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default
    ): LeisureActivityEntity? = pickByCategoryIds(
        candidates = candidates,
        allowedCategoryIds = enabledCategories.map { it.name }.toSet(),
        now = now,
        random = random
    )

    /**
     * String-id variant accepting both built-in category names and
     * custom category ids ([com.averycorp.prismtask.domain.model.CustomLeisureCategory.ID_PREFIX]).
     */
    fun pickByCategoryIds(
        candidates: List<LeisureActivityEntity>,
        allowedCategoryIds: Set<String>,
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default
    ): LeisureActivityEntity? {
        val filtered = candidates.filter { activity ->
            activity.enabled && activity.category in allowedCategoryIds
        }
        if (filtered.isEmpty()) return null

        val weights = filtered.map { activity ->
            val last = activity.lastCompletedAt ?: 0L
            val daysSince = max(0L, (now - last) / DAY_MILLIS)
            // Always at least 1 so a brand-new activity (last == 0L,
            // daysSince huge) doesn't accidentally collapse to zero
            // weight, and a just-completed one (daysSince == 0) still
            // has a chance to surface.
            max(1L, daysSince).toDouble()
        }
        val total = weights.sum()
        if (total <= 0.0) return filtered.first()

        val target = random.nextDouble() * total
        var cumulative = 0.0
        for (i in filtered.indices) {
            cumulative += weights[i]
            if (target <= cumulative) return filtered[i]
        }
        return filtered.last()
    }

    companion object {
        private const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L
    }
}
