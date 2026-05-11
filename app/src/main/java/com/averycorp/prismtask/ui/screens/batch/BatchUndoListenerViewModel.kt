package com.averycorp.prismtask.ui.screens.batch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.repository.BatchOperationsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bridges [BatchUndoEventBus] into the host screen's snackbar surface.
 *
 * The Today screen instantiates this VM via `hiltViewModel()` so its
 * lifetime matches the screen's. It exposes the bus's event flow so
 * the screen can collect inside a `LaunchedEffect`, and provides a
 * [undo] entry point that runs the reverse mutation off the UI thread.
 *
 * Lives outside [BatchPreviewViewModel] so the preview can pop its
 * own back-stack entry (and lose its VM scope) without taking the
 * undo capability with it.
 */
@HiltViewModel
class BatchUndoListenerViewModel
@Inject
constructor(private val repository: BatchOperationsRepository, bus: BatchUndoEventBus) :
    ViewModel() {
    val events: SharedFlow<BatchAppliedEvent> = bus.events

    fun undo(batchId: String, onResult: (BatchOperationsRepository.BatchUndoResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.undoBatch(batchId)
            onResult(result)
        }
    }
}
