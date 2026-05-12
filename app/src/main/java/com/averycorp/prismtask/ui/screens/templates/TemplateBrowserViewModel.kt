package com.averycorp.prismtask.ui.screens.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.repository.SelfCareRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel behind Settings → Browse Templates. Reuses the same picker
 * content as onboarding but commits selections immediately on demand (rather
 * than on onboarding completion). Pre-populates nothing so returning users
 * can additively pick more templates without fighting existing state.
 *
 * Leisure Budget v2.0: leisure-slot template seeding (music/flex/language)
 * is retired with the v1.x slot model. The new leisure pool is seeded
 * directly via [com.averycorp.prismtask.ui.screens.leisure.LeisurePoolScreen]
 * → "Add activity" rather than through template selection.
 */
@HiltViewModel
class TemplateBrowserViewModel
@Inject
constructor(
    private val selfCareRepository: SelfCareRepository
) : ViewModel() {
    private val _selections = MutableStateFlow(TemplateSelections())
    val selections: StateFlow<TemplateSelections> = _selections.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun update(selections: TemplateSelections) {
        _selections.value = selections
    }

    fun commit() {
        val snapshot = _selections.value
        viewModelScope.launch {
            listOf("morning", "bedtime", "housework").forEach { routineType ->
                val stepIds = snapshot.effectiveStepIds(routineType)
                if (stepIds.isNotEmpty()) {
                    selfCareRepository.seedSelfCareSteps(routineType, stepIds.toList())
                }
            }
            _selections.value = TemplateSelections()
            _messages.tryEmit("Templates added")
        }
    }
}
