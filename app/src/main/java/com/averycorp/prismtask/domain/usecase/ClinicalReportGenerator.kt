package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.MedicationRefillEntity
import com.averycorp.prismtask.data.local.entity.MoodEnergyLogEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.domain.model.LifeCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clinical report inputs (v1.4.0 V8).
 *
 * All fields are optional — the generator gracefully emits "No data for
 * this period" for any section whose source data is absent. This lets the
 * caller pass whatever they have without gating on feature flags.
 *
 * [dateRangeStart] and [dateRangeEnd] are inclusive millis bounds.
 */
data class ClinicalReportInputs(
    val userName: String?,
    val dateRangeStart: Long,
    val dateRangeEnd: Long,
    val tasks: List<TaskEntity> = emptyList(),
    val moodEnergyLogs: List<MoodEnergyLogEntity> = emptyList(),
    val medications: List<MedicationRefillEntity> = emptyList(),
    val medicationAdherencePercentages: Map<String, Float> = emptyMap(),
    val burnoutScoresByDay: Map<Long, Int> = emptyMap(),
    val sectionsEnabled: Set<ClinicalReportSection> = ClinicalReportSection.ALL
)

/** Toggleable sections — the config dialog maps directly to this enum set. */
enum class ClinicalReportSection {
    OVERVIEW,
    MEDICATION,
    MOOD_ENERGY,
    TASKS,
    BALANCE,
    BURNOUT;

    companion object {
        val ALL: Set<ClinicalReportSection> = values().toSet()
    }
}

/**
 * Result of generating a report: a plain-text version suitable for
 * pasting into a patient portal message or sharing via email, plus a
 * structured section breakdown the PDF writer will walk over.
 */
data class ClinicalReport(val title: String, val subtitle: String, val sections: List<ClinicalReportSectionBlock>, val plainText: String)

/**
 * One section of the report with a header and a list of body lines.
 * The PDF writer renders each line as a row; the text version joins
 * them with newlines.
 */
data class ClinicalReportSectionBlock(val id: ClinicalReportSection, val header: String, val lines: List<String>)

/**
 * On-device clinical report generator.
 *
 * Takes already-aggregated clinical data and produces a self-contained
 * [ClinicalReport] with a plain-text version for sharing via email or
 * patient portal messaging. A PDF output path that walks the same
 * section blocks will land in a follow-up commit (using
 * `android.graphics.pdf.PdfDocument`).
 *
 * The report explicitly notes that the data is self-reported and
 * belongs in a conversation with the user's provider — not as a
 * substitute for medical records.
 */
class ClinicalReportGenerator {
    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    fun generate(inputs: ClinicalReportInputs): ClinicalReport {
        val title = "PrismTask Health & Wellness Report"
        val subtitle = buildString {
            val name = inputs.userName?.takeIf { it.isNotBlank() }
            if (name != null) append("For $name — ")
            append(dateFormat.format(Date(inputs.dateRangeStart)))
            append(" to ")
            append(dateFormat.format(Date(inputs.dateRangeEnd)))
        }
        val sections = buildSections(inputs)
        val plainText = renderPlainText(title, subtitle, sections)
        return ClinicalReport(title, subtitle, sections, plainText)
    }

    private fun buildSections(inputs: ClinicalReportInputs): List<ClinicalReportSectionBlock> {
        val blocks = mutableListOf<ClinicalReportSectionBlock>()
        if (ClinicalReportSection.OVERVIEW in inputs.sectionsEnabled) {
            blocks.add(overviewSection(inputs))
        }
        if (ClinicalReportSection.MEDICATION in inputs.sectionsEnabled) {
            blocks.add(medicationSection(inputs))
        }
        if (ClinicalReportSection.MOOD_ENERGY in inputs.sectionsEnabled) {
            blocks.add(moodEnergySection(inputs))
        }
        if (ClinicalReportSection.TASKS in inputs.sectionsEnabled) {
            blocks.add(taskSection(inputs))
        }
        if (ClinicalReportSection.BALANCE in inputs.sectionsEnabled) {
            blocks.add(balanceSection(inputs))
        }
        if (ClinicalReportSection.BURNOUT in inputs.sectionsEnabled) {
            blocks.add(burnoutSection(inputs))
        }
        return blocks
    }

    private fun overviewSection(inputs: ClinicalReportInputs): ClinicalReportSectionBlock {
        val dayCount = dayCount(inputs)
        val lines = listOf(
            "Reporting period: $dayCount days",
            "Data sources: tasks, habits, mood/energy, medication",
            "Note: all values are self-reported and should be discussed with your healthcare provider."
        )
        return ClinicalReportSectionBlock(ClinicalReportSection.OVERVIEW, "Overview", lines)
    }

    private fun medicationSection(inputs: ClinicalReportInputs): ClinicalReportSectionBlock {
        val lines = if (inputs.medications.isEmpty()) {
            listOf("No data for this period.")
        } else {
            inputs.medications.map { med ->
                val adherence = inputs.medicationAdherencePercentages[med.medicationName]
                val adherenceLabel = adherence?.let { "${(it * 100).toInt()}% adherence" } ?: "adherence unknown"
                val refillLine = med.lastRefillDate?.let { "last refill ${dateFormat.format(Date(it))}" } ?: "no refill date on file"
                "${med.medicationName}: ${med.pillCount} pills remaining, $adherenceLabel ($refillLine)"
            }
        }
        return ClinicalReportSectionBlock(ClinicalReportSection.MEDICATION, "Medication", lines)
    }

    private fun moodEnergySection(inputs: ClinicalReportInputs): ClinicalReportSectionBlock {
        if (inputs.moodEnergyLogs.isEmpty()) {
            return ClinicalReportSectionBlock(
                ClinicalReportSection.MOOD_ENERGY,
                "Mood & Energy",
                listOf("No data for this period.")
            )
        }
        val avgMood = inputs.moodEnergyLogs.map { it.mood }.average()
        val avgEnergy = inputs.moodEnergyLogs.map { it.energy }.average()
        val lowDays = inputs.moodEnergyLogs.count { it.mood <= 2 }
        val highDays = inputs.moodEnergyLogs.count { it.mood >= 4 }
        val lines = listOf(
            "Entries: ${inputs.moodEnergyLogs.size}",
            "Average mood: ${"%.1f".format(avgMood)} / 5",
            "Average energy: ${"%.1f".format(avgEnergy)} / 5",
            "Low-mood days (≤2): $lowDays",
            "High-mood days (≥4): $highDays"
        )
        return ClinicalReportSectionBlock(ClinicalReportSection.MOOD_ENERGY, "Mood & Energy", lines)
    }

    private fun taskSection(inputs: ClinicalReportInputs): ClinicalReportSectionBlock {
        val rangeStart = inputs.dateRangeStart
        val rangeEnd = inputs.dateRangeEnd
        val completed = inputs.tasks.count { t ->
            t.isCompleted && t.completedAt != null && t.completedAt in rangeStart..rangeEnd
        }
        val open = inputs.tasks.count { !it.isCompleted && it.archivedAt == null }
        val overdue = inputs.tasks.count { t ->
            !t.isCompleted &&
                t.archivedAt == null &&
                t.dueDate != null &&
                t.dueDate < rangeEnd &&
                t.dueDate >= rangeStart
        }
        val lines = listOf(
            "Completed: $completed",
            "Still open: $open",
            "Overdue in period: $overdue"
        )
        return ClinicalReportSectionBlock(ClinicalReportSection.TASKS, "Task Completion", lines)
    }

    private fun balanceSection(inputs: ClinicalReportInputs): ClinicalReportSectionBlock {
        val categoryCounts = LifeCategory.TRACKED.associateWith { 0 }.toMutableMap()
        for (task in inputs.tasks) {
            if (!task.isCompleted) continue
            val completedAt = task.completedAt ?: continue
            if (completedAt !in inputs.dateRangeStart..inputs.dateRangeEnd) continue
            val category = LifeCategory.fromStorage(task.lifeCategory)
            if (category in LifeCategory.TRACKED) {
                categoryCounts[category] = (categoryCounts[category] ?: 0) + 1
            }
        }
        val total = categoryCounts.values.sum()
        val lines = if (total == 0) {
            listOf("No categorized tasks for this period.")
        } else {
            categoryCounts.map { (cat, count) ->
                val pct = (count * 100f / total).toInt()
                "${LifeCategory.label(cat)}: $count ($pct%)"
            }
        }
        return ClinicalReportSectionBlock(ClinicalReportSection.BALANCE, "Life Balance", lines)
    }

    private fun burnoutSection(inputs: ClinicalReportInputs): ClinicalReportSectionBlock {
        if (inputs.burnoutScoresByDay.isEmpty()) {
            return ClinicalReportSectionBlock(
                ClinicalReportSection.BURNOUT,
                "Burnout Score Trend",
                listOf("No data for this period.")
            )
        }
        val scores = inputs.burnoutScoresByDay.values
        val avg = scores.average()
        val peak = scores.max()
        val trough = scores.min()
        val lines = listOf(
            "Average score: ${"%.0f".format(avg)} / 100",
            "Peak score: $peak",
            "Lowest score: $trough",
            "Observations: ${scores.size} day(s)"
        )
        return ClinicalReportSectionBlock(ClinicalReportSection.BURNOUT, "Burnout Score Trend", lines)
    }

    private fun renderPlainText(
        title: String,
        subtitle: String,
        sections: List<ClinicalReportSectionBlock>
    ): String = buildString {
        appendLine(title)
        appendLine(subtitle)
        appendLine()
        sections.forEach { section ->
            appendLine(section.header)
            appendLine("-".repeat(section.header.length))
            section.lines.forEach { line -> appendLine("  $line") }
            appendLine()
        }
        appendLine("Generated by PrismTask. Not a medical document.")
    }

    private fun dayCount(inputs: ClinicalReportInputs): Int {
        val millis = inputs.dateRangeEnd - inputs.dateRangeStart
        if (millis <= 0) return 0
        return ((millis / DAY_MILLIS).toInt() + 1).coerceAtLeast(1)
    }

    companion object {
        private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}
