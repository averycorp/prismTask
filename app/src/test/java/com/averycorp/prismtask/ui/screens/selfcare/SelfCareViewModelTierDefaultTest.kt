package com.averycorp.prismtask.ui.screens.selfcare

import androidx.lifecycle.SavedStateHandle
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.SelfCareTierDefaults
import com.averycorp.prismtask.data.repository.SelfCareRepository
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Regression: the Self-Care screen previously read `tierDefaults.value`
 * from a `WhileSubscribed(5000)` StateFlow that nothing collected, so the
 * user's stored default tier was discarded and the screen always showed
 * the data-class fallback ("solid"). The Habits card on the same routine
 * read the configured value correctly because `HabitListViewModel` folds
 * `tierDefaults` into a subscribed combine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelfCareViewModelTierDefaultTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var repository: SelfCareRepository
    private lateinit var advancedTuningPreferences: AdvancedTuningPreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        every { repository.getTodayLog(any()) } returns flowOf(null)
        every { repository.getSteps(any()) } returns flowOf(emptyList())
        advancedTuningPreferences = mockk(relaxed = true)
        every { advancedTuningPreferences.getSelfCareTierDefaults() } returns
            flowOf(SelfCareTierDefaults(morning = "survival", bedtime = "survival"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(routineType: String): SelfCareViewModel {
        val handle = SavedStateHandle(mapOf("routineType" to routineType))
        return SelfCareViewModel(repository, handle, Gson(), advancedTuningPreferences)
    }

    @Test
    fun morningRoutine_usesConfiguredSurvivalDefault_whenLogIsNull() = runTest(dispatcher) {
        val vm = newViewModel("morning")
        val tier = vm.getSelectedTier(
            log = null,
            defaults = SelfCareTierDefaults(morning = "survival", bedtime = "survival")
        )
        assertEquals("survival", tier)
    }

    @Test
    fun bedtimeRoutine_usesConfiguredSurvivalDefault_whenLogIsNull() = runTest(dispatcher) {
        val vm = newViewModel("bedtime")
        val tier = vm.getSelectedTier(
            log = null,
            defaults = SelfCareTierDefaults(morning = "survival", bedtime = "survival")
        )
        assertEquals("survival", tier)
    }

    @Test
    fun configuredDefault_outsideTierOrder_fallsBackToPenultimate() = runTest(dispatcher) {
        val vm = newViewModel("morning")
        // morningTierOrder = [survival, solid, full]; "basic" is not present.
        val tier = vm.getSelectedTier(
            log = null,
            defaults = SelfCareTierDefaults(morning = "basic")
        )
        assertEquals("solid", tier)
    }
}
