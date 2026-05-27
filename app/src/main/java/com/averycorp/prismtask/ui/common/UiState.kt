package com.averycorp.prismtask.ui.common

sealed class UiState<out T> {
    data object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String, val throwable: Throwable? = null) : UiState<Nothing>()
    data object Empty : UiState<Nothing>()
}

fun <T> UiState<T>.dataOrNull(): T? = if (this is UiState.Success) data else null
fun <T> UiState<T>.isLoading(): Boolean = this is UiState.Loading
fun <T> UiState<T>.isError(): Boolean = this is UiState.Error
fun <T> UiState<T>.errorMessage(): String? = if (this is UiState.Error) message else null
