package com.averycorp.prismtask.data.export

/**
 * Mutable accumulator for import counts and errors, shared across the
 * orchestrator ([DataImporter]) and the per-format helpers
 * ([EntityImporters], [ConfigImporter], [MedicationTierBackfiller]).
 *
 * Top-level (rather than nested) so package-internal helpers split out of
 * [DataImporter] can receive and mutate it without exposing it on the
 * public API.
 */
internal class ImportContext(val options: ImportOptions) {
    val errors = mutableListOf<String>()
    var tasksImported = 0
    var projectsImported = 0
    var tagsImported = 0
    var taskCompletionsImported = 0
    var habitsImported = 0
    var habitCompletionsImported = 0
    var habitLogsImported = 0
    var medicationsImported = 0
    var medicationDosesImported = 0
    var leisureLogsImported = 0
    var selfCareLogsImported = 0
    var selfCareStepsImported = 0
    var coursesImported = 0
    var assignmentsImported = 0
    var courseCompletionsImported = 0
    var configImported = false
    var duplicatesSkipped = 0
    var lwwOverwrites = 0
    var orphansSkipped = 0
    var schemaVersion = 0
    var derivedDataSkipped = false

    // D8 Item 8 — count of `medication_tier_states` rows
    // inserted/updated by the post-restore backfill. Surfaced via
    // logging only; not part of the public ImportResult contract.
    var medicationTierStatesBackfilled = 0

    fun toResult() = ImportResult(
        tasksImported = tasksImported,
        projectsImported = projectsImported,
        tagsImported = tagsImported,
        taskCompletionsImported = taskCompletionsImported,
        habitsImported = habitsImported,
        habitCompletionsImported = habitCompletionsImported,
        habitLogsImported = habitLogsImported,
        medicationsImported = medicationsImported,
        medicationDosesImported = medicationDosesImported,
        leisureLogsImported = leisureLogsImported,
        selfCareLogsImported = selfCareLogsImported,
        selfCareStepsImported = selfCareStepsImported,
        coursesImported = coursesImported,
        assignmentsImported = assignmentsImported,
        courseCompletionsImported = courseCompletionsImported,
        configImported = configImported,
        duplicatesSkipped = duplicatesSkipped,
        lwwOverwrites = lwwOverwrites,
        orphansSkipped = orphansSkipped,
        derivedDataSkipped = derivedDataSkipped,
        schemaVersion = schemaVersion,
        errors = errors
    )
}
