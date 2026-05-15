package com.averycorp.prismtask.ui.screens.batch

import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.preferences.NdPreferences
import com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
import com.averycorp.prismtask.data.remote.api.AmbiguousEntityHintResponse
import com.averycorp.prismtask.data.remote.api.BatchParseResponse
import com.averycorp.prismtask.data.remote.api.ProposedMutationResponse
import com.averycorp.prismtask.data.repository.BatchOperationsRepository
import io.mockk.coEvery
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [BatchPreviewViewModel]'s ambiguity-handling paths. The
 * audit (`cowork_outputs/medication_ambiguous_name_resolution_REPORT.md`)
 * verdicted RED-P1 on the silent-wrong-pick risk: when Haiku flags a phrase
 * as ambiguous AND emits a mutation for one of the candidates, the user
 * could approve a wrong-medication dose without realising. The auto-strip
 * safeguard in [BatchPreviewViewModel.loadPreview] is the belt-and-suspenders
 * guard regardless of whether the picker is ever shown. The GREEN-GO pass
 * adds a deterministic [BatchOperationsRepository.BatchParseOutcome] hook
 * that lets the matcher commit known-correct entity_ids; tests covering
 * the override + the new low-confidence guard live below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatchPreviewViewModelAmbiguityTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var repository: BatchOperationsRepository
    private lateinit var undoBus: BatchUndoEventBus
    private lateinit var ndPreferencesDataStore: NdPreferencesDataStore
    private lateinit var customBrainModePreferences:
        com.averycorp.prismtask.data.preferences.CustomBrainModePreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk()
        undoBus = mockk(relaxed = true)
        ndPreferencesDataStore = mockk()
        every { ndPreferencesDataStore.ndPreferencesFlow } returns flowOf(NdPreferences())
        customBrainModePreferences = mockk()
        every { customBrainModePreferences.observe() } returns flowOf(emptyList())
        every { customBrainModePreferences.observeActiveName() } returns flowOf(null)
        every { customBrainModePreferences.observeActive() } returns flowOf(null)
        coEvery { repository.getTagNamesForTasks(any()) } returns emptyMap()
        coEvery { repository.getMedicationsByIds(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadPreview_stripsMutationsListedInAmbiguousCandidates() = runTest(dispatcher) {
        stubParse(
            response = BatchParseResponse(
                mutations = listOf(
                    medicationCompleteMutation(entityId = "42", slotKey = "morning")
                ),
                confidence = 0.5f,
                ambiguousEntities = listOf(
                    AmbiguousEntityHintResponse(
                        phrase = "Wellbutrin",
                        candidateEntityType = "MEDICATION",
                        candidateEntityIds = listOf("42", "43"),
                        note = "Two medications match"
                    )
                )
            )
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("took my Wellbutrin")
        advanceUntilIdle()

        val loaded = viewModel.state.value as BatchPreviewState.Loaded
        assertTrue(
            "ambiguous-id mutation should be stripped before reaching the UI",
            loaded.mutations.isEmpty()
        )
        assertEquals(1, loaded.strippedAmbiguousCount)
        assertEquals(
            "stripped mutation retained for picker recovery",
            1,
            loaded.strippedMutations.size
        )
        assertEquals("42", loaded.strippedMutations.single().entityId)
    }

    @Test
    fun loadPreview_keepsMutationsNotListedInAmbiguousCandidates() = runTest(dispatcher) {
        stubParse(
            response = BatchParseResponse(
                mutations = listOf(
                    medicationCompleteMutation(entityId = "99", slotKey = "evening")
                ),
                confidence = 0.95f,
                ambiguousEntities = listOf(
                    AmbiguousEntityHintResponse(
                        phrase = "Wellbutrin",
                        candidateEntityType = "MEDICATION",
                        candidateEntityIds = listOf("42", "43")
                    )
                )
            )
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("complex command")
        advanceUntilIdle()

        val loaded = viewModel.state.value as BatchPreviewState.Loaded
        assertEquals(1, loaded.mutations.size)
        assertEquals("99", loaded.mutations.single().entityId)
        assertEquals(0, loaded.strippedAmbiguousCount)
    }

    @Test
    fun loadPreview_emptyAmbiguousEntities_keepsAllMutations() = runTest(dispatcher) {
        stubParse(
            response = BatchParseResponse(
                mutations = listOf(
                    medicationCompleteMutation(entityId = "42", slotKey = "morning"),
                    medicationCompleteMutation(entityId = "43", slotKey = "evening")
                ),
                confidence = 0.95f,
                ambiguousEntities = emptyList()
            )
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("took both meds")
        advanceUntilIdle()

        val loaded = viewModel.state.value as BatchPreviewState.Loaded
        assertEquals(2, loaded.mutations.size)
        assertEquals(0, loaded.strippedAmbiguousCount)
        assertTrue(loaded.strippedMutations.isEmpty())
    }

    @Test
    fun loadPreview_shortCircuitsForUnambiguousMedicationOnlyCommand() = runTest(dispatcher) {
        // Matcher committed entity_id "42" — Haiku still flagged Wellbutrin as
        // ambiguous, but the override must keep the committed mutation in the
        // live list. This proves the deterministic pre-resolver wins over a
        // false-positive Haiku ambiguity flag.
        stubParse(
            response = BatchParseResponse(
                mutations = listOf(
                    medicationCompleteMutation(entityId = "42", slotKey = "morning")
                ),
                confidence = 0.95f,
                ambiguousEntities = listOf(
                    AmbiguousEntityHintResponse(
                        phrase = "Wellbutrin",
                        candidateEntityType = "MEDICATION",
                        candidateEntityIds = listOf("42", "43")
                    )
                )
            ),
            committedIds = setOf("42")
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("took my Wellbutrin")
        advanceUntilIdle()

        val loaded = viewModel.state.value as BatchPreviewState.Loaded
        assertEquals(
            "committed match overrides Haiku's ambiguity flag",
            1,
            loaded.mutations.size
        )
        assertEquals("42", loaded.mutations.single().entityId)
        assertEquals(0, loaded.strippedAmbiguousCount)
    }

    @Test
    fun loadPreview_stripsLowConfidenceMedicationMutation() = runTest(dispatcher) {
        // Haiku confidence below the 0.85 floor + medication NOT committed by
        // the matcher → strip and surface a synthetic ambiguous hint so the
        // user can pick. This is the audit's failure-mode #2 firewall.
        stubParse(
            response = BatchParseResponse(
                mutations = listOf(
                    medicationCompleteMutation(entityId = "42", slotKey = "morning")
                ),
                confidence = 0.6f,
                ambiguousEntities = emptyList()
            )
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("took my Welbutrn") // typo — matcher returns NoMatch
        advanceUntilIdle()

        val loaded = viewModel.state.value as BatchPreviewState.Loaded
        assertTrue(loaded.mutations.isEmpty())
        assertEquals(1, loaded.strippedAmbiguousCount)
        assertEquals(
            "synthetic ambiguous hint surfaced for stripped low-confidence med",
            1,
            loaded.ambiguousEntities.size
        )
        assertEquals(
            listOf("42"),
            loaded.ambiguousEntities.single().candidateEntityIds
        )
    }

    @Test
    fun loadPreview_keepsLowConfidenceTaskMutation() = runTest(dispatcher) {
        // TASK mutations are intentionally exempt from the medication
        // confidence floor — wrong-day scheduling is recoverable, wrong-
        // medication is not. Haiku confidence well below 0.85 must still
        // pass the task mutation through.
        stubParse(
            response = BatchParseResponse(
                mutations = listOf(
                    ProposedMutationResponse(
                        entityType = "TASK",
                        entityId = "7",
                        mutationType = "RESCHEDULE",
                        proposedNewValues = mapOf("due_date" to "2026-05-02"),
                        humanReadableDescription = "Reschedule task 7"
                    )
                ),
                confidence = 0.4f,
                ambiguousEntities = emptyList()
            )
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("push my task to friday")
        advanceUntilIdle()

        val loaded = viewModel.state.value as BatchPreviewState.Loaded
        assertEquals(1, loaded.mutations.size)
        assertEquals(0, loaded.strippedAmbiguousCount)
    }

    @Test
    fun resolveAmbiguity_substitutesPickedEntityIdAndRemovesHint() = runTest(dispatcher) {
        stubParse(
            response = BatchParseResponse(
                mutations = listOf(
                    medicationCompleteMutation(entityId = "42", slotKey = "morning")
                ),
                confidence = 0.4f,
                ambiguousEntities = listOf(
                    AmbiguousEntityHintResponse(
                        phrase = "Wellbutrin",
                        candidateEntityType = "MEDICATION",
                        candidateEntityIds = listOf("42", "43")
                    )
                )
            )
        )
        coEvery { repository.getMedicationsByIds(listOf(42L, 43L)) } returns listOf(
            MedicationEntity(id = 42L, name = "Wellbutrin XL 150mg"),
            MedicationEntity(id = 43L, name = "Wellbutrin SR 100mg")
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("took my Wellbutrin")
        advanceUntilIdle()

        val loadedBefore = viewModel.state.value as BatchPreviewState.Loaded
        assertEquals(1, loadedBefore.strippedAmbiguousCount)
        assertNotNull(loadedBefore.medicationCandidates[0])

        viewModel.resolveAmbiguity(hintIndex = 0, pickedEntityId = "43")

        val loadedAfter = viewModel.state.value as BatchPreviewState.Loaded
        assertEquals(
            "picker resolution adds one mutation back to the list",
            1,
            loadedAfter.mutations.size
        )
        val resolved = loadedAfter.mutations.single()
        assertEquals("43", resolved.entityId)
        assertEquals("COMPLETE", resolved.mutationType)
        assertEquals("morning", resolved.proposedNewValues["slot_key"])
        assertTrue("hint dropped after resolution", loadedAfter.ambiguousEntities.isEmpty())
        assertFalse(
            "stripped mutation consumed by resolution",
            loadedAfter.strippedMutations.any { it.entityId == "42" }
        )
        assertEquals(0, loadedAfter.strippedAmbiguousCount)
    }

    @Test
    fun resolveAmbiguity_recoversAllStrippedMutationsForOneHint() = runTest(dispatcher) {
        // "skip my morning AND evening Wellbutrin" — same ambiguous phrase
        // can fan out into multiple stripped mutations (different slot_keys).
        // Picking once must recover BOTH; the previous firstOrNull shape
        // silently dropped everything except the first.
        stubParse(
            response = BatchParseResponse(
                mutations = listOf(
                    medicationCompleteMutation(entityId = "42", slotKey = "morning"),
                    medicationCompleteMutation(entityId = "42", slotKey = "evening")
                ),
                confidence = 0.4f,
                ambiguousEntities = listOf(
                    AmbiguousEntityHintResponse(
                        phrase = "Wellbutrin",
                        candidateEntityType = "MEDICATION",
                        candidateEntityIds = listOf("42", "43")
                    )
                )
            )
        )
        coEvery { repository.getMedicationsByIds(listOf(42L, 43L)) } returns listOf(
            MedicationEntity(id = 42L, name = "Wellbutrin XL 150mg"),
            MedicationEntity(id = 43L, name = "Wellbutrin SR 100mg")
        )

        val viewModel = newViewModel()
        viewModel.loadPreview("took my morning and evening Wellbutrin")
        advanceUntilIdle()

        val before = viewModel.state.value as BatchPreviewState.Loaded
        assertEquals(2, before.strippedAmbiguousCount)
        assertEquals(2, before.strippedMutations.size)

        viewModel.resolveAmbiguity(hintIndex = 0, pickedEntityId = "43")

        val after = viewModel.state.value as BatchPreviewState.Loaded
        assertEquals(
            "both stripped mutations recovered with the picked id",
            2,
            after.mutations.size
        )
        assertTrue(after.mutations.all { it.entityId == "43" })
        val slotKeys = after.mutations.map { it.proposedNewValues["slot_key"] }.toSet()
        assertEquals(setOf("morning", "evening"), slotKeys)
        assertTrue("hint dropped", after.ambiguousEntities.isEmpty())
        assertEquals(0, after.strippedAmbiguousCount)
        assertTrue("strippedMutations drained", after.strippedMutations.isEmpty())
    }

    private fun newViewModel(): BatchPreviewViewModel = BatchPreviewViewModel(
        repository = repository,
        undoBus = undoBus,
        ndPreferencesDataStore = ndPreferencesDataStore,
        customBrainModePreferences = customBrainModePreferences
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

    private fun medicationCompleteMutation(
        entityId: String,
        slotKey: String,
        date: String = "2026-04-25"
    ): ProposedMutationResponse = ProposedMutationResponse(
        entityType = "MEDICATION",
        entityId = entityId,
        mutationType = "COMPLETE",
        proposedNewValues = mapOf("slot_key" to slotKey, "date" to date),
        humanReadableDescription = "COMPLETE on medication $entityId"
    )
}
