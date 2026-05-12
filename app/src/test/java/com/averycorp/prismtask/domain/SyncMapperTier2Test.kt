package com.averycorp.prismtask.domain

import com.averycorp.prismtask.data.local.entity.CourseCompletionEntity
import com.averycorp.prismtask.data.local.entity.CourseEntity
import com.averycorp.prismtask.data.local.entity.SelfCareLogEntity
import com.averycorp.prismtask.data.local.entity.SelfCareStepEntity
import com.averycorp.prismtask.data.remote.mapper.SyncMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the five Tier-2 sync entity mappers preserve [updatedAt] in
 * both directions — i.e. the output mapper uses entity.updatedAt (not
 * System.currentTimeMillis()) and the input mapper reads updatedAt back from
 * the Firestore data map. These invariants underpin the last-write-wins
 * conflict resolution added in CP2.
 */
class SyncMapperTier2Test {

    // ── SelfCareLog ───────────────────────────────────────────────────────────

    @Test
    fun selfCareLog_outputMapper_usesEntityUpdatedAt() {
        val entity = SelfCareLogEntity(
            id = 1,
            routineType = "morning",
            date = 1000L,
            updatedAt = 42_000L
        )
        val map = SyncMapper.selfCareLogToMap(entity)
        assertEquals(42_000L, map["updatedAt"])
    }

    @Test
    fun selfCareLog_inputMapper_readsUpdatedAt() {
        val map = mapOf<String, Any?>(
            "routineType" to "morning",
            "date" to 1000L,
            "selectedTier" to "solid",
            "completedSteps" to "[]",
            "tiersByTime" to "{}",
            "isComplete" to false,
            "createdAt" to 500L,
            "updatedAt" to 99_000L
        )
        val entity = SyncMapper.mapToSelfCareLog(map, localId = 5)
        assertEquals(99_000L, entity.updatedAt)
    }

    @Test
    fun selfCareLog_inputMapper_missingUpdatedAt_defaultsZero() {
        val map = mapOf<String, Any?>("routineType" to "morning", "date" to 1000L)
        val entity = SyncMapper.mapToSelfCareLog(map)
        assertEquals(0L, entity.updatedAt)
    }

    @Test
    fun selfCareLog_roundTrip_preservesUpdatedAt() {
        val entity = SelfCareLogEntity(
            id = 3,
            routineType = "bedtime",
            date = 2000L,
            selectedTier = "survival",
            completedSteps = """["step1"]""",
            updatedAt = 77_000L
        )
        val restored = SyncMapper.mapToSelfCareLog(SyncMapper.selfCareLogToMap(entity), 3)
        assertEquals(77_000L, restored.updatedAt)
    }

    // ── SelfCareStep ──────────────────────────────────────────────────────────

    @Test
    fun selfCareStep_outputMapper_usesEntityUpdatedAt() {
        val entity = SelfCareStepEntity(
            id = 1,
            stepId = "sc_water",
            routineType = "morning",
            label = "Drink water",
            duration = "1 min",
            tier = "survival",
            phase = "Hydration",
            updatedAt = 55_000L
        )
        val map = SyncMapper.selfCareStepToMap(entity)
        assertEquals(55_000L, map["updatedAt"])
    }

    @Test
    fun selfCareStep_inputMapper_readsUpdatedAt() {
        val map = mapOf<String, Any?>(
            "stepId" to "sc_water",
            "routineType" to "morning",
            "label" to "Drink water",
            "duration" to "1 min",
            "tier" to "survival",
            "phase" to "Hydration",
            "sortOrder" to 0,
            "updatedAt" to 33_000L
        )
        val entity = SyncMapper.mapToSelfCareStep(map, localId = 2)
        assertEquals(33_000L, entity.updatedAt)
    }

    @Test
    fun selfCareStep_inputMapper_missingUpdatedAt_defaultsZero() {
        val map = mapOf<String, Any?>(
            "stepId" to "x",
            "routineType" to "morning",
            "label" to "L",
            "duration" to "1 min",
            "tier" to "survival",
            "phase" to "P"
        )
        val entity = SyncMapper.mapToSelfCareStep(map)
        assertEquals(0L, entity.updatedAt)
    }

    // ── LeisureLog tests retired in Leisure Budget v2.0 (migration 81→82)
    // — v1.x slot-pick rows are gone. v2.0 leisure_activities /
    // leisure_sessions get fresh mapper tests in a follow-on.

    // ── Course ────────────────────────────────────────────────────────────────

    @Test
    fun course_outputMapper_usesEntityUpdatedAt() {
        val entity = CourseEntity(
            id = 1,
            name = "Math",
            code = "MATH101",
            updatedAt = 11_000L
        )
        val map = SyncMapper.courseToMap(entity)
        assertEquals(11_000L, map["updatedAt"])
    }

    @Test
    fun course_inputMapper_readsUpdatedAt() {
        val map = mapOf<String, Any?>(
            "name" to "Math",
            "code" to "MATH101",
            "color" to 0,
            "icon" to "\uD83D\uDCDA",
            "active" to true,
            "sortOrder" to 0,
            "createdAt" to 500L,
            "updatedAt" to 22_000L
        )
        val entity = SyncMapper.mapToCourse(map, localId = 9)
        assertEquals(22_000L, entity.updatedAt)
    }

    @Test
    fun course_inputMapper_missingUpdatedAt_defaultsZero() {
        val map = mapOf<String, Any?>("name" to "Bio", "code" to "BIO101")
        val entity = SyncMapper.mapToCourse(map)
        assertEquals(0L, entity.updatedAt)
    }

    // ── CourseCompletion ──────────────────────────────────────────────────────

    @Test
    fun courseCompletion_outputMapper_usesEntityUpdatedAt() {
        val entity = CourseCompletionEntity(
            id = 1,
            date = 5000L,
            courseId = 2L,
            completed = true,
            completedAt = 5100L,
            updatedAt = 44_000L
        )
        val map = SyncMapper.courseCompletionToMap(entity, "cloud_course_1")
        assertEquals(44_000L, map["updatedAt"])
    }

    @Test
    fun courseCompletion_inputMapper_readsUpdatedAt() {
        val map = mapOf<String, Any?>(
            "date" to 5000L,
            "courseCloudId" to "cloud_course_1",
            "completed" to true,
            "completedAt" to 5100L,
            "createdAt" to 1000L,
            "updatedAt" to 55_000L
        )
        val entity = SyncMapper.mapToCourseCompletion(map, localId = 4, courseLocalId = 2)
        assertEquals(55_000L, entity.updatedAt)
    }

    @Test
    fun courseCompletion_inputMapper_missingUpdatedAt_defaultsZero() {
        val map = mapOf<String, Any?>("date" to 5000L, "completed" to false)
        val entity = SyncMapper.mapToCourseCompletion(map, courseLocalId = 2)
        assertEquals(0L, entity.updatedAt)
    }

    // ── Cross-entity sanity: output updatedAt is not System.currentTimeMillis ─

    @Test
    fun selfCareLog_outputMapper_doesNotUseCurrentTimeMillis() {
        val knownTimestamp = 12345L
        val entity = SelfCareLogEntity(id = 1, routineType = "morning", date = 1000L, updatedAt = knownTimestamp)
        val map = SyncMapper.selfCareLogToMap(entity)
        assertEquals("outputMapper must use entity.updatedAt, not current time", knownTimestamp, map["updatedAt"])
        assertTrue(
            "entity.updatedAt must not be overwritten with system clock",
            (map["updatedAt"] as Long) < System.currentTimeMillis()
        )
    }
}
