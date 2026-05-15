package com.averycorp.prismtask.ui.screens.briefing

import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.remote.api.BriefingPriorityResponse
import com.averycorp.prismtask.data.remote.api.DailyBriefingResponse
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.SuggestedTaskResponse
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Unit tests for [DailyBriefingViewModel] focused on the cloud_id resolution
 * layer added when backend AI endpoints flipped task_id Long → String.
 *
 * Resolution-path: cloud IDs the dao recognizes are mapped to local Long
 * task ids and surface in topPriorities / suggestedOrder.
 *
 * Unresolved-path: cloud IDs the dao does not recognize (e.g. tasks
 * created on another device but not yet synced down) are demoted into
 * pendingSyncTitles so the rest of the briefing still renders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyBriefingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var api: PrismTaskApi
    private lateinit var taskRepository: TaskRepository
    private lateinit var proFeatureGate: ProFeatureGate

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mockk()
        taskRepository = mockk(relaxed = true)
        proFeatureGate = mockk(relaxed = true)
        every { proFeatureGate.userTier } returns MutableStateFlow(UserTier.PRO)
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_BRIEFING) } returns true
        // Default resolutions for the canonical fixture cloud ids.
        coEvery { taskRepository.getIdByCloudId("cloud-1") } returns 1L
        coEvery { taskRepository.getIdByCloudId("cloud-2") } returns 2L
        coEvery { taskRepository.getIdByCloudId("cloud-3") } returns 3L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = DailyBriefingViewModel(api, taskRepository, proFeatureGate, io.mockk.mockk(relaxed = true))

    private fun httpException(code: Int): HttpException {
        val body = "".toResponseBody("application/json".toMediaType())
        return HttpException(Response.error<Any>(code, body))
    }

    @Test
    fun generateBriefing_resolvesCloudIdsToLocalLongs() = runTest(dispatcher) {
        coEvery { api.getDailyBriefing(any()) } returns DailyBriefingResponse(
            greeting = "Good morning!",
            topPriorities = listOf(
                BriefingPriorityResponse("cloud-1", "Fix bug", "Due today"),
                BriefingPriorityResponse("cloud-2", "Write report", "High priority")
            ),
            headsUp = emptyList(),
            suggestedOrder = listOf(
                SuggestedTaskResponse("cloud-1", "Fix bug", "9:00", "Hardest first"),
                SuggestedTaskResponse("cloud-3", "Reply emails", "11:00", "Quick wins")
            ),
            habitReminders = emptyList(),
            dayType = "moderate"
        )

        val vm = newViewModel()
        vm.generateBriefing(date = "2026-04-30")
        advanceUntilIdle()

        val briefing = vm.briefing.value
        assertNotNull(briefing)
        assertEquals(2, briefing!!.topPriorities.size)
        assertEquals(1L, briefing.topPriorities[0].taskId)
        assertEquals(2L, briefing.topPriorities[1].taskId)
        assertEquals(2, briefing.suggestedOrder.size)
        assertEquals(1L, briefing.suggestedOrder[0].taskId)
        assertEquals(3L, briefing.suggestedOrder[1].taskId)
        // No unresolved entries → pendingSyncTitles empty.
        assertTrue(briefing.pendingSyncTitles.isEmpty())
    }

    @Test
    fun generateBriefing_rateLimitHttp429_surfacesActionableError() = runTest(dispatcher) {
        coEvery { api.getDailyBriefing(any()) } throws httpException(429)

        val vm = newViewModel()
        vm.generateBriefing(date = "2026-04-30")
        advanceUntilIdle()

        assertNull(vm.briefing.value)
        assertEquals(
            "Daily Briefing limit reached — try again in a few minutes. (HTTP 429)",
            vm.error.value
        )
    }

    @Test
    fun generateBriefing_aiFeaturesDisabledHttp451_surfacesActionableError() = runTest(dispatcher) {
        coEvery { api.getDailyBriefing(any()) } throws httpException(451)

        val vm = newViewModel()
        vm.generateBriefing(date = "2026-04-30")
        advanceUntilIdle()

        assertEquals(
            "AI features are disabled. Turn them on in Settings → AI Features. (HTTP 451)",
            vm.error.value
        )
    }

    @Test
    fun generateBriefing_anthropicOutageHttp503_surfacesActionableError() = runTest(dispatcher) {
        coEvery { api.getDailyBriefing(any()) } throws httpException(503)

        val vm = newViewModel()
        vm.generateBriefing(date = "2026-04-30")
        advanceUntilIdle()

        assertEquals(
            "AI service temporarily unavailable — try again in a moment. (HTTP 503)",
            vm.error.value
        )
    }

    @Test
    fun generateBriefing_networkIOException_surfacesNetworkError() = runTest(dispatcher) {
        coEvery { api.getDailyBriefing(any()) } throws IOException("connection refused")

        val vm = newViewModel()
        vm.generateBriefing(date = "2026-04-30")
        advanceUntilIdle()

        assertEquals(
            "Network error — check your connection and try again.",
            vm.error.value
        )
    }

    @Test
    fun generateBriefing_demotesUnresolvedCloudIdsIntoPendingSync() = runTest(dispatcher) {
        // "cloud-99" is not in the dao mocks → returns null → demote.
        coEvery { taskRepository.getIdByCloudId("cloud-99") } returns null
        coEvery { api.getDailyBriefing(any()) } returns DailyBriefingResponse(
            greeting = "Good morning!",
            topPriorities = listOf(
                BriefingPriorityResponse("cloud-1", "Fix bug", "Due today"),
                BriefingPriorityResponse("cloud-99", "Cross-device task", "Created on phone")
            ),
            headsUp = emptyList(),
            suggestedOrder = listOf(
                SuggestedTaskResponse("cloud-99", "Cross-device task", "9:30", "Suggested order")
            ),
            habitReminders = emptyList(),
            dayType = "light"
        )

        val vm = newViewModel()
        vm.generateBriefing(date = "2026-04-30")
        advanceUntilIdle()

        val briefing = vm.briefing.value
        assertNotNull(briefing)
        // Resolved priority + suggestion still render.
        assertEquals(1, briefing!!.topPriorities.size)
        assertEquals(1L, briefing.topPriorities[0].taskId)
        assertTrue(briefing.suggestedOrder.isEmpty())
        // Unresolved title surfaces once (deduped via distinct()).
        assertEquals(listOf("Cross-device task"), briefing.pendingSyncTitles)
    }
}
