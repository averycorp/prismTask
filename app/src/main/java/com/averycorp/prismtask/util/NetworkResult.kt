package com.averycorp.prismtask.util

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(val code: Int = -1, val message: String, val throwable: Throwable? = null) : NetworkResult<Nothing>()
    data object Loading : NetworkResult<Nothing>()
    data object Offline : NetworkResult<Nothing>()
}

inline fun <T> NetworkResult<T>.onSuccess(action: (T) -> Unit): NetworkResult<T> {
    if (this is NetworkResult.Success) action(data)
    return this
}

inline fun <T> NetworkResult<T>.onError(action: (Int, String) -> Unit): NetworkResult<T> {
    if (this is NetworkResult.Error) action(code, message)
    return this
}
