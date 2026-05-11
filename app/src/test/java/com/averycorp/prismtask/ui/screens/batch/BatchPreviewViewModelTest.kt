package com.averycorp.prismtask.ui.screens.batch

import app.cash.turbine.test
import com.averycorp.prismtask.data.preferences.NdPreferences
import com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
import com.averycorp.prismtask.data.remote.api.BatchParseResponse
import com.averycorp.prismtask.data.remote.api.ProposedMutationResponse
import com.averycorp.prismtask.data.repository.BatchOperationsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Covers the four [BatchPreviewState] transitions the audit's Phase 1.1
 * flagged MISSING — Ready, EmptyMutations (treated as Loaded with no
 * mutations + a friendly message), AiGate451, ParseFailure — plus the
 * happy-path apply transition. The ambiguity-handling branches live in
 * [BatchPreviewViewModelAmbiguityTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatchPreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var repository: BatchOperationsRepository
    private lateinit var undoBus: BatchUndoEventBus
    private lateinit var ndPreferencesDataStore: NdPreferencesDataStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk()
        undoBus = mockk(relaxed = true)
        ndPreferencesDataStore = mockk()
        every { ndPreferencesDataStore.ndPreferencesFlow } returns flowOf(NdPreferences())
        coEvery { repository.getTagNamesForTasks(any()) } returns emptyMap()
        coEvery { repository.getMedicationsByIds(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadPreview_success_transitionsToLoadedWithMutations() = runTest(dispatcher) {
        stubParse(
            BatchParseResponse(
                mutations = listOf(taskRescheduleMutation(entityId = "7", due = "2026-05-03")),
                confidence = 0.95f,
                ambiguousEntities = emptyList()
            )
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("push my task to friday")
        advanceUntilIdle()

        val loaded = viewModel.state.value as BatchPreviewState.Loaded
        assertEquals("push my task to friday", loaded.commandText)
        assertEquals(1, loaded.mutations.size)
        assertEquals("7", loaded.mutations.single().entityId)
    }

    @Test
    fun loadPreview_emptyMutations_transitionsToLoadedWithEmptyList() = runTest(dispatcher) {
        // The screen renders an explicit "no matching changes" hint when
        // mutations is empty; the state must still be Loaded (not Error)
        // so the user can hit Cancel without seeing a parse-failure scare.
        stubParse(
            BatchParseResponse(
                mutations = emptyList(),
                confidence = 0.95f,
                ambiguousEntities = emptyList()
            )
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("nothing to do")
        advanceUntilIdle()

        val loaded = viewModel.state.value as BatchPreviewState.Loaded
        assertTrue(loaded.mutations.isEmpty())
        assertEquals("nothing to do", loaded.commandText)
    }

    @Test
    fun loadPreview_http451_transitionsToAiGateError() = runTest(dispatcher) {
        // AiFeatureGateInterceptor short-circuits AI-touching requests with
        // a synthetic 451 when the user has disabled AI features. The
        // preview must surface a Settings-pointing copy, not a generic
        // parse-failure message.
        coEvery { repository.parseCommand(any()) } throws HttpException(
            Response.error<Any>(
                451,
                "{}".toResponseBody("application/json".toMediaType())
            )
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("delete my tasks")
        advanceUntilIdle()

        val error = viewModel.state.value as BatchPreviewState.Error
        assertEquals(BatchPreviewErrorKind.AiGate451, error.kind)
        assertTrue(
            "AI-gate copy should mention Settings",
            error.message.contains("Settings", ignoreCase = true)
        )
    }

    @Test
    fun loadPreview_otherHttpFailure_transitionsToNetworkError() = runTest(dispatcher) {
        coEvery { repository.parseCommand(any()) } throws HttpException(
            Response.error<Any>(
                503,
                "{}".toResponseBody("application/json".toMediaType())
            )
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("anything")
        advanceUntilIdle()

        val error = viewModel.state.value as BatchPreviewState.Error
        assertEquals(BatchPreviewErrorKind.Network, error.kind)
    }

    @Test
    fun loadPreview_genericException_transitionsToParseFailure() = runTest(dispatcher) {
        coEvery { repository.parseCommand(any()) } throws IllegalStateException("malformed")

        val viewModel = newViewModel()
        viewModel.loadPreview("anything")
        advanceUntilIdle()

        val error = viewModel.state.value as BatchPreviewState.Error
        assertEquals(BatchPreviewErrorKind.ParseFailure, error.kind)
        assertEquals("malformed", error.message)
    }

    @Test
    fun approve_success_emitsApprovedEventAndNotifiesUndoBus() = runTest(dispatcher) {
        stubParse(
            BatchParseResponse(
                mutations = listOf(taskRescheduleMutation(entityId = "7", due = "2026-05-03")),
                confidence = 0.95f,
                ambiguousEntities = emptyList()
            )
        )
        coEvery { repository.applyBatch(any(), any()) } returns
            BatchOperationsRepository.BatchApplyResult(
                batchId = "batch-abc",
                commandText = "push my task to friday",
                appliedCount = 1,
                skipped = emptyList()
            )

        val viewModel = newViewModel()
        viewModel.loadPreview("push my task to friday")
        advanceUntilIdle()

        // Turbine subscribes BEFORE we trigger approve, which matters because
        // _events is a SharedFlow with replay = 0 — emissions delivered
        // before subscription are lost. With Turbine, the test() block
        // owns the subscription lifecycle and pumps awaitItem suspensions
        // through the StandardTestDispatcher cleanly.
        viewModel.events.test {
            viewModel.approve()
            val event = awaitItem()
            assertTrue(event is BatchEvent.Approved)
            val approved = event as BatchEvent.Approved
            assertEquals("batch-abc", approved.batchId)
            assertEquals(1, approved.appliedCount)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { undoBus.notifyApplied(match { it.batchId == "batch-abc" }) }
    }

    @Test
    fun approve_success_transitionsToAppliedTerminalState() = runTest(dispatcher) {
        stubParse(
            BatchParseResponse(
                mutations = listOf(taskRescheduleMutation(entityId = "7", due = "2026-05-03")),
                confidence = 0.95f,
                ambiguousEntities = emptyList()
            )
        )
        coEvery { repository.applyBatch(any(), any()) } returns
            BatchOperationsRepository.BatchApplyResult(
                batchId = "batch-applied",
                commandText = "anything",
                appliedCount = 3,
                skipped = listOf(
                    BatchOperationsRepository.SkippedMutation(
                        mutation = taskRescheduleMutation(entityId = "9", due = "2026-05-04"),
                        reason = "stale"
                    )
                )
            )

        val viewModel = newViewModel()
        viewModel.loadPreview("anything")
        advanceUntilIdle()
        viewModel.approve()
        advanceUntilIdle()

        val terminal = viewModel.state.value as BatchPreviewState.Applied
        assertEquals("batch-applied", terminal.batchId)
        assertEquals(3, terminal.appliedCount)
        assertEquals(1, terminal.skippedCount)
    }

    @Test
    fun loadPreview_doesNotReParseWhenStateIsApplied() = runTest(dispatcher) {
        stubParse(
            BatchParseResponse(
                mutations = listOf(taskRescheduleMutation(entityId = "7", due = "2026-05-03")),
                confidence = 0.95f,
                ambiguousEntities = emptyList()
            )
        )
        coEvery { repository.applyBatch(any(), any()) } returns
            BatchOperationsRepository.BatchApplyResult(
                batchId = "batch-applied",
                commandText = "complete all tasks today",
                appliedCount = 1,
                skipped = emptyList()
            )

        val viewModel = newViewModel()
        viewModel.loadPreview("complete all tasks today")
        advanceUntilIdle()
        viewModel.approve()
        advanceUntilIdle()
        // Sanity: state is now terminal Applied.
        assertTrue(viewModel.state.value is BatchPreviewState.Applied)

        // Simulate the LaunchedEffect re-firing during the pop transition —
        // this is the audit's CAUSE-C symptom (Phase 1, A4 Defect C-1):
        // before the fix, the guard only caught Loading, so a re-call would
        // pass through, kick a second Haiku call, and overwrite Applied.
        viewModel.loadPreview("complete all tasks today")
        advanceUntilIdle()

        // parseCommand must have run exactly once, and state must still be
        // the post-approve terminal Applied.
        coVerify(exactly = 1) { repository.parseCommand("complete all tasks today") }
        assertTrue(viewModel.state.value is BatchPreviewState.Applied)
    }

    @Test
    fun loadPreview_doesNotReParseWhenStateIsLoadedSameCommand() = runTest(dispatcher) {
        stubParse(
            BatchParseResponse(
                mutations = listOf(taskRescheduleMutation(entityId = "7", due = "2026-05-03")),
                confidence = 0.95f,
                ambiguousEntities = emptyList()
            )
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("push my task to friday")
        advanceUntilIdle()
        // First-pass populates exclusions; if we re-parsed, exclusions would
        // be wiped (line 118 in loadPreview). Lock one in to detect that.
        viewModel.toggleExclusion(0)

        viewModel.loadPreview("push my task to friday")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.parseCommand("push my task to friday") }
        assertTrue(0 in viewModel.excluded.value)
    }

    @Test
    fun approve_calledTwiceInSameFrame_invokesApplyBatchExactlyOnce() = runTest(dispatcher) {
        // Test 1.3d (May 10, 2026) repro: foreground→background→
        // foreground→tap Approve produced double-applied mutations. The
        // state-machine guard alone has a narrow race window if a single
        // physical tap is delivered to the recomposed button as two click
        // events before the first launch flips state to Committing. The
        // AtomicBoolean re-entry latch in approve() closes that window
        // regardless of state-machine timing.
        stubParse(
            BatchParseResponse(
                mutations = listOf(taskRescheduleMutation(entityId = "7", due = "2026-05-03")),
                confidence = 0.95f,
                ambiguousEntities = emptyList()
            )
        )
        coEvery { repository.applyBatch(any(), any()) } returns
            BatchOperationsRepository.BatchApplyResult(
                batchId = "batch-once",
                commandText = "push my task to friday",
                appliedCount = 1,
                skipped = emptyList()
            )

        val viewModel = newViewModel()
        viewModel.loadPreview("push my task to friday")
        advanceUntilIdle()

        // Synchronously fire approve() twice — simulates a same-frame
        // double-dispatch from the button's pointer input.
        viewModel.approve()
        viewModel.approve()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.applyBatch(any(), any()) }
        assertTrue(viewModel.state.value is BatchPreviewState.Applied)
    }

    @Test
    fun approve_afterErrorRecoveryAndRetry_appliesBatch() = runTest(dispatcher) {
        // Regression guard: the AtomicBoolean latch must reset on the
        // Error path so a user who hits Retry → Approve again actually
        // commits. Without the reset, post-error retries would silently
        // no-op.
        coEvery { repository.applyBatch(any(), any()) } throws IllegalStateException("first commit fails")
        stubParse(
            BatchParseResponse(
                mutations = listOf(taskRescheduleMutation(entityId = "7", due = "2026-05-03")),
                confidence = 0.95f,
                ambiguousEntities = emptyList()
            )
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("retry me")
        advanceUntilIdle()
        viewModel.approve()
        advanceUntilIdle()
        assertTrue(
            "First commit must surface as Error",
            viewModel.state.value is BatchPreviewState.Error
        )

        // Operator hits Retry → loadPreview re-runs → state goes Loaded
        // again. Then they tap Approve. With the latch reset on Error
        // path, this must reach applyBatch.
        coEvery { repository.applyBatch(any(), any()) } returns
            BatchOperationsRepository.BatchApplyResult(
                batchId = "batch-retry-ok",
                commandText = "retry me",
                appliedCount = 1,
                skipped = emptyList()
            )
        viewModel.loadPreview("retry me")
        advanceUntilIdle()
        viewModel.approve()
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.applyBatch(any(), any()) }
        assertTrue(viewModel.state.value is BatchPreviewState.Applied)
    }

    @Test
    fun loadPreview_reParsesWhenStateIsLoadedButCommandTextDiffers() = runTest(dispatcher) {
        // Regression guard: the same-commandText short-circuit must NOT
        // block legitimate re-parses for a different command (e.g. nav arg
        // genuinely changes). This case isn't on the BatchPreview entry
        // path today, but the guard shape must permit it.
        stubParse(
            BatchParseResponse(
                mutations = listOf(taskRescheduleMutation(entityId = "7", due = "2026-05-03")),
                confidence = 0.95f,
                ambiguousEntities = emptyList()
            )
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("first command")
        advanceUntilIdle()
        viewModel.loadPreview("second command")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.parseCommand("first command") }
        coVerify(exactly = 1) { repository.parseCommand("second command") }
    }

    private fun newViewModel(): BatchPreviewViewModel = BatchPreviewViewModel(
        repository = repository,
        undoBus = undoBus,
        ndPreferencesDataStore = ndPreferencesDataStore
    )

    private fun stubParse(
        response: BatchParseResponse,
        committedIds: Set<String> = emptySet()
    ) {
        coEvery { repository.parseCommand(any()) } returns
            BatchOperationsRepository.BatchParseOutcome(
                response = response,
                committedMedicationIds = committedIds
            )
    }

    private fun taskRescheduleMutation(
        entityId: String,
        due: String
    ): ProposedMutationResponse = ProposedMutationResponse(
        entityType = "TASK",
        entityId = entityId,
        mutationType = "RESCHEDULE",
        proposedNewValues = mapOf("due_date" to due),
        humanReadableDescription = "Reschedule task $entityId to $due"
    )
}
