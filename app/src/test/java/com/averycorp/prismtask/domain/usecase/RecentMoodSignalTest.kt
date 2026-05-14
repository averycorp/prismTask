package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.repository.MoodEnergyRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mental-Health-First § G7 — pins the v1 fixed-threshold gate
 * (48h window, mood ≤ 2/5). These tests are the source of truth for the
 * audit's Phase 4 answer. Any future move to user-configurable thresholds
 * must update the constants alongside this test file.
 */
class RecentMoodSignalTest {
    private val now = 1_700_000_000_000L
    private val clock = RecentMoodSignal.NowClock { now }
    private val hour = RecentMoodSignal.MILLIS_PER_HOUR

    private fun signal(repo: MoodEnergyRepository) = RecentMoodSignal(
        moodEnergyRepository = repo,
        clock = clock
    )

    @Test
    fun returns_true_for_mood_1_logged_4_hours_ago() = runBlocking {
        // 4h ago < 48h, mood=1 ≤ 2 → gate trips.
        val repo = mockk<MoodEnergyRepository> {
            coEvery {
                hasLowMoodSince(
                    moodCeiling = 2,
                    sinceCreatedAtMillis = now - 48 * hour
                )
            } returns true
        }
        assertTrue(signal(repo).isLowMoodWithin(48))
    }

    @Test
    fun returns_false_for_mood_3_logged_4_hours_ago() = runBlocking {
        // mood=3 is above the ≤2 floor — DAO sees zero rows matching the
        // (mood<=2, created>=cutoff) predicate.
        val repo = mockk<MoodEnergyRepository> {
            coEvery {
                hasLowMoodSince(
                    moodCeiling = 2,
                    sinceCreatedAtMillis = now - 48 * hour
                )
            } returns false
        }
        assertFalse(signal(repo).isLowMoodWithin(48))
    }

    @Test
    fun returns_false_for_mood_1_logged_60_hours_ago() = runBlocking {
        // 60h > 48h window — the row exists but lies outside the cutoff,
        // so the DAO predicate yields zero matches.
        val repo = mockk<MoodEnergyRepository> {
            coEvery {
                hasLowMoodSince(
                    moodCeiling = 2,
                    sinceCreatedAtMillis = now - 48 * hour
                )
            } returns false
        }
        assertFalse(signal(repo).isLowMoodWithin(48))
    }

    @Test
    fun default_window_is_48_hours() = runBlocking {
        // Audit § G7 Phase 4 — v1 fixed window. Pin the constant.
        assertEquals(48L, RecentMoodSignal.DEFAULT_WINDOW_HOURS)

        val repo = mockk<MoodEnergyRepository>(relaxed = true)
        coEvery { repo.hasLowMoodSince(any(), any()) } returns false
        signal(repo).isLowMoodWithin()

        coVerify(exactly = 1) {
            repo.hasLowMoodSince(
                moodCeiling = 2,
                sinceCreatedAtMillis = now - 48 * hour
            )
        }
    }

    @Test
    fun default_floor_is_2_out_of_5() = runBlocking {
        // Audit § G7 Phase 4 — v1 fixed floor. Pin the constant.
        assertEquals(2, RecentMoodSignal.LOW_MOOD_CEILING)
    }

    @Test
    fun non_positive_window_returns_false_without_db_hit() = runBlocking {
        val repo = mockk<MoodEnergyRepository>(relaxed = true)
        assertFalse(signal(repo).isLowMoodWithin(0))
        assertFalse(signal(repo).isLowMoodWithin(-5))
        coVerify(exactly = 0) { repo.hasLowMoodSince(any(), any()) }
    }
}
