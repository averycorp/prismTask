package com.averycorp.prismtask.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [NdPreferencesDataStore]. Uses [PreferenceDataStoreFactory] with
 * a temp file so tests run as pure JVM without Android/Robolectric.
 */
class NdPreferencesDataStoreTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var ndPrefs: NdPreferencesDataStore

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val file = File(tmpFolder.root, "nd_prefs_test.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        ndPrefs = NdPreferencesDataStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // region Defaults

    @Test
    fun `defaults have all three modes on and cascade sub-settings on`() = runTest {
        // Operator product decision 2026-05-14: presume ND baseline for
        // first-time users. Fresh DataStore (no keys written) reads all
        // three top-level modes ON and their cascade sub-settings ON to
        // match `setX(true)` semantics. See
        // `docs/audits/BRAIN_MODE_PAGE_DEFAULT_ON_AUDIT.md`.
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertTrue(prefs.adhdModeEnabled)
        assertTrue(prefs.calmModeEnabled)
        assertTrue(prefs.focusReleaseModeEnabled)
        assertTrue(prefs.reduceAnimations)
        assertTrue(prefs.mutedColorPalette)
        assertTrue(prefs.quietMode)
        assertTrue(prefs.reduceHaptics)
        assertTrue(prefs.softContrast)
        assertEquals(25, prefs.checkInIntervalMinutes)
        assertTrue(prefs.completionAnimations)
        assertTrue(prefs.streakCelebrations)
        assertTrue(prefs.showProgressBars)
    }

    @Test
    fun `returning user explicit-false is preserved across the default-on flip`() = runTest {
        // Migration safety: a v1.x user who explicitly turned a mode OFF
        // (via the BrainModePage toggle) wrote `false` to the key. After
        // the default flip, the explicit-false must still read as false —
        // the `?: true` fallback only fires for absent keys.
        ndPrefs.setAdhdMode(false)
        ndPrefs.setCalmMode(false)
        ndPrefs.setFocusReleaseMode(false)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertFalse(prefs.adhdModeEnabled)
        assertFalse(prefs.calmModeEnabled)
        assertFalse(prefs.focusReleaseModeEnabled)
        assertFalse(prefs.reduceAnimations)
        assertFalse(prefs.completionAnimations)
    }

    // endregion

    // region ADHD Mode activation

    @Test
    fun `enabling ADHD mode flips all ADHD sub-settings on`() = runTest {
        ndPrefs.setAdhdMode(true)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertTrue(prefs.adhdModeEnabled)
        assertTrue(prefs.completionAnimations)
        assertTrue(prefs.streakCelebrations)
        assertTrue(prefs.showProgressBars)
    }

    @Test
    fun `enabling ADHD mode does not affect Calm sub-settings`() = runTest {
        // Explicitly write Calm to a known-off baseline first so the
        // independence assertion doesn't lean on the (now default-on)
        // fallback for absent Calm keys.
        ndPrefs.setCalmMode(false)
        ndPrefs.setAdhdMode(true)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertFalse(prefs.calmModeEnabled)
        assertFalse(prefs.reduceAnimations)
        assertFalse(prefs.mutedColorPalette)
        assertFalse(prefs.quietMode)
        assertFalse(prefs.reduceHaptics)
        assertFalse(prefs.softContrast)
    }

    @Test
    fun `disabling ADHD mode flips all ADHD sub-settings off`() = runTest {
        ndPrefs.setAdhdMode(true)
        ndPrefs.setAdhdMode(false)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertFalse(prefs.adhdModeEnabled)
        assertFalse(prefs.completionAnimations)
        assertFalse(prefs.streakCelebrations)
        assertFalse(prefs.showProgressBars)
    }

    // endregion

    // region Calm Mode activation

    @Test
    fun `enabling Calm mode flips all Calm sub-settings on`() = runTest {
        ndPrefs.setCalmMode(true)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertTrue(prefs.calmModeEnabled)
        assertTrue(prefs.reduceAnimations)
        assertTrue(prefs.mutedColorPalette)
        assertTrue(prefs.quietMode)
        assertTrue(prefs.reduceHaptics)
        assertTrue(prefs.softContrast)
    }

    @Test
    fun `enabling Calm mode does not affect ADHD sub-settings`() = runTest {
        // Explicitly write ADHD to a known-off baseline first so the
        // independence assertion doesn't lean on the (now default-on)
        // fallback for absent ADHD keys.
        ndPrefs.setAdhdMode(false)
        ndPrefs.setCalmMode(true)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertFalse(prefs.adhdModeEnabled)
        assertFalse(prefs.completionAnimations)
        assertFalse(prefs.streakCelebrations)
        assertFalse(prefs.showProgressBars)
    }

    @Test
    fun `disabling Calm mode flips all Calm sub-settings off`() = runTest {
        ndPrefs.setCalmMode(true)
        ndPrefs.setCalmMode(false)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertFalse(prefs.calmModeEnabled)
        assertFalse(prefs.reduceAnimations)
        assertFalse(prefs.mutedColorPalette)
        assertFalse(prefs.quietMode)
        assertFalse(prefs.reduceHaptics)
        assertFalse(prefs.softContrast)
    }

    // endregion

    // region Mode independence

    @Test
    fun `disabling ADHD mode does not affect active Calm mode settings`() = runTest {
        ndPrefs.setAdhdMode(true)
        ndPrefs.setCalmMode(true)
        ndPrefs.setAdhdMode(false)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        // ADHD off
        assertFalse(prefs.adhdModeEnabled)
        assertFalse(prefs.completionAnimations)
        // Calm still on
        assertTrue(prefs.calmModeEnabled)
        assertTrue(prefs.reduceAnimations)
        assertTrue(prefs.mutedColorPalette)
        assertTrue(prefs.quietMode)
        assertTrue(prefs.reduceHaptics)
        assertTrue(prefs.softContrast)
    }

    @Test
    fun `disabling Calm mode does not affect active ADHD mode settings`() = runTest {
        ndPrefs.setAdhdMode(true)
        ndPrefs.setCalmMode(true)
        ndPrefs.setCalmMode(false)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        // Calm off
        assertFalse(prefs.calmModeEnabled)
        assertFalse(prefs.reduceAnimations)
        // ADHD still on
        assertTrue(prefs.adhdModeEnabled)
        assertTrue(prefs.completionAnimations)
    }

    // endregion

    // region Both modes active (ADHD + Calm combo)

    @Test
    fun `both modes on sets reduceAnimations and completionAnimations both true`() = runTest {
        ndPrefs.setAdhdMode(true)
        ndPrefs.setCalmMode(true)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertTrue(prefs.reduceAnimations)
        assertTrue(prefs.completionAnimations)
    }

    @Test
    fun `both modes on shouldShowRewardAnimation returns true`() = runTest {
        ndPrefs.setAdhdMode(true)
        ndPrefs.setCalmMode(true)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertTrue(prefs.reduceAnimations)
        assertTrue(shouldShowRewardAnimation(prefs))
    }

    // endregion

    // region Individual sub-setting changes

    @Test
    fun `individual sub-setting change does not disable parent mode toggle`() = runTest {
        ndPrefs.setAdhdMode(true)
        ndPrefs.setCompletionAnimations(false)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertTrue(prefs.adhdModeEnabled) // parent mode stays on
        assertFalse(prefs.completionAnimations) // sub-setting overridden
    }

    @Test
    fun `individual calm sub-setting change does not disable calm mode toggle`() = runTest {
        ndPrefs.setCalmMode(true)
        ndPrefs.setReduceAnimations(false)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertTrue(prefs.calmModeEnabled) // parent mode stays on
        assertFalse(prefs.reduceAnimations) // sub-setting overridden
    }

    @Test
    fun `check-in interval clamped to 10 to 60 range`() = runTest {
        ndPrefs.setCheckInIntervalMinutes(5)
        assertEquals(10, ndPrefs.ndPreferencesFlow.first().checkInIntervalMinutes)
        ndPrefs.setCheckInIntervalMinutes(120)
        assertEquals(60, ndPrefs.ndPreferencesFlow.first().checkInIntervalMinutes)
        ndPrefs.setCheckInIntervalMinutes(30)
        assertEquals(30, ndPrefs.ndPreferencesFlow.first().checkInIntervalMinutes)
    }

    // endregion

    // region Persistence round-trip

    @Test
    fun `preferences survive DataStore round trip`() = runTest {
        ndPrefs.setAdhdMode(true)
        ndPrefs.setCalmMode(true)
        ndPrefs.setCheckInIntervalMinutes(45)
        ndPrefs.setCompletionAnimations(false)

        // Create a new NdPreferencesDataStore reading from the same DataStore
        val ndPrefs2 = NdPreferencesDataStore(dataStore)
        val prefs = ndPrefs2.ndPreferencesFlow.first()
        assertTrue(prefs.adhdModeEnabled)
        assertTrue(prefs.calmModeEnabled)
        assertEquals(45, prefs.checkInIntervalMinutes)
        assertFalse(prefs.completionAnimations) // individual override persisted
        assertTrue(prefs.reduceAnimations) // calm sub-setting still on
    }

    // endregion

    // region updateNdPreference generic setter

    @Test
    fun `updateNdPreference sets boolean value by key name`() = runTest {
        ndPrefs.updateNdPreference("reduce_animations", true)
        assertTrue(ndPrefs.ndPreferencesFlow.first().reduceAnimations)
    }

    @Test
    fun `updateNdPreference sets int value by key name`() = runTest {
        ndPrefs.updateNdPreference("check_in_interval_minutes", 40)
        assertEquals(40, ndPrefs.ndPreferencesFlow.first().checkInIntervalMinutes)
    }

    @Test
    fun `updateNdPreference with adhd_mode_enabled triggers full mode activation`() = runTest {
        ndPrefs.updateNdPreference("adhd_mode_enabled", true)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertTrue(prefs.adhdModeEnabled)
        assertTrue(prefs.completionAnimations)
        assertTrue(prefs.streakCelebrations)
    }

    @Test
    fun `updateNdPreference with calm_mode_enabled triggers full mode activation`() = runTest {
        ndPrefs.updateNdPreference("calm_mode_enabled", true)
        val prefs = ndPrefs.ndPreferencesFlow.first()
        assertTrue(prefs.calmModeEnabled)
        assertTrue(prefs.reduceAnimations)
        assertTrue(prefs.softContrast)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `updateNdPreference throws for unknown key`() = runTest {
        ndPrefs.updateNdPreference("unknown_key", true)
    }

    @Test
    fun `updateNdPreference accepts legacy forgiveness_streaks key as no-op`() = runTest {
        // The `forgivenessStreaks` field was removed in the mental-health-first
        // audit § R6 (duplicate of the global ForgivenessPrefs.enabled). Old
        // backups still carry the `forgiveness_streaks` key in the `nd` config
        // block; we accept the key without throwing so legacy restores succeed.
        ndPrefs.updateNdPreference("forgiveness_streaks", true)
        ndPrefs.updateNdPreference("forgiveness_streaks", false)
        // No assertion on field state — the field no longer exists; this test
        // exists to assert the absence of `IllegalArgumentException`.
    }

    // endregion

    // region shouldShowRewardAnimation helper

    @Test
    fun `shouldShowRewardAnimation returns false when completionAnimations off`() {
        val prefs = NdPreferences(completionAnimations = false, reduceAnimations = false)
        assertFalse(shouldShowRewardAnimation(prefs))
    }

    @Test
    fun `shouldShowRewardAnimation returns true when completionAnimations on regardless of reduceAnimations`() {
        val prefs = NdPreferences(completionAnimations = true, reduceAnimations = true)
        assertTrue(shouldShowRewardAnimation(prefs))
    }

    @Test
    fun `shouldShowRewardAnimation returns true when completionAnimations on and reduceAnimations off`() {
        val prefs = NdPreferences(completionAnimations = true, reduceAnimations = false)
        assertTrue(shouldShowRewardAnimation(prefs))
    }

    // endregion
}
