package com.averycorp.prismtask.ui.screens.builtinupdates

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.domain.model.AcceptedChanges
import com.averycorp.prismtask.domain.model.TemplateDiff
import com.averycorp.prismtask.domain.usecase.BuiltInUpdateDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiffSelections(
    val acceptedFieldNames: Set<String> = emptySet(),
    val acceptedAddedStepIds: Set<String> = emptySet(),
    val acceptedRemovedStepIds: Set<String> = emptySet(),
    val acceptedModifiedStepIds: Set<String> = emptySet()
)

data class TemplateDiffUiState(
    val diff: TemplateDiff? = null,
    val selections: DiffSelections = DiffSelections(),
    val applied: Boolean = false,
    val notFound: Boolean = false
)

@HiltViewModel
class TemplateDiffViewModel @Inject constructor(
    private val detector: BuiltInUpdateDetector,
    private val habitRepository: HabitRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val templateKey: String = savedStateHandle.get<String>("templateKey").orEmpty()

    private val _uiState = MutableStateFlow(TemplateDiffUiState())
    val uiState: StateFlow<TemplateDiffUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            detector.refreshPendingUpdates()
            val diff = detector.diffFor(templateKey)
            if (diff == null) {
                _uiState.value = TemplateDiffUiState(notFound = true)
            } else {
                _uiState.value = TemplateDiffUiState(
                    diff = diff,
                    selections = defaultSelections(diff)
                )
            }
        }
    }

    fun toggleField(fieldName: String) = mutate {
        it.copy(
            acceptedFieldNames = it.acceptedFieldNames.toggle(fieldName)
        )
    }

    fun toggleAddedStep(stepId: String) = mutate {
        it.copy(acceptedAddedStepIds = it.acceptedAddedStepIds.toggle(stepId))
    }

    fun toggleRemovedStep(stepId: String) = mutate {
        it.copy(acceptedRemovedStepIds = it.acceptedRemovedStepIds.toggle(stepId))
    }

    fun toggleModifiedStep(stepId: String) = mutate {
        it.copy(acceptedModifiedStepIds = it.acceptedModifiedStepIds.toggle(stepId))
    }

    fun applySelected() {
        val state = _uiState.value
        val diff = state.diff ?: return
        viewModelScope.launch {
            val habit = habitRepository.getByTemplateKeyOnce(diff.templateKey) ?: return@launch
            val accepted = AcceptedChanges(
                habit = habit,
                acceptedFieldNames = state.selections.acceptedFieldNames,
                acceptedAddedStepIds = state.selections.acceptedAddedStepIds,
                acceptedRemovedStepIds = state.selections.acceptedRemovedStepIds,
                acceptedModifiedStepIds = state.selections.acceptedModifiedStepIds
            )
            detector.applyUpdate(diff, accepted)
            _uiState.value = state.copy(applied = true)
        }
    }

    private fun mutate(transform: (DiffSelections) -> DiffSelections) {
        _uiState.value = _uiState.value.copy(selections = transform(_uiState.value.selections))
    }

    private fun defaultSelections(diff: TemplateDiff): DiffSelections = DiffSelections(
        // Default-on for additive changes; default-off for field overwrites
        // when the user has previously edited the row, and always default-off
        // for removals so we don't surprise-delete user content.
        acceptedFieldNames = diff.habitFieldChanges
            .filterNot { it.userModified }
            .mapTo(mutableSetOf()) { it.fieldName },
        acceptedAddedStepIds = diff.addedSteps.mapTo(mutableSetOf()) { it.stepId },
        acceptedRemovedStepIds = emptySet(),
        acceptedModifiedStepIds = diff.modifiedSteps.mapTo(mutableSetOf()) { it.stepId }
    )

    private fun Set<String>.toggle(value: String): Set<String> =
        if (value in this) this - value else this + value
}
