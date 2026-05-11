package com.averycorp.prismtask.ui.screens.automation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.repository.AutomationTemplateRepository
import com.averycorp.prismtask.data.repository.AutomationTemplateRepository.ImportResult
import com.averycorp.prismtask.data.seed.AutomationTemplate
import com.averycorp.prismtask.data.seed.AutomationTemplateCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutomationTemplateLibraryViewModel @Inject constructor(private val templateRepository: AutomationTemplateRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val sections: StateFlow<List<AutomationTemplateCategorySection>> = _query
        .map { q -> buildSections(q) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = buildSections("")
        )

    private fun buildSections(query: String): List<AutomationTemplateCategorySection> {
        val matches = templateRepository.search(query)
        // Preserve enum declaration order so the top of the list matches the
        // audit doc's category order.
        return AutomationTemplateCategory.values().mapNotNull { cat ->
            val rules = matches.filter { it.category == cat }
            if (rules.isEmpty()) {
                null
            } else {
                AutomationTemplateCategorySection(category = cat, templates = rules)
            }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun importTemplate(templateId: String) {
        viewModelScope.launch {
            val result = templateRepository.importTemplate(templateId)
            val template = templateRepository.findById(templateId)
            val event = when (result) {
                is ImportResult.Created ->
                    template?.let { LibraryEvent.Imported(it.name) } ?: LibraryEvent.ImportFailed
                is ImportResult.AlreadyImported ->
                    template?.let { LibraryEvent.AlreadyImported(it.name) } ?: LibraryEvent.ImportFailed
                ImportResult.NotFound -> LibraryEvent.ImportFailed
            }
            _events.trySend(event)
        }
    }

    sealed interface LibraryEvent {
        data class Imported(val templateName: String) : LibraryEvent
        data class AlreadyImported(val templateName: String) : LibraryEvent
        data object ImportFailed : LibraryEvent
    }
}

/** Render-side projection: one row per category, ordered by enum decl. */
data class AutomationTemplateCategorySection(val category: AutomationTemplateCategory, val templates: List<AutomationTemplate>)
