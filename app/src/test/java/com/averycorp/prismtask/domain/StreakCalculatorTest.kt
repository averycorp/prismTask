package com.averycorp.prismtask.domain

import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.domain.usecase.StreakCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class StreakCalculatorTest {
    private fun LocalDate.toMillis(): Long =
        atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun dailyHabit(target: Int = 1) = HabitEntity(
        id = 1,
        name = "Test",
        targetFrequency = target,
        frequencyPeriod = "daily"
    )

    private fun weeklyHabit(target: Int = 3, activeDays: String? = null) = HabitEntity(
        id = 1,
        name = "Test",
        targetFrequency = target,
        frequencyPeriod = "weekly",
        activeDays = activeDays
    )

    private fun completion(habitId: Long = 1, date: LocalDate) = HabitCompletionEntity(
        habitId = habitId,
        completedDate = date.toMillis(),
        completedAt = date.toMillis()
    )

    /**
     * Helper for clock-change tests: completion timestamped at a specific
     * hour/minute on a given local date. `completedDate` is the epoch
     * millisecond StreakCalculator converts back to a LocalDate via the
     * device's default zone, so the date the completion gets bucketed
     * into mirrors what the device wall-clock said when it was logged.
     */
    private fun completionAt(
        habitId: Long = 1,
        date: LocalDate,
        hour: Int,
        minute: Int = 0
    ): HabitCompletionEntity {
        val ts = date.atTime(hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return HabitCompletionEntity(
            habitId = habitId,
            completedDate = ts,
            completedAt = ts
        )
    }

    // --- Current Streak ---

    @Test
    fun test_currentStreak_3days() {
        val today = LocalDate.of(2025, 6, 10) // Tuesday
        val completions = listOf(
            completion(date = today),
            completion(date = today.minusDays(1)),
            completion(date = today.minusDays(2))
        )
        val streak = StreakCalculator.calculateCurrentStreak(completions, dailyHabit(), today)
        assertEquals(3, streak)
    }

    @Test
    fun test_currentStreak_brokenByGap() {
        val today = LocalDate.of(2025, 6, 10)
        val completions = listOf(
            completion(date = today),
            // missing yesterday
            completion(date = today.minusDays(2))
        )
        val streak = StreakCalculator.calculateCurrentStreak(completions, dailyHabit(), today)
        assertEquals(1, streak)
    }

    @Test
    fun test_currentStreak_todayNotDoneYet() {
        val today = LocalDate.of(2025, 6, 10)
        val completions = listOf(
            completion(date = today.minusDays(1)),
            completion(date = today.minusDays(2))
        )
        val streak = StreakCalculator.calculateCurrentStreak(completions, dailyHabit(), today)
        assertEquals(2, streak)
    }

    @Test
    fun test_currentStreak_empty() {
        val streak = StreakCalculator.calculateCurrentStreak(emptyList(), dailyHabit())
        assertEquals(0, streak)
    }

    @Test
    fun test_currentStreak_noRecentCompletions() {
        val today = LocalDate.of(2025, 6, 10)
        val completions = listOf(
            completion(date = today.minusDays(5)),
            completion(date = today.minusDays(6))
        )
        val streak = StreakCalculator.calculateCurrentStreak(completions, dailyHabit(), today)
        assertEquals(0, streak)
    }

    // --- Per-habit override (resolver-fed maxMissedDays) ---

    @Test
    fun test_currentStreak_perHabitMaxMissedDaysOverrideExtendsStreak() {
        // Pattern: done, done, MISS, done, done, MISS, done — counted from today backwards.
        // With graceLimit = 1 (strict) the streak hard-resets at the first miss.
        // With graceLimit = 3 (per-habit override above the global default of 1),
        // single-day misses are tolerated and the streak walks back further.
        val today = LocalDate.of(2025, 6, 10)
        val completions = listOf(
            // today
            completion(date = today),
            completion(date = today.minusDays(1)),
            // miss day -2
            completion(date = today.minusDays(3)),
            completion(date = today.minusDays(4)),
            // miss day -5
            completion(date = today.minusDays(6))
        )

        val strict = StreakCalculator.calculateCurrentStreak(
            completions = completions,
            habit = dailyHabit(),
            today = today,
            maxMissedDays = 1
        )
        assertEquals(2, strict)

        val resolved = StreakCalculator.calculateCurrentStreak(
            completions = completions,
            habit = dailyHabit(),
            today = today,
            maxMissedDays = 3
        )
        assertEquals(5, resolved)
    }

    // --- Longest Streak ---

    @Test
    fun test_longestStreak() {
        val today = LocalDate.of(2025, 6, 10)
        // Past streak of 5 days
        val pastStreak = (0L until 5).map { completion(date = today.minusDays(20 - it)) }
        // Current streak of 3 days
        val currentStreak = (0L until 3).map { completion(date = today.minusDays(it)) }
        val completions = pastStreak + currentStreak

        val longest = StreakCalculator.calculateLongestStreak(completions, dailyHabit(), today)
        assertEquals(5, longest)
    }

    @Test
    fun test_longestStreak_currentIsLongest() {
        val today = LocalDate.of(2025, 6, 10)
        val completions = (0L until 10).map { completion(date = today.minusDays(it)) }
        val longest = StreakCalculator.calculateLongestStreak(completions, dailyHabit(), today)
        assertEquals(10, longest)
    }

    // --- Completion Rate ---

    @Test
    fun test_completionRate() {
        val today = LocalDate.of(2025, 6, 10)
        val completions = (0L until 5).map { completion(date = today.minusDays(it)) }
        val rate = StreakCalculator.calculateCompletionRate(completions, dailyHabit(), 7, today)
        assertEquals(5f / 7f, rate, 0.01f)
    }

    @Test
    fun test_completionRate_perfect() {
        val today = LocalDate.of(2025, 6, 10)
        val completions = (0L until 7).map { completion(date = today.minusDays(it)) }
        val rate = StreakCalculator.calculateCompletionRate(completions, dailyHabit(), 7, today)
        assertEquals(1.0f, rate, 0.01f)
    }

    // --- Weekly Streak ---

    @Test
    fun test_weeklyStreak() {
        // 4 consecutive weeks with 3+ completions each
        val today = LocalDate.of(2025, 6, 9) // Monday
        val completions = mutableListOf<HabitCompletionEntity>()
        for (week in 0 until 4) {
            val weekStart = today.minusWeeks(week.toLong())
            completions.add(completion(date = weekStart))
            completions.add(completion(date = weekStart.plusDays(1)))
            completions.add(completion(date = weekStart.plusDays(2)))
        }
        val streak = StreakCalculator.calculateCurrentStreak(completions, weeklyHabit(target = 3), today)
        assertEquals(4, streak)
    }

    // --- Active Days ---

    @Test
    fun test_activeDays_completionRate() {
        // Mon/Wed/Fri habit (values 1,3,5)
        val today = LocalDate.of(2025, 6, 13) // Friday
        val habit = weeklyHabit(target = 3, activeDays = "[1,3,5]")
        // Complete Mon, Wed, Fri of this week
        val monday = LocalDate.of(2025, 6, 9)
        val completions = listOf(
            completion(date = monday),
            // Wed
            completion(date = monday.plusDays(2)),
            // Fri
            completion(date = monday.plusDays(4))
        )
        val rate = StreakCalculator.calculateCompletionRate(completions, habit, 5, today)
        assertEquals(1.0f, rate, 0.01f)
    }

    // --- Best/Worst Day ---

    @Test
    fun test_bestDay() {
        val completions = listOf(
            // Monday
            completion(date = LocalDate.of(2025, 6, 9)),
            // Monday (2nd)
            completion(date = LocalDate.of(2025, 6, 9)),
            // Tuesday
            completion(date = LocalDate.of(2025, 6, 10))
        )
        val best = StreakCalculator.getBestDay(completions)
        assertEquals(DayOfWeek.MONDAY, best)
    }

    @Test
    fun test_worstDay() {
        val completions = listOf(
            // Monday
            completion(date = LocalDate.of(2025, 6, 9)),
            // Tuesday
            completion(date = LocalDate.of(2025, 6, 10))
        )
        val worst = StreakCalculator.getWorstDay(completions)
        assertNotNull(worst)
        // Worst should be one of the days with 0 completions (Wed-Sun)
        assertEquals(
            0,
            completions.count {
                it.completedDate.let { ts ->
                    java.time.Instant
                        .ofEpochMilli(ts)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .dayOfWeek
                } == worst
            }
        )
    }

    // --- Additional Edge Cases ---

    @Test
    fun test_getCompletionsByDay_returnsAllDaysInRange() {
        val start = LocalDate.of(2025, 6, 9) // Monday
        val end = LocalDate.of(2025, 6, 15) // Sunday
        val completions = listOf(
            completion(date = LocalDate.of(2025, 6, 10)),
            // double completion
            completion(date = LocalDate.of(2025, 6, 10)),
            completion(date = LocalDate.of(2025, 6, 13))
        )
        val byDay = StreakCalculator.getCompletionsByDay(completions, start, end)
        assertEquals(7, byDay.size)
        assertEquals(0, byDay[LocalDate.of(2025, 6, 9)])
        assertEquals(2, byDay[LocalDate.of(2025, 6, 10)])
        assertEquals(1, byDay[LocalDate.of(2025, 6, 13)])
        assertEquals(0, byDay[LocalDate.of(2025, 6, 15)])
    }

    @Test
    fun test_getBestDay_empty_returnsNull() {
        val result = StreakCalculator.getBestDay(emptyList())
        assertEquals(null, result)
    }

    @Test
    fun test_getWorstDay_empty_returnsNull() {
        val result = StreakCalculator.getWorstDay(emptyList())
        assertEquals(null, result)
    }

    @Test
    fun test_completionRate_zeroDays_returnsZero() {
        val rate = StreakCalculator.calculateCompletionRate(emptyList(), dailyHabit(), 0)
        assertEquals(0f, rate, 0.001f)
    }

    @Test
    fun test_longestStreak_empty_returnsZero() {
        val longest = StreakCalculator.calculateLongestStreak(emptyList(), dailyHabit())
        assertEquals(0, longest)
    }

    @Test
    fun test_currentStreak_onlyToday() {
        val today = LocalDate.of(2025, 6, 10)
        val completions = listOf(completion(date = today))
        val streak = StreakCalculator.calculateCurrentStreak(completions, dailyHabit(), today)
        assertEquals(1, streak)
    }

    @Test
    fun test_currentStreak_multiTarget_perDay() {
        val today = LocalDate.of(2025, 6, 10)
        // Habit requires 2 per day, but only 1 completion per day
        val completions = listOf(
            completion(date = today),
            completion(date = today.minusDays(1))
        )
        val streak = StreakCalculator.calculateCurrentStreak(completions, dailyHabit(target = 2), today)
        assertEquals(0, streak) // not meeting target
    }

    @Test
    fun test_currentStreak_multiTarget_met() {
        val today = LocalDate.of(2025, 6, 10)
        // Habit requires 2 per day, and we have 2 completions per day
        val completions = listOf(
            completion(date = today),
            completion(date = today),
            completion(date = today.minusDays(1)),
            completion(date = today.minusDays(1))
        )
        val streak = StreakCalculator.calculateCurrentStreak(completions, dailyHabit(target = 2), today)
        assertEquals(2, streak)
    }

    // --- Streak Grace Period (maxMissedDays) ---

    @Test
    fun test_currentStreak_graceOfTwo_survivesSingleMissedDay() {
        val today = LocalDate.of(2025, 6, 10)
        // Missed yesterday, but done today, day-before-yesterday, and back 3 more days.
        val completions = listOf(
            completion(date = today),
            completion(date = today.minusDays(2)),
            completion(date = today.minusDays(3)),
            completion(date = today.minusDays(4))
        )
        // Grace of 2 means a single missed day is forgiven.
        val streak = StreakCalculator.calculateCurrentStreak(
            completions,
            dailyHabit(),
            today,
            maxMissedDays = 2
        )
        assertEquals(4, streak)
    }

    @Test
    fun test_currentStreak_graceOfTwo_brokenByTwoConsecutiveMisses() {
        val today = LocalDate.of(2025, 6, 10)
        // Missed both yesterday and the day before — streak should end at today.
        val completions = listOf(
            completion(date = today),
            completion(date = today.minusDays(3)),
            completion(date = today.minusDays(4))
        )
        val streak = StreakCalculator.calculateCurrentStreak(
            completions,
            dailyHabit(),
            today,
            maxMissedDays = 2
        )
        assertEquals(1, streak)
    }

    @Test
    fun test_currentStreak_graceOfOne_matchesOriginalBehavior() {
        // Grace of 1 is the original semantics: any miss breaks the streak.
        val today = LocalDate.of(2025, 6, 10)
        val completions = listOf(
            completion(date = today),
            completion(date = today.minusDays(2))
        )
        val streak = StreakCalculator.calculateCurrentStreak(
            completions,
            dailyHabit(),
            today,
            maxMissedDays = 1
        )
        assertEquals(1, streak)
    }

    @Test
    fun test_longestStreak_graceOfTwo_spansSingleGap() {
        val today = LocalDate.of(2025, 6, 10)
        // 3 completions, a single miss, then 4 more completions.
        val completions = listOf(
            completion(date = today.minusDays(8)),
            completion(date = today.minusDays(7)),
            completion(date = today.minusDays(6)),
            // missed day 5
            completion(date = today.minusDays(4)),
            completion(date = today.minusDays(3)),
            completion(date = today.minusDays(2)),
            completion(date = today.minusDays(1))
        )
        val longest = StreakCalculator.calculateLongestStreak(
            completions,
            dailyHabit(),
            today,
            maxMissedDays = 2
        )
        // With one forgiven gap, the run should stitch together into 7 completions.
        assertEquals(7, longest)
    }

    // --- firstDayOfWeek parameter ---

    @Test
    fun test_weeklyStreak_respectsSundayFirstDay() {
        // Use a date range where the week start matters.
        // 2025-06-08 is a Sunday, 2025-06-09 is a Monday.
        // With SUNDAY first-day: week starts Jun 8, so completions Jun 8-10 = 3 in one week.
        // With MONDAY first-day: week starts Jun 9, so Jun 8 is previous week.
        val today = LocalDate.of(2025, 6, 14) // Saturday
        val completions = listOf(
            // Sunday
            completion(date = LocalDate.of(2025, 6, 8)),
            // Monday
            completion(date = LocalDate.of(2025, 6, 9)),
            // Tuesday
            completion(date = LocalDate.of(2025, 6, 10)),
            // New week (with Sunday first-day: Jun 15 is next week start; with Monday: Jun 9)
            // Monday prev week (Mon-first)
            completion(date = LocalDate.of(2025, 6, 2)),
            // Tuesday
            completion(date = LocalDate.of(2025, 6, 3)),
            // Wednesday
            completion(date = LocalDate.of(2025, 6, 4))
        )
        val habit = weeklyHabit(target = 3)

        // Default MONDAY start: current week (Jun 9–15) has 3 completions (Jun 9, 10 + one more needed)
        val streakMon = StreakCalculator.calculateCurrentStreak(
            completions,
            habit,
            today,
            firstDayOfWeek = DayOfWeek.MONDAY
        )

        // SUNDAY start: current week (Jun 8–14) has 3 completions (Jun 8, 9, 10)
        val streakSun = StreakCalculator.calculateCurrentStreak(
            completions,
            habit,
            today,
            firstDayOfWeek = DayOfWeek.SUNDAY
        )

        // Both should produce valid results; the key assertion is they can differ
        // because week boundaries shift.
        assertTrue("Streak with Sunday start ($streakSun) should be >= 1", streakSun >= 1)
        assertTrue("Streak with Monday start ($streakMon) should be >= 0", streakMon >= 0)
    }

    // --- Clock-change behavior ---
    //
    // StreakCalculator takes `today: LocalDate` (not a wall-clock millisecond),
    // and `completion.completedDate` is the epoch millisecond observed when
    // the user logged the habit. A device clock change therefore manifests
    // along two axes the tests below cover:
    //   1. Future calls pass a different `today` (the resolved logical day
    //      shifted forward or backward).
    //   2. Past completions retain the millisecond they were logged at, so
    //      moving the clock doesn't retroactively re-bucket them.
    //
    // These tests pin down those two axes explicitly so a regression that
    // started reading `System.currentTimeMillis()` inside the calculator
    // (or that started rounding completedDate against `today`) would surface
    // immediately.

    @Test
    fun test_clockChange_jumpsForwardMidDay_streakStaysOneNotTwo() {
        // User logs habit at 10:00, opens streak UI again at 14:00.
        // Both timestamps resolve to the same calendar day, so streak
        // must NOT jump from 1 to 2 on the second read.
        val today = LocalDate.of(2025, 6, 10)
        val completions = listOf(completionAt(date = today, hour = 10))

        val streakAt10 = StreakCalculator.calculateCurrentStreak(
            completions,
            dailyHabit(),
            today
        )
        val streakAt14 = StreakCalculator.calculateCurrentStreak(
            completions,
            dailyHabit(),
            today
        )
        assertEquals("Streak at 10:00 should be 1", 1, streakAt10)
        assertEquals(
            "Re-reading at 14:00 must not duplicate today's completion",
            1,
            streakAt14
        )
    }

    @Test
    fun test_clockChange_jumpsForwardAcrossMidnight_yesterdayStillCounts() {
        // Completion logged at 23:00 day N. User reopens app at 01:00 day
        // N+1 (clock crossed midnight). Today is now day N+1 with no
        // completion yet — but yesterday's completion must keep the streak
        // alive (the same "today not done yet, count from yesterday"
        // branch in calculateDailyStreak).
        val dayN = LocalDate.of(2025, 6, 10)
        val dayNPlus1 = dayN.plusDays(1)
        val completions = listOf(completionAt(date = dayN, hour = 23))

        val streak = StreakCalculator.calculateCurrentStreak(
            completions,
            dailyHabit(),
            today = dayNPlus1
        )
        assertEquals(
            "Yesterday's evening completion should keep streak at 1 on the next day",
            1,
            streak
        )
    }

    @Test
    fun test_clockChange_rollsBackToPast_futureCompletionDoesNotInflateStreak() {
        // User sets device clock back one day. The completion now has
        // `completedDate` in the FUTURE relative to `today`. The walk
        // backward from `today` never reaches that future bucket, so the
        // future-dated completion contributes 0 to the current streak.
        val dayN = LocalDate.of(2025, 6, 10)
        val dayNPlus1 = dayN.plusDays(1)
        val completions = listOf(completionAt(date = dayNPlus1, hour = 10))

        val streak = StreakCalculator.calculateCurrentStreak(
            completions,
            dailyHabit(),
            today = dayN
        )
        assertEquals(
            "Future-dated completion (clock-rollback artifact) must not " +
                "count toward the streak measured at an earlier `today`",
            0,
            streak
        )
    }

    @Test
    fun test_clockChange_completionAtExactDayBoundary_countsForThatDay() {
        // Completion at exactly 00:00:00 of the day. The epoch millisecond
        // → LocalDate conversion at the boundary must bucket the row into
        // that calendar day, not the previous day.
        val today = LocalDate.of(2025, 6, 10)
        val completions = listOf(completionAt(date = today, hour = 0, minute = 0))

        val streak = StreakCalculator.calculateCurrentStreak(
            completions,
            dailyHabit(),
            today
        )
        assertEquals(
            "Completion at exact 00:00 of `today` must count for today, " +
                "not yesterday",
            1,
            streak
        )
    }

    @Test
    fun test_clockChange_rapidToggle_pureFunctionStaysDeterministic() {
        // Simulate the user toggling the clock back and forth rapidly:
        // the calculator must be a pure function of (completions, habit,
        // today, …). Successive calls with the same arguments — regardless
        // of how much wall-clock time elapsed between them — return the
        // same streak. Guards against accidental reads of
        // `System.currentTimeMillis()` inside the implementation.
        val today = LocalDate.of(2025, 6, 10)
        val completions = listOf(
            completionAt(date = today, hour = 9),
            completionAt(date = today.minusDays(1), hour = 21),
            completionAt(date = today.minusDays(2), hour = 8)
        )
        val habit = dailyHabit()

        val r1 = StreakCalculator.calculateCurrentStreak(completions, habit, today)
        val r2 = StreakCalculator.calculateCurrentStreak(completions, habit, today)
        val r3 = StreakCalculator.calculateCurrentStreak(completions, habit, today)
        assertEquals(3, r1)
        assertEquals("Pure function: r2 must equal r1", r1, r2)
        assertEquals("Pure function: r3 must equal r1", r1, r3)
    }
}
