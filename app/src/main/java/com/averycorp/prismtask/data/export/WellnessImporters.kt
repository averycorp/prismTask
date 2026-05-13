package com.averycorp.prismtask.data.export

import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationDoseDao
import com.averycorp.prismtask.data.local.dao.SchoolworkDao
import com.averycorp.prismtask.data.local.dao.SelfCareDao
import com.averycorp.prismtask.data.local.entity.AssignmentEntity
import com.averycorp.prismtask.data.local.entity.CourseCompletionEntity
import com.averycorp.prismtask.data.local.entity.CourseEntity
import com.averycorp.prismtask.data.local.entity.MedicationDoseEntity
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.local.entity.SelfCareLogEntity
import com.averycorp.prismtask.data.local.entity.SelfCareStepEntity
import com.google.gson.JsonObject

/**
 * Importers for wellness-adjacent entities: self-care logs/steps,
 * medications + doses (with tier-state backfill), and schoolwork
 * (courses, assignments, course completions). Split out of
 * [EntityImporters] so each helper stays under the audit's per-file
 * budget while sharing the [ImportContext] accumulator.
 */
internal class WellnessImporters(
    private val selfCareDao: SelfCareDao,
    private val schoolworkDao: SchoolworkDao,
    private val medicationDao: MedicationDao,
    private val medicationDoseDao: MedicationDoseDao,
    private val tierBackfiller: MedicationTierBackfiller
) {
    suspend fun importSelfCareLogs(ctx: ImportContext, root: JsonObject) {
        val existingSelfCareLogKeys = selfCareDao
            .getAllLogsOnce()
            .map { it.routineType to it.date }
            .toMutableSet()
        var importedAnyMedicationLog = false
        root.getAsJsonArray("selfCareLogs")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val routineType = obj.get("routineType")?.takeIf { !it.isJsonNull }?.asString
                    ?: return@forEach
                val date = obj.get("date")?.takeIf { !it.isJsonNull }?.asLong ?: return@forEach
                val key = routineType to date
                if (key in existingSelfCareLogKeys) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val default = SelfCareLogEntity(routineType = routineType, date = date)
                val merged = mergeEntityWithDefaults(default, obj)
                selfCareDao.insertLog(merged.copy(id = 0))
                existingSelfCareLogKeys.add(key)
                ctx.selfCareLogsImported++
                if (routineType == "medication" && merged.tiersByTime.isNotBlank() &&
                    merged.tiersByTime != "{}"
                ) {
                    importedAnyMedicationLog = true
                }
            } catch (e: Exception) {
                ctx.errors.add("Failed to import self-care log: ${e.message}")
            }
        }
        if (importedAnyMedicationLog) {
            runCatching { tierBackfiller.backfillAfterRestore(ctx) }
                .onFailure {
                    ctx.errors.add(
                        "Failed to backfill medication_tier_states after restore: ${it.message}"
                    )
                }
        }
    }

    suspend fun importSelfCareSteps(ctx: ImportContext, root: JsonObject) {
        val existingStepIds = selfCareDao.getAllStepsOnce().map { it.stepId }.toMutableSet()
        root.getAsJsonArray("selfCareSteps")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val stepId = obj.get("stepId")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                if (stepId in existingStepIds) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val routineType = obj.get("routineType")?.takeIf { !it.isJsonNull }?.asString
                    ?: return@forEach
                val label = obj.get("label")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                val duration = obj.get("duration")?.takeIf { !it.isJsonNull }?.asString ?: "0"
                val tier = obj.get("tier")?.takeIf { !it.isJsonNull }?.asString ?: "solid"
                val phase = obj.get("phase")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val default = SelfCareStepEntity(
                    stepId = stepId,
                    routineType = routineType,
                    label = label,
                    duration = duration,
                    tier = tier,
                    phase = phase
                )
                val merged = mergeEntityWithDefaults(default, obj)
                selfCareDao.insertStep(merged.copy(id = 0))
                existingStepIds.add(stepId)
                ctx.selfCareStepsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import self-care step: ${e.message}")
            }
        }
    }

    /**
     * Imports top-level medications (v1.4+). Returns a map from the
     * exported primary-key id → new local id so [importMedicationDoses]
     * can remap the FK. MERGE mode dedups by unique normalized name
     * (lower-cased); newer `updatedAt` overwrites the existing row in
     * place.
     */
    suspend fun importMedications(
        ctx: ImportContext,
        root: JsonObject,
        mode: ImportMode
    ): Map<Long, Long> {
        val medIdRemap = mutableMapOf<Long, Long>()
        val existingByNameLower = medicationDao.getAllOnce()
            .associateBy { it.name.trim().lowercase() }

        root.getAsJsonArray("medications")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString
                    ?: return@forEach
                val exportedId = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val existing = existingByNameLower[name.trim().lowercase()]

                if (mode == ImportMode.MERGE && existing != null) {
                    val incomingUpdatedAt =
                        obj.get("updatedAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    if (incomingUpdatedAt > existing.updatedAt) {
                        val merged = mergeEntityWithDefaults(
                            MedicationEntity(name = name),
                            obj
                        )
                        medicationDao.update(merged.copy(id = existing.id))
                        ctx.lwwOverwrites++
                    } else {
                        ctx.duplicatesSkipped++
                    }
                    if (exportedId != 0L) medIdRemap[exportedId] = existing.id
                    return@forEach
                }

                val default = MedicationEntity(name = name)
                val merged = mergeEntityWithDefaults(default, obj)
                val med = merged.copy(
                    id = 0,
                    // Drop the exported cloud_id so the UNIQUE(cloud_id)
                    // index doesn't collide if this DB already has a
                    // row with the same cloud_id from a prior sync.
                    // Next sync push re-mints a cloud_id anyway.
                    cloudId = null,
                    updatedAt = System.currentTimeMillis()
                )
                val newId = medicationDao.insert(med)
                if (exportedId != 0L) medIdRemap[exportedId] = newId
                ctx.medicationsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import medication: ${e.message}")
            }
        }
        return medIdRemap
    }

    /**
     * Imports medication dose history. Skips any row whose
     * `medication_id` can't be remapped through the provided map
     * (parent row didn't import, likely due to an export-side data
     * issue — counted under `orphansSkipped`).
     */
    suspend fun importMedicationDoses(
        ctx: ImportContext,
        root: JsonObject,
        medIdRemap: Map<Long, Long>
    ) {
        root.getAsJsonArray("medicationDoses")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val exportedMedId = obj.get("medicationId")
                    ?.takeIf { !it.isJsonNull }?.asLong
                val customMedicationName = obj.get("customMedicationName")
                    ?.takeIf { !it.isJsonNull }?.asString
                // A dose must reference either a tracked medication (FK
                // remap-able) or carry a custom-medication name. Rows
                // missing both are export-side data corruption — skip.
                if (exportedMedId == null && customMedicationName.isNullOrBlank()) {
                    return@forEach
                }
                val localMedId = exportedMedId?.let { medIdRemap[it] }
                if (exportedMedId != null && localMedId == null) {
                    // Tracked-medication dose whose parent didn't import.
                    ctx.orphansSkipped++
                    return@forEach
                }
                val slotKey = obj.get("slotKey")?.takeIf { !it.isJsonNull }?.asString
                    ?: "anytime"
                val takenAt = obj.get("takenAt")?.takeIf { !it.isJsonNull }?.asLong
                    ?: return@forEach
                val takenDateLocal =
                    obj.get("takenDateLocal")?.takeIf { !it.isJsonNull }?.asString
                        ?: return@forEach
                val default = MedicationDoseEntity(
                    medicationId = localMedId,
                    customMedicationName = customMedicationName,
                    slotKey = slotKey,
                    takenAt = takenAt,
                    takenDateLocal = takenDateLocal
                )
                val merged = mergeEntityWithDefaults(default, obj)
                medicationDoseDao.insert(
                    merged.copy(
                        id = 0,
                        cloudId = null,
                        medicationId = localMedId,
                        customMedicationName = customMedicationName
                    )
                )
                ctx.medicationDosesImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import medication dose: ${e.message}")
            }
        }
    }

    suspend fun importCourses(
        ctx: ImportContext,
        root: JsonObject,
        mode: ImportMode
    ): MutableMap<String, Long> {
        val courseNameToId = mutableMapOf<String, Long>()
        if (mode == ImportMode.MERGE) {
            schoolworkDao.getAllCoursesOnce().forEach { courseNameToId[it.name.lowercase()] = it.id }
        }

        root.getAsJsonArray("courses")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                if (mode == ImportMode.MERGE && name.lowercase() in courseNameToId) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val code = obj.get("code")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val default = CourseEntity(name = name, code = code)
                val merged = mergeEntityWithDefaults(default, obj)
                val id = schoolworkDao.insertCourse(merged.copy(id = 0))
                courseNameToId[name.lowercase()] = id
                ctx.coursesImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import course: ${e.message}")
            }
        }
        return courseNameToId
    }

    suspend fun importAssignments(
        ctx: ImportContext,
        root: JsonObject,
        courseNameToId: Map<String, Long>
    ) {
        val existingAssignmentKeys = schoolworkDao
            .getAllAssignmentsOnce()
            .map { Triple(it.courseId, it.title, it.dueDate) }
            .toMutableSet()
        root.getAsJsonArray("assignments")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val courseName = (obj.get("_courseName") ?: obj.get("courseName"))
                    ?.takeIf { !it.isJsonNull }
                    ?.asString ?: return@forEach
                val courseId = courseNameToId[courseName.lowercase()] ?: return@forEach
                val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                val dueDate = obj.get("dueDate")?.takeIf { !it.isJsonNull }?.asLong
                val key = Triple(courseId, title, dueDate)
                if (key in existingAssignmentKeys) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val default = AssignmentEntity(courseId = courseId, title = title)
                val merged = mergeEntityWithDefaults(default, obj)
                schoolworkDao.insertAssignment(merged.copy(id = 0, courseId = courseId))
                existingAssignmentKeys.add(key)
                ctx.assignmentsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import assignment: ${e.message}")
            }
        }
    }

    suspend fun importCourseCompletions(
        ctx: ImportContext,
        root: JsonObject,
        courseNameToId: Map<String, Long>
    ) {
        val existingCourseCompletionKeys = schoolworkDao
            .getAllCompletionsOnce()
            .map { it.courseId to it.date }
            .toMutableSet()
        derivedArray(root, "courseCompletions")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val courseName = (obj.get("_courseName") ?: obj.get("courseName"))
                    ?.takeIf { !it.isJsonNull }
                    ?.asString ?: return@forEach
                val courseId = courseNameToId[courseName.lowercase()] ?: return@forEach
                val date = obj.get("date")?.takeIf { !it.isJsonNull }?.asLong ?: return@forEach
                val key = courseId to date
                if (key in existingCourseCompletionKeys) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val default = CourseCompletionEntity(date = date, courseId = courseId)
                val merged = mergeEntityWithDefaults(default, obj)
                schoolworkDao.insertCompletion(merged.copy(id = 0, courseId = courseId))
                existingCourseCompletionKeys.add(key)
                ctx.courseCompletionsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import course completion: ${e.message}")
            }
        }
    }
}
