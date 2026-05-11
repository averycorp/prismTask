package com.averycorp.prismtask.ui.screens.templates

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.TaskTemplateEntity
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.data.repository.TaskTemplateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Ephemeral banner state emitted after a quick-use template action so the
 * [TemplateListScreen] can show a snackbar-style bar with View and Undo
 * actions. Using a dedicated state model instead of [androidx.compose.material3.SnackbarHostState]
 * because Material's Snackbar only supports a single action button.
 */
data class QuickUseBanner(val newTaskId: Long, val taskTitle: String)

@HiltViewModel
class TemplateListViewModel
@Inject
constructor(
    private val templateRepository: TaskTemplateRepository,
    private val taskRepository: TaskRepository
) : ViewModel() {
    /**
     * Banner shown after a successful quick-use, with the id of the task
     * that was just created so the screen can route "View" to the editor
     * and "Undo" back through [undoQuickUse].
     */
    private val _quickUseBanner = MutableStateFlow<QuickUseBanner?>(null)
    val quickUseBanner: StateFlow<QuickUseBanner?> = _quickUseBanner.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * All templates, filtered client-side by the current category and search
     * query. Keeping the filter here (instead of querying the DAO per change)
     * lets the screen react instantly to chip taps without re-subscribing to
     * a new Flow each time. The DAO already sorts by usage_count DESC (then
     * last_used_at DESC), so "most used" ordering is free — we just preserve
     * it here.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val templates: StateFlow<List<TaskTemplateEntity>> = combine(
        templateRepository.getAllTemplates(),
        _selectedCategory,
        _searchQuery
    ) { all, category, query ->
        val normalized = query.trim()
        all.filter { template ->
            val matchesCategory = category == null || template.category == category
            val matchesQuery = normalized.isEmpty() ||
                template.name.contains(normalized, ignoreCase = true) ||
                (template.templateTitle?.contains(normalized, ignoreCase = true) == true)
            matchesCategory && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<String>> = templateRepository
        .getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setCategory(category: String?) {
        _selectedCategory.value = category
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun deleteTemplate(id: Long) {
        viewModelScope.launch {
            try {
                templateRepository.deleteTemplate(id)
            } catch (e: Exception) {
                Log.e("TemplateListVM", "Failed to delete template", e)
            }
        }
    }

    /**
     * Removes a category label from every template that currently has it.
     * The templates themselves survive — only their [category] field is
     * cleared. Invoked from the "Manage Categories" dialog in [TemplateListScreen].
     */
    fun deleteCategory(category: String) {
        viewModelScope.launch {
            try {
                templateRepository.clearCategory(category)
                // If the user was currently filtering by this category, drop
                // the filter so the list doesn't go suddenly empty on them.
                if (_selectedCategory.value == category) {
                    _selectedCategory.value = null
                }
            } catch (e: Exception) {
                Log.e("TemplateListVM", "Failed to delete category", e)
            }
        }
    }

    /**
     * Quick-use a template: creates a task immediately from the template
     * and exposes a banner with View + Undo actions via [quickUseBanner].
     * This is the "fastest path" entry point for users who don't need to
     * tweak anything before creating the task.
     */
    fun quickUseTemplate(templateId: Long) {
        viewModelScope.launch {
            try {
                val newTaskId = templateRepository.createTaskFromTemplate(templateId, quickUse = true)
                val createdTask = taskRepository.getTaskByIdOnce(newTaskId)
                val title = createdTask?.title.orEmpty().ifBlank { "task" }
                _quickUseBanner.value = QuickUseBanner(
                    newTaskId = newTaskId,
                    taskTitle = title
                )
            } catch (e: Exception) {
                Log.e("TemplateListVM", "Failed to quick-use template", e)
            }
        }
    }

    /** Clears the quick-use banner (e.g. after auto-dismiss or View tap). */
    fun dismissQuickUseBanner() {
        _quickUseBanner.value = null
    }

    /**
     * Undoes a quick-use action by deleting the task the repository just
     * created. Safe to call even if the banner has already been dismissed
     * from the UI; the deletion is idempotent on a missing task.
     */
    fun undoQuickUse(taskId: Long) {
        viewModelScope.launch {
            try {
                taskRepository.deleteTask(taskId)
                _quickUseBanner.value = null
            } catch (e: Exception) {
                Log.e("TemplateListVM", "Failed to undo quick-use", e)
            }
        }
    }
}
