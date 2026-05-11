package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.remote.ClaudeParserService
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

data class ParsedTodoItem(
    val title: String,
    val description: String? = null,
    val dueDate: Long? = null,
    val priority: Int = 0,
    val completed: Boolean = false,
    val subtasks: List<ParsedTodoItem> = emptyList()
)

data class ParsedTodoList(val name: String?, val items: List<ParsedTodoItem>)

@Singleton
class TodoListParser
@Inject
constructor(private val claudeParserService: ClaudeParserService) {
    suspend fun parse(content: String): ParsedTodoList? {
        // Try Claude API first (if key is configured)
        val claudeResult = claudeParserService.parse(content)
        if (claudeResult != null) return claudeResult

        // Fall back to regex parsing
        return parseWithRegex(content)
    }

    fun parseWithRegex(content: String): ParsedTodoList? {
        // Try structured format with sections/categories first
        val sectioned = parseSectionedFormat(content)
        if (sectioned != null && sectioned.items.isNotEmpty()) return sectioned

        // Try flat array format
        val flat = parseFlatArrayFormat(content)
        if (flat != null && flat.items.isNotEmpty()) return flat

        // Try simple text list (checkbox markdown)
        val markdown = parseMarkdownChecklist(content)
        if (markdown != null && markdown.items.isNotEmpty()) return markdown

        return null
    }

    // Handles: const TODOS = [ { category: "Work", items: [ { text: "..." }, ... ] }, ... ]
    private fun parseSectionedFormat(content: String): ParsedTodoList? {
        // Look for arrays of objects that have an items/tasks sub-array
        val sectionPattern = Regex(
            """\{\s*(?:category|section|group|name|title):\s*"([^"]+)"[^}]*?(?:items|tasks|todos):\s*\[(.*?)\]\s*\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        val sections = sectionPattern.findAll(content).toList()
        if (sections.isEmpty()) return null

        val listName = extractListName(content)
        val items = mutableListOf<ParsedTodoItem>()

        for (section in sections) {
            val sectionName = section.groupValues[1]
            val itemsBlock = section.groupValues[2]
            val parsed = parseItemObjects(itemsBlock)
            // Prefix section name to items for context
            for (item in parsed) {
                items.add(item.copy(title = "[$sectionName] ${item.title}"))
            }
        }

        return ParsedTodoList(name = listName, items = items)
    }

    // Handles flat arrays: const TASKS = [ { text: "...", done: true }, ... ]
    private fun parseFlatArrayFormat(content: String): ParsedTodoList? {
        // Find the main array — match balanced brackets by finding `];` or `]\n`
        val arrayPattern = Regex(
            """(?:const|let|var)\s+\w+\s*(?::\s*\w+(?:\[\])?\s*)?=\s*\[(.*?)\];""",
            RegexOption.DOT_MATCHES_ALL
        )
        val arrayMatch = arrayPattern.find(content) ?: return null
        val arrayContent = arrayMatch.groupValues[1]

        val items = parseItemObjects(arrayContent)
        if (items.isEmpty()) return null

        return ParsedTodoList(name = extractListName(content), items = items)
    }

    // Handles markdown-style: - [x] Task name
    private fun parseMarkdownChecklist(content: String): ParsedTodoList? {
        val checkboxPattern = Regex("""^\s*[-*]\s*\[([ xX])\]\s*(.+)$""", RegexOption.MULTILINE)
        val matches = checkboxPattern.findAll(content).toList()
        if (matches.isEmpty()) return null

        val items = matches.map { match ->
            val completed = match.groupValues[1].lowercase() == "x"
            val title = match.groupValues[2].trim()
            ParsedTodoItem(title = title, completed = completed)
        }

        return ParsedTodoList(name = null, items = items)
    }

    private fun parseItemObjects(block: String): List<ParsedTodoItem> {
        val items = mutableListOf<ParsedTodoItem>()

        // Match individual objects — flexible field order
        val objectPattern = Regex("""\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}""")
        for (objMatch in objectPattern.findAll(block)) {
            val obj = objMatch.groupValues[1]

            // Skip off/rest/holiday items
            val typeVal = extractStringField(obj, "type")
            if (typeVal != null && typeVal.lowercase() in listOf("off", "break", "rest", "holiday")) continue

            // Extract title from common field names
            val title = extractStringField(obj, "text", "title", "label", "name", "summary", "task", "topic")
                ?: continue

            val description = extractStringField(obj, "description", "desc", "notes", "details")
            val duration = extractStringField(obj, "hours", "time", "duration", "estimate")
            val dueDate = extractStringField(obj, "date", "dueDate", "due", "deadline")
                ?.let { parseDateString(it) }
            val priority = extractPriority(obj)
            val completed = extractBoolField(obj, "done", "completed", "checked", "finished")

            // Check for nested subtasks
            val subtaskPattern = Regex("""(?:subtasks|children|items):\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            val subtaskMatch = subtaskPattern.find(obj)
            val subtasks = if (subtaskMatch != null) parseItemObjects(subtaskMatch.groupValues[1]) else emptyList()

            // Build description from explicit desc + duration/hours
            val descParts = listOfNotNull(description, duration)
            val fullDescription = descParts.joinToString(" — ").ifEmpty { null }

            // Prefix type label for categorized items (exam, etc.)
            val typePrefix = when (typeVal?.lowercase()) {
                "exam" -> "\u26A0 EXAM: "
                "reading" -> "\u25B6 "
                else -> ""
            }

            // Exams get high priority automatically
            val effectivePriority = if (typeVal?.lowercase() == "exam" && priority == 0) 4 else priority

            items.add(
                ParsedTodoItem(
                    title = "$typePrefix$title",
                    description = fullDescription,
                    dueDate = dueDate,
                    priority = effectivePriority,
                    completed = completed,
                    subtasks = subtasks
                )
            )
        }

        return items
    }

    private fun extractStringField(obj: String, vararg fieldNames: String): String? {
        for (name in fieldNames) {
            // Match both quoted and template-literal values
            val pattern = Regex("""$name\s*:\s*["'`]([^"'`]+)["'`]""")
            val match = pattern.find(obj)
            if (match != null) return match.groupValues[1].trim()
        }
        return null
    }

    private fun extractBoolField(obj: String, vararg fieldNames: String): Boolean {
        for (name in fieldNames) {
            val pattern = Regex("""$name\s*:\s*(true|false)""")
            val match = pattern.find(obj)
            if (match != null) return match.groupValues[1] == "true"
        }
        return false
    }

    private fun extractPriority(obj: String): Int {
        // Numeric priority: priority: 3
        val numPattern = Regex("""priority\s*:\s*(\d)""")
        val numMatch = numPattern.find(obj)
        if (numMatch != null) return numMatch.groupValues[1].toInt().coerceIn(0, 4)

        // String priority: priority: "high"
        val strPattern = Regex("""priority\s*:\s*["'](\w+)["']""")
        val strMatch = strPattern.find(obj)
        if (strMatch != null) {
            return when (strMatch.groupValues[1].lowercase()) {
                "urgent", "critical" -> 4
                "high" -> 3
                "medium", "med", "normal" -> 2
                "low" -> 1
                else -> 0
            }
        }
        return 0
    }

    private fun extractListName(content: String): String? {
        // Try to find a title in h1/h2 tags
        val h1Pattern = Regex(""">\s*([^<]{3,60}?)\s*</h[12]>""", RegexOption.DOT_MATCHES_ALL)
        val h1Match = h1Pattern.find(content)
        if (h1Match != null) return h1Match.groupValues[1].trim()

        // Try a top-level title field (not inside an array item)
        // Look for title in a config/header object, not inside [ ... ]
        val titlePattern = Regex("""(?:pageTitle|listTitle|heading):\s*["']([^"']{3,60})["']""")
        val titleMatch = titlePattern.find(content)
        if (titleMatch != null) return titleMatch.groupValues[1].trim()

        // Try JSX component/function name: export default function CompExamSchedule()
        val funcPattern = Regex("""(?:export\s+(?:default\s+)?)?function\s+(\w+)""")
        val funcMatch = funcPattern.find(content)
        if (funcMatch != null) {
            val name = funcMatch.groupValues[1]
            // Convert PascalCase to readable
            return name
                .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .replace("_", " ")
        }

        // Try variable name as fallback
        val varPattern = Regex("""(?:const|let|var)\s+(\w+)""")
        val varMatch = varPattern.find(content)
        if (varMatch != null) {
            val name = varMatch.groupValues[1]
            return name
                .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .replace("_", " ")
                .lowercase()
                .replaceFirstChar { it.uppercase() }
        }

        return null
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

        // "Apr 3", "Apr 15"
        val shortPattern = Regex("""(\w{3})\s+(\d{1,2})""")
        val shortMatch = shortPattern.find(dateStr)
        if (shortMatch != null) {
            val month = months[shortMatch.groupValues[1]] ?: return null
            val day = shortMatch.groupValues[2].toIntOrNull() ?: return null
            val cal = Calendar.getInstance()
            cal.set(cal.get(Calendar.YEAR), month, day, 23, 59, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        // "2026-04-15" ISO format
        val isoPattern = Regex("""(\d{4})-(\d{2})-(\d{2})""")
        val isoMatch = isoPattern.find(dateStr)
        if (isoMatch != null) {
            val year = isoMatch.groupValues[1].toIntOrNull() ?: return null
            val month = (isoMatch.groupValues[2].toIntOrNull() ?: return null) - 1
            val day = isoMatch.groupValues[3].toIntOrNull() ?: return null
            val cal = Calendar.getInstance()
            cal.set(year, month, day, 23, 59, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        // "4/15/2026" or "04/15"
        val slashPattern = Regex("""(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?""")
        val slashMatch = slashPattern.find(dateStr)
        if (slashMatch != null) {
            val month = (slashMatch.groupValues[1].toIntOrNull() ?: return null) - 1
            val day = slashMatch.groupValues[2].toIntOrNull() ?: return null
            val cal = Calendar.getInstance()
            val year = slashMatch.groupValues[3].toIntOrNull()?.let {
                if (it < 100) it + 2000 else it
            } ?: cal.get(Calendar.YEAR)
            cal.set(year, month, day, 23, 59, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        return null
    }
}
