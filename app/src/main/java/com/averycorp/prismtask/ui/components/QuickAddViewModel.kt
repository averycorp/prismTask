package com.averycorp.prismtask.ui.components

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.converter.RecurrenceConverter
import com.averycorp.prismtask.data.local.dao.UsageLogDao
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTemplateEntity
import com.averycorp.prismtask.data.local.entity.UsageLogEntity
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.preferences.VoicePreferences
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.data.repository.TaskTemplateRepository
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.model.ProjectStatus
import com.averycorp.prismtask.domain.usecase.BatchIntentDetector
import com.averycorp.prismtask.domain.usecase.LifeCategoryClassifier
import com.averycorp.prismtask.domain.usecase.MultiCreateDetector
import com.averycorp.prismtask.domain.usecase.NaturalLanguageParser
import com.averycorp.prismtask.domain.usecase.ParsedTask
import com.averycorp.prismtask.domain.usecase.ParsedTaskResolver
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.domain.usecase.ProjectIntent
import com.averycorp.prismtask.domain.usecase.ProjectIntentParser
import com.averycorp.prismtask.domain.usecase.TextToSpeechManager
import com.averycorp.prismtask.domain.usecase.VoiceCommand
import com.averycorp.prismtask.domain.usecase.VoiceCommandParser
import com.averycorp.prismtask.domain.usecase.VoiceInputManager
import com.averycorp.prismtask.domain.usecase.extractKeywords
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class QuickAddViewModel
@Inject
constructor(
    private val parser: NaturalLanguageParser,
    private val intentParser: ProjectIntentParser,
    private val resolver: ParsedTaskResolver,
    private val taskRepository: TaskRepository,
    private val tagRepository: TagRepository,
    private val projectRepository: ProjectRepository,
    private val templateRepository: TaskTemplateRepository,
    private val usageLogDao: UsageLogDao,
    private val proFeatureGate: ProFeatureGate,
    val voiceInputManager: VoiceInputManager,
    private val voiceCommandParser: VoiceCommandParser,
    private val tts: TextToSpeechManager,
    private val voicePreferences: VoicePreferences,
    private val advancedTuningPreferences: com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences,
    private val userPreferencesDataStore: UserPreferencesDataStore
) : ViewModel() {

    /**
     * User-configurable max-lines cap for the QuickAdd input field (E2).
     * Defaults to 5 to match the prior hardcoded value.
     */
    val quickAddMaxLines: StateFlow<Int> =
        advancedTuningPreferences.getQuickAddRows()
            .map { it.maxLines }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 5)

    /**
     * List of candidate templates shown in the disambiguation popup when a
     * "/query" shortcut matches more than one template. Null when the popup
     * is dismissed; non-empty when the user needs to pick.
     */
    private val _templateDisambiguation = MutableStateFlow<List<TaskTemplateEntity>?>(null)
    val templateDisambiguation: StateFlow<List<TaskTemplateEntity>?> =
        _templateDisambiguation.asStateFlow()

    /**
     * Live snapshot of the user's custom life-category keywords. The
     * classifier is rebuilt from this on every quick-add classification
     * so newly-added keywords (Settings → Advanced Tuning) take effect on
     * the very next quick-add submission.
     */
    private val lifeCategoryCustomKeywords:
        StateFlow<com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords> =
        advancedTuningPreferences.getLifeCategoryCustomKeywords().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords()
        )

    private fun lifeCategoryClassifier(): LifeCategoryClassifier =
        LifeCategoryClassifier.withCustomKeywords(lifeCategoryCustomKeywords.value)
    private val batchIntentDetector = BatchIntentDetector()
    private val multiCreateDetector = MultiCreateDetector()

    /**
     * Emits when the user submits a batch-style command. The hosting
     * screen navigates to BatchPreviewScreen with the command text.
     * Free-tier users emit a paywall message via [_voiceMessages] and
     * never see this flow.
     *
     * Backed by a [Channel] (vs. `MutableSharedFlow`) so each emission is
     * delivered to **exactly one** collector. The screen-level fix is to
     * give each `QuickAddBar` composition site a distinct `hiltViewModel`
     * key so it gets its own VM, but a `Channel` defends in depth: if a
     * future composition site under `MainTabs` accidentally shares this
     * VM (or someone strips a key), two `LaunchedEffect` collectors on a
     * `SharedFlow` would both fire `onBatchCommand`, navigating twice and
     * stacking two `BatchPreview` screens — the user-reported "preview,
     * accept, then it runs a second time" bug. `Channel.receiveAsFlow()`
     * collapses fan-out at the VM layer.
     */
    private val _batchIntents = Channel<String>(capacity = Channel.BUFFERED)
    val batchIntents: Flow<String> = _batchIntents.receiveAsFlow()

    /**
     * Emits when the user submits multi-task input (rule-(a) newlines or
     * rule-(b) ≥3-comma-segments + ≥50%-time-markers per the multi-task
     * creation audit Item 2). The hosting screen navigates to the
     * `MultiCreateBottomSheet` route with the raw text. Free-tier users
     * skip this flow — the detector still fires but the gate at
     * [onSubmit] drops them through to the single-task path because
     * extract-from-text is a paid Haiku call.
     *
     * Same `Channel` rationale as [_batchIntents] — see its KDoc.
     */
    private val _multiCreateIntents = Channel<String>(capacity = Channel.BUFFERED)
    val multiCreateIntents: Flow<String> = _multiCreateIntents.receiveAsFlow()

    val inputText = MutableStateFlow("")

    val parsedPreview: StateFlow<ParsedTask?> = inputText
        .debounce(200)
        .map { text ->
            if (text.isBlank()) null else parser.parse(text)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isExpanded = MutableStateFlow(false)
    val isExpanded: StateFlow<Boolean> = _isExpanded

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting

    /**
     * Confirm-before-save preference (default true). Drives whether
     * [onSubmit] writes the task immediately or stages it in
     * [pendingConfirm] for the user to review in [TaskConfirmSheet].
     * Continuous voice mode bypasses confirmation either way — see
     * [onSubmit] for the gate.
     */
    private val showConfirmation: StateFlow<Boolean> =
        userPreferencesDataStore.quickAddFlow
            .map { it.showConfirmation }
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Pending preview emitted before the actual insert when
     * [showConfirmation] is on. Consumers render [TaskConfirmSheet] from
     * this state; null means no preview is pending.
     */
    private val _pendingConfirm = MutableStateFlow<PendingConfirmTask?>(null)
    val pendingConfirm: StateFlow<PendingConfirmTask?> = _pendingConfirm.asStateFlow()

    // ----- Voice input surface -----

    /** Emits user-facing messages (command confirmations, errors) that the
     *  hosting screen should display in a Snackbar. */
    private val _voiceMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val voiceMessages: SharedFlow<String> = _voiceMessages.asSharedFlow()

    val isListening: StateFlow<Boolean> = voiceInputManager.isListening
    val voicePartialText: StateFlow<String> = voiceInputManager.partialText
    val voiceRmsLevel: StateFlow<Float> = voiceInputManager.rmsLevel

    private val _voiceInputEnabled = MutableStateFlow(true)
    val voiceInputEnabled: StateFlow<Boolean> = _voiceInputEnabled.asStateFlow()

    private val _continuousModeActive = MutableStateFlow(false)
    val continuousModeActive: StateFlow<Boolean> = _continuousModeActive.asStateFlow()

    /** Id of the most recently created task — voice commands like
     *  "add to project X" operate on this id. */
    private var lastCreatedTaskId: Long? = null

    init {
        viewModelScope.launch {
            voicePreferences.getVoiceInputEnabled().collect { enabled ->
                _voiceInputEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            // Pipe live partial transcription into the visible input field
            // so the user sees the words appear as they speak.
            voiceInputManager.partialText.collect { partial ->
                if (voiceInputManager.isListening.value && partial.isNotEmpty()) {
                    inputText.value = partial
                    if (!_isExpanded.value) _isExpanded.value = true
                }
            }
        }
    }

    fun onInputChanged(text: String) {
        inputText.value = text
    }

    fun onToggleExpand() {
        _isExpanded.value = !_isExpanded.value
    }

    // ----- Voice input entry points -----

    /** Toggle voice recognition on the quick-add bar. Caller is responsible
     *  for requesting RECORD_AUDIO permission before invoking. */
    fun toggleVoiceInput() {
        if (voiceInputManager.isListening.value) {
            voiceInputManager.stopListening()
            return
        }
        _isExpanded.value = true
        voiceInputManager.clearPartialText()
        voiceInputManager.startListening(
            onResult = { transcript ->
                handleVoiceTranscript(transcript)
            },
            onError = { message ->
                viewModelScope.launch { _voiceMessages.emit(message) }
            }
        )
    }

    /** Enter hands-free continuous mode. Same permission contract as
     *  [toggleVoiceInput]. Commands fall through [handleVoiceTranscript]. */
    fun startContinuousVoiceMode() {
        viewModelScope.launch {
            if (!voicePreferences.getContinuousModeEnabled().first()) {
                _voiceMessages.emit("Continuous mode is disabled in Settings")
                return@launch
            }
            _continuousModeActive.value = true
            if (!voiceInputManager.isListening.value) {
                voiceInputManager.clearPartialText()
                voiceInputManager.startListening(
                    onResult = { transcript ->
                        handleVoiceTranscript(transcript)
                    },
                    onError = { message ->
                        viewModelScope.launch { _voiceMessages.emit(message) }
                    }
                )
            }
        }
    }

    /** Exit hands-free mode and stop recognition. */
    fun stopContinuousVoiceMode() {
        _continuousModeActive.value = false
        voiceInputManager.stopListening()
    }

    /** Start the next utterance in continuous mode (auto-called by the UI
     *  after each transcript is processed). */
    fun restartContinuousListening() {
        if (!_continuousModeActive.value) return
        voiceInputManager.clearPartialText()
        voiceInputManager.startListening(
            onResult = { transcript -> handleVoiceTranscript(transcript) },
            onError = { message ->
                viewModelScope.launch { _voiceMessages.emit(message) }
            }
        )
    }

    /** Entry point from the continuous voice overlay: a single utterance is
     *  handled the same way as a tap-driven mic press. */
    fun handleVoiceTranscript(transcript: String, plannedDateOverride: Long? = null) {
        val text = transcript.trim()
        if (text.isBlank()) return
        inputText.value = text

        val command = voiceCommandParser.parseCommand(text)
        if (command != null) {
            executeVoiceCommand(command)
        } else {
            onSubmit(plannedDateOverride)
        }
    }

    private fun executeVoiceCommand(command: VoiceCommand) {
        viewModelScope.launch {
            try {
                val confirmation: String = when (command) {
                    is VoiceCommand.CompleteTask -> completeTaskByName(command.query)
                    is VoiceCommand.DeleteTask -> deleteTaskByName(command.query)
                    is VoiceCommand.RescheduleTask ->
                        rescheduleTaskByName(command.query, command.dateText)
                    is VoiceCommand.MoveToProject -> moveLastTaskToProject(command.projectQuery)
                    is VoiceCommand.StartTimer -> "Timer started on: ${command.query}"
                    VoiceCommand.StopTimer -> "Timer stopped"
                    VoiceCommand.WhatsNext -> buildWhatsNextResponse()
                    VoiceCommand.TaskCount -> buildTaskCountResponse()
                    VoiceCommand.StartFocus -> "Starting focus session"
                    VoiceCommand.ExitVoiceMode -> {
                        stopContinuousVoiceMode()
                        "Voice mode off"
                    }
                }
                if (voicePreferences.getVoiceFeedbackEnabled().first()) {
                    tts.speak(confirmation)
                }
                _voiceMessages.emit(confirmation)
                inputText.value = ""
            } catch (e: Exception) {
                Log.e("QuickAddVM", "Voice command failed", e)
                _voiceMessages.emit("Voice command failed")
            }
        }
    }

    private suspend fun completeTaskByName(query: String): String {
        val all = taskRepository.getAllTasksOnce().filter { !it.isCompleted && it.archivedAt == null }
        val match = voiceCommandParser.fuzzyMatch(all, query) { it.title }
            ?: return "Couldn't find a task matching \"$query\""
        taskRepository.completeTask(match.id)
        return "Completed: ${match.title}"
    }

    private suspend fun deleteTaskByName(query: String): String {
        val all = taskRepository.getAllTasksOnce().filter { it.archivedAt == null }
        val match = voiceCommandParser.fuzzyMatch(all, query) { it.title }
            ?: return "Couldn't find a task matching \"$query\""
        taskRepository.deleteTask(match.id)
        return "Deleted: ${match.title}"
    }

    private suspend fun rescheduleTaskByName(query: String, dateText: String): String {
        val all = taskRepository.getAllTasksOnce().filter { !it.isCompleted && it.archivedAt == null }
        val match = voiceCommandParser.fuzzyMatch(all, query) { it.title }
            ?: return "Couldn't find a task matching \"$query\""
        // Reuse the NLP parser to turn "tomorrow" / "next friday" into a
        // concrete date millis. We feed "placeholder <dateText>" in and only
        // keep the extracted due date.
        val parsed = parser.parse("reschedule $dateText")
        val newDue = parsed.dueDate
            ?: return "Couldn't understand date \"$dateText\""
        taskRepository.updateTask(
            match.copy(dueDate = newDue, updatedAt = System.currentTimeMillis())
        )
        return "Rescheduled \"${match.title}\" to $dateText"
    }

    private suspend fun moveLastTaskToProject(projectQuery: String): String {
        val lastId = lastCreatedTaskId ?: return "No recent task to move"
        val projects = projectRepository.getAllProjects().first()
        val match = voiceCommandParser.fuzzyMatch(projects, projectQuery) { it.name }
            ?: return "Couldn't find project \"$projectQuery\""
        val task = taskRepository.getTaskByIdOnce(lastId)
            ?: return "No recent task to move"
        taskRepository.updateTask(
            task.copy(projectId = match.id, updatedAt = System.currentTimeMillis())
        )
        return "Moved \"${task.title}\" to ${match.name}"
    }

    private suspend fun buildWhatsNextResponse(): String {
        val all = taskRepository
            .getAllTasksOnce()
            .filter { !it.isCompleted && it.archivedAt == null }
            .sortedWith(
                compareByDescending<TaskEntity> { it.priority }
                    .thenBy { it.dueDate ?: Long.MAX_VALUE }
            )
        val top = all.firstOrNull() ?: return "You're all caught up — nothing pending"
        val suffix = if (top.dueDate != null) ", due soon" else ""
        return "Your top priority is: ${top.title}$suffix"
    }

    private suspend fun buildTaskCountResponse(): String {
        val all = taskRepository.getAllTasksOnce().filter { it.archivedAt == null }
        val remaining = all.count { !it.isCompleted }
        return "You have $remaining tasks remaining"
    }

    fun onSubmit(plannedDateOverride: Long? = null) {
        val text = inputText.value.trim()
        if (text.isBlank()) return

        // Re-entry guard. Compose dispatches click events serially on the
        // main thread, but `viewModelScope.launch{}` returns immediately —
        // without claiming `_isSubmitting` synchronously *here*, a second
        // tap (or a Send-then-IME-Done combo) lands before the launched
        // coroutine flips the flag, races through, and emits
        // `_batchIntents` / `_multiCreateIntents` twice. Two emissions =
        // two navigations = two fresh `BatchPreviewViewModel`s = two
        // Haiku calls returning non-deterministic mutations (the user
        // sees "different options the second time"). Three branches,
        // one race shape (A4 / A5 / A6).
        if (_isSubmitting.value) return
        _isSubmitting.value = true

        // A2 NLP batch ops — heuristic intercept BEFORE template / project
        // intent / single-task NLP. False positives here would trap normal
        // users in the heavier batch flow, so the detector requires two
        // distinct signal categories (quantifier + time range, etc.).
        //
        // Carve-outs in BatchIntentDetector return NotABatch even when ≥2
        // signals fire: recurrence patterns (`every monday at 8am`) and
        // explicit negation prefixes (`don't complete all tasks today`).
        // Those inputs proceed through to MultiCreate / single-task NLP.
        val batchIntent = batchIntentDetector.detect(text)
        if (batchIntent is BatchIntentDetector.Result.Batch) {
            if (!proFeatureGate.hasAccess(ProFeatureGate.AI_BATCH_OPS)) {
                viewModelScope.launch {
                    try {
                        _voiceMessages.emit(
                            "Batch commands are a Pro feature — upgrade to use them."
                        )
                    } finally {
                        _isSubmitting.value = false
                    }
                }
                return
            }
            viewModelScope.launch {
                try {
                    _batchIntents.send(batchIntent.commandText)
                    inputText.value = ""
                } finally {
                    _isSubmitting.value = false
                }
            }
            return
        }

        // Multi-task creation pre-pass (Phase B / PR-C). Routes
        // newline-separated or comma-segmented + time-marker-dense
        // input to the dedicated bottom sheet. Pro users get the
        // Haiku-backed extractor; Free users fall through to a local
        // split-and-create-each loop so multi-line / multi-segment
        // input creates one task per segment instead of collapsing
        // into a single combined title (which previously read as
        // "only the first task got added").
        val multiCreate = multiCreateDetector.detect(text)
        if (multiCreate is MultiCreateDetector.Result.MultiCreate) {
            if (proFeatureGate.hasAccess(ProFeatureGate.AI_NLP)) {
                viewModelScope.launch {
                    try {
                        _multiCreateIntents.send(multiCreate.rawText)
                        inputText.value = ""
                    } finally {
                        _isSubmitting.value = false
                    }
                }
            } else {
                // createTasksLocallyFromSegments runs its own
                // viewModelScope.launch and toggles _isSubmitting in a
                // finally block. The outer sync set above + the inner
                // toggle compose cleanly: outer sets true, inner sets
                // true (no-op), inner sets false in finally — flag
                // released after the local create finishes.
                createTasksLocallyFromSegments(multiCreate.segments, plannedDateOverride)
            }
            return
        }

        // Template shortcut branch — "/name" or "template:name" bypass the
        // normal NLP pipeline and instead resolve against the user's
        // template library.
        val templateQuery = parser.extractTemplateQuery(text)
        if (templateQuery != null) {
            viewModelScope.launch {
                _isSubmitting.value = true
                try {
                    resolveAndCreateFromTemplate(templateQuery, plannedDateOverride)
                } catch (e: Exception) {
                    Log.e("QuickAddVM", "Failed to resolve template shortcut", e)
                } finally {
                    _isSubmitting.value = false
                }
            }
            return
        }

        // v1.4.0 Projects feature intent pre-pass. Only CreateTask flows
        // fall through to the task-creation path below — project/milestone
        // intents short-circuit here. See ProjectIntentParser for the regex
        // set and the fallback rules.
        val intent = intentParser.parse(text)
        if (intent is ProjectIntent.CreateProject ||
            intent is ProjectIntent.CompleteProject ||
            intent is ProjectIntent.AddMilestone
        ) {
            viewModelScope.launch {
                _isSubmitting.value = true
                try {
                    handleProjectIntent(intent)
                    inputText.value = ""
                } catch (e: Exception) {
                    Log.e("QuickAddVM", "Failed to handle project intent: $intent", e)
                } finally {
                    _isSubmitting.value = false
                }
            }
            return
        }

        val projectHintFromIntent = (intent as? ProjectIntent.CreateTask)?.projectHint

        // Confirm-before-save path: stage the parse result and let the UI
        // surface [TaskConfirmSheet] for the user to review/edit. Skipped
        // when continuous voice mode is active (hands-free contract — the
        // utterance IS the confirm) or when the preference is off.
        if (showConfirmation.value && !_continuousModeActive.value) {
            viewModelScope.launch {
                try {
                    val parsed = if (proFeatureGate.hasAccess(ProFeatureGate.AI_NLP)) {
                        parser.parseRemote(text)
                    } else {
                        parser.parse(text)
                    }
                    val resolvedCategory = parsed.lifeCategory ?: run {
                        val guess = lifeCategoryClassifier().classify(parsed.title)
                        if (guess == LifeCategory.UNCATEGORIZED) null else guess.name
                    }
                    _pendingConfirm.value = PendingConfirmTask(
                        title = parsed.title,
                        dueDate = parsed.dueDate,
                        dueTime = parsed.dueTime,
                        priority = parsed.priority,
                        projectName = parsed.projectName ?: projectHintFromIntent,
                        tags = parsed.tags,
                        recurrenceHint = parsed.recurrenceHint,
                        lifeCategory = resolvedCategory,
                        taskMode = parsed.taskMode,
                        cognitiveLoad = parsed.cognitiveLoad,
                        plannedDateOverride = plannedDateOverride
                    )
                } catch (e: Exception) {
                    Log.e("QuickAddVM", "Failed to parse for confirm", e)
                } finally {
                    _isSubmitting.value = false
                }
            }
            return
        }

        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                // Use backend NLP for Pro users, local regex parser for free users
                val parsed = if (proFeatureGate.hasAccess(ProFeatureGate.AI_NLP)) {
                    parser.parseRemote(text)
                } else {
                    parser.parse(text)
                }
                val resolved = resolver.resolve(parsed)

                // Auto-create unmatched tags
                val newTagIds = resolved.unmatchedTags.map { tagName ->
                    tagRepository.addTag(name = tagName)
                }
                val allTagIds = resolved.tagIds + newTagIds

                // Auto-create unmatched project
                var projectId = resolved.projectId
                if (projectId == null && resolved.unmatchedProject != null) {
                    projectId = projectRepository.addProject(name = resolved.unmatchedProject)
                }

                // If the NLP parser didn't pick up a project but the
                // intent parser found a trailing "for the X project"
                // hint, resolve (or auto-create) by name.
                if (projectId == null && projectHintFromIntent != null) {
                    projectId = findOrCreateProjectByName(projectHintFromIntent)
                }

                // Build recurrence JSON
                val recurrenceJson = resolved.recurrenceRule?.let { RecurrenceConverter.toJson(it) }

                val now = System.currentTimeMillis()
                // If NLP didn't pick up a category tag, fall back to the
                // keyword classifier so Today's balance bar still gets data.
                val resolvedCategory = resolved.lifeCategory ?: run {
                    val guess = lifeCategoryClassifier().classify(resolved.title)
                    if (guess == LifeCategory.UNCATEGORIZED) null else guess.name
                }
                val task = TaskEntity(
                    title = resolved.title,
                    dueDate = resolved.dueDate,
                    dueTime = resolved.dueTime,
                    priority = resolved.priority,
                    projectId = projectId,
                    recurrenceRule = recurrenceJson,
                    plannedDate = plannedDateOverride,
                    lifeCategory = resolvedCategory,
                    createdAt = now,
                    updatedAt = now
                )
                val taskId = taskRepository.insertTask(task)
                lastCreatedTaskId = taskId

                // Assign tags
                if (allTagIds.isNotEmpty()) {
                    tagRepository.setTagsForTask(taskId, allTagIds)
                }

                // Log usage for suggestions
                val keywords = extractKeywords(resolved.title).joinToString(",")
                if (keywords.isNotBlank()) {
                    allTagIds.forEach { tagId ->
                        val tagName = resolved.unmatchedTags.getOrNull(
                            (tagId - (resolved.tagIds.lastOrNull() ?: 0) - 1).toInt().coerceAtLeast(0)
                        ) ?: resolved.title
                        usageLogDao.insert(
                            UsageLogEntity(
                                eventType = "tag_assigned",
                                entityId = tagId,
                                entityName = tagName,
                                taskTitle = resolved.title,
                                titleKeywords = keywords
                            )
                        )
                    }
                    if (projectId != null) {
                        usageLogDao.insert(
                            UsageLogEntity(
                                eventType = "project_assigned",
                                entityId = projectId,
                                entityName = resolved.unmatchedProject ?: "",
                                taskTitle = resolved.title,
                                titleKeywords = keywords
                            )
                        )
                    }
                }

                inputText.value = ""
            } catch (e: Exception) {
                Log.e("QuickAddVM", "Failed to create task", e)
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    /**
     * Free-tier fallback for multi-task input. Runs the offline
     * [NaturalLanguageParser] over each detected segment and inserts each
     * resulting task sequentially. The Pro Haiku extractor + bottom-sheet
     * preview is gated behind `ProFeatureGate.AI_NLP`; this path keeps the
     * core "type a list, get a list of tasks" experience working without
     * the network call.
     */
    private fun createTasksLocallyFromSegments(
        segments: List<String>,
        plannedDateOverride: Long?
    ) {
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                var lastId: Long? = null
                for (segment in segments) {
                    val cleaned = segment.trim()
                    if (cleaned.isEmpty()) continue
                    val parsed = parser.parse(cleaned)
                    val resolved = resolver.resolve(parsed)

                    val newTagIds = resolved.unmatchedTags.map { tagName ->
                        tagRepository.addTag(name = tagName)
                    }
                    val allTagIds = resolved.tagIds + newTagIds

                    var projectId = resolved.projectId
                    if (projectId == null && resolved.unmatchedProject != null) {
                        projectId = projectRepository.addProject(name = resolved.unmatchedProject)
                    }

                    val recurrenceJson = resolved.recurrenceRule?.let { RecurrenceConverter.toJson(it) }
                    val now = System.currentTimeMillis()
                    val resolvedCategory = resolved.lifeCategory ?: run {
                        val guess = lifeCategoryClassifier().classify(resolved.title)
                        if (guess == LifeCategory.UNCATEGORIZED) null else guess.name
                    }
                    val task = TaskEntity(
                        title = resolved.title,
                        dueDate = resolved.dueDate,
                        dueTime = resolved.dueTime,
                        priority = resolved.priority,
                        projectId = projectId,
                        recurrenceRule = recurrenceJson,
                        plannedDate = plannedDateOverride,
                        lifeCategory = resolvedCategory,
                        createdAt = now,
                        updatedAt = now
                    )
                    val taskId = taskRepository.insertTask(task)
                    lastId = taskId
                    if (allTagIds.isNotEmpty()) {
                        tagRepository.setTagsForTask(taskId, allTagIds)
                    }
                }
                lastCreatedTaskId = lastId
                inputText.value = ""
            } catch (e: Exception) {
                Log.e("QuickAddVM", "Local multi-task create failed", e)
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    /**
     * Dispatches a project-management intent. Projects feature Phase 4.
     * All branches are idempotent-ish: creating a project with the same
     * name twice yields two projects (matching the rest of the app's
     * current no-dedup behavior), and "complete/add milestone" fail
     * silently when the named project doesn't exist yet — surfacing a
     * user-facing error channel is a follow-up polish item.
     */
    private suspend fun handleProjectIntent(intent: ProjectIntent) {
        when (intent) {
            is ProjectIntent.CreateProject -> {
                projectRepository.addProject(
                    name = intent.name,
                    description = null,
                    status = ProjectStatus.ACTIVE,
                    startDate = null,
                    endDate = null,
                    themeColorKey = null
                )
            }
            is ProjectIntent.CompleteProject -> {
                val match = findProjectByName(intent.projectName) ?: return
                projectRepository.completeProject(match)
            }
            is ProjectIntent.AddMilestone -> {
                val projectId = findOrCreateProjectByName(intent.projectName)
                projectRepository.addMilestone(projectId, intent.milestoneTitle)
            }
            is ProjectIntent.CreateTask -> Unit // handled by the main onSubmit path
        }
    }

    private suspend fun findProjectByName(name: String): Long? {
        val query = name.trim().lowercase()
        if (query.isEmpty()) return null
        val all = try {
            projectRepository.getAllProjects().first()
        } catch (e: Exception) {
            Log.e("QuickAddVM", "Project lookup failed", e)
            return null
        }
        // Exact case-insensitive match first, then contains fallback.
        all.firstOrNull { it.name.lowercase() == query }?.let { return it.id }
        return all.firstOrNull { it.name.lowercase().contains(query) }?.id
    }

    private suspend fun findOrCreateProjectByName(name: String): Long {
        val existing = findProjectByName(name)
        if (existing != null) return existing
        return projectRepository.addProject(name = name.trim())
    }

    /**
     * Resolve a template name query against the user's library. If exactly
     * one template matches (case-insensitive substring), creates the task
     * from it immediately. If multiple match, exposes the candidates via
     * [templateDisambiguation] so the UI can show a picker popup.
     */
    private suspend fun resolveAndCreateFromTemplate(
        query: String,
        plannedDateOverride: Long?
    ) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        val all = try {
            templateRepository.getAllTemplates().first()
        } catch (e: Exception) {
            Log.e("QuickAddVM", "Failed to fetch templates", e)
            return
        }
        val matches = fuzzyMatchTemplates(all, normalized)
        when {
            matches.isEmpty() -> { }
            matches.size == 1 -> {
                createFromTemplate(matches.first().id, plannedDateOverride)
                inputText.value = ""
            }
            else -> {
                // More than one candidate — let the UI disambiguate.
                _templateDisambiguation.value = matches
            }
        }
    }

    /**
     * Plan-aware template creation: delegates to the repository's
     * [TaskTemplateRepository.createTaskFromTemplate] with the current
     * plan-date override so quick-add shortcuts in the Plan-for-Today sheet
     * land tasks on today's dashboard.
     */
    private suspend fun createFromTemplate(templateId: Long, plannedDateOverride: Long?) {
        try {
            val newTaskId = templateRepository.createTaskFromTemplate(
                templateId = templateId,
                dueDateOverride = plannedDateOverride,
                quickUse = true
            )
            if (plannedDateOverride != null) {
                taskRepository.planTaskForToday(newTaskId)
            }
            lastCreatedTaskId = newTaskId
        } catch (e: Exception) {
            Log.e("QuickAddVM", "Failed to create from template", e)
        }
    }

    /**
     * Called by the UI when the user taps a candidate in the disambiguation
     * popup. Creates the task from the chosen template, clears the input,
     * and dismisses the popup.
     */
    fun onDisambiguationSelected(templateId: Long, plannedDateOverride: Long? = null) {
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                createFromTemplate(templateId, plannedDateOverride)
                inputText.value = ""
            } finally {
                _templateDisambiguation.value = null
                _isSubmitting.value = false
            }
        }
    }

    fun onDismissDisambiguation() {
        _templateDisambiguation.value = null
    }

    /**
     * Commit a confirm-sheet preview to the database. Reuses [resolver] to
     * convert raw tag / project names into ids (auto-creating both as
     * needed) so the resulting task is structurally identical to one made
     * via the immediate-insert path.
     */
    fun confirmAndSave(edited: PendingConfirmTask) {
        _pendingConfirm.value = null
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                val synthetic = ParsedTask(
                    title = edited.title,
                    dueDate = edited.dueDate,
                    dueTime = edited.dueTime,
                    tags = edited.tags,
                    projectName = edited.projectName,
                    priority = edited.priority,
                    recurrenceHint = edited.recurrenceHint,
                    lifeCategory = edited.lifeCategory,
                    taskMode = edited.taskMode,
                    cognitiveLoad = edited.cognitiveLoad
                )
                val resolved = resolver.resolve(synthetic)

                val newTagIds = resolved.unmatchedTags.map { tagName ->
                    tagRepository.addTag(name = tagName)
                }
                val allTagIds = resolved.tagIds + newTagIds

                var projectId = resolved.projectId
                if (projectId == null && resolved.unmatchedProject != null) {
                    projectId = projectRepository.addProject(name = resolved.unmatchedProject)
                }

                val recurrenceJson = resolved.recurrenceRule?.let { RecurrenceConverter.toJson(it) }
                val now = System.currentTimeMillis()
                val task = TaskEntity(
                    title = resolved.title,
                    dueDate = resolved.dueDate,
                    dueTime = resolved.dueTime,
                    priority = resolved.priority,
                    projectId = projectId,
                    recurrenceRule = recurrenceJson,
                    plannedDate = edited.plannedDateOverride,
                    lifeCategory = resolved.lifeCategory,
                    taskMode = resolved.taskMode,
                    cognitiveLoad = resolved.cognitiveLoad,
                    createdAt = now,
                    updatedAt = now
                )
                val taskId = taskRepository.insertTask(task)
                lastCreatedTaskId = taskId

                if (allTagIds.isNotEmpty()) {
                    tagRepository.setTagsForTask(taskId, allTagIds)
                }

                val keywords = extractKeywords(resolved.title).joinToString(",")
                if (keywords.isNotBlank()) {
                    allTagIds.forEach { tagId ->
                        val tagName = resolved.unmatchedTags.getOrNull(
                            (tagId - (resolved.tagIds.lastOrNull() ?: 0) - 1).toInt().coerceAtLeast(0)
                        ) ?: resolved.title
                        usageLogDao.insert(
                            UsageLogEntity(
                                eventType = "tag_assigned",
                                entityId = tagId,
                                entityName = tagName,
                                taskTitle = resolved.title,
                                titleKeywords = keywords
                            )
                        )
                    }
                    if (projectId != null) {
                        usageLogDao.insert(
                            UsageLogEntity(
                                eventType = "project_assigned",
                                entityId = projectId,
                                entityName = resolved.unmatchedProject ?: "",
                                taskTitle = resolved.title,
                                titleKeywords = keywords
                            )
                        )
                    }
                }

                inputText.value = ""
            } catch (e: Exception) {
                Log.e("QuickAddVM", "Failed to insert from confirm", e)
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    /** Discard a pending confirm preview without writing anything. */
    fun dismissPendingConfirm() {
        _pendingConfirm.value = null
    }

    override fun onCleared() {
        super.onCleared()
        voiceInputManager.stopListening()
    }

    /**
     * Simple substring-based fuzzy matcher: returns templates whose name
     * contains any of the space-separated tokens in [query] (case-insensitive).
     * Ranks exact-name matches first, then prefix matches, then substring.
     */
    private fun fuzzyMatchTemplates(
        templates: List<TaskTemplateEntity>,
        query: String
    ): List<TaskTemplateEntity> {
        val q = query.lowercase()
        return templates
            .mapNotNull { template ->
                val name = template.name.lowercase()
                val score = when {
                    name == q -> 0
                    name.startsWith(q) -> 1
                    name.contains(q) -> 2
                    else -> null
                }
                score?.let { template to it }
            }.sortedBy { it.second }
            .map { it.first }
    }
}
