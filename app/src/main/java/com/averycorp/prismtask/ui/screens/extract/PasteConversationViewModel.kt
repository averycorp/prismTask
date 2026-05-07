package com.averycorp.prismtask.ui.screens.extract

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.converter.RecurrenceConverter
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.usecase.ConversationTaskExtractor
import com.averycorp.prismtask.domain.usecase.ExtractedTask
import com.averycorp.prismtask.domain.usecase.NaturalLanguageParser
import com.averycorp.prismtask.domain.usecase.ParsedTaskResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Paste Conversation screen (v1.4.0 V9).
 *
 * Holds the pasted input, the list of extracted candidates with their
 * selected/title-edit state, and drives the create-tasks batch on
 * confirmation.
 */
@HiltViewModel
class PasteConversationViewModel
@Inject
constructor(
    private val taskRepository: TaskRepository,
    private val tagRepository: TagRepository,
    private val projectRepository: ProjectRepository,
    private val parser: NaturalLanguageParser,
    private val parsedTaskResolver: ParsedTaskResolver
) : ViewModel() {
    private val extractor = ConversationTaskExtractor()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _candidates = MutableStateFlow<List<EditableCandidate>>(emptyList())
    val candidates: StateFlow<List<EditableCandidate>> = _candidates.asStateFlow()

    private val _createdCount = MutableStateFlow<Int?>(null)
    val createdCount: StateFlow<Int?> = _createdCount.asStateFlow()

    fun onInputChange(text: String) {
        _input.value = text
    }

    fun onSourceLabel(source: String?) {
        _candidates.value = _candidates.value.map { it.copy(source = source) }
    }

    fun extract(source: String? = null) {
        val results = extractor.extract(_input.value, source)
        _candidates.value = results.map { task ->
            EditableCandidate(
                title = task.title,
                confidence = task.confidence,
                source = task.source,
                selected = true
            )
        }
    }

    fun toggle(index: Int) {
        val list = _candidates.value.toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(selected = !list[index].selected)
        _candidates.value = list
    }

    fun editTitle(index: Int, newTitle: String) {
        val list = _candidates.value.toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(title = newTitle)
        _candidates.value = list
    }

    fun createSelected() {
        viewModelScope.launch {
            val selected = _candidates.value.filter { it.selected && it.title.isNotBlank() }
            for (candidate in selected) {
                // Pipe each extracted candidate through the same NLP pipeline
                // Quick Add uses so a title like `send report by Friday !2`
                // pulled out of chat prose lands with the parsed due date and
                // priority instead of as a literal string. Offline parser
                // only — N×backend cost would be prohibitive on a multi-task
                // batch insert.
                val parsed = parser.parse(candidate.title)
                val resolved = parsedTaskResolver.resolve(parsed)
                val newTagIds = resolved.unmatchedTags.map { tagRepository.addTag(name = it) }
                val projectId = resolved.projectId
                    ?: resolved.unmatchedProject?.let { projectRepository.addProject(name = it) }
                val recurrenceJson = resolved.recurrenceRule?.let { RecurrenceConverter.toJson(it) }
                val taskId = taskRepository.addTask(
                    title = resolved.title,
                    dueDate = resolved.dueDate,
                    dueTime = resolved.dueTime,
                    priority = resolved.priority,
                    projectId = projectId,
                    lifeCategory = resolved.lifeCategory,
                    taskMode = resolved.taskMode,
                    cognitiveLoad = resolved.cognitiveLoad,
                    recurrenceRule = recurrenceJson
                )
                val allTagIds = resolved.tagIds + newTagIds
                if (allTagIds.isNotEmpty()) {
                    tagRepository.setTagsForTask(taskId, allTagIds)
                }
            }
            _createdCount.value = selected.size
        }
    }

    fun reset() {
        _input.value = ""
        _candidates.value = emptyList()
        _createdCount.value = null
    }
}

data class EditableCandidate(
    val title: String,
    val confidence: Float,
    val source: String?,
    val selected: Boolean
) {
    fun toExtracted(): ExtractedTask = ExtractedTask(
        title = title,
        confidence = confidence,
        source = source
    )
}
