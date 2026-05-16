package com.averycorp.prismtask.ui.screens.batch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.preferences.CustomBrainModePreferences
import com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
import com.averycorp.prismtask.data.preferences.effectiveNdPreferencesFlow
import com.averycorp.prismtask.data.remote.api.AmbiguousEntityHintResponse
import com.averycorp.prismtask.data.remote.api.ProposedMutationResponse
import com.averycorp.prismtask.data.repository.BatchOperationsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * State + actions for the BatchPreviewScreen.
 *
 * The hosting NavGraph route receives the user's command text via
 * SavedStateHandle (set by the QuickAddBar caller) and triggers
 * [loadPreview] on first composition. Approve commits via the
 * repository and emits [BatchEvent.Approved] (with the freshly
 * created `batch_id`) so the upstream Snackbar can offer Undo.
 */
@HiltViewModel
class BatchPreviewViewModel
@Inject
constructor(
    private val repository: BatchOperationsRepository,
    private val undoBus: BatchUndoEventBus,
    ndPreferencesDataStore: NdPreferencesDataStore,
    customBrainModePreferences: CustomBrainModePreferences
) : ViewModel() {
    private val _state = MutableStateFlow<BatchPreviewState>(BatchPreviewState.Idle)
    val state: StateFlow<BatchPreviewState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BatchEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BatchEvent> = _events.asSharedFlow()

    /** Per-mutation include flag, keyed by index in the loaded mutations list. */
    private val _excluded = MutableStateFlow<Set<Int>>(emptySet())
    val excluded: StateFlow<Set<Int>> = _excluded.asStateFlow()

    // Synchronous re-entry latch for the approve path. Belt-and-suspenders
    // on top of the `_state` machine guards: under a lifecycle resume
    // (foreground after backgrounding) Compose can deliver a second click
    // event to the freshly re-laid-out button before the first launch's
    // `_state.value = Committing` mutation has been observed by the next
    // recomposition. `compareAndSet` is atomic across threads, so the
    // second call short-circuits before reaching `applyBatch`. The latch
    // is reset on the Error path so Retry from Error still works; success
    // leaves it set forever since `Applied` is terminal and the screen
    // pops on the `Approved` event.
    private val _isApproving = AtomicBoolean(false)

    /**
     * Calm Mode (sensory-reduction tier) suppresses the inline disambiguation
     * picker and routes the user to a Cancel-and-retype flow instead. Other
     * ND modes leave the picker visible — only Calm Mode signals "fewer
     * decision affordances at once."
     *
     * Reads the *effective* flow so an active [com.averycorp.prismtask
     * .data.preferences.CustomBrainMode] that forces `calmModeEnabled` on
     * (or off) takes precedence over the base setting.
     */
    val simplifiedUi: StateFlow<Boolean> = ndPreferencesDataStore
        .effectiveNdPreferencesFlow(customBrainModePreferences)
        .map { it.calmModeEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun loadPreview(commandText: String) {
        // Re-entry guards. The screen's `LaunchedEffect(commandText)` can
        // re-fire on recomposition (Compose Navigation transition / config
        // change), and we MUST NOT re-run Haiku once the user has already
        // tapped Approve — re-parsing would burn a Haiku call on now-stale
        // state and, for non-idempotent operations like tag adds or date
        // increments, risk a double-apply if the user taps Approve on the
        // re-fired preview.
        when (val current = _state.value) {
            is BatchPreviewState.Loading,
            is BatchPreviewState.Committing,
            is BatchPreviewState.Applied -> return
            is BatchPreviewState.Loaded ->
                if (current.commandText == commandText) return
            BatchPreviewState.Idle, is BatchPreviewState.Error -> Unit
        }
        _state.value = BatchPreviewState.Loading(commandText)
        viewModelScope.launch {
            try {
                val outcome = repository.parseCommand(commandText)
                val response = outcome.response
                // Belt-and-suspenders: even if Haiku flagged a phrase as
                // ambiguous via `ambiguous_entities`, Hard Rule #3 in the
                // system prompt is non-deterministic — Haiku may still emit
                // a mutation for one of the candidates. Strip those before
                // they can be silently approved.
                //
                // BUT: a mutation whose entity_id matches one of the
                // pre-resolver's *committed* matches stays — the local
                // matcher already proved it's unambiguous, so the
                // safeguard would only suppress correct mutations.
                val ambiguousIds: Set<String> = response.ambiguousEntities
                    .flatMap { it.candidateEntityIds }
                    .toSet()
                val committedIds = outcome.committedMedicationIds
                val (autoStripped, afterStrip) = response.mutations.partition {
                    it.entityId in ambiguousIds && it.entityId !in committedIds
                }
                // Confidence guard (audit failure mode #2 — false-confident
                // typo): MEDICATION mutations from Haiku are stripped if
                // confidence is below 0.85 AND the entity_id wasn't
                // committed by the deterministic matcher. TASK mutations
                // are left alone — wrong-day scheduling is recoverable,
                // wrong-medication is not.
                val (lowConfStripped, keptMutations) = afterStrip.partition {
                    it.entityType == "MEDICATION" &&
                        response.confidence < MEDICATION_CONFIDENCE_FLOOR &&
                        it.entityId !in committedIds
                }
                val strippedMutations = autoStripped + lowConfStripped
                val ambiguousEntities = augmentAmbiguityForLowConfidence(
                    response.ambiguousEntities,
                    lowConfStripped
                )
                val medCandidates = collectMedicationCandidates(ambiguousEntities)
                val tagChangeTaskIds = keptMutations
                    .asSequence()
                    .filter { it.mutationType == "TAG_CHANGE" && it.entityType == "TASK" }
                    .mapNotNull { it.entityId.toLongOrNull() }
                    .distinct()
                    .toList()
                val currentTags = repository.getTagNamesForTasks(tagChangeTaskIds)
                _state.value = BatchPreviewState.Loaded(
                    commandText = commandText,
                    mutations = keptMutations,
                    confidence = response.confidence,
                    ambiguousEntities = ambiguousEntities,
                    currentTags = currentTags,
                    strippedAmbiguousCount = strippedMutations.size,
                    strippedMutations = strippedMutations,
                    medicationCandidates = medCandidates
                )
                _excluded.value = emptySet()
            } catch (e: HttpException) {
                _state.value = if (e.code() == HTTP_AI_FEATURES_DISABLED) {
                    BatchPreviewState.Error(
                        commandText = commandText,
                        kind = BatchPreviewErrorKind.AiGate451,
                        message = "AI features are disabled — enable them in Settings → AI Features."
                    )
                } else {
                    BatchPreviewState.Error(
                        commandText = commandText,
                        kind = BatchPreviewErrorKind.Network,
                        message = e.message ?: "Backend unavailable"
                    )
                }
            } catch (e: Exception) {
                _state.value = BatchPreviewState.Error(
                    commandText = commandText,
                    kind = BatchPreviewErrorKind.ParseFailure,
                    message = e.message ?: "Failed to parse batch command"
                )
            }
        }
    }

    /**
     * For each medication mutation we just stripped on low confidence, fold
     * a synthetic `AmbiguousEntityHintResponse` into the hint list so the
     * banner + picker still surface the choice instead of silently dropping
     * it. The note copy is what the user sees beside the row.
     */
    private fun augmentAmbiguityForLowConfidence(
        existing: List<AmbiguousEntityHintResponse>,
        lowConfStripped: List<ProposedMutationResponse>
    ): List<AmbiguousEntityHintResponse> {
        if (lowConfStripped.isEmpty()) return existing
        val existingByPhraseAndIds = existing.map {
            it.phrase to it.candidateEntityIds.toSet()
        }.toSet()
        val additions = lowConfStripped.mapNotNull { mutation ->
            val phrase = mutation.humanReadableDescription.ifBlank { mutation.entityId }
            val ids = listOf(mutation.entityId)
            if ((phrase to ids.toSet()) in existingByPhraseAndIds) {
                null
            } else {
                AmbiguousEntityHintResponse(
                    phrase = phrase,
                    candidateEntityType = "MEDICATION",
                    candidateEntityIds = ids,
                    note = "Couldn't confirm the medication for this command — pick below or rephrase."
                )
            }
        }
        return existing + additions
    }

    /**
     * Per-hint medication candidate options for the disambiguation picker.
     * Only populated when the hint targets MEDICATION and the candidate IDs
     * resolve to live local rows — archived/missing meds are filtered out
     * so the picker never offers a stale choice.
     */
    private suspend fun collectMedicationCandidates(
        hints: List<AmbiguousEntityHintResponse>
    ): Map<Int, List<MedicationCandidate>> {
        val out = mutableMapOf<Int, List<MedicationCandidate>>()
        hints.forEachIndexed { idx, hint ->
            if (hint.candidateEntityType != "MEDICATION") return@forEachIndexed
            val ids = hint.candidateEntityIds.mapNotNull { it.toLongOrNull() }
            if (ids.isEmpty()) return@forEachIndexed
            val resolved = repository.getMedicationsByIds(ids)
                .map { MedicationCandidate(it.id.toString(), it.name, it.displayLabel) }
            if (resolved.isNotEmpty()) out[idx] = resolved
        }
        return out
    }

    /**
     * User picked [pickedEntityId] from the picker for [hintIndex]. Look up
     * EVERY stripped mutation that originally targeted one of this hint's
     * candidates, swap each entity_id to the user's pick, append the lot
     * to the mutations list, and drop both the hint + the recovered
     * stripped mutations from state. The picker is one-way per hint —
     * there's no "change my mind" path; the user can still uncheck the
     * row from the normal mutation list.
     *
     * Multi-recovery matters when a single ambiguous phrase fans out into
     * several mutations. Example: "skip my morning AND evening Wellbutrin"
     * produces two SKIP mutations (one per slot_key). Picking once should
     * recover both — the previous `firstOrNull` shape silently dropped
     * everything except the first match.
     */
    fun resolveAmbiguity(hintIndex: Int, pickedEntityId: String) {
        val loaded = _state.value as? BatchPreviewState.Loaded ?: return
        val hint = loaded.ambiguousEntities.getOrNull(hintIndex) ?: return
        if (pickedEntityId !in hint.candidateEntityIds) return
        val candidateSet = hint.candidateEntityIds.toSet()
        val recovered = loaded.strippedMutations.filter {
            it.entityId in candidateSet && it.entityType == hint.candidateEntityType
        }
        if (recovered.isEmpty()) return
        val resolved = recovered.map { it.copy(entityId = pickedEntityId) }
        val recoveredSet = recovered.toSet()
        _state.value = loaded.copy(
            mutations = loaded.mutations + resolved,
            ambiguousEntities = loaded.ambiguousEntities.filterIndexed { i, _ -> i != hintIndex },
            strippedMutations = loaded.strippedMutations - recoveredSet,
            strippedAmbiguousCount = (loaded.strippedAmbiguousCount - recovered.size).coerceAtLeast(0),
            medicationCandidates = loaded.medicationCandidates
                .filterKeys { it != hintIndex }
                .mapKeys { (k, _) -> if (k > hintIndex) k - 1 else k }
        )
    }

    fun toggleExclusion(index: Int) {
        val current = _excluded.value
        _excluded.value = if (index in current) current - index else current + index
    }

    fun approve() {
        // Atomic re-entry guard — see `_isApproving` KDoc. Test 1.3d
        // (May 10, 2026) repro'd `applyBatch` double-firing after a
        // foreground/background cycle; the state-machine guard alone
        // had a narrow same-frame race window. CAS closes it without
        // relying on `_state.value` mutation timing.
        if (!_isApproving.compareAndSet(false, true)) return
        val loaded = _state.value as? BatchPreviewState.Loaded
        if (loaded == null) {
            _isApproving.set(false)
            return
        }
        val toApply = loaded.mutations.filterIndexed { idx, _ -> idx !in _excluded.value }
        if (toApply.isEmpty()) {
            _isApproving.set(false)
            viewModelScope.launch {
                _events.emit(BatchEvent.Cancelled(reason = "No mutations selected"))
            }
            return
        }
        _state.value = BatchPreviewState.Committing(loaded.commandText)
        viewModelScope.launch {
            try {
                val result = repository.applyBatch(loaded.commandText, toApply)
                undoBus.notifyApplied(
                    BatchAppliedEvent(
                        batchId = result.batchId,
                        commandText = loaded.commandText,
                        appliedCount = result.appliedCount,
                        skippedCount = result.skipped.size
                    )
                )
                _state.value = BatchPreviewState.Applied(
                    batchId = result.batchId,
                    appliedCount = result.appliedCount,
                    skippedCount = result.skipped.size
                )
                _events.emit(
                    BatchEvent.Approved(
                        batchId = result.batchId,
                        appliedCount = result.appliedCount,
                        skippedCount = result.skipped.size
                    )
                )
                // success: leave _isApproving=true. Applied is terminal;
                // the screen pops on the Approved event collector.
            } catch (e: Exception) {
                _state.value = BatchPreviewState.Error(
                    commandText = loaded.commandText,
                    kind = BatchPreviewErrorKind.Network,
                    message = e.message ?: "Failed to commit batch"
                )
                // Error is recoverable via Retry → loadPreview → user
                // re-taps Approve, so the latch must be released.
                _isApproving.set(false)
            }
        }
    }

    fun cancel() {
        viewModelScope.launch {
            _events.emit(BatchEvent.Cancelled(reason = null))
        }
    }

    private companion object {
        // 0.85 is the boundary the audit doc fixed for failure mode #2:
        // below this floor, MEDICATION-typed mutations get auto-stripped
        // unless the deterministic matcher confirmed the medication.
        // TASK / HABIT / PROJECT mutations stay regardless — the
        // wrong-medication blast radius is the only one we won't tolerate
        // silently.
        const val MEDICATION_CONFIDENCE_FLOOR = 0.85f
        const val HTTP_AI_FEATURES_DISABLED = 451
    }
}

sealed class BatchPreviewState {
    data object Idle : BatchPreviewState()
    data class Loading(val commandText: String) : BatchPreviewState()
    data class Loaded(
        val commandText: String,
        val mutations: List<ProposedMutationResponse>,
        val confidence: Float,
        val ambiguousEntities: List<AmbiguousEntityHintResponse>,
        /**
         * Current tag names keyed by task id, populated only for tasks
         * targeted by TAG_CHANGE mutations. The preview row renders a
         * before/after diff against this list.
         */
        val currentTags: Map<Long, List<String>> = emptyMap(),
        /**
         * Number of mutations that were dropped from [mutations] because
         * their `entity_id` appeared in `ambiguous_entities[].candidate_entity_ids`.
         * Surfaced in the AmbiguityBanner copy so the user knows that
         * Haiku-flagged-ambiguous mutations were withheld.
         */
        val strippedAmbiguousCount: Int = 0,
        /**
         * The mutations that were stripped, retained so the picker can
         * recover the original `mutation_type` / `proposed_new_values`
         * (date, slot_key, etc.) when the user picks a candidate.
         */
        val strippedMutations: List<ProposedMutationResponse> = emptyList(),
        /**
         * Resolved medication candidates per hint index, for picker UI.
         * Only present for MEDICATION-typed hints whose candidate IDs
         * still resolve to live local rows.
         */
        val medicationCandidates: Map<Int, List<MedicationCandidate>> = emptyMap()
    ) : BatchPreviewState()
    data class Committing(val commandText: String) : BatchPreviewState()

    /**
     * Terminal state after [BatchPreviewViewModel.approve] commits the
     * batch successfully. The screen pops back via the `Approved` event
     * collector almost immediately, so this is rendered the same as
     * [Committing] (a loading body) — its load-bearing job is to
     * short-circuit a re-fire of `loadPreview` if `LaunchedEffect`
     * recomposes during the pop transition.
     */
    data class Applied(
        val batchId: String,
        val appliedCount: Int,
        val skippedCount: Int
    ) : BatchPreviewState()
    data class Error(
        val commandText: String,
        val kind: BatchPreviewErrorKind,
        val message: String
    ) : BatchPreviewState()
}

/**
 * Distinguishes the user-actionable failure modes for batch parsing so the
 * preview screen can render copy that points at the right fix:
 *  - [AiGate451] — master AI toggle is off (HTTP 451). Send the user to
 *    Settings, no Retry.
 *  - [Network]   — backend unreachable / 5xx. Retry is meaningful.
 *  - [ParseFailure] — backend reachable but response unparseable / unexpected.
 */
enum class BatchPreviewErrorKind { AiGate451, Network, ParseFailure }

data class MedicationCandidate(
    val entityId: String,
    val name: String,
    val displayLabel: String?
)

sealed class BatchEvent {
    data class Approved(val batchId: String, val appliedCount: Int, val skippedCount: Int) : BatchEvent()
    data class Cancelled(val reason: String?) : BatchEvent()
}
