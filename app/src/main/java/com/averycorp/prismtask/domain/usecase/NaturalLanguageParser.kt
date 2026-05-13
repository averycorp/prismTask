package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.core.time.SystemTimeProvider
import com.averycorp.prismtask.core.time.TimeProvider
import com.averycorp.prismtask.data.preferences.StartOfDay
import com.averycorp.prismtask.data.preferences.StartOfDayProvider
import com.averycorp.prismtask.data.remote.api.ExtractFromTextRequest
import com.averycorp.prismtask.data.remote.api.ExtractedTaskCandidateResponse
import com.averycorp.prismtask.data.remote.api.ParseRequest
import com.averycorp.prismtask.data.remote.api.ParsedTaskResponse
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import com.averycorp.prismtask.core.time.DayBoundary as LogicalDayBoundary

data class ParsedTask(
    val title: String,
    val dueDate: Long? = null,
    val dueTime: Long? = null,
    val tags: List<String> = emptyList(),
    val projectName: String? = null,
    val priority: Int = 0,
    val recurrenceHint: String? = null,
    /**
     * Set when the input starts with a "/templatename" or "template:templatename"
     * shortcut. The value is the raw query portion (with no leading prefix or
     * whitespace). QuickAddViewModel fuzzy-matches this against the template
     * library and either creates the task directly or surfaces a
     * disambiguation popup.
     */
    val templateQuery: String? = null,
    /**
     * Work-Life Balance category extracted from category tags like `#work`,
     * `#self-care`, `#personal`, `#health`. Stored as the [com.averycorp.prismtask.domain.model.LifeCategory]
     * enum name so ViewModels can route it to TaskEntity.lifeCategory. Null
     * when no category tag was present (or the classifier will take over).
     */
    val lifeCategory: String? = null,
    /**
     * Reward / output mode extracted from `#work-mode`, `#play-mode`, or
     * `#relax-mode`. Stored as the [com.averycorp.prismtask.domain.model.TaskMode]
     * enum name. Orthogonal to [lifeCategory] — see `docs/WORK_PLAY_RELAX.md`.
     * Null when no mode tag was present.
     */
    val taskMode: String? = null,
    /**
     * Start-friction load extracted from `#easy-load`, `#medium-load`, or
     * `#hard-load`. Stored as the
     * [com.averycorp.prismtask.domain.model.CognitiveLoad] enum name.
     * Orthogonal to [lifeCategory] / [taskMode] — see
     * `docs/COGNITIVE_LOAD.md`. Null when no load tag was present.
     */
    val cognitiveLoad: String? = null
)

@Singleton
class NaturalLanguageParser
@Inject
constructor(
    private val api: PrismTaskApi,
    private val startOfDayProvider: StartOfDayProvider = DefaultStartOfDayProvider,
    private val timeProvider: TimeProvider = SystemTimeProvider()
) {
    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * Reads the user's current Start of Day synchronously. Used from the
     * offline regex [parse] code path which cannot suspend. DataStore reads
     * are in-memory once the cache is warm, which it always is by the time
     * NLP runs (MainActivity reads SoD at startup), so the `runBlocking`
     * here does not actually block on I/O.
     */
    private fun currentStartOfDay(): StartOfDay = runBlocking {
        startOfDayProvider.current()
    }

    companion object {
        /**
         * Fallback provider used when [NaturalLanguageParser] is constructed
         * without DI (e.g. in unit tests that only exercise the offline regex
         * parser with its default midnight SoD). Returns
         * [StartOfDay] with hour=0, minute=0.
         */
        private val DefaultStartOfDayProvider = object : StartOfDayProvider {
            override suspend fun current(): StartOfDay = StartOfDay()
        }

        /**
         * Mirrors the backend Pydantic cap (`ExtractFromTextRequest.text`
         * `max_length=10_000`) so [extractFromText] truncates client-side
         * before we send a request the server is guaranteed to reject.
         */
        const val EXTRACT_INPUT_MAX_CHARS: Int = 10_000
    }

    /**
     * API-first parse: calls the backend `/api/v1/tasks/parse` endpoint, and
     * falls back to the offline regex [parse] if the network call fails for
     * any reason (no connectivity, server error, parse error, etc.).
     *
     * Use this from coroutine contexts (ViewModels). The synchronous [parse]
     * method remains available for callers that cannot suspend (e.g. flow
     * `map` operators that build live previews).
     */
    suspend fun parseRemote(input: String): ParsedTask = withContext(Dispatchers.IO) {
        // Template shortcuts are resolved locally — routing them through the
        // API would waste a round-trip and the server doesn't know about
        // the user's template library anyway.
        extractTemplateQuery(input)?.let { query ->
            return@withContext ParsedTask(title = query, templateQuery = query)
        }
        try {
            // Forward the user's Start-of-Day so the backend resolves
            // "today"/"tomorrow" against the user's logical day (matching
            // the offline regex parser).
            val sod = currentStartOfDay()
            val response = api.parseTask(
                ParseRequest(
                    text = input,
                    startOfDayHour = sod.hour,
                    startOfDayMinute = sod.minute
                )
            )
            response.toParsedTask(fallbackTitle = input)
        } catch (_: Exception) {
            parse(input)
        }
    }

    /**
     * API-first multi-task extraction: posts [input] to
     * `/api/v1/ai/tasks/extract-from-text` and returns the structured
     * task candidates Haiku produces (title + due date + priority +
     * project + confidence). On any failure — Pro gate off, network error,
     * Retrofit deserialization failure, server 5xx, etc. — falls back to
     * the offline [ConversationTaskExtractor] regex extractor so the user
     * still sees something.
     *
     * Per the multi-task creation audit (Phase B / PR-B), input is
     * truncated to [EXTRACT_INPUT_MAX_CHARS] (= 10,000) to match the
     * backend Pydantic cap (`ExtractFromTextRequest.text` max_length).
     * Empty / blank input returns an empty list without making any call.
     *
     * [isProEnabled] is invoked once at the start; passing `false`
     * immediately routes to the regex extractor without a network call.
     * The default `{ true }` lets unit tests and any caller that has
     * already gated externally use this method without ceremony.
     */
    suspend fun extractFromText(
        input: String,
        source: String? = null,
        isProEnabled: () -> Boolean = { true }
    ): List<ExtractedTask> = withContext(Dispatchers.IO) {
        if (input.isBlank()) return@withContext emptyList()
        val truncated = if (input.length > EXTRACT_INPUT_MAX_CHARS) {
            input.substring(0, EXTRACT_INPUT_MAX_CHARS)
        } else {
            input
        }
        if (!isProEnabled()) {
            return@withContext regexFallback(truncated, source)
        }
        try {
            val response = api.extractTasksFromText(
                ExtractFromTextRequest(text = truncated, source = source)
            )
            response.tasks
                .mapNotNull { it.toExtractedTask(source) }
                .ifEmpty { regexFallback(truncated, source) }
        } catch (_: Exception) {
            regexFallback(truncated, source)
        }
    }

    private fun regexFallback(input: String, source: String?): List<ExtractedTask> =
        ConversationTaskExtractor().extract(input, source)

    private fun ExtractedTaskCandidateResponse.toExtractedTask(
        source: String?
    ): ExtractedTask? {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return null
        val dueMillis = suggestedDueDate
            ?.let { parseIsoDate(it) }
            ?.atStartOfDay(zone)
            ?.toInstant()
            ?.toEpochMilli()
        return ExtractedTask(
            title = cleanTitle,
            confidence = confidence.coerceIn(0f, 1f),
            source = source,
            suggestedPriority = suggestedPriority.coerceIn(0, 4),
            suggestedDueDate = dueMillis,
            suggestedProject = suggestedProject?.takeIf { it.isNotBlank() }
        )
    }

    private fun ParsedTaskResponse.toParsedTask(fallbackTitle: String): ParsedTask {
        val date = dueDate?.let { parseIsoDate(it) }
        val timeMillis = dueTime?.let { parseDateTimeMillis(date, it) }
        val dateMillis = date?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        return ParsedTask(
            title = title.ifBlank { fallbackTitle.trim() },
            dueDate = dateMillis,
            dueTime = timeMillis,
            tags = tagSuggestions ?: emptyList(),
            projectName = projectSuggestion,
            priority = priority ?: 0,
            recurrenceHint = recurrenceHint
        )
    }

    private fun parseIsoDate(value: String): LocalDate? = try {
        LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun parseDateTimeMillis(date: LocalDate?, time: String): Long? {
        val parsedTime = try {
            LocalTime.parse(time, DateTimeFormatter.ISO_LOCAL_TIME)
        } catch (_: DateTimeParseException) {
            return null
        }
        if (date == null) {
            // Time-only Haiku result — anchor it to the user's *logical* day
            // using the configured Start of Day, not to the calendar today.
            // This makes "remind me at 2 AM" with SoD=4 AM correctly stay in
            // today's logical day instead of getting flipped to tomorrow.
            val sod = currentStartOfDay()
            return LogicalDayBoundary.resolveAmbiguousTime(
                now = timeProvider.now(),
                targetHour = parsedTime.hour,
                targetMinute = parsedTime.minute,
                sodHour = sod.hour,
                sodMinute = sod.minute,
                zone = zone
            ).toEpochMilli()
        }
        return date
            .atTime(parsedTime)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * If [input] begins with a "/templatename" or "template:templatename"
     * shortcut, returns the raw query portion (trimmed, with no prefix).
     * Returns null otherwise. Used by QuickAddViewModel to dispatch to a
     * template match instead of the regular task-creation pipeline.
     */
    fun extractTemplateQuery(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        // Explicit `template:query` form — supports anything after the colon.
        val colonRegex = Regex("""(?i)^template:\s*(\S.*)$""")
        colonRegex.matchEntire(trimmed)?.let { return it.groupValues[1].trim() }
        // Slash shortcut `/query` — single leading slash, then a name that
        // doesn't start with another slash (so "/" alone is ignored and
        // doesn't clobber plain text starting with "/").
        if (trimmed.startsWith("/") && trimmed.length >= 2 && trimmed[1] != '/') {
            return trimmed.substring(1).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    fun parse(input: String): ParsedTask {
        // Template shortcut detection runs before any other parsing so the
        // query isn't mangled by tag/project/priority regexes.
        extractTemplateQuery(input)?.let { query ->
            return ParsedTask(title = query, templateQuery = query)
        }

        var text = input
        // Derive `today` as the user's *logical* day (Start-of-Day-aware), not
        // the calendar day. With SoD = 4 AM, a user typing "buy milk today" at
        // 2 AM still means yesterday's calendar date — habits, streaks, and
        // the Today filter all already roll over at SoD, so NLP must agree.
        // The injected timeProvider keeps pinned-clock tests deterministic.
        val sod = currentStartOfDay()
        val today = LogicalDayBoundary.logicalDate(timeProvider.now(), sod.hour, sod.minute, zone)

        var lifeCategory: String? = null

        // 1a. Hyphenated life-category tag (v1.4.0 V1): #self-care / #self care.
        // Handled before the standard tag regex because the dash is not part of
        // \w+ so the generic regex would only capture "self" and leave "-care"
        // in the title. We also push "self-care" into the regular tag list so
        // the existing tag-filtering UI keeps working.
        val selfCareRegex = Regex("""(?i)(?:^|(?<=\s))#(self[-_]?care)(?=\s|$)""")
        val selfCareMatch = selfCareRegex.find(text)
        val hyphenatedSelfCare = selfCareMatch != null
        if (hyphenatedSelfCare) {
            lifeCategory = "SELF_CARE"
            text = selfCareRegex.replace(text, "")
        }

        // 1a-bis. Hyphenated task-mode tags (#work-mode / #play-mode /
        // #relax-mode). Stripped before the generic #\w+ regex so the dash
        // doesn't terminate matching mid-token. Mode tags are intentionally
        // NOT pushed into the regular tag list — mode is a separate dimension
        // (see docs/WORK_PLAY_RELAX.md), not a tag.
        var taskMode: String? = null
        val modeRegex = Regex("""(?i)(?:^|(?<=\s))#(work|play|relax)[-_]mode(?=\s|$)""")
        val modeMatch = modeRegex.find(text)
        if (modeMatch != null) {
            taskMode = when (modeMatch.groupValues[1].lowercase(Locale.ROOT)) {
                "work" -> "WORK"
                "play" -> "PLAY"
                "relax" -> "RELAX"
                else -> null
            }
            text = modeRegex.replace(text, "")
        }

        // 1a-ter. Hyphenated cognitive-load tags (#easy-load / #medium-load /
        // #hard-load). Same hyphenation strategy as task-mode — stripped
        // before the generic #\w+ regex. Load tags are NOT promoted into
        // the regular tag list (see docs/COGNITIVE_LOAD.md).
        //
        // Extracted into a helper to keep the cyclomatic complexity of
        // `parse` under detekt's threshold (65) — the parallel mode tag
        // path is intentionally left inline so the detekt drift is
        // localized to the dimension that pushed it past.
        val (textAfterLoad, cognitiveLoad) = extractCognitiveLoadTag(text)
        text = textAfterLoad

        // 1. Tags — #word but not C# (must be preceded by space or at start)
        val tags = mutableListOf<String>()
        val tagRegex = Regex("""(?:^|(?<=\s))#(\w+)""")
        tags.addAll(tagRegex.findAll(text).map { it.groupValues[1] })
        text = tagRegex.replace(text, "")
        if (hyphenatedSelfCare) tags.add("self-care")

        // 1b. Category tags that also live in the regular tag list
        // (#work, #personal, #health, #selfcare). These remain as tags so the
        // existing tag-filter UI still works — we only *additionally* set
        // [lifeCategory]. If the user already chose SELF_CARE via the
        // hyphenated form above we leave the first winner alone.
        if (lifeCategory == null) {
            for (tag in tags) {
                val mapped = when (tag.lowercase(Locale.ROOT)) {
                    "work" -> "WORK"
                    "personal" -> "PERSONAL"
                    "health" -> "HEALTH"
                    "selfcare" -> "SELF_CARE"
                    else -> null
                }
                if (mapped != null) {
                    lifeCategory = mapped
                    break
                }
            }
        }

        // 2. Projects — @word but not emails (@ must be preceded by space or at start)
        var projectName: String? = null
        val projectRegex = Regex("""(?:^|(?<=\s))@(\w+)""")
        val projectMatch = projectRegex.find(text)
        if (projectMatch != null) {
            projectName = projectMatch.groupValues[1]
            text = projectRegex.replace(text, "")
        }

        // 3. Priority — use space/start boundary since \b doesn't work before !
        var priority = 0
        val priorityPatterns = listOf(
            Regex("""(?i)(?:^|(?<=\s))!urgent(?:\s|$)""") to 4,
            Regex("""(?:^|(?<=\s))!{4}(?:\s|$)""") to 4,
            Regex("""(?:^|(?<=\s))!4(?:\s|$)""") to 4,
            Regex("""(?i)(?:^|(?<=\s))!high(?:\s|$)""") to 3,
            Regex("""(?:^|(?<=\s))!{3}(?!!)(?:\s|$)""") to 3,
            Regex("""(?:^|(?<=\s))!3(?:\s|$)""") to 3,
            Regex("""(?i)(?:^|(?<=\s))!med(?:ium)?(?:\s|$)""") to 2,
            Regex("""(?:^|(?<=\s))!{2}(?!!)(?:\s|$)""") to 2,
            Regex("""(?:^|(?<=\s))!2(?:\s|$)""") to 2,
            Regex("""(?i)(?:^|(?<=\s))!low(?:\s|$)""") to 1,
            Regex("""(?:^|(?<=\s))!1(?:\s|$)""") to 1,
            Regex("""(?:^|(?<=\s))!(?!\w|!)(?:\s|$)""") to 1
        )
        for ((regex, level) in priorityPatterns) {
            if (regex.containsMatchIn(text)) {
                priority = level
                text = regex.replace(text, "")
                break
            }
        }

        // 3b. Urgency-keyword inference — only when no explicit `!` priority
        // was set above. Keywords are left in the title (they are natural
        // language the user probably wants to keep, e.g. "urgent client call")
        // and the highest-tier match wins. Order matters: priority-4 keywords
        // are checked before priority-3 before priority-2 so a title that
        // mentions both "urgent" and "important" lands on 4.
        if (priority == 0) {
            val urgencyPatterns = listOf(
                Regex("""(?i)\b(asap|urgent|urgently|immediately|critical)\b""") to 4,
                Regex("""(?i)\b(important|high\s+priority|high-priority)\b""") to 3,
                Regex("""(?i)\bsoon\b""") to 2
            )
            for ((regex, level) in urgencyPatterns) {
                if (regex.containsMatchIn(text)) {
                    priority = level
                    break
                }
            }
        }

        // 4. Recurrence hints (before date parsing to avoid conflicts)
        var recurrenceHint: String? = null
        val recurrencePatterns = listOf(
            Regex("""(?i)\bevery\s+day\b""") to "daily",
            Regex("""(?i)\bdaily\b""") to "daily",
            Regex("""(?i)\bevery\s+week\b""") to "weekly",
            Regex("""(?i)\bweekly\b""") to "weekly",
            Regex("""(?i)\bevery\s+month\b""") to "monthly",
            Regex("""(?i)\bmonthly\b""") to "monthly",
            Regex("""(?i)\bevery\s+year\b""") to "yearly",
            Regex("""(?i)\byearly\b""") to "yearly"
        )
        for ((regex, hint) in recurrencePatterns) {
            if (regex.containsMatchIn(text)) {
                recurrenceHint = hint
                text = regex.replace(text, "")
                break
            }
        }

        // 5. Time parsing
        var parsedTime: LocalTime? = null

        // "at noon"
        val noonRegex = Regex("""(?i)\bat\s+noon\b""")
        if (noonRegex.containsMatchIn(text)) {
            parsedTime = LocalTime.NOON
            text = noonRegex.replace(text, "")
        }
        // "at midnight"
        val midnightRegex = Regex("""(?i)\bat\s+midnight\b""")
        if (parsedTime == null && midnightRegex.containsMatchIn(text)) {
            parsedTime = LocalTime.MIDNIGHT
            text = midnightRegex.replace(text, "")
        }
        // "at 3pm", "at 3:30pm", "at 15:00"
        val timeRegex = Regex("""(?i)\bat\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""")
        if (parsedTime == null) {
            val timeMatch = timeRegex.find(text)
            if (timeMatch != null) {
                var hour = timeMatch.groupValues[1].toInt()
                val minute = timeMatch.groupValues[2].ifEmpty { "0" }.toInt()
                val ampm = timeMatch.groupValues[3].lowercase()
                when {
                    ampm == "pm" && hour < 12 -> hour += 12
                    ampm == "am" && hour == 12 -> hour = 0
                }
                parsedTime = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
                text = text.removeRange(timeMatch.range)
            }
        }

        // 6. Date parsing
        var parsedDate: LocalDate? = null

        // "today"
        val todayRegex = Regex("""(?i)\btoday\b""")
        if (todayRegex.containsMatchIn(text)) {
            parsedDate = today
            text = todayRegex.replace(text, "")
        }

        // "tomorrow" / "tmrw"
        if (parsedDate == null) {
            val tomorrowRegex = Regex("""(?i)\b(?:tomorrow|tmrw)\b""")
            if (tomorrowRegex.containsMatchIn(text)) {
                parsedDate = today.plusDays(1)
                text = tomorrowRegex.replace(text, "")
            }
        }

        // "next monday", "next tue", etc.
        if (parsedDate == null) {
            val nextDayRegex = Regex("""(?i)\bnext\s+(${dayNames()})\b""")
            val nextDayMatch = nextDayRegex.find(text)
            if (nextDayMatch != null) {
                val dow = parseDayOfWeek(nextDayMatch.groupValues[1])
                if (dow != null) {
                    // "next X" means the occurrence at least 7 days out
                    parsedDate = today.with(TemporalAdjusters.next(dow))
                    if (parsedDate?.isBefore(today.plusDays(7)) == true) {
                        parsedDate = parsedDate?.plusWeeks(1)
                    }
                    text = text.removeRange(nextDayMatch.range)
                }
            }
        }

        // "in N days/weeks/months"
        if (parsedDate == null) {
            val relativeRegex = Regex("""(?i)\bin\s+(\d+)\s+(days?|weeks?|months?)\b""")
            val relMatch = relativeRegex.find(text)
            if (relMatch != null) {
                val n = relMatch.groupValues[1].toLong()
                val unit = relMatch.groupValues[2].lowercase()
                parsedDate = when {
                    unit.startsWith("day") -> today.plusDays(n)
                    unit.startsWith("week") -> today.plusWeeks(n)
                    unit.startsWith("month") -> today.plusMonths(n)
                    else -> null
                }
                text = text.removeRange(relMatch.range)
            }
        }

        // "on the 1st" / "on the 15th"
        if (parsedDate == null) {
            val ordinalRegex = Regex("""(?i)\bon\s+the\s+(\d{1,2})(?:st|nd|rd|th)\b""")
            val ordinalMatch = ordinalRegex.find(text)
            if (ordinalMatch != null) {
                val dayOfMonth = ordinalMatch.groupValues[1].toInt()
                if (dayOfMonth in 1..31) {
                    parsedDate = if (today.dayOfMonth < dayOfMonth) {
                        today.withDayOfMonth(dayOfMonth.coerceAtMost(today.lengthOfMonth()))
                    } else {
                        today.plusMonths(1).withDayOfMonth(dayOfMonth.coerceAtMost(today.plusMonths(1).lengthOfMonth()))
                    }
                    text = text.removeRange(ordinalMatch.range)
                }
            }
        }

        // Day name without "next": "monday", "tue", etc.
        if (parsedDate == null) {
            val dayRegex = Regex("""(?i)\b(${dayNames()})\b""")
            val dayMatch = dayRegex.find(text)
            if (dayMatch != null) {
                val dow = parseDayOfWeek(dayMatch.groupValues[1])
                if (dow != null) {
                    parsedDate = today.with(TemporalAdjusters.nextOrSame(dow))
                    if (parsedDate == today) {
                        parsedDate = today.with(TemporalAdjusters.next(dow))
                    }
                    text = text.removeRange(dayMatch.range)
                }
            }
        }

        // Absolute: "jan 15", "march 3", "december 25"
        if (parsedDate == null) {
            val monthDayRegex = Regex("""(?i)\b(${monthNames()})\s+(\d{1,2})\b""")
            val monthDayMatch = monthDayRegex.find(text)
            if (monthDayMatch != null) {
                val month = parseMonth(monthDayMatch.groupValues[1])
                val day = monthDayMatch.groupValues[2].toInt()
                if (month != null && day in 1..31) {
                    var date = LocalDate.of(today.year, month, day.coerceAtMost(month.length(today.isLeapYear)))
                    if (date.isBefore(today)) {
                        date = date.plusYears(1)
                    }
                    parsedDate = date
                    text = text.removeRange(monthDayMatch.range)
                }
            }
        }

        // Slash date: "5/20", "12/25"
        if (parsedDate == null) {
            val slashRegex = Regex("""\b(\d{1,2})/(\d{1,2})\b""")
            val slashMatch = slashRegex.find(text)
            if (slashMatch != null) {
                val m = slashMatch.groupValues[1].toInt()
                val d = slashMatch.groupValues[2].toInt()
                if (m in 1..12 && d in 1..31) {
                    val month = Month.of(m)
                    var date = LocalDate.of(today.year, month, d.coerceAtMost(month.length(today.isLeapYear)))
                    if (date.isBefore(today)) {
                        date = date.plusYears(1)
                    }
                    parsedDate = date
                    text = text.removeRange(slashMatch.range)
                }
            }
        }

        // ISO date: "2026-05-15"
        if (parsedDate == null) {
            val isoRegex = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
            val isoMatch = isoRegex.find(text)
            if (isoMatch != null) {
                try {
                    parsedDate = LocalDate.of(
                        isoMatch.groupValues[1].toInt(),
                        isoMatch.groupValues[2].toInt(),
                        isoMatch.groupValues[3].toInt()
                    )
                    text = text.removeRange(isoMatch.range)
                } catch (_: Exception) {
                }
            }
        }

        // If time parsed but no date, anchor it via the logical-day SoD rule
        // rather than naively defaulting to the calendar today. This preserves
        // "remind me at 2 AM" as today's logical day even when the wall clock
        // has already crossed midnight.
        val anchoredTimeInstant = if (parsedTime != null && parsedDate == null) {
            val sod = currentStartOfDay()
            LogicalDayBoundary.resolveAmbiguousTime(
                now = timeProvider.now(),
                targetHour = parsedTime.hour,
                targetMinute = parsedTime.minute,
                sodHour = sod.hour,
                sodMinute = sod.minute,
                zone = zone
            )
        } else {
            null
        }

        if (anchoredTimeInstant != null) {
            parsedDate = anchoredTimeInstant.atZone(zone).toLocalDate()
        }

        // Convert to epoch millis
        val dueDateMillis = parsedDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val dueTimeMillis = anchoredTimeInstant?.toEpochMilli()
            ?: parsedTime?.let { time ->
                val date = parsedDate ?: today
                date
                    .atTime(time)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
            }

        // 7. Clean up title
        val title = text.trim().replace(Regex("""\s{2,}"""), " ").replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }

        return ParsedTask(
            title = title,
            dueDate = dueDateMillis,
            dueTime = dueTimeMillis,
            tags = tags,
            projectName = projectName,
            priority = priority,
            recurrenceHint = recurrenceHint,
            lifeCategory = lifeCategory,
            taskMode = taskMode,
            cognitiveLoad = cognitiveLoad
        )
    }

    /**
     * Strip a `#easy-load` / `#medium-load` / `#hard-load` hashtag from
     * [text] and return the cleaned text alongside the matched load enum
     * name (or null when no tag was present). Extracted out of `parse(...)`
     * to keep the parser's cyclomatic complexity under detekt's threshold
     * — see `docs/COGNITIVE_LOAD.md` § NLP hashtags.
     */
    private fun extractCognitiveLoadTag(text: String): Pair<String, String?> {
        val regex = Regex("""(?i)(?:^|(?<=\s))#(easy|medium|hard)[-_]load(?=\s|$)""")
        val match = regex.find(text) ?: return text to null
        val load = when (match.groupValues[1].lowercase(Locale.ROOT)) {
            "easy" -> "EASY"
            "medium" -> "MEDIUM"
            "hard" -> "HARD"
            else -> null
        }
        return regex.replace(text, "") to load
    }

    private fun dayNames(): String =
        "monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun"

    private fun parseDayOfWeek(name: String): DayOfWeek? = when (name.lowercase().take(3)) {
        "mon" -> DayOfWeek.MONDAY
        "tue" -> DayOfWeek.TUESDAY
        "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY
        "sat" -> DayOfWeek.SATURDAY
        "sun" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun monthNames(): String =
        "january|february|march|april|may|june|july|august|september|october|november|december|" +
            "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"

    private fun parseMonth(name: String): Month? = when (name.lowercase().take(3)) {
        "jan" -> Month.JANUARY
        "feb" -> Month.FEBRUARY
        "mar" -> Month.MARCH
        "apr" -> Month.APRIL
        "may" -> Month.MAY
        "jun" -> Month.JUNE
        "jul" -> Month.JULY
        "aug" -> Month.AUGUST
        "sep" -> Month.SEPTEMBER
        "oct" -> Month.OCTOBER
        "nov" -> Month.NOVEMBER
        "dec" -> Month.DECEMBER
        else -> null
    }
}
