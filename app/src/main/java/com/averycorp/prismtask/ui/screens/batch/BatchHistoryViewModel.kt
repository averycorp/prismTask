package com.averycorp.prismtask.ui.screens.batch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.dao.BatchUndoLogDao
import com.averycorp.prismtask.data.local.entity.BatchUndoLogEntry
import com.averycorp.prismtask.data.repository.BatchOperationsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Settings → "Batch command history" screen.
 *
 * Surfaces the last 24 hours of batches grouped by `batch_id`, with a
 * per-batch summary (the original command text, applied count, undone
 * state). Tapping a batch reveals the per-entry detail. The Undo button
 * delegates to [BatchOperationsRepository.undoBatch] and emits to
 * [events] so the screen can show feedback / pop the detail view.
 *
 * The 24-hour expiry is enforced by [BatchUndoSweepWorker]; this VM
 * just renders whatever `batch_undo_log` currently contains.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BatchHistoryViewModel
@Inject
constructor(private val dao: BatchUndoLogDao, private val repository: BatchOperationsRepository) :
    ViewModel() {
    /** All batches in the table, newest first. Each batch is a list of
     *  per-entity entries — they're rendered as a single grouped card. */
    val batches: StateFlow<List<BatchSummary>> = dao
        .observeBatchIds()
        .flatMapLatest { ids ->
            // Cheap fanout — typical history is 0-10 batches in a 24h window.
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                flowOf(ids).map { list ->
                    list.map { id ->
                        val entries = dao.getEntriesForBatchOnce(id)
                        BatchSummary(
                            batchId = id,
                            commandText = entries.firstOrNull()?.batchCommandText.orEmpty(),
                            createdAt = entries.firstOrNull()?.createdAt ?: 0L,
                            appliedCount = entries.size,
                            isUndone = entries.all { it.undoneAt != null },
                            undoneAt = entries.firstOrNull { it.undoneAt != null }?.undoneAt,
                            entries = entries
                        )
                    }.sortedByDescending { it.createdAt }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedBatchId = MutableStateFlow<String?>(null)
    val selectedBatchId: StateFlow<String?> = _selectedBatchId.asStateFlow()

    private val _undoInProgressId = MutableStateFlow<String?>(null)
    val undoInProgressId: StateFlow<String?> = _undoInProgressId.asStateFlow()

    private val _events = MutableSharedFlow<HistoryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<HistoryEvent> = _events.asSharedFlow()

    fun selectBatch(batchId: String?) {
        _selectedBatchId.value = batchId
    }

    fun undo(batchId: String) {
        if (_undoInProgressId.value != null) return
        _undoInProgressId.value = batchId
        viewModelScope.launch {
            try {
                val result = repository.undoBatch(batchId)
                _events.emit(
                    HistoryEvent.UndoFinished(
                        batchId = batchId,
                        restored = result.restored,
                        partial = result.failed.isNotEmpty()
                    )
                )
            } catch (e: Exception) {
                _events.emit(HistoryEvent.UndoFailed(batchId, e.message ?: "Undo failed"))
            } finally {
                _undoInProgressId.value = null
            }
        }
    }

    data class BatchSummary(
        val batchId: String,
        val commandText: String,
        val createdAt: Long,
        val appliedCount: Int,
        val isUndone: Boolean,
        val undoneAt: Long?,
        val entries: List<BatchUndoLogEntry>
    )

    sealed class HistoryEvent {
        data class UndoFinished(val batchId: String, val restored: Int, val partial: Boolean) : HistoryEvent()
        data class UndoFailed(val batchId: String, val reason: String) : HistoryEvent()
    }
}
