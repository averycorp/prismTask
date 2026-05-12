package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import com.averycorp.prismtask.domain.model.LeisureCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LeisureSamplerTest {
    private val sampler = LeisureSampler()
    private val now = 1_700_000_000_000L
    private val dayMillis = 24L * 60L * 60L * 1000L

    @Test
    fun pick_returnsNullForEmptyCandidates() {
        val pick = sampler.pick(
            candidates = emptyList(),
            enabledCategories = LeisureCategory.DEFAULT_ENABLED
        )
        assertNull(pick)
    }

    @Test
    fun pick_skipsDisabledActivities() {
        val candidates = listOf(
            activity(id = 1, category = "PHYSICAL", enabled = false),
            activity(id = 2, category = "PHYSICAL", enabled = true)
        )
        val pick = sampler.pick(
            candidates = candidates,
            enabledCategories = setOf(LeisureCategory.PHYSICAL),
            now = now,
            random = Random(seed = 0)
        )
        assertEquals(2L, pick?.id)
    }

    @Test
    fun pick_skipsDisabledCategories() {
        val candidates = listOf(
            activity(id = 1, category = "PHYSICAL"),
            activity(id = 2, category = "SOCIAL")
        )
        val pick = sampler.pick(
            candidates = candidates,
            enabledCategories = setOf(LeisureCategory.PHYSICAL),
            now = now,
            random = Random(seed = 0)
        )
        assertEquals(1L, pick?.id)
    }

    @Test
    fun pick_prefersRecentlyUncompletedOverFreshlyCompleted() {
        // Run many trials and verify the never-completed activity wins
        // more often than the just-completed one. Activity 1 = 0 days
        // since completion; activity 2 = never completed.
        val candidates = listOf(
            activity(id = 1, category = "PHYSICAL", lastCompletedAt = now),
            activity(id = 2, category = "PHYSICAL", lastCompletedAt = null)
        )
        var picksFor2 = 0
        repeat(1000) { trial ->
            val pick = sampler.pick(
                candidates = candidates,
                enabledCategories = setOf(LeisureCategory.PHYSICAL),
                now = now,
                random = Random(seed = trial.toLong())
            )
            if (pick?.id == 2L) picksFor2++
        }
        assertTrue(
            "Expected recency-weighted sampler to prefer the never-completed " +
                "activity (got $picksFor2 / 1000)",
            picksFor2 > 700
        )
    }

    @Test
    fun pick_oneCandidate_alwaysPicksIt() {
        val solo = activity(id = 42, category = "CREATIVE")
        val pick = sampler.pick(
            candidates = listOf(solo),
            enabledCategories = setOf(LeisureCategory.CREATIVE),
            now = now,
            random = Random(seed = 0)
        )
        assertEquals(42L, pick?.id)
    }

    @Test
    fun pick_filtersOutInvalidCategoryStrings() {
        val candidates = listOf(
            activity(id = 1, category = "PHYSICAL"),
            activity(id = 2, category = "TYPO_CATEGORY")
        )
        val pick = sampler.pick(
            candidates = candidates,
            enabledCategories = LeisureCategory.DEFAULT_ENABLED,
            now = now,
            random = Random(seed = 0)
        )
        assertEquals(1L, pick?.id)
    }

    private fun activity(
        id: Long,
        category: String,
        enabled: Boolean = true,
        lastCompletedAt: Long? = null
    ): LeisureActivityEntity = LeisureActivityEntity(
        id = id,
        name = "Activity $id",
        category = category,
        enabled = enabled,
        lastCompletedAt = lastCompletedAt
    )
}
