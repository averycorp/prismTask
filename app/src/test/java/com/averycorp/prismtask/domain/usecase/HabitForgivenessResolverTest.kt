package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.HabitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sentinel-semantics coverage for [HabitForgivenessResolver].
 *
 * The resolver is the single source of truth that turns the four per-habit
 * sentinel columns into effective values; mismatching the boundaries here is
 * a silent streak-math bug.
 */
class HabitForgivenessResolverTest {

    private fun habit(
        streakMaxMissedDays: Int = -1,
        forgivenessEnabled: Int = -1,
        forgivenessAllowedMisses: Int = -1,
        forgivenessGracePeriodDays: Int = -1
    ): HabitEntity = HabitEntity(
        id = 1L,
        name = "Test",
        streakMaxMissedDays = streakMaxMissedDays,
        forgivenessEnabled = forgivenessEnabled,
        forgivenessAllowedMisses = forgivenessAllowedMisses,
        forgivenessGracePeriodDays = forgivenessGracePeriodDays
    )

    private val globalConfig = ForgivenessConfig(
        enabled = true,
        gracePeriodDays = 7,
        allowedMisses = 1
    )

    // -----------------------------------------------------------------
    // resolveMaxMissedDays
    // -----------------------------------------------------------------

    @Test
    fun `resolveMaxMissedDays returns global when override sentinel is -1`() {
        val h = habit(streakMaxMissedDays = -1)
        assertEquals(3, HabitForgivenessResolver.resolveMaxMissedDays(h, globalDefault = 3))
    }

    @Test
    fun `resolveMaxMissedDays returns per-habit value when set to 1`() {
        val h = habit(streakMaxMissedDays = 1)
        assertEquals(1, HabitForgivenessResolver.resolveMaxMissedDays(h, globalDefault = 5))
    }

    @Test
    fun `resolveMaxMissedDays returns per-habit value when set above 1`() {
        val h = habit(streakMaxMissedDays = 5)
        assertEquals(5, HabitForgivenessResolver.resolveMaxMissedDays(h, globalDefault = 1))
    }

    @Test
    fun `resolveMaxMissedDays falls back to global on stored 0`() {
        // 0 is not a valid override value for this axis — only >=1 wins.
        val h = habit(streakMaxMissedDays = 0)
        assertEquals(2, HabitForgivenessResolver.resolveMaxMissedDays(h, globalDefault = 2))
    }

    // -----------------------------------------------------------------
    // resolveForgivenessConfig: enabled three-state
    // -----------------------------------------------------------------

    @Test
    fun `forgivenessEnabled sentinel -1 inherits global enabled`() {
        val globalOn = globalConfig.copy(enabled = true)
        val globalOff = globalConfig.copy(enabled = false)
        assertTrue(
            HabitForgivenessResolver
                .resolveForgivenessConfig(habit(forgivenessEnabled = -1), globalOn)
                .enabled
        )
        assertFalse(
            HabitForgivenessResolver
                .resolveForgivenessConfig(habit(forgivenessEnabled = -1), globalOff)
                .enabled
        )
    }

    @Test
    fun `forgivenessEnabled 0 forces off regardless of global`() {
        val h = habit(forgivenessEnabled = 0)
        assertFalse(
            HabitForgivenessResolver
                .resolveForgivenessConfig(h, globalConfig.copy(enabled = true))
                .enabled
        )
    }

    @Test
    fun `forgivenessEnabled 1 forces on regardless of global`() {
        val h = habit(forgivenessEnabled = 1)
        assertTrue(
            HabitForgivenessResolver
                .resolveForgivenessConfig(h, globalConfig.copy(enabled = false))
                .enabled
        )
    }

    @Test
    fun `forgivenessEnabled unknown values fall back to global`() {
        val h = habit(forgivenessEnabled = 42)
        assertTrue(
            HabitForgivenessResolver
                .resolveForgivenessConfig(h, globalConfig.copy(enabled = true))
                .enabled
        )
    }

    // -----------------------------------------------------------------
    // resolveForgivenessConfig: allowedMisses boundary (>= 0)
    // -----------------------------------------------------------------

    @Test
    fun `forgivenessAllowedMisses 0 is preserved as opt-in zero budget`() {
        val h = habit(forgivenessAllowedMisses = 0)
        assertEquals(
            0,
            HabitForgivenessResolver
                .resolveForgivenessConfig(h, globalConfig.copy(allowedMisses = 3))
                .allowedMisses
        )
    }

    @Test
    fun `forgivenessAllowedMisses sentinel -1 inherits global`() {
        val h = habit(forgivenessAllowedMisses = -1)
        assertEquals(
            3,
            HabitForgivenessResolver
                .resolveForgivenessConfig(h, globalConfig.copy(allowedMisses = 3))
                .allowedMisses
        )
    }

    @Test
    fun `forgivenessAllowedMisses positive overrides global`() {
        val h = habit(forgivenessAllowedMisses = 4)
        assertEquals(
            4,
            HabitForgivenessResolver
                .resolveForgivenessConfig(h, globalConfig.copy(allowedMisses = 1))
                .allowedMisses
        )
    }

    // -----------------------------------------------------------------
    // resolveForgivenessConfig: gracePeriodDays (>= 1)
    // -----------------------------------------------------------------

    @Test
    fun `forgivenessGracePeriodDays sentinel -1 inherits global`() {
        val h = habit(forgivenessGracePeriodDays = -1)
        assertEquals(
            14,
            HabitForgivenessResolver
                .resolveForgivenessConfig(h, globalConfig.copy(gracePeriodDays = 14))
                .gracePeriodDays
        )
    }

    @Test
    fun `forgivenessGracePeriodDays 0 falls back to global`() {
        val h = habit(forgivenessGracePeriodDays = 0)
        assertEquals(
            7,
            HabitForgivenessResolver
                .resolveForgivenessConfig(h, globalConfig.copy(gracePeriodDays = 7))
                .gracePeriodDays
        )
    }

    @Test
    fun `forgivenessGracePeriodDays positive overrides global`() {
        val h = habit(forgivenessGracePeriodDays = 21)
        assertEquals(
            21,
            HabitForgivenessResolver
                .resolveForgivenessConfig(h, globalConfig.copy(gracePeriodDays = 7))
                .gracePeriodDays
        )
    }

    // -----------------------------------------------------------------
    // Combined axes
    // -----------------------------------------------------------------

    @Test
    fun `axes resolve independently`() {
        val h = habit(
            forgivenessEnabled = 1,
            forgivenessAllowedMisses = -1, // inherit
            forgivenessGracePeriodDays = 10 // override
        )
        val resolved = HabitForgivenessResolver.resolveForgivenessConfig(
            h,
            ForgivenessConfig(enabled = false, allowedMisses = 2, gracePeriodDays = 7)
        )
        assertTrue(resolved.enabled)
        assertEquals(2, resolved.allowedMisses) // from global
        assertEquals(10, resolved.gracePeriodDays) // from habit
    }
}
