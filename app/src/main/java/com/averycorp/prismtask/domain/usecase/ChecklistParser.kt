package com.averycorp.prismtask.domain.usecase

import android.util.Log
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.data.preferences.AuthTokenPreferences
import com.averycorp.prismtask.data.remote.api.ParseChecklistRequest
import com.averycorp.prismtask.data.remote.api.ParseChecklistResponse
import com.averycorp.prismtask.data.remote.api.ParsedChecklistTaskResponse
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

// --- Public result models ---

data class ComprehensiveImportResult(
    val course: ParsedCourse,
    val project: ParsedProject,
    val tags: List<ParsedTag>,
    val tasks: List<ChecklistParsedTask>,
    // F.8 project-import extensions. Default empty so existing schoolwork
    // callers (which ignore them) are unaffected.
    val phases: List<ParsedProjectPhaseDomain> = emptyList(),
    val risks: List<ParsedProjectRiskDomain> = emptyList(),
    val externalAnchors: List<ParsedExternalAnchorDomain> = emptyList(),
    val taskDependencies: List<ParsedTaskDependencyDomain> = emptyList()
)

data class ParsedProjectPhaseDomain(
    val name: String,
    val description: String?,
    val startDate: Long?,
    val endDate: Long?,
    val orderIndex: Int
)

data class ParsedProjectRiskDomain(
    val title: String,
    val description: String?,
    val level: String
)

data class ParsedExternalAnchorDomain(
    val title: String,
    val type: String,
    val phaseName: String?,
    val targetDate: Long?
)

data class ParsedTaskDependencyDomain(
    val blockerTitle: String,
    val blockedTitle: String
)

data class ParsedCourse(
    val code: String,
    val name: String,
    val deadline: Long?,
    val assignments: List<ParsedAssignment>
)

data class ParsedAssignment(
    val title: String,
    val dueDate: Long?,
    val time: String?,
    val type: String?,
    val completed: Boolean
)

data class ParsedProject(
    val name: String,
    val color: String,
    val icon: String
)

data class ParsedTag(
    val name: String,
    val color: String
)

data class ChecklistParsedTask(
    val title: String,
    val description: String?,
    val dueDate: Long?,
    val priority: Int,
    val completed: Boolean,
    val tags: List<String>,
    val subtasks: List<ChecklistParsedTask>,
    val estimatedMinutes: Int?,
    // F.8: name reference into ComprehensiveImportResult.phases.
    val phaseName: String? = null
)

/**
 * Parses course syllabi / comprehensive schedules by sending content to the
 * PrismTask backend, which calls Claude Haiku server-side.
 *
 * Falls back to a regex-based parser when the user is not logged in or the
 * backend call fails.
 */
@Singleton
class ChecklistParser
@Inject
constructor(
    private val api: PrismTaskApi,
    private val authTokenPreferences: AuthTokenPreferences
) {
    suspend fun parse(content: String): ComprehensiveImportResult? {
        // Try backend (Claude Haiku) first when the user is logged in
        val token = authTokenPreferences.getAccessToken()
        if (!token.isNullOrBlank()) {
            try {
                val response = api.parseChecklist(ParseChecklistRequest(content = content))
                return response.toComprehensiveImportResult()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("ChecklistParser", "Backend parse failed", e)
                // Fall through to regex
            }
        }

        // Fall back to regex → wrap in comprehensive result
        val regexCourse = parseWithRegex(content) ?: return null
        return wrapRegexResult(regexCourse)
    }

    fun parseWithRegex(content: String): ParsedCourse? = parseScheduleFormat(content) ?: parseTasksFormat(content)

    private fun wrapRegexResult(course: ParsedCourse): ComprehensiveImportResult {
        val tags = course.assignments.mapNotNull { it.type }.distinct().map { type ->
            ParsedTag(name = type, color = tagColorForType(type))
        }
        val tasks = course.assignments.map { a ->
            ChecklistParsedTask(
                title = a.title,
                description = a.time?.let { "Duration: $it" },
                dueDate = a.dueDate,
                priority = if (a.type == "exam") 4 else 0,
                completed = a.completed,
                tags = listOfNotNull(a.type),
                subtasks = emptyList(),
                estimatedMinutes = null
            )
        }
        return ComprehensiveImportResult(
            course = course,
            project = ParsedProject(
                name = if (course.code.isBlank()) course.name else "${course.code} — ${course.name}",
                color = "#4A90D9",
                icon = "\uD83D\uDCDA"
            ),
            tags = tags,
            tasks = tasks
        )
    }

    private fun tagColorForType(type: String): String = when (type) {
        "exam" -> "#EF4444"
        "video" -> "#3B82F6"
        "assignment" -> "#F59E0B"
        "code" -> "#10B981"
        "reading" -> "#8B5CF6"
        else -> "#6B7280"
    }

    // --- Regex fallback parsers ---

    private fun parseScheduleFormat(content: String): ParsedCourse? {
        if (!content.contains("SCHEDULE")) return null

        val code = extractCourseCode(content) ?: return null
        val name = extractCourseName(content) ?: code

        val assignments = mutableListOf<ParsedAssignment>()

        val dayPattern =
            Regex("""\{\s*date:\s*"([^"]+)"[^}]*?tasks:\s*\[(.*?)\]\s*\}""", RegexOption.DOT_MATCHES_ALL)
        for (dayMatch in dayPattern.findAll(content)) {
            val dateStr = dayMatch.groupValues[1]
            val tasksBlock = dayMatch.groupValues[2]

            val taskPattern =
                Regex(
                    """\{\s*id:\s*"[^"]*"\s*,\s*text:\s*"([^"]+)"\s*,\s*""" +
                        """time:\s*"([^"]+)"\s*,\s*type:\s*"([^"]+)"\s*""" +
                        """(?:,\s*done:\s*(true))?\s*\}"""
                )
            for (taskMatch in taskPattern.findAll(tasksBlock)) {
                val text = taskMatch.groupValues[1]
                val time = taskMatch.groupValues[2]
                val type = taskMatch.groupValues[3]
                val done = taskMatch.groupValues[4] == "true"

                val typePrefix = when (type) {
                    "video" -> "\u25B6 "
                    "assignment" -> "\u270E "
                    "code" -> "\u27E8/\u27E9 "
                    else -> ""
                }

                assignments.add(
                    ParsedAssignment(
                        title = "$typePrefix$text",
                        dueDate = parseDateString(dateStr),
                        time = time,
                        type = type,
                        completed = done
                    )
                )
            }
        }

        if (assignments.isEmpty()) return null
        return ParsedCourse(code = code, name = name, deadline = null, assignments = assignments)
    }

    private fun parseTasksFormat(content: String): ParsedCourse? {
        if (!content.contains("const TASKS")) return null

        val code = extractCourseCode(content) ?: return null
        val name = extractCourseName(content) ?: code

        val assignments = mutableListOf<ParsedAssignment>()

        val itemPattern = Regex(
            """\{\s*id:\s*"[^"]*"\s*,\s*date:\s*"([^"]+)"\s*,\s*""" +
                """label:\s*"([^"]+)"\s*""" +
                """(?:,\s*time:\s*"([^"]*)")?(?:\s*,\s*done:\s*(true))?""" +
                """(?:\s*,\s*off:\s*true)?(?:\s*,\s*buffer:\s*true)?\s*\}"""
        )

        for (match in itemPattern.findAll(content)) {
            val dateStr = match.groupValues[1]
            val label = match.groupValues[2]
            val time = match.groupValues[3].ifEmpty { null }
            val done = match.groupValues[4] == "true"

            val fullMatch = match.value
            if (fullMatch.contains("off: true") || label.endsWith("— OFF") || label == "OFF") continue

            val isBuffer = fullMatch.contains("buffer: true")
            if (isBuffer && (label.startsWith("BUFFER") || label == "Buffer")) continue

            assignments.add(
                ParsedAssignment(
                    title = label,
                    dueDate = parseDateString(dateStr),
                    time = time,
                    type = guessType(label),
                    completed = done
                )
            )
        }

        if (assignments.isEmpty()) return null
        return ParsedCourse(code = code, name = name, deadline = null, assignments = assignments)
    }

    private fun extractCourseCode(content: String): String? {
        val codePattern = Regex("""[A-Z]{2,5}\s*\d{4}""")
        return codePattern.find(content)?.value
    }

    private fun extractCourseName(content: String): String? {
        val h1Pattern = Regex(""">([^<]{10,80})</h1>""")
        val match = h1Pattern.find(content)
        if (match != null) return match.groupValues[1].trim()

        val titlePattern = Regex("""title:\s*"([^"]{10,80})"""")
        return titlePattern.find(content)?.groupValues?.get(1)
    }

    private fun parseDateString(dateStr: String): Long? {
        val months = mapOf(
            "Jan" to Calendar.JANUARY,
            "Feb" to Calendar.FEBRUARY,
            "Mar" to Calendar.MARCH,
            "Apr" to Calendar.APRIL,
            "May" to Calendar.MAY,
            "Jun" to Calendar.JUNE,
            "Jul" to Calendar.JULY,
            "Aug" to Calendar.AUGUST,
            "Sep" to Calendar.SEPTEMBER,
            "Oct" to Calendar.OCTOBER,
            "Nov" to Calendar.NOVEMBER,
            "Dec" to Calendar.DECEMBER
        )
        val parts = dateStr.trim().split(" ", limit = 2)
        if (parts.size != 2) return null
        val month = months[parts[0]] ?: return null
        val day = parts[1].toIntOrNull() ?: return null

        val cal = Calendar.getInstance()
        val currentYear = cal.get(Calendar.YEAR)
        cal.set(currentYear, month, day, 23, 59, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun guessType(label: String): String {
        val lower = label.lowercase()
        return when {
            lower.startsWith("watch:") || lower.contains("video") -> "video"
            lower.contains("programming") || lower.contains("final exam") || lower.contains("coding") -> "code"
            lower.contains(
                "assignment"
            ) ||
                lower.contains("quiz") ||
                lower.contains("graded") ||
                lower.contains("honor code") -> "assignment"
            else -> "assignment"
        }
    }
}

// --- Mapping from backend response to domain models ---

private fun ParseChecklistResponse.toComprehensiveImportResult(): ComprehensiveImportResult {
    val domainCourse = ParsedCourse(
        code = course.code,
        name = course.name,
        deadline = null,
        assignments = tasks.map { t ->
            ParsedAssignment(
                title = t.title,
                dueDate = t.dueDate?.let { parseDateStringIso(it) },
                time = t.estimatedMinutes?.let { "${it}min" },
                type = t.tags.firstOrNull(),
                completed = t.completed
            )
        }
    )
    val domainProject = ParsedProject(
        name = project.name,
        color = project.color,
        icon = project.icon
    )
    val domainTags = tags.map { ParsedTag(name = it.name, color = it.color ?: "#6B7280") }
    val domainTasks = tasks.map { it.toDomain() }
    return ComprehensiveImportResult(
        course = domainCourse,
        project = domainProject,
        tags = domainTags,
        tasks = domainTasks,
        phases = phases.map {
            ParsedProjectPhaseDomain(
                name = it.name,
                description = it.description,
                startDate = it.startDate?.let(::parseDateStringIso),
                endDate = it.endDate?.let(::parseDateStringIso),
                orderIndex = it.orderIndex
            )
        },
        risks = risks.map {
            ParsedProjectRiskDomain(
                title = it.title,
                description = it.description,
                level = it.level
            )
        },
        externalAnchors = externalAnchors.map {
            ParsedExternalAnchorDomain(
                title = it.title,
                type = it.type,
                phaseName = it.phaseName,
                targetDate = it.targetDate?.let(::parseDateStringIso)
            )
        },
        taskDependencies = taskDependencies.map {
            ParsedTaskDependencyDomain(
                blockerTitle = it.blockerTitle,
                blockedTitle = it.blockedTitle
            )
        }
    )
}

private fun ParsedChecklistTaskResponse.toDomain(): ChecklistParsedTask = ChecklistParsedTask(
    title = title,
    description = description,
    dueDate = dueDate?.let { parseDateStringIso(it) },
    priority = priority.coerceIn(0, 4),
    completed = completed,
    tags = tags,
    subtasks = subtasks.map { it.toDomain() },
    estimatedMinutes = estimatedMinutes,
    phaseName = phaseName
)

private fun parseDateStringIso(dateStr: String): Long? {
    val isoPattern = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    val match = isoPattern.find(dateStr) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = (match.groupValues[2].toIntOrNull() ?: return null) - 1
    val day = match.groupValues[3].toIntOrNull() ?: return null
    val cal = Calendar.getInstance()
    cal.set(year, month, day, 23, 59, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
