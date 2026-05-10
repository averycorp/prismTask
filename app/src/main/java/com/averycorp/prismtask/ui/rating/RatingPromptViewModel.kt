package com.averycorp.prismtask.ui.rating

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.repository.RatingFeedbackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RatingPromptUiState {
    object Idle : RatingPromptUiState()
    object Submitting : RatingPromptUiState()
    object Submitted : RatingPromptUiState()
    data class Error(val message: String) : RatingPromptUiState()
}

enum class RatingSentiment(val wireValue: String) {
    THUMB_UP("thumb_up"),
    THUMB_DOWN("thumb_down"),
}

@HiltViewModel
class RatingPromptViewModel @Inject constructor(
    private val repository: RatingFeedbackRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<RatingPromptUiState>(RatingPromptUiState.Idle)
    val state: StateFlow<RatingPromptUiState> = _state.asStateFlow()

    fun submit(sentiment: RatingSentiment, freeText: String) {
        if (_state.value is RatingPromptUiState.Submitting) return
        _state.value = RatingPromptUiState.Submitting
        viewModelScope.launch {
            val result = repository.submit(
                sentiment = sentiment.wireValue,
                freeText = freeText.trim().ifBlank { null },
                clientTimestampMs = System.currentTimeMillis(),
            )
            _state.value = result.fold(
                onSuccess = { RatingPromptUiState.Submitted },
                onFailure = { RatingPromptUiState.Error("Couldn't send — try again?") },
            )
        }
    }

    fun resetError() {
        if (_state.value is RatingPromptUiState.Error) {
            _state.value = RatingPromptUiState.Idle
        }
    }
}
