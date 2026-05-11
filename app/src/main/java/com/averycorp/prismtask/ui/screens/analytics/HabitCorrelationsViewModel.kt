package com.averycorp.prismtask.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.repository.HabitCorrelationsOutcome
import com.averycorp.prismtask.data.repository.HabitCorrelationsRepository
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HabitCorrelationsUiState(val isLoading: Boolean = false, val outcome: HabitCorrelationsOutcome? = null)

@HiltViewModel
class HabitCorrelationsViewModel
@Inject
constructor(
    private val repository: HabitCorrelationsRepository,
    private val proFeatureGate: ProFeatureGate
) : ViewModel() {
    private val _state = MutableStateFlow(HabitCorrelationsUiState())
    val state: StateFlow<HabitCorrelationsUiState> = _state.asStateFlow()

    fun analyze() {
        if (_state.value.isLoading) return
        if (!proFeatureGate.hasAccess(ProFeatureGate.ANALYTICS_CORRELATIONS)) {
            _state.update { it.copy(outcome = HabitCorrelationsOutcome.NotPro) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val outcome = repository.fetch()
            _state.update { HabitCorrelationsUiState(isLoading = false, outcome = outcome) }
        }
    }
}
