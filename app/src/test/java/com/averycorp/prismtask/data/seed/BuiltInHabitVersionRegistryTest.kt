package com.averycorp.prismtask.data.seed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity tests for [BuiltInHabitVersionRegistry] — every known key
 * resolves, no version is < 1, and registry-vs-key-set matches.
 */
class BuiltInHabitVersionRegistryTest {

    @Test
    fun every_known_key_has_a_definition() {
        val knownKeys = listOf(
            BuiltInHabitVersionRegistry.KEY_SCHOOL,
            BuiltInHabitVersionRegistry.KEY_LEISURE,
            BuiltInHabitVersionRegistry.KEY_MORNING_SELFCARE,
            BuiltInHabitVersionRegistry.KEY_BEDTIME_SELFCARE,
            BuiltInHabitVersionRegistry.KEY_MEDICATION,
            BuiltInHabitVersionRegistry.KEY_HOUSEWORK,
            BuiltInHabitVersionRegistry.KEY_WORKDAY_SETUP,
            BuiltInHabitVersionRegistry.KEY_WINDDOWN,
            BuiltInHabitVersionRegistry.KEY_ERRANDS
        )
        knownKeys.forEach { key ->
            val def = BuiltInHabitVersionRegistry.current(key)
            assertNotNull("missing definition for $key", def)
            assertTrue("version must be >= 1 for $key", def!!.version >= 1)
        }
    }

    @Test
    fun versionFor_returns_zero_for_unknown_key() {
        assertEquals(0, BuiltInHabitVersionRegistry.versionFor("not_a_real_key"))
    }

    @Test
    fun current_returns_null_for_unknown_key() {
        assertNull(BuiltInHabitVersionRegistry.current("not_a_real_key"))
    }

    @Test
    fun allKeys_matches_known_set() {
        val expected = setOf(
            BuiltInHabitVersionRegistry.KEY_SCHOOL,
            BuiltInHabitVersionRegistry.KEY_LEISURE,
            BuiltInHabitVersionRegistry.KEY_MORNING_SELFCARE,
            BuiltInHabitVersionRegistry.KEY_BEDTIME_SELFCARE,
            BuiltInHabitVersionRegistry.KEY_MEDICATION,
            BuiltInHabitVersionRegistry.KEY_HOUSEWORK,
            BuiltInHabitVersionRegistry.KEY_WORKDAY_SETUP,
            BuiltInHabitVersionRegistry.KEY_WINDDOWN,
            BuiltInHabitVersionRegistry.KEY_ERRANDS
        )
        assertEquals(expected, BuiltInHabitVersionRegistry.allKeys())
    }

    @Test
    fun routine_steps_carry_unique_step_ids() {
        BuiltInHabitVersionRegistry.allCurrent().forEach { def ->
            val ids = def.steps.map { it.stepId }
            assertEquals("duplicate step_id in ${def.templateKey}", ids.size, ids.toSet().size)
        }
    }
}
