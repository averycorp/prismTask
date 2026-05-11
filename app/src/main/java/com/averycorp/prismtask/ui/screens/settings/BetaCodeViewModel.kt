package com.averycorp.prismtask.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.repository.BetaCodeRepository
import com.averycorp.prismtask.data.repository.BetaRedeemOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State machine for the beta-code redemption screen.
 *
 *   Idle  -> [redeem] -> Loading -> Success | Error
 *   Error -> [reset]  -> Idle
 *   Success -> (terminal; user dismisses screen)
 */
sealed interface BetaCodeUiState {
    data object Idle : BetaCodeUiState
    data object Loading : BetaCodeUiState
    data class Success(val proUntil: String?) : BetaCodeUiState
    data class Error(val message: String) : BetaCodeUiState
}

@HiltViewModel
class BetaCodeViewModel
@Inject
constructor(private val repository: BetaCodeRepository) : ViewModel() {
    private val _state = MutableStateFlow<BetaCodeUiState>(BetaCodeUiState.Idle)
    val state: StateFlow<BetaCodeUiState> = _state.asStateFlow()

    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code.asStateFlow()

    fun onCodeChanged(value: String) {
        _code.value = value
        if (_state.value is BetaCodeUiState.Error) {
            _state.value = BetaCodeUiState.Idle
        }
    }

    fun redeem() {
        val trimmed = _code.value.trim()
        if (trimmed.isEmpty()) return
        if (_state.value is BetaCodeUiState.Loading) return
        _state.value = BetaCodeUiState.Loading
        viewModelScope.launch {
            _state.value = when (val outcome = repository.redeem(trimmed)) {
                is BetaRedeemOutcome.Granted -> BetaCodeUiState.Success(outcome.proUntil)
                is BetaRedeemOutcome.Failure -> BetaCodeUiState.Error(messageFor(outcome))
            }
        }
    }

    fun reset() {
        _state.value = BetaCodeUiState.Idle
    }

    private fun messageFor(failure: BetaRedeemOutcome.Failure): String = when (failure) {
        is BetaRedeemOutcome.Failure.UnknownCode ->
            "We couldn't find that code. Double-check the spelling and try again."
        is BetaRedeemOutcome.Failure.Revoked ->
            "This code has been revoked. Reach out to support if you think this is wrong."
        is BetaRedeemOutcome.Failure.Expired ->
            "This code is no longer valid."
        is BetaRedeemOutcome.Failure.AlreadyRedeemed ->
            "You've already redeemed this code on this account."
        is BetaRedeemOutcome.Failure.CapReached ->
            "This code has reached its redemption limit."
        is BetaRedeemOutcome.Failure.NotSignedIn ->
            "Sign in before redeeming a beta code."
        is BetaRedeemOutcome.Failure.Network ->
            "Couldn't reach the server. Check your connection and try again."
    }
}
