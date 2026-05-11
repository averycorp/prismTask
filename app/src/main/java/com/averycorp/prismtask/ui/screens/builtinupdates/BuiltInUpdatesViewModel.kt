package com.averycorp.prismtask.ui.screens.builtinupdates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.domain.model.PendingBuiltInUpdate
import com.averycorp.prismtask.domain.usecase.BuiltInUpdateDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BuiltInUpdatesViewModel @Inject constructor(private val detector: BuiltInUpdateDetector) : ViewModel() {

    val pendingUpdates: StateFlow<List<PendingBuiltInUpdate>> = detector.pendingUpdates

    init {
        viewModelScope.launch { detector.refreshPendingUpdates() }
    }

    fun refresh() {
        viewModelScope.launch { detector.refreshPendingUpdates() }
    }

    fun dismiss(templateKey: String, version: Int) {
        viewModelScope.launch { detector.dismiss(templateKey, version) }
    }

    fun detach(templateKey: String) {
        viewModelScope.launch { detector.detach(templateKey) }
    }
}
