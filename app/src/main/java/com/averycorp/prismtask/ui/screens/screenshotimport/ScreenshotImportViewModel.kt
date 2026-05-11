package com.averycorp.prismtask.ui.screens.screenshotimport

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.converter.RecurrenceConverter
import com.averycorp.prismtask.data.repository.AttachmentRepository
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.usecase.NaturalLanguageParser
import com.averycorp.prismtask.domain.usecase.ParsedTaskResolver
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.util.ScreenshotEncoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Smart Screenshot Import screen (G).
 *
 * Lifecycle:
 *  1. User picks an image -> [onImagePicked] kicks off encode + Vision API call.
 *  2. UI observes [uiState] for Loading -> Loaded transitions.
 *  3. User edits / toggles candidates inline.
 *  4. User taps "Create N tasks" -> [createSelected] calls into the
 *     existing NLP pipeline so each candidate flows through the same
 *     [ParsedTaskResolver] PasteConversation uses, then attaches the
 *     original screenshot to every created task.
 */
@HiltViewModel
class ScreenshotImportViewModel
@Inject
constructor(
    private val repository: ScreenshotImportRepository,
    private val taskRepository: TaskRepository,
    private val tagRepository: TagRepository,
    private val projectRepository: ProjectRepository,
    private val attachmentRepository: AttachmentRepository,
    private val parser: NaturalLanguageParser,
    private val parsedTaskResolver: ParsedTaskResolver,
    private val proFeatureGate: ProFeatureGate
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScreenshotImportUiState>(ScreenshotImportUiState.Idle)
    val uiState: StateFlow<ScreenshotImportUiState> = _uiState.asStateFlow()

    private val _candidates = MutableStateFlow<List<EditableScreenshotCandidate>>(emptyList())
    val candidates: StateFlow<List<EditableScreenshotCandidate>> = _candidates.asStateFlow()

    /**
     * URI of the original image, held until task creation so the same
     * source bitmap can be saved as an attachment to every created task.
     */
    private var sourceImageUri: Uri? = null

    private val _createdCount = MutableStateFlow<Int?>(null)
    val createdCount: StateFlow<Int?> = _createdCount.asStateFlow()

    fun onImagePicked(context: Context, uri: Uri) {
        sourceImageUri = uri
        _candidates.value = emptyList()
        _uiState.value = ScreenshotImportUiState.Loading
        viewModelScope.launch {
            val encoded = when (val r = ScreenshotEncoder.encodeForVision(context, uri)) {
                is ScreenshotEncoder.EncodeResult.Success -> r.encoded
                ScreenshotEncoder.EncodeResult.UnreadableImage -> {
                    _uiState.value = ScreenshotImportUiState.Error(
                        "Couldn't read that image. Try a different screenshot."
                    )
                    return@launch
                }
                ScreenshotEncoder.EncodeResult.TooLarge -> {
                    _uiState.value = ScreenshotImportUiState.Error(
                        "Image is too large even after compression. Try a smaller screenshot."
                    )
                    return@launch
                }
            }

            try {
                val results = repository.extractTasksFromScreenshot(
                    imageBase64 = encoded.base64,
                    imageMediaType = encoded.mediaType
                )
                if (results.isEmpty()) {
                    _uiState.value = ScreenshotImportUiState.Empty
                } else {
                    _candidates.value = results.map { r ->
                        EditableScreenshotCandidate(
                            title = r.title,
                            confidence = r.confidence,
                            selected = true
                        )
                    }
                    _uiState.value = ScreenshotImportUiState.Loaded
                }
            } catch (e: Exception) {
                _uiState.value = ScreenshotImportUiState.Error(
                    "Couldn't extract tasks. Check your network and AI settings, then try again."
                )
            }
        }
    }

    fun onUserCancelled() {
        sourceImageUri = null
        _candidates.value = emptyList()
        _uiState.value = ScreenshotImportUiState.Idle
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

    fun createSelected(context: Context) {
        viewModelScope.launch {
            val selected = _candidates.value.filter { it.selected && it.title.isNotBlank() }
            val proAi = proFeatureGate.hasAccess(ProFeatureGate.AI_NLP)
            val sourceUri = sourceImageUri
            for (candidate in selected) {
                val parsed = if (proAi) {
                    parser.parseRemote(candidate.title)
                } else {
                    parser.parse(candidate.title)
                }
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
                // Attach the original screenshot to every created task so the
                // user can revisit the source image from any task's detail
                // view. Reuses the existing AttachmentRepository which writes
                // to filesDir/attachments and tracks the entity through sync.
                if (sourceUri != null) {
                    runCatching {
                        attachmentRepository.addImageAttachment(
                            context = context,
                            taskId = taskId,
                            sourceUri = sourceUri
                        )
                    }
                }
            }
            _createdCount.value = selected.size
        }
    }
}

data class EditableScreenshotCandidate(
    val title: String,
    val confidence: Float,
    val selected: Boolean
)

sealed interface ScreenshotImportUiState {
    data object Idle : ScreenshotImportUiState
    data object Loading : ScreenshotImportUiState
    data object Loaded : ScreenshotImportUiState
    data object Empty : ScreenshotImportUiState
    data class Error(val message: String) : ScreenshotImportUiState
}
