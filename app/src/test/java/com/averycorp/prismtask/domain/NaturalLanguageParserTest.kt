package com.averycorp.prismtask.domain

import com.averycorp.prismtask.core.time.TimeProvider
import com.averycorp.prismtask.data.remote.api.AdminBugReportResponse
import com.averycorp.prismtask.data.remote.api.BatchParseRequest
import com.averycorp.prismtask.data.remote.api.BatchParseResponse
import com.averycorp.prismtask.data.remote.api.BugReportMirrorResponse
import com.averycorp.prismtask.data.remote.api.BugReportStatusUpdateRequest
import com.averycorp.prismtask.data.remote.api.ChatRequest
import com.averycorp.prismtask.data.remote.api.ChatResponse
import com.averycorp.prismtask.data.remote.api.CoachingRequest
import com.averycorp.prismtask.data.remote.api.CoachingResponse
import com.averycorp.prismtask.data.remote.api.DailyBriefingRequest
import com.averycorp.prismtask.data.remote.api.DailyBriefingResponse
import com.averycorp.prismtask.data.remote.api.EisenhowerClassifyTextRequest
import com.averycorp.prismtask.data.remote.api.EisenhowerClassifyTextResponse
import com.averycorp.prismtask.data.remote.api.EisenhowerRequest
import com.averycorp.prismtask.data.remote.api.EisenhowerResponse
import com.averycorp.prismtask.data.remote.api.EveningSummaryRequest
import com.averycorp.prismtask.data.remote.api.EveningSummaryResponse
import com.averycorp.prismtask.data.remote.api.FirebaseTokenRequest
import com.averycorp.prismtask.data.remote.api.HabitCorrelationsResponse
import com.averycorp.prismtask.data.remote.api.ImportResponse
import com.averycorp.prismtask.data.remote.api.LifeCategoryClassifyTextRequest
import com.averycorp.prismtask.data.remote.api.LifeCategoryClassifyTextResponse
import com.averycorp.prismtask.data.remote.api.LoginRequest
import com.averycorp.prismtask.data.remote.api.ParseChecklistRequest
import com.averycorp.prismtask.data.remote.api.ParseChecklistResponse
import com.averycorp.prismtask.data.remote.api.ParseImportRequest
import com.averycorp.prismtask.data.remote.api.ParseImportResponse
import com.averycorp.prismtask.data.remote.api.ParseRequest
import com.averycorp.prismtask.data.remote.api.ParsedTaskResponse
import com.averycorp.prismtask.data.remote.api.PomodoroCoachingRequest
import com.averycorp.prismtask.data.remote.api.PomodoroCoachingResponse
import com.averycorp.prismtask.data.remote.api.PomodoroRequest
import com.averycorp.prismtask.data.remote.api.PomodoroResponse
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.ReengagementRequest
import com.averycorp.prismtask.data.remote.api.ReengagementResponse
import com.averycorp.prismtask.data.remote.api.RefreshRequest
import com.averycorp.prismtask.data.remote.api.RegisterRequest
import com.averycorp.prismtask.data.remote.api.SyllabusConfirmRequest
import com.averycorp.prismtask.data.remote.api.SyllabusConfirmResponse
import com.averycorp.prismtask.data.remote.api.SyllabusParseResponse
import com.averycorp.prismtask.data.remote.api.TimeBlockRequest
import com.averycorp.prismtask.data.remote.api.TimeBlockResponse
import com.averycorp.prismtask.data.remote.api.TokenResponse
import com.averycorp.prismtask.data.remote.api.UserInfoResponse
import com.averycorp.prismtask.data.remote.api.VersionResponse
import com.averycorp.prismtask.data.remote.api.WeeklyPlanRequest
import com.averycorp.prismtask.data.remote.api.WeeklyPlanResponse
import com.averycorp.prismtask.data.remote.api.WeeklyReviewRequest
import com.averycorp.prismtask.data.remote.api.WeeklyReviewResponse
import com.averycorp.prismtask.data.remote.sync.SyncPullResponse
import com.averycorp.prismtask.data.remote.sync.SyncPushRequest
import com.averycorp.prismtask.data.remote.sync.SyncPushResponse
import com.averycorp.prismtask.domain.usecase.NaturalLanguageParser
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class NaturalLanguageParserTest {
    private lateinit var parser: NaturalLanguageParser
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()

    /** Stub Retrofit API — these tests only exercise the offline regex parser. */
    private val stubApi = object : PrismTaskApi {
        override suspend fun getMe(): UserInfoResponse =
            error("not used in offline parser tests")

        override suspend fun updateTier(
            request: com.averycorp.prismtask.data.remote.api.UpdateTierRequest
        ): UserInfoResponse = error("not used in offline parser tests")

        override suspend fun register(request: RegisterRequest): TokenResponse =
            error("not used in offline parser tests")

        override suspend fun login(request: LoginRequest): TokenResponse =
            error("not used in offline parser tests")

        override suspend fun firebaseLogin(request: FirebaseTokenRequest): TokenResponse =
            error("not used in offline parser tests")

        override suspend fun refresh(request: RefreshRequest): TokenResponse =
            error("not used in offline parser tests")

        override suspend fun getDeletionStatus(): com.averycorp.prismtask.data.remote.api.DeletionStatusResponse =
            error("not used in offline parser tests")

        override suspend fun requestDeletion(
            request: com.averycorp.prismtask.data.remote.api.DeletionRequest
        ): com.averycorp.prismtask.data.remote.api.DeletionStatusResponse =
            error("not used in offline parser tests")

        override suspend fun cancelDeletion(): com.averycorp.prismtask.data.remote.api.DeletionStatusResponse =
            error("not used in offline parser tests")

        override suspend fun purgeAccount(): retrofit2.Response<Unit> =
            error("not used in offline parser tests")

        override suspend fun redeemBetaCode(
            request: com.averycorp.prismtask.data.remote.api.BetaRedeemRequest
        ): com.averycorp.prismtask.data.remote.api.BetaRedeemResponse =
            error("not used in offline parser tests")

        override suspend fun parseTask(request: ParseRequest): ParsedTaskResponse =
            error("not used in offline parser tests")

        override suspend fun extractTasksFromText(
            request: com.averycorp.prismtask.data.remote.api.ExtractFromTextRequest
        ): com.averycorp.prismtask.data.remote.api.ExtractFromTextResponse =
            error("not used in offline parser tests")

        override suspend fun getVersion(): VersionResponse =
            error("not used in offline parser tests")

        override suspend fun syncPush(request: SyncPushRequest): SyncPushResponse =
            error("not used in offline parser tests")

        override suspend fun syncPull(since: String?): SyncPullResponse =
            error("not used in offline parser tests")

        override suspend fun exportJson(): ResponseBody =
            error("not used in offline parser tests")

        override suspend fun importJson(file: MultipartBody.Part, mode: String): ImportResponse =
            error("not used in offline parser tests")

        override suspend fun categorizeEisenhower(request: EisenhowerRequest): EisenhowerResponse =
            error("not used in offline parser tests")

        override suspend fun classifyEisenhowerText(
            request: EisenhowerClassifyTextRequest
        ): EisenhowerClassifyTextResponse =
            error("not used in offline parser tests")

        override suspend fun classifyLifeCategoryText(
            request: LifeCategoryClassifyTextRequest
        ): LifeCategoryClassifyTextResponse =
            error("not used in offline parser tests")

        override suspend fun planPomodoro(request: PomodoroRequest): PomodoroResponse =
            error("not used in offline parser tests")

        override suspend fun getPomodoroCoaching(
            request: PomodoroCoachingRequest
        ): PomodoroCoachingResponse =
            error("not used in offline parser tests")

        override suspend fun getDailyBriefing(request: DailyBriefingRequest): DailyBriefingResponse =
            error("not used in offline parser tests")

        override suspend fun getWeeklyPlan(request: WeeklyPlanRequest): WeeklyPlanResponse =
            error("not used in offline parser tests")

        override suspend fun getTimeBlock(request: TimeBlockRequest): TimeBlockResponse =
            error("not used in offline parser tests")

        override suspend fun parseBatchCommand(request: BatchParseRequest): BatchParseResponse =
            error("not used in offline parser tests")

        override suspend fun aiChat(request: ChatRequest): ChatResponse =
            error("not used in offline parser tests")

        override suspend fun getEveningSummary(request: EveningSummaryRequest): EveningSummaryResponse =
            error("not used in offline parser tests")

        override suspend fun getReengagementNudge(request: ReengagementRequest): ReengagementResponse =
            error("not used in offline parser tests")

        override suspend fun getCoaching(request: CoachingRequest): CoachingResponse =
            error("not used in offline parser tests")

        override suspend fun parseImport(request: ParseImportRequest): ParseImportResponse =
            error("not used in offline parser tests")

        override suspend fun parseChecklist(request: ParseChecklistRequest): ParseChecklistResponse =
            error("not used in offline parser tests")

        override suspend fun submitBugReport(body: Map<String, Any?>): BugReportMirrorResponse =
            error("not used in offline parser tests")

        override suspend fun parseSyllabus(file: MultipartBody.Part): SyllabusParseResponse =
            error("not used in offline parser tests")

        override suspend fun confirmSyllabus(request: SyllabusConfirmRequest): SyllabusConfirmResponse =
            error("not used in offline parser tests")

        override suspend fun getWeeklyReview(request: WeeklyReviewRequest): WeeklyReviewResponse =
            error("not used in offline parser tests")

        override suspend fun listBugReports(
            statusFilter: String?,
            severity: String?,
            page: Int,
            limit: Int
        ): List<AdminBugReportResponse> =
            error("not used in offline parser tests")

        override suspend fun updateBugReportStatus(
            reportId: String,
            body: BugReportStatusUpdateRequest
        ): AdminBugReportResponse =
            error("not used in offline parser tests")

        override suspend fun getHabitCorrelations(): HabitCorrelationsResponse =
            error("not used in offline parser tests")

        override suspend fun automationComplete(
            request: com.averycorp.prismtask.data.remote.api.AutomationCompleteRequest
        ): com.averycorp.prismtask.data.remote.api.AutomationCompleteResponse =
            error("not used in offline parser tests")

        override suspend fun automationSummarize(
            request: com.averycorp.prismtask.data.remote.api.AutomationSummarizeRequest
        ): com.averycorp.prismtask.data.remote.api.AutomationSummarizeResponse =
            error("not used in offline parser tests")
    }

    private fun dateMillis(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun timeMillis(date: LocalDate, time: LocalTime): Long =
        date
            .atTime(time)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    // Pin the parser's clock to the exact start of `today` (00:00:00) so
    // time-of-day tests like "at 3pm" or "at midnight" always resolve to
    // today, not tomorrow. Without this, the logical-day boundary rolls
    // past target times forward whenever the CI wall clock is past them;
    // using the very first instant of the day keeps every target time
    // inside the current logical day and >= now.
    private val fixedTimeProvider = object : TimeProvider {
        override fun now(): Instant =
            today.atStartOfDay(zone).toInstant()

        override fun zone(): ZoneId = zone
    }

    @Before
    fun setup() {
        parser = NaturalLanguageParser(stubApi, timeProvider = fixedTimeProvider)
    }

    // Basic extraction tests

    @Test
    fun test_simpleTitle() {
        val result = parser.parse("Buy milk")
        assertEquals("Buy milk", result.title)
        assertNull(result.dueDate)
        assertTrue(result.tags.isEmpty())
        assertEquals(0, result.priority)
    }

    @Test
    fun test_titleWithTag() {
        val result = parser.parse("Buy milk #groceries")
        assertEquals("Buy milk", result.title)
        assertEquals(listOf("groceries"), result.tags)
    }

    @Test
    fun test_titleWithMultipleTags() {
        val result = parser.parse("Review PR #work #urgent")
        assertEquals("Review PR", result.title)
        assertEquals(listOf("work", "urgent"), result.tags)
    }

    @Test
    fun test_titleWithProject() {
        val result = parser.parse("Fix bug @PrismTask")
        assertEquals("Fix bug", result.title)
        assertEquals("PrismTask", result.projectName)
    }

    @Test
    fun test_titleWithPriority() {
        val result = parser.parse("Call doctor !high")
        assertEquals("Call doctor", result.title)
        assertEquals(3, result.priority)
    }

    @Test
    fun test_titleWithBangShorthand() {
        val result = parser.parse("Call doctor !!")
        assertEquals("Call doctor", result.title)
        assertEquals(2, result.priority)
    }

    @Test
    fun test_titleWithEverything() {
        val result = parser.parse("Buy groceries tomorrow at 3pm #errands @home !high")
        assertEquals("Buy groceries", result.title)
        assertEquals(dateMillis(today.plusDays(1)), result.dueDate)
        assertEquals(timeMillis(today.plusDays(1), LocalTime.of(15, 0)), result.dueTime)
        assertEquals(listOf("errands"), result.tags)
        assertEquals("home", result.projectName)
        assertEquals(3, result.priority)
    }

    // Date parsing tests

    @Test
    fun test_today() {
        val result = parser.parse("Do laundry today")
        assertEquals("Do laundry", result.title)
        assertEquals(dateMillis(today), result.dueDate)
    }

    @Test
    fun test_tomorrow() {
        val result = parser.parse("Pay bills tomorrow")
        assertEquals("Pay bills", result.title)
        assertEquals(dateMillis(today.plusDays(1)), result.dueDate)
    }

    @Test
    fun test_tmrw() {
        val result = parser.parse("Pay bills tmrw")
        assertEquals("Pay bills", result.title)
        assertEquals(dateMillis(today.plusDays(1)), result.dueDate)
    }

    @Test
    fun test_nextMonday() {
        val result = parser.parse("Meeting next monday")
        val expected = today.with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
        val nextMonday = if (expected.isBefore(today.plusDays(7))) expected.plusWeeks(1) else expected
        assertEquals("Meeting", result.title)
        assertEquals(dateMillis(nextMonday), result.dueDate)
    }

    @Test
    fun test_dayName() {
        val result = parser.parse("Meeting wednesday")
        val expected = today.with(TemporalAdjusters.next(java.time.DayOfWeek.WEDNESDAY))
        assertEquals("Meeting", result.title)
        assertEquals(dateMillis(expected), result.dueDate)
    }

    @Test
    fun test_inNDays() {
        val result = parser.parse("Review in 3 days")
        assertEquals("Review", result.title)
        assertEquals(dateMillis(today.plusDays(3)), result.dueDate)
    }

    @Test
    fun test_inNWeeks() {
        val result = parser.parse("Deploy in 2 weeks")
        assertEquals("Deploy", result.title)
        assertEquals(dateMillis(today.plusWeeks(2)), result.dueDate)
    }

    @Test
    fun test_absoluteDate() {
        val result = parser.parse("Deadline jan 15")
        var expected = LocalDate.of(today.year, 1, 15)
        if (expected.isBefore(today)) expected = expected.plusYears(1)
        assertEquals("Deadline", result.title)
        assertEquals(dateMillis(expected), result.dueDate)
    }

    @Test
    fun test_slashDate() {
        val result = parser.parse("Due 5/20")
        var expected = LocalDate.of(today.year, 5, 20)
        if (expected.isBefore(today)) expected = expected.plusYears(1)
        assertEquals("Due", result.title)
        assertEquals(dateMillis(expected), result.dueDate)
    }

    // Time parsing tests
    //
    // These rely on the parser's injected [timeProvider] being pinned to
    // 00:01 local of [today] (see setup()). Without the pin, the logical-day
    // boundary rolls past target times forward to tomorrow whenever the CI
    // wall clock is past the target time.

    @Test
    fun test_atTime() {
        val result = parser.parse("Call at 3pm")
        assertEquals("Call", result.title)
        assertEquals(timeMillis(today, LocalTime.of(15, 0)), result.dueTime)
    }

    @Test
    fun test_at24hr() {
        val result = parser.parse("Deploy at 15:00")
        assertEquals("Deploy", result.title)
        assertEquals(timeMillis(today, LocalTime.of(15, 0)), result.dueTime)
    }

    @Test
    fun test_atTimeWithMinutes() {
        val result = parser.parse("Meeting at 2:30pm")
        assertEquals("Meeting", result.title)
        assertEquals(timeMillis(today, LocalTime.of(14, 30)), result.dueTime)
    }

    @Test
    fun test_noon() {
        val result = parser.parse("Lunch at noon")
        assertEquals("Lunch", result.title)
        assertEquals(timeMillis(today, LocalTime.NOON), result.dueTime)
    }

    @Test
    fun test_midnight() {
        val result = parser.parse("Deploy at midnight")
        assertEquals("Deploy", result.title)
        assertEquals(timeMillis(today, LocalTime.MIDNIGHT), result.dueTime)
    }

    // Recurrence tests

    @Test
    fun test_daily() {
        val result = parser.parse("Meditate every day")
        assertEquals("Meditate", result.title)
        assertEquals("daily", result.recurrenceHint)
    }

    @Test
    fun test_weekly() {
        val result = parser.parse("Team sync weekly")
        assertEquals("Team sync", result.title)
        assertEquals("weekly", result.recurrenceHint)
    }

    // Edge cases

    @Test
    fun test_emptyInput() {
        val result = parser.parse("")
        assertEquals("", result.title)
        assertNull(result.dueDate)
        assertTrue(result.tags.isEmpty())
    }

    @Test
    fun test_onlyTags() {
        val result = parser.parse("#work #urgent")
        assertEquals("", result.title)
        assertEquals(listOf("work", "urgent"), result.tags)
    }

    @Test
    fun test_hashInMiddle() {
        val result = parser.parse("C# programming")
        assertEquals("C# programming", result.title)
        assertTrue(result.tags.isEmpty())
    }

    @Test
    fun test_emailNotProject() {
        val result = parser.parse("Email john@gmail.com")
        assertEquals("Email john@gmail.com", result.title)
        assertNull(result.projectName)
    }

    @Test
    fun test_priorityUrgent() {
        val result = parser.parse("Fix server !urgent")
        assertEquals(4, result.priority)
    }

    @Test
    fun test_urgentPriority() {
        val result = parser.parse("Fix now !urgent")
        assertEquals(4, result.priority)
    }

    @Test
    fun test_numberedPriority() {
        val result = parser.parse("Task !3")
        assertEquals(3, result.priority)
    }

    @Test
    fun test_fourBangs() {
        val result = parser.parse("Task !!!!")
        assertEquals(4, result.priority)
    }

    @Test
    fun test_lowPriority() {
        val result = parser.parse("Someday !low")
        assertEquals(1, result.priority)
    }

    @Test
    fun test_complexInput() {
        val result = parser.parse("Deploy v2.0 in 2 weeks !urgent #release @backend")
        assertEquals("Deploy v2.0", result.title)
        assertEquals(dateMillis(today.plusWeeks(2)), result.dueDate)
        assertEquals(4, result.priority)
        assertEquals(listOf("release"), result.tags)
        assertEquals("backend", result.projectName)
    }

    @Test
    fun test_timeWithoutDate_defaultsToToday() {
        val result = parser.parse("Call dentist at 3pm")
        assertEquals(dateMillis(today), result.dueDate)
        assertEquals(timeMillis(today, LocalTime.of(15, 0)), result.dueTime)
    }

    @Test
    fun test_titleWithNumber_notConfusedAsDate() {
        val result = parser.parse("Read chapter 5 !low #reading")
        assertEquals("Read chapter 5", result.title)
        assertEquals(1, result.priority)
        assertEquals(listOf("reading"), result.tags)
        assertNull(result.dueDate)
    }

    @Test
    fun test_colonAndCommasPreserved() {
        val result = parser.parse("Groceries: eggs, milk, bread #errands")
        assertEquals("Groceries: eggs, milk, bread", result.title)
        assertEquals(listOf("errands"), result.tags)
    }

    @Test
    fun test_whitespaceOnly() {
        val result = parser.parse("   ")
        assertEquals("", result.title)
    }

    @Test
    fun test_monthly() {
        val result = parser.parse("Pay rent monthly")
        assertEquals("Pay rent", result.title)
        assertEquals("monthly", result.recurrenceHint)
    }

    // Life Category tags (v1.4.0 V1)

    @Test
    fun test_workTagSetsLifeCategory() {
        val result = parser.parse("Prep slides #work")
        assertEquals("Prep slides", result.title)
        assertEquals(listOf("work"), result.tags)
        assertEquals("WORK", result.lifeCategory)
    }

    @Test
    fun test_personalTagSetsLifeCategory() {
        val result = parser.parse("Grocery run #personal")
        assertEquals("Grocery run", result.title)
        assertEquals("PERSONAL", result.lifeCategory)
    }

    @Test
    fun test_selfCareHyphenatedTagSetsLifeCategory() {
        val result = parser.parse("Meditate #self-care")
        assertEquals("Meditate", result.title)
        assertEquals("SELF_CARE", result.lifeCategory)
        assertTrue(result.tags.contains("self-care"))
    }

    @Test
    fun test_healthTagSetsLifeCategory() {
        val result = parser.parse("Pharmacy run #health")
        assertEquals("Pharmacy run", result.title)
        assertEquals("HEALTH", result.lifeCategory)
    }

    @Test
    fun test_noCategoryTagLeavesLifeCategoryNull() {
        val result = parser.parse("Buy milk #groceries")
        assertNull(result.lifeCategory)
    }

    // Task Mode hashtags (Work / Play / Relax — see docs/WORK_PLAY_RELAX.md)

    @Test
    fun test_workModeHashtagSetsTaskMode() {
        val result = parser.parse("Ship the release notes #work-mode")
        assertEquals("Ship the release notes", result.title)
        assertEquals("WORK", result.taskMode)
        // Mode tag is NOT promoted into the regular tag list — it's a
        // separate dimension, not a tag.
        assertFalse(result.tags.contains("work-mode"))
    }

    @Test
    fun test_playModeHashtagSetsTaskMode() {
        val result = parser.parse("Pickup basketball #play-mode")
        assertEquals("Pickup basketball", result.title)
        assertEquals("PLAY", result.taskMode)
    }

    @Test
    fun test_relaxModeHashtagSetsTaskMode() {
        val result = parser.parse("Long bath #relax-mode")
        assertEquals("Long bath", result.title)
        assertEquals("RELAX", result.taskMode)
    }

    @Test
    fun test_modeAndCategoryAreOrthogonal() {
        // A health task can be in any mode — see docs/WORK_PLAY_RELAX.md.
        val result = parser.parse("Hike with friends #health #play-mode")
        assertEquals("HEALTH", result.lifeCategory)
        assertEquals("PLAY", result.taskMode)
    }

    @Test
    fun test_noModeHashtagLeavesTaskModeNull() {
        val result = parser.parse("Buy milk #groceries")
        assertNull(result.taskMode)
    }

    // ──────────────────────────────────────────────────────────────────
    // Cognitive Load hashtags (Easy / Medium / Hard — see docs/COGNITIVE_LOAD.md)

    @Test
    fun test_easyLoadHashtagSetsCognitiveLoad() {
        val result = parser.parse("Quick reply to mom #easy-load")
        assertEquals("Quick reply to mom", result.title)
        assertEquals("EASY", result.cognitiveLoad)
        // Load tag is NOT promoted into the regular tag list — separate
        // dimension, not a tag (parallel to mode hashtag rule).
        assertFalse(result.tags.contains("easy-load"))
    }

    @Test
    fun test_mediumLoadHashtagSetsCognitiveLoad() {
        val result = parser.parse("Review the PR #medium-load")
        assertEquals("Review the PR", result.title)
        assertEquals("MEDIUM", result.cognitiveLoad)
    }

    @Test
    fun test_hardLoadHashtagSetsCognitiveLoad() {
        val result = parser.parse("Start the recommendation letter #hard-load")
        assertEquals("Start the recommendation letter", result.title)
        assertEquals("HARD", result.cognitiveLoad)
    }

    @Test
    fun test_loadIsOrthogonalToModeAndCategory() {
        // A health-mode-easy task is a real shape — see docs/COGNITIVE_LOAD.md.
        val result = parser.parse("Pickup basketball #health #play-mode #easy-load")
        assertEquals("HEALTH", result.lifeCategory)
        assertEquals("PLAY", result.taskMode)
        assertEquals("EASY", result.cognitiveLoad)
    }

    @Test
    fun test_noLoadHashtagLeavesCognitiveLoadNull() {
        val result = parser.parse("Buy milk #groceries")
        assertNull(result.cognitiveLoad)
    }

    // ── Start-of-Day-aware "today"/"tomorrow" resolution ───────────────────
    //
    // With SoD = 04:00, a user typing "today" at 02:00 still means yesterday's
    // calendar date — habits/streaks/Today filter all roll over at SoD, so NLP
    // must agree. Regression cover for the bug where the parser used calendar
    // midnight to resolve "today" while every other surface used logical day.

    @Test
    fun test_todayBeforeStartOfDayResolvesToPreviousCalendarDate() {
        val sodHour = 4
        val sodMinute = 0
        // Pin "now" to 02:00 local on the chosen day — before SoD, so the
        // logical day is the previous calendar date.
        val pinnedDay = LocalDate.of(2026, 5, 15)
        val nowAt2am = pinnedDay.atTime(2, 0).atZone(zone).toInstant()
        val provider = object : com.averycorp.prismtask.data.preferences.StartOfDayProvider {
            override suspend fun current() =
                com.averycorp.prismtask.data.preferences.StartOfDay(
                    hour = sodHour,
                    minute = sodMinute,
                    hasBeenSet = true
                )
        }
        val sodParser = NaturalLanguageParser(
            stubApi,
            startOfDayProvider = provider,
            timeProvider = object : TimeProvider {
                override fun now(): Instant = nowAt2am
                override fun zone(): ZoneId = zone
            }
        )

        val result = sodParser.parse("Buy milk today")

        val expected = LocalDate.of(2026, 5, 14)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, result.dueDate)
    }

    @Test
    fun test_tomorrowBeforeStartOfDayResolvesToCurrentCalendarDate() {
        // 02:00 with SoD = 04:00 → logical today = May 14, so "tomorrow" =
        // May 15 (today's calendar date), not May 16.
        val pinnedDay = LocalDate.of(2026, 5, 15)
        val nowAt2am = pinnedDay.atTime(2, 0).atZone(zone).toInstant()
        val provider = object : com.averycorp.prismtask.data.preferences.StartOfDayProvider {
            override suspend fun current() =
                com.averycorp.prismtask.data.preferences.StartOfDay(
                    hour = 4,
                    minute = 0,
                    hasBeenSet = true
                )
        }
        val sodParser = NaturalLanguageParser(
            stubApi,
            startOfDayProvider = provider,
            timeProvider = object : TimeProvider {
                override fun now(): Instant = nowAt2am
                override fun zone(): ZoneId = zone
            }
        )

        val result = sodParser.parse("Buy milk tomorrow")

        val expected = LocalDate.of(2026, 5, 15)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, result.dueDate)
    }

    @Test
    fun test_todayAfterStartOfDayResolvesToCurrentCalendarDate() {
        // 06:00 with SoD = 04:00 → already past SoD, so logical today =
        // calendar today. Regression-protects the no-rollover branch.
        val pinnedDay = LocalDate.of(2026, 5, 15)
        val nowAt6am = pinnedDay.atTime(6, 0).atZone(zone).toInstant()
        val provider = object : com.averycorp.prismtask.data.preferences.StartOfDayProvider {
            override suspend fun current() =
                com.averycorp.prismtask.data.preferences.StartOfDay(
                    hour = 4,
                    minute = 0,
                    hasBeenSet = true
                )
        }
        val sodParser = NaturalLanguageParser(
            stubApi,
            startOfDayProvider = provider,
            timeProvider = object : TimeProvider {
                override fun now(): Instant = nowAt6am
                override fun zone(): ZoneId = zone
            }
        )

        val result = sodParser.parse("Buy milk today")

        val expected = LocalDate.of(2026, 5, 15)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, result.dueDate)
    }
}
