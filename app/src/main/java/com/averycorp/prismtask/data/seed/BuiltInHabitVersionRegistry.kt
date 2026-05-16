package com.averycorp.prismtask.data.seed

import com.averycorp.prismtask.domain.model.SelfCareRoutines

/**
 * Canonical, code-defined definitions for the 6 built-in habits, plus a
 * monotonically-increasing version on each. The detector compares a user's
 * `habits.source_version` to `versionFor(templateKey)` to find pending
 * updates; the differ uses [current] to compute the per-field diff.
 *
 * To ship a new version of a built-in:
 *  1. Edit the entry below (label, steps, frequency, etc.)
 *  2. Bump its [BuiltInHabitDefinition.version] by 1
 *  3. (Optional) Add the prior shape to [previousDefinitions] if you want
 *     skipped-version users to see "this update incorporates v2 + v3"
 *
 * The original v1 definitions live here as the seed shape — they are the
 * "what got installed pre-versioning" reference. The migration pins every
 * existing built-in habit row to source_version = 1.
 */
data class BuiltInHabitDefinition(
    val templateKey: String,
    val version: Int,
    val name: String,
    val description: String?,
    val frequency: String,
    val targetCount: Int,
    val activeDaysCsv: String,
    val steps: List<BuiltInStepDefinition> = emptyList()
)

data class BuiltInStepDefinition(
    val stepId: String,
    val routineType: String,
    val label: String,
    val duration: String,
    val tier: String,
    val phase: String,
    val sortOrder: Int,
    val note: String = ""
)

object BuiltInHabitVersionRegistry {

    const val KEY_SCHOOL = "builtin_school"
    const val KEY_LEISURE = "builtin_leisure"
    const val KEY_MORNING_SELFCARE = "builtin_morning_selfcare"
    const val KEY_BEDTIME_SELFCARE = "builtin_bedtime_selfcare"
    const val KEY_MEDICATION = "builtin_medication"
    const val KEY_HOUSEWORK = "builtin_housework"
    const val KEY_WORKDAY_SETUP = "builtin_workday_setup"
    const val KEY_WINDDOWN = "builtin_winddown"
    const val KEY_ERRANDS = "builtin_errands"

    private val definitions: Map<String, BuiltInHabitDefinition> = listOf(
        BuiltInHabitDefinition(
            templateKey = KEY_SCHOOL,
            version = 1,
            name = "School",
            description = null,
            frequency = "daily",
            targetCount = 1,
            activeDaysCsv = ""
        ),
        BuiltInHabitDefinition(
            templateKey = KEY_LEISURE,
            version = 1,
            name = "Leisure",
            description = null,
            frequency = "daily",
            targetCount = 1,
            activeDaysCsv = ""
        ),
        BuiltInHabitDefinition(
            templateKey = KEY_MORNING_SELFCARE,
            version = 1,
            name = "Morning Self-Care",
            description = null,
            frequency = "daily",
            targetCount = 1,
            activeDaysCsv = "",
            steps = stepsFromRoutine(SelfCareRoutines.morningSteps, "morning")
        ),
        BuiltInHabitDefinition(
            templateKey = KEY_BEDTIME_SELFCARE,
            version = 1,
            name = "Bedtime Self-Care",
            description = null,
            frequency = "daily",
            targetCount = 1,
            activeDaysCsv = "",
            steps = stepsFromRoutine(SelfCareRoutines.bedtimeSteps, "bedtime")
        ),
        BuiltInHabitDefinition(
            templateKey = KEY_MEDICATION,
            version = 1,
            name = "Medication",
            description = null,
            frequency = "daily",
            targetCount = 1,
            activeDaysCsv = "",
            steps = stepsFromRoutine(SelfCareRoutines.medicationSteps, "medication")
        ),
        BuiltInHabitDefinition(
            templateKey = KEY_HOUSEWORK,
            version = 1,
            name = "Housework",
            description = null,
            frequency = "daily",
            targetCount = 1,
            activeDaysCsv = "",
            steps = stepsFromRoutine(SelfCareRoutines.houseworkSteps, "housework")
        ),
        BuiltInHabitDefinition(
            templateKey = KEY_WORKDAY_SETUP,
            version = 1,
            name = "Work-Day Setup",
            description = "Set up your work day",
            frequency = "daily",
            targetCount = 1,
            activeDaysCsv = "",
            steps = stepsFromRoutine(SelfCareRoutines.workdaySteps, "workday")
        ),
        BuiltInHabitDefinition(
            templateKey = KEY_WINDDOWN,
            version = 1,
            name = "Wind-Down",
            description = "Transition into a calm evening",
            frequency = "daily",
            targetCount = 1,
            activeDaysCsv = "",
            steps = stepsFromRoutine(SelfCareRoutines.winddownSteps, "winddown")
        ),
        BuiltInHabitDefinition(
            templateKey = KEY_ERRANDS,
            version = 1,
            name = "Errands",
            description = "Complete your weekly errands",
            frequency = "weekly",
            targetCount = 1,
            activeDaysCsv = "",
            steps = stepsFromRoutine(SelfCareRoutines.errandsSteps, "errands")
        )
    ).associateBy { it.templateKey }

    fun current(templateKey: String): BuiltInHabitDefinition? = definitions[templateKey]

    fun versionFor(templateKey: String): Int = definitions[templateKey]?.version ?: 0

    fun allCurrent(): List<BuiltInHabitDefinition> = definitions.values.toList()

    fun allKeys(): Set<String> = definitions.keys

    private fun stepsFromRoutine(
        steps: List<com.averycorp.prismtask.domain.model.RoutineStep>,
        routineType: String
    ): List<BuiltInStepDefinition> = steps.mapIndexed { index, step ->
        BuiltInStepDefinition(
            stepId = step.id,
            routineType = routineType,
            label = step.label,
            duration = step.duration,
            tier = step.tier,
            phase = step.phase,
            sortOrder = index,
            note = step.note
        )
    }
}
