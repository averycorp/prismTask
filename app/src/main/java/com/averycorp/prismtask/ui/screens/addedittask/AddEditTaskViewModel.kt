package com.averycorp.prismtask.ui.screens.addedittask

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.converter.RecurrenceConverter
import com.averycorp.prismtask.data.local.entity.AttachmentEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskDependencyEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.AttachmentRepository
import com.averycorp.prismtask.data.repository.BoundaryRuleRepository
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskDependencyRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.data.repository.TaskTemplateRepository
import com.averycorp.prismtask.data.repository.TaskTimingRepository
import com.averycorp.prismtask.domain.model.CognitiveLoad
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.model.RecurrenceRule
import com.averycorp.prismtask.domain.model.TaskMode
import com.averycorp.prismtask.domain.usecase.BoundaryDecision
import com.averycorp.prismtask.domain.usecase.BoundaryEnforcer
import com.averycorp.prismtask.domain.usecase.CognitiveLoadClassifier
import com.averycorp.prismtask.domain.usecase.LifeCategoryClassifier
import com.averycorp.prismtask.domain.usecase.NaturalLanguageParser
import com.averycorp.prismtask.domain.usecase.ParsedTaskResolver
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.domain.usecase.TaskModeClassifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lightweight local model for subtasks surfaced in the editor's Details tab.
 * Subtasks are held in VM state until the task is persisted — this lets
 * templates pre-fill a list of titles that the user can tweak before the
 * save path flushes them into [TaskRepository] as real [TaskEntity] rows.
 */
data class PendingSubtask(
    val id: Long,
    val title: String,
    val isCompleted: Boolean = false
)

@HiltViewModel
class AddEditTaskViewModel
@Inject
constructor(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val tagRepository: TagRepository,
    private val attachmentRepository: AttachmentRepository,
    private val templateRepository: TaskTemplateRepository,
    private val taskTimingRepository: TaskTimingRepository,
    private val taskDependencyRepository: TaskDependencyRepository,
    private val boundaryRuleRepository: BoundaryRuleRepository,
    private val notificationPreferences: NotificationPreferences,
    private val userPreferencesDataStore: com.averycorp.prismtask.data.preferences.UserPreferencesDataStore,
    taskBehaviorPreferences: TaskBehaviorPreferences,
    private val advancedTuningPreferences: com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences,
    private val parser: NaturalLanguageParser,
    private val parsedTaskResolver: ParsedTaskResolver,
    private val proFeatureGate: ProFeatureGate,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val boundaryEnforcer = BoundaryEnforcer()

    /**
     * User-configurable reminder offsets (millis-before-due). Falls back to the
     * factory list `0,15m,30m,1h,1d` when the user hasn't customized presets.
     * The "None" option is added by the picker UI; this list is only the
     * positive-offset choices.
     */
    val reminderPresets: StateFlow<List<Long>> = taskBehaviorPreferences
        .getReminderPresets()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            listOf(0L, 900_000L, 1_800_000L, 3_600_000L, 86_400_000L)
        )

    val editorFieldRows: StateFlow<com.averycorp.prismtask.data.preferences.EditorFieldRows> =
        advancedTuningPreferences.getEditorFieldRows()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.averycorp.prismtask.data.preferences.EditorFieldRows()
            )

    private val _errorMessages = MutableSharedFlow<String>()
    val errorMessages: SharedFlow<String> = _errorMessages.asSharedFlow()

    private var currentTaskId: Long? by mutableStateOf(null)
    private val _taskIdFlow = MutableStateFlow<Long?>(null)
    val isEditMode: Boolean get() = currentTaskId != null

    /** The id of the task currently being edited, or null in create mode. */
    val currentEditingTaskId: Long? get() = currentTaskId

    private var existingTask: TaskEntity? = null
    private var loadJob: Job? = null

    var title by mutableStateOf("")
        private set
    var description by mutableStateOf("")
        private set
    var dueDate by mutableStateOf<Long?>(null)
        private set
    var dueTime by mutableStateOf<Long?>(null)
        private set
    var priority by mutableIntStateOf(0)
        private set
    var projectId by mutableStateOf<Long?>(null)
        private set
    var parentTaskId by mutableStateOf<Long?>(null)
        private set
    var recurrenceRule by mutableStateOf<RecurrenceRule?>(null)
        private set
    var reminderOffset by mutableStateOf<Long?>(null)
        private set
    var estimatedDuration by mutableStateOf<Int?>(null)
        private set
    var titleError by mutableStateOf(false)
        private set
    var notes by mutableStateOf("")
        private set
    var selectedTagIds by mutableStateOf(setOf<Long>())
        private set

    /**
     * Work-Life Balance category currently displayed on the Organize tab.
     * `null` means no chip is selected — either because the title is still
     * blank (the auto-press hasn't picked yet) or the classifier returned
     * UNCATEGORIZED. Save path falls back to a last-chance classifier run if
     * still `null` at save time.
     */
    var lifeCategory by mutableStateOf<LifeCategory?>(null)
        private set

    /**
     * True iff the user explicitly tapped a real chip. The auto-press
     * respects this (won't overwrite a manual pick); tapping the on-screen
     * "Auto" button forces it back to `false` and re-runs the classifier.
     */
    var lifeCategoryManuallySet by mutableStateOf(false)
        private set

    /**
     * Live snapshot of the user's custom life-category keywords. The
     * classifier is rebuilt from this at save time so newly-added keywords
     * (Settings → Advanced Tuning) take effect on the next task creation
     * without needing the editor to be re-opened.
     */
    private val lifeCategoryCustomKeywords:
        StateFlow<com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords> =
        advancedTuningPreferences.getLifeCategoryCustomKeywords().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords()
        )

    /**
     * Reward / output mode for this task. `null` means no chip is shown —
     * see [lifeCategory] for the auto-press semantic. Orthogonal to
     * [lifeCategory] (see `docs/WORK_PLAY_RELAX.md`).
     */
    var taskMode by mutableStateOf<TaskMode?>(null)
        private set

    var taskModeManuallySet by mutableStateOf(false)
        private set

    private val taskModeCustomKeywords: StateFlow<com.averycorp.prismtask.data.preferences.TaskModeCustomKeywords> =
        advancedTuningPreferences.getTaskModeCustomKeywords().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            com.averycorp.prismtask.data.preferences.TaskModeCustomKeywords()
        )

    /**
     * Start-friction for this task. `null` means no chip is shown — see
     * [lifeCategory] for the auto-press semantic. Orthogonal to
     * [lifeCategory] / [taskMode] (see `docs/COGNITIVE_LOAD.md`).
     */
    var cognitiveLoad by mutableStateOf<CognitiveLoad?>(null)
        private set

    var cognitiveLoadManuallySet by mutableStateOf(false)
        private set

    private val cognitiveLoadCustomKeywords:
        StateFlow<com.averycorp.prismtask.data.preferences.CognitiveLoadCustomKeywords> =
        advancedTuningPreferences.getCognitiveLoadCustomKeywords().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            com.averycorp.prismtask.data.preferences.CognitiveLoadCustomKeywords()
        )

    /**
     * Boundary-rule decision for the task currently being edited. The save
     * path checks this and bubbles a [BoundaryDecision.Block] up to the UI
     * via [pendingBoundaryBlock]; [BoundaryDecision.Suggest] pre-fills the
     * life category field.
     */
    var pendingBoundaryBlock by mutableStateOf<BoundaryDecision.Block?>(null)
        private set

    fun dismissBoundaryBlock() {
        pendingBoundaryBlock = null
    }

    /**
     * Evaluate boundary rules against the current draft. Returns `true` if
     * the save should proceed, `false` if the UI should show the block dialog.
     * A SUGGEST decision silently pre-fills [lifeCategory] unless the user
     * already set one manually.
     */
    private suspend fun evaluateBoundaryRules(): Boolean {
        val draftCategory = lifeCategory
            ?: LifeCategoryClassifier.withCustomKeywords(lifeCategoryCustomKeywords.value)
                .classify(title, description.ifBlank { null })
                .takeIf { it != LifeCategory.UNCATEGORIZED }
            ?: return true
        val rules = boundaryRuleRepository.getRulesOnce()
        if (rules.isEmpty()) return true
        return when (val decision = boundaryEnforcer.evaluate(rules, draftCategory)) {
            is BoundaryDecision.Allow -> true
            is BoundaryDecision.Block -> {
                pendingBoundaryBlock = decision
                false
            }
            is BoundaryDecision.Suggest -> {
                if (!lifeCategoryManuallySet) {
                    lifeCategory = decision.category
                }
                true
            }
        }
    }

    /**
     * Unpersisted subtasks for the task currently being composed. Populated
     * either by the user typing into the Details tab's subtask field or by
     * applying a template (which dumps its blueprint subtask titles here).
     * Flushed into real [TaskEntity] rows when [saveTask] succeeds.
     */
    val pendingSubtasks: SnapshotStateList<PendingSubtask> = mutableStateListOf()
    private var nextPendingSubtaskId: Long = 1L

    // Snapshot of initial values for unsaved-changes detection.
    private var hasInitialized: Boolean = false
    private var initialTitle: String = ""
    private var initialDescription: String = ""
    private var initialDueDate: Long? = null
    private var initialDueTime: Long? = null
    private var initialPriority: Int = 0
    private var initialProjectId: Long? = null
    private var initialParentTaskId: Long? = null
    private var initialRecurrenceRule: RecurrenceRule? = null
    private var initialReminderOffset: Long? = null
    private var initialEstimatedDuration: Int? = null
    private var initialNotes: String = ""
    private var initialSelectedTagIds: Set<Long> = emptySet()
    private var initialLifeCategory: LifeCategory? = null
    private var initialTaskMode: TaskMode? = null
    private var initialCognitiveLoad: CognitiveLoad? = null

    val projects: StateFlow<List<ProjectEntity>> = projectRepository
        .getAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTags: StateFlow<List<TagEntity>> = tagRepository
        .getAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val attachments: StateFlow<List<AttachmentEntity>> = _taskIdFlow
        .flatMapLatest { id ->
            if (id != null) {
                attachmentRepository.getAttachments(id)
            } else {
                flowOf(emptyList())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Count of persisted subtasks for the task being edited. Drives the
    // "Include Subtasks (N)" checkbox on the duplicate dialog.
    @OptIn(ExperimentalCoroutinesApi::class)
    val subtaskCount: StateFlow<Int> = _taskIdFlow
        .flatMapLatest { id ->
            if (id != null) {
                taskRepository.getSubtasks(id).map { it.size }
            } else {
                flowOf(0)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Sum of all logged time entries (manual / pomodoro / timer) for the task
    // being edited. Drives the "Logged Time" section in the Schedule tab.
    // Empty in create mode (no taskId yet) — the section hides via isEditMode.
    @OptIn(ExperimentalCoroutinesApi::class)
    val loggedMinutes: StateFlow<Int> = _taskIdFlow
        .flatMapLatest { id ->
            if (id != null) {
                taskTimingRepository.observeSumMinutesForTask(id)
            } else {
                flowOf(0)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * Edges where the currently-edited task is the *blocked* endpoint —
     * i.e. the tasks that must finish before this one can start. Drives
     * the Organize tab's Blockers section. Empty until the task is
     * persisted (edges FK to a real `tasks.id`).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val blockers: StateFlow<List<TaskDependencyEntity>> = _taskIdFlow
        .flatMapLatest { id ->
            if (id != null) {
                taskDependencyRepository.observeBlockersOf(id)
            } else {
                flowOf(emptyList())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Snapshot of all tasks for the blocker picker. The dependency table
     * is project-agnostic — cross-project edges are legal — so the picker
     * shows every task in the database minus the currently-edited one
     * and any tasks already on the blocker list.
     */
    val allTasksForPicker: StateFlow<List<TaskEntity>> = taskRepository
        .getAllTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics
                .getInstance()
                .setCustomKey("screen", "AddEditTaskScreen")
        } catch (_: Exception) {
        }
        // Backward compat: when opened via the navigation route, SavedStateHandle
        // contains the taskId nav arg. Sheet-based invocation leaves this null and
        // calls initialize() explicitly from the host composable.
        val routeTaskId = savedStateHandle.get<Long>("taskId")
        if (routeTaskId != null) {
            initialize(
                taskId = routeTaskId.takeIf { it != -1L },
                projectId = null,
                initialDate = null
            )
        }
    }

    /**
     * Prepare the form for a new invocation. Resets all fields, then either
     * loads an existing task (edit mode) or applies the given defaults (create mode).
     * Safe to call multiple times — each call re-seeds the form and cancels any
     * in-flight load from a previous call.
     */
    fun initialize(taskId: Long?, projectId: Long?, initialDate: Long?) {
        loadJob?.cancel()

        // Reset all fields to defaults / supplied create-mode seeds.
        currentTaskId = taskId
        _taskIdFlow.value = taskId
        existingTask = null
        title = ""
        description = ""
        dueDate = initialDate
        dueTime = null
        priority = 0
        this.projectId = projectId
        parentTaskId = null
        recurrenceRule = null
        reminderOffset = null
        estimatedDuration = null
        notes = ""
        selectedTagIds = emptySet()
        lifeCategory = null
        lifeCategoryManuallySet = false
        taskMode = null
        taskModeManuallySet = false
        cognitiveLoad = null
        cognitiveLoadManuallySet = false
        titleError = false
        pendingSubtasks.clear()
        nextPendingSubtaskId = 1L

        if (taskId != null) {
            // Snapshot with defaults until the real task loads — the load
            // will replace the snapshot with the true initial values.
            hasInitialized = false
            loadJob = viewModelScope.launch {
                try {
                    val task = taskRepository.getTaskById(taskId).firstOrNull()
                    val tagIds = tagRepository
                        .getTagsForTask(taskId)
                        .firstOrNull()
                        ?.map { it.id }
                        ?.toSet()
                        ?: emptySet()
                    if (task != null) {
                        existingTask = task
                        title = task.title
                        description = task.description.orEmpty()
                        dueDate = task.dueDate
                        dueTime = task.dueTime
                        priority = task.priority
                        this@AddEditTaskViewModel.projectId = task.projectId
                        parentTaskId = task.parentTaskId
                        recurrenceRule = task.recurrenceRule?.let { RecurrenceConverter.fromJson(it) }
                        reminderOffset = task.reminderOffset
                        estimatedDuration = task.estimatedDuration
                        notes = task.notes.orEmpty()
                        selectedTagIds = tagIds
                        val loadedCategory = LifeCategory.fromStorage(task.lifeCategory)
                        lifeCategory = loadedCategory.takeIf { it != LifeCategory.UNCATEGORIZED }
                        lifeCategoryManuallySet = lifeCategory != null
                        val loadedMode = TaskMode.fromStorage(task.taskMode)
                        taskMode = loadedMode.takeIf { it != TaskMode.UNCATEGORIZED }
                        taskModeManuallySet = taskMode != null
                        val loadedLoad = CognitiveLoad.fromStorage(task.cognitiveLoad)
                        cognitiveLoad = loadedLoad.takeIf { it != CognitiveLoad.UNCATEGORIZED }
                        cognitiveLoadManuallySet = cognitiveLoad != null
                        snapshotInitialValuesFromTask(task, tagIds)
                    } else {
                        snapshotInitialValuesForCreate(projectId, initialDate)
                    }
                } catch (e: Exception) {
                    Log.e("AddEditTaskVM", "Failed to load task", e)
                    snapshotInitialValuesForCreate(projectId, initialDate)
                } finally {
                    hasInitialized = true
                }
            }
        } else {
            snapshotInitialValuesForCreate(projectId, initialDate)
            hasInitialized = true
            // Pre-fill the reminder offset for new tasks from the user's
            // configured default. A value of OFFSET_NONE / -1 means "no
            // default" (leave it null so the user must opt in explicitly).
            // Edit-mode never goes through this path, so we never overwrite
            // an existing task's saved reminder.
            loadJob = viewModelScope.launch {
                try {
                    val default = notificationPreferences.getDefaultReminderOffsetOnce()
                    if (default != NotificationPreferences.OFFSET_NONE && default >= 0L) {
                        // Only apply if the user hasn't already changed it.
                        if (reminderOffset == null) {
                            reminderOffset = default
                            initialReminderOffset = default
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AddEditTaskVM", "Failed to load default reminder offset", e)
                }
            }
        }
    }

    private fun snapshotInitialValuesFromTask(task: TaskEntity, tagIds: Set<Long>) {
        initialTitle = task.title
        initialDescription = task.description.orEmpty()
        initialDueDate = task.dueDate
        initialDueTime = task.dueTime
        initialPriority = task.priority
        initialProjectId = task.projectId
        initialParentTaskId = task.parentTaskId
        initialRecurrenceRule = task.recurrenceRule?.let { RecurrenceConverter.fromJson(it) }
        initialReminderOffset = task.reminderOffset
        initialEstimatedDuration = task.estimatedDuration
        initialNotes = task.notes.orEmpty()
        initialSelectedTagIds = tagIds
        initialLifeCategory = LifeCategory.fromStorage(task.lifeCategory).takeIf {
            it != LifeCategory.UNCATEGORIZED
        }
        initialTaskMode = TaskMode.fromStorage(task.taskMode).takeIf {
            it != TaskMode.UNCATEGORIZED
        }
        initialCognitiveLoad = CognitiveLoad.fromStorage(task.cognitiveLoad).takeIf {
            it != CognitiveLoad.UNCATEGORIZED
        }
    }

    private fun snapshotInitialValuesForCreate(projectId: Long?, initialDate: Long?) {
        initialTitle = ""
        initialDescription = ""
        initialDueDate = initialDate
        initialDueTime = null
        initialPriority = 0
        initialProjectId = projectId
        initialParentTaskId = null
        initialRecurrenceRule = null
        initialReminderOffset = null
        initialEstimatedDuration = null
        initialNotes = ""
        initialSelectedTagIds = emptySet()
        initialLifeCategory = null
        initialTaskMode = null
        initialCognitiveLoad = null
    }

    val hasUnsavedChanges: Boolean
        get() = hasInitialized &&
            (
                title != initialTitle ||
                    description != initialDescription ||
                    dueDate != initialDueDate ||
                    dueTime != initialDueTime ||
                    priority != initialPriority ||
                    projectId != initialProjectId ||
                    parentTaskId != initialParentTaskId ||
                    recurrenceRule != initialRecurrenceRule ||
                    reminderOffset != initialReminderOffset ||
                    estimatedDuration != initialEstimatedDuration ||
                    notes != initialNotes ||
                    selectedTagIds != initialSelectedTagIds ||
                    lifeCategory != initialLifeCategory ||
                    taskMode != initialTaskMode ||
                    cognitiveLoad != initialCognitiveLoad
                )

    fun onTitleChange(value: String) {
        title = value
        if (value.isNotBlank()) titleError = false
    }

    fun onDescriptionChange(value: String) {
        description = value
    }

    fun onDueDateChange(value: Long?) {
        dueDate = value
    }

    fun onDueTimeChange(value: Long?) {
        dueTime = value
    }

    fun onPriorityChange(value: Int) {
        priority = value
    }

    fun onProjectIdChange(value: Long?) {
        projectId = value
    }

    fun onRecurrenceRuleChange(value: RecurrenceRule?) {
        recurrenceRule = value
    }

    fun onNotesChange(value: String) {
        notes = value
    }

    fun onReminderOffsetChange(value: Long?) {
        reminderOffset = value
    }

    fun onEstimatedDurationChange(value: Int?) {
        estimatedDuration = value
    }

    /**
     * Append a manual time-tracking entry to the task being edited. Only
     * valid in edit mode (the task must already exist so timings can FK to
     * it). Negative or zero minutes are silently ignored at the call site —
     * the underlying repository requires `> 0` and would otherwise throw.
     */
    fun logTime(minutes: Int) {
        val taskId = currentTaskId ?: return
        if (minutes <= 0) return
        viewModelScope.launch {
            try {
                taskTimingRepository.logTime(taskId = taskId, durationMinutes = minutes)
            } catch (e: Exception) {
                Log.w("AddEditTaskVM", "logTime failed", e)
                _errorMessages.emit("Failed to log time")
            }
        }
    }

    fun onSelectedTagIdsChange(value: Set<Long>) {
        selectedTagIds = value
    }

    fun onParentTaskIdChange(value: Long?) {
        parentTaskId = value
    }

    /**
     * Set the [LifeCategory] chip from a manual user tap. The user's pick
     * is sticky — auto-press will not overwrite it until [autoPickLifeCategory]
     * is invoked with `force = true` (the on-screen "Auto" button).
     */
    fun onLifeCategoryChange(value: LifeCategory?) {
        lifeCategory = value
        lifeCategoryManuallySet = value != null
    }

    /** Manual user pick on the Task Mode chip — see [onLifeCategoryChange]. */
    fun onTaskModeChange(value: TaskMode?) {
        taskMode = value
        taskModeManuallySet = value != null
    }

    /** Manual user pick on the Cognitive Load chip — see [onLifeCategoryChange]. */
    fun onCognitiveLoadChange(value: CognitiveLoad?) {
        cognitiveLoad = value
        cognitiveLoadManuallySet = value != null
    }

    /**
     * Run the keyword classifier on the current title/description and pick
     * a real [LifeCategory] chip for the user. Driven by the Organize tab's
     * `LaunchedEffect` (auto-press) and by the on-screen "Auto" button
     * (manual press, with `force = true`).
     *
     * - Skips when the user has already manually picked a chip, unless
     *   [force] is set (i.e. the operator explicitly tapped Auto to reset).
     * - Empty title or no-keyword-match leaves the chip unselected so the
     *   next title/description edit gets another chance to pick.
     * - Never flips [lifeCategoryManuallySet] to `true` — that's reserved for
     *   real user taps so the boundary-suggestion gate at the top of this
     *   class can still override an auto-picked value.
     */
    fun autoPickLifeCategory(force: Boolean = false) {
        if (lifeCategoryManuallySet && !force) return
        if (force) lifeCategoryManuallySet = false
        val guess = if (title.isBlank()) {
            LifeCategory.UNCATEGORIZED
        } else {
            LifeCategoryClassifier
                .withCustomKeywords(lifeCategoryCustomKeywords.value)
                .classify(title, description.ifBlank { null })
        }
        lifeCategory = guess.takeIf { it != LifeCategory.UNCATEGORIZED }
    }

    /** Auto-press the Task Mode chip — see [autoPickLifeCategory]. */
    fun autoPickTaskMode(force: Boolean = false) {
        if (taskModeManuallySet && !force) return
        if (force) taskModeManuallySet = false
        val guess = if (title.isBlank()) {
            TaskMode.UNCATEGORIZED
        } else {
            TaskModeClassifier
                .withCustomKeywords(taskModeCustomKeywords.value)
                .classify(title, description.ifBlank { null })
        }
        taskMode = guess.takeIf { it != TaskMode.UNCATEGORIZED }
    }

    /** Auto-press the Cognitive Load chip — see [autoPickLifeCategory]. */
    fun autoPickCognitiveLoad(force: Boolean = false) {
        if (cognitiveLoadManuallySet && !force) return
        if (force) cognitiveLoadManuallySet = false
        val guess = if (title.isBlank()) {
            CognitiveLoad.UNCATEGORIZED
        } else {
            CognitiveLoadClassifier
                .withCustomKeywords(cognitiveLoadCustomKeywords.value)
                .classify(title, description.ifBlank { null })
        }
        cognitiveLoad = guess.takeIf { it != CognitiveLoad.UNCATEGORIZED }
    }

    /**
     * Resolve the final life_category value to persist. The displayed value
     * is the source of truth — if a chip is showing (auto-picked or manual),
     * we persist that. Falls back to a last-chance classifier run only when
     * the editor never reached the Organize tab and the chip is empty.
     */
    internal fun resolveLifeCategoryForSave(): String {
        lifeCategory?.let { return it.name }
        val classifier = LifeCategoryClassifier.withCustomKeywords(lifeCategoryCustomKeywords.value)
        return classifier.classify(title, description.ifBlank { null }).name
    }

    /** Save-path resolver for task_mode — see [resolveLifeCategoryForSave]. */
    internal fun resolveTaskModeForSave(): String {
        taskMode?.let { return it.name }
        val classifier = TaskModeClassifier.withCustomKeywords(taskModeCustomKeywords.value)
        return classifier.classify(title, description.ifBlank { null }).name
    }

    /** Save-path resolver for cognitive_load — see [resolveLifeCategoryForSave]. */
    internal fun resolveCognitiveLoadForSave(): String {
        cognitiveLoad?.let { return it.name }
        val classifier = CognitiveLoadClassifier.withCustomKeywords(cognitiveLoadCustomKeywords.value)
        return classifier.classify(title, description.ifBlank { null }).name
    }

    private data class NlpEnrichment(
        val title: String,
        val dueDate: Long?,
        val dueTime: Long?,
        val priority: Int,
        val projectId: Long?,
        val tagIds: List<Long>,
        val recurrenceRule: RecurrenceRule?,
        val lifeCategory: String?,
        val taskMode: String?,
        val cognitiveLoad: String?
    )

    /**
     * Runs [rawTitle] through [NaturalLanguageParser] (Haiku-backed
     * `parseRemote` for Pro users, offline `parse` for Free) and resolves
     * it via [ParsedTaskResolver]. Returns null when the parser extracted
     * nothing actionable so callers can skip the merge cheaply.
     * Auto-creates unmatched tags / projects so `selectedTagIds` and
     * `projectId` can land on the new task — mirrors
     * `QuickAddViewModel.onSubmit` exactly. `parseRemote` already falls
     * back to local `parse` on backend failure, so the form save stays
     * resilient to backend outages.
     */
    private suspend fun enrichWithNlp(rawTitle: String): NlpEnrichment? {
        if (rawTitle.isBlank()) return null
        val parsed = if (proFeatureGate.hasAccess(ProFeatureGate.AI_NLP)) {
            parser.parseRemote(rawTitle)
        } else {
            parser.parse(rawTitle)
        }
        val resolved = parsedTaskResolver.resolve(parsed)
        val nothingExtracted = resolved.title == rawTitle &&
            resolved.dueDate == null &&
            resolved.dueTime == null &&
            resolved.priority == 0 &&
            resolved.projectId == null &&
            resolved.unmatchedProject == null &&
            resolved.tagIds.isEmpty() &&
            resolved.unmatchedTags.isEmpty() &&
            resolved.recurrenceRule == null &&
            resolved.lifeCategory == null &&
            resolved.taskMode == null &&
            resolved.cognitiveLoad == null
        if (nothingExtracted) return null

        val newTagIds = resolved.unmatchedTags.map { tagRepository.addTag(name = it) }
        val effectiveProjectId = resolved.projectId
            ?: resolved.unmatchedProject?.let { projectRepository.addProject(name = it) }
        return NlpEnrichment(
            title = resolved.title,
            dueDate = resolved.dueDate,
            dueTime = resolved.dueTime,
            priority = resolved.priority,
            projectId = effectiveProjectId,
            tagIds = resolved.tagIds + newTagIds,
            recurrenceRule = resolved.recurrenceRule,
            lifeCategory = resolved.lifeCategory,
            taskMode = resolved.taskMode,
            cognitiveLoad = resolved.cognitiveLoad
        )
    }

    /**
     * Merges [enrichment] into the form's manual values. Manual values win
     * on conflict so a user who picked a date in the picker isn't silently
     * overridden by NLP. Title is always replaced with the stripped form
     * (the user can see the field — leaving raw NLP tokens in the title
     * after submit would be the bigger surprise).
     */
    private fun applyNlpEnrichment(enrichment: NlpEnrichment) {
        title = enrichment.title
        if (dueDate == null) dueDate = enrichment.dueDate
        if (dueTime == null) dueTime = enrichment.dueTime
        if (priority == 0 && enrichment.priority != 0) priority = enrichment.priority
        if (projectId == null) projectId = enrichment.projectId
        if (recurrenceRule == null) recurrenceRule = enrichment.recurrenceRule
        if (lifeCategory == null && enrichment.lifeCategory != null) {
            runCatching { LifeCategory.valueOf(enrichment.lifeCategory) }
                .onSuccess { lifeCategory = it }
        }
        if (taskMode == null && enrichment.taskMode != null) {
            runCatching { TaskMode.valueOf(enrichment.taskMode) }
                .onSuccess { taskMode = it }
        }
        if (cognitiveLoad == null && enrichment.cognitiveLoad != null) {
            runCatching { CognitiveLoad.valueOf(enrichment.cognitiveLoad) }
                .onSuccess { cognitiveLoad = it }
        }
        if (enrichment.tagIds.isNotEmpty()) {
            selectedTagIds = selectedTagIds + enrichment.tagIds.toSet()
        }
    }

    /**
     * Appends a new pending subtask with the supplied [title] and returns
     * the generated id. Subtasks are kept in VM state until [saveTask]
     * flushes them to the database.
     */
    fun addPendingSubtask(title: String): Long {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return -1L
        val id = nextPendingSubtaskId++
        pendingSubtasks.add(PendingSubtask(id = id, title = trimmed))
        return id
    }

    /** Toggles the local completion flag on an in-progress subtask. */
    fun togglePendingSubtask(id: Long) {
        val idx = pendingSubtasks.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val current = pendingSubtasks[idx]
            pendingSubtasks[idx] = current.copy(isCompleted = !current.isCompleted)
        }
    }

    /** Removes a pending subtask from the list by id. */
    fun removePendingSubtask(id: Long) {
        pendingSubtasks.removeAll { it.id == id }
    }

    /**
     * Loads the template referenced by [templateId] and pre-fills the
     * in-progress form with its blueprint fields. Fires only in create mode
     * — the editor hides its template button in edit mode so callers don't
     * accidentally stomp an existing task's data. Returns true on success.
     */
    suspend fun applyTemplate(templateId: Long): Boolean {
        return try {
            val template = templateRepository.getTemplateById(templateId)
                ?: run {
                    _errorMessages.emit("Template not found")
                    return false
                }
            title = template.templateTitle ?: template.name
            description = template.templateDescription.orEmpty()
            priority = template.templatePriority ?: priority
            projectId = template.templateProjectId ?: projectId
            recurrenceRule = template.templateRecurrenceJson
                ?.let { RecurrenceConverter.fromJson(it) }
            estimatedDuration = template.templateDuration
            val templateTagIds = TaskTemplateRepository
                .parseTagIds(template.templateTagsJson)
                .toSet()
            if (templateTagIds.isNotEmpty()) {
                selectedTagIds = templateTagIds
            }
            pendingSubtasks.clear()
            TaskTemplateRepository
                .parseSubtaskTitles(template.templateSubtasksJson)
                .forEach { subtaskTitle -> addPendingSubtask(subtaskTitle) }
            titleError = false
            true
        } catch (e: Exception) {
            Log.e("AddEditTaskVM", "Failed to apply template", e)
            _errorMessages.emit("Failed to apply template")
            false
        }
    }

    /**
     * Captures the currently-being-edited task as a reusable template.
     * Only meaningful in edit mode — there is no draft state to persist in
     * create mode, so this returns null without doing anything there.
     */
    suspend fun saveAsTemplate(
        name: String,
        icon: String?,
        category: String?
    ): Long? {
        val taskId = currentTaskId ?: return null
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return null
        return try {
            templateRepository.createTemplateFromTask(
                taskId = taskId,
                name = trimmedName,
                icon = icon?.takeIf { it.isNotBlank() },
                category = category?.trim()?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Log.e("AddEditTaskVM", "Failed to save task as template", e)
            _errorMessages.emit("Failed to save template")
            null
        }
    }

    /**
     * Inline project creation from the Organize tab. Creates the project via the
     * repository and then selects it on the in-progress form. Uses the default
     * project icon so the caller only has to supply name + color.
     */
    fun createAndSelectProject(name: String, color: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val newId = projectRepository.addProject(name = trimmed, color = color)
                projectId = newId
            } catch (e: Exception) {
                Log.e("AddEditTaskVM", "Failed to create project", e)
                _errorMessages.emit("Failed to create project")
            }
        }
    }

    /**
     * Inline tag creation from the Organize tab. Creates the tag via the
     * repository and then adds it to the currently selected tag set so the
     * new tag is immediately assigned to the task.
     */
    fun createAndAssignTag(name: String, color: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val newId = tagRepository.addTag(name = trimmed, color = color)
                selectedTagIds = selectedTagIds + newId
            } catch (e: Exception) {
                Log.e("AddEditTaskVM", "Failed to create tag", e)
                _errorMessages.emit("Failed to create tag")
            }
        }
    }

    fun onAddImageAttachment(context: Context, uri: Uri) {
        val id = currentTaskId ?: return
        viewModelScope.launch {
            try {
                attachmentRepository.addImageAttachment(context, id, uri)
            } catch (e: Exception) {
                Log.e("AddEditTaskVM", "Failed to add image attachment", e)
                _errorMessages.emit("Failed to add attachment")
            }
        }
    }

    fun onAddLinkAttachment(url: String) {
        val id = currentTaskId ?: return
        viewModelScope.launch {
            try {
                attachmentRepository.addLinkAttachment(id, url)
            } catch (e: Exception) {
                Log.e("AddEditTaskVM", "Failed to add link attachment", e)
                _errorMessages.emit("Failed to add attachment")
            }
        }
    }

    fun onDeleteAttachment(context: Context, attachment: AttachmentEntity) {
        viewModelScope.launch {
            try {
                attachmentRepository.deleteAttachment(context, attachment)
            } catch (e: Exception) {
                Log.e("AddEditTaskVM", "Failed to delete attachment", e)
                _errorMessages.emit("Failed to delete attachment")
            }
        }
    }

    /**
     * Adds [blockerTaskId] to this task's blocker set. Edit-mode only —
     * dependency edges FK to a saved `tasks.id`, so the call is rejected
     * with an error message in create mode. Cycle detection runs in the
     * repository; rejection bubbles back as a user-facing snackbar.
     */
    fun addBlocker(blockerTaskId: Long) {
        val blockedTaskId = currentTaskId
        if (blockedTaskId == null) {
            viewModelScope.launch {
                _errorMessages.emit("Save the task first to add blockers")
            }
            return
        }
        if (blockerTaskId == blockedTaskId) {
            viewModelScope.launch {
                _errorMessages.emit("A task can't block itself")
            }
            return
        }
        viewModelScope.launch {
            val result = taskDependencyRepository.addDependency(
                blockerTaskId = blockerTaskId,
                blockedTaskId = blockedTaskId
            )
            result.exceptionOrNull()?.let { err ->
                val message = when (err) {
                    is TaskDependencyRepository.DependencyError.CycleRejected ->
                        "That blocker would close a cycle"
                    else -> "Couldn't add blocker"
                }
                _errorMessages.emit(message)
            }
        }
    }

    /** Removes a single blocker edge by id. No-op if the edge is gone. */
    fun removeBlocker(edge: TaskDependencyEntity) {
        viewModelScope.launch {
            try {
                taskDependencyRepository.removeById(edge.id)
            } catch (e: Exception) {
                Log.e("AddEditTaskVM", "Failed to remove blocker", e)
                _errorMessages.emit("Couldn't remove blocker")
            }
        }
    }

    /**
     * Save the current draft. Callers that want to bypass boundary rules
     * (e.g. the user tapped "Create Anyway" in the block dialog) can pass
     * [ignoreBoundaries] = true.
     */
    suspend fun saveTask(ignoreBoundaries: Boolean = false): Boolean {
        if (title.isBlank()) {
            titleError = true
            return false
        }

        if (!ignoreBoundaries && !evaluateBoundaryRules()) {
            return false
        }

        return try {
            // On the create path only, run the title through the same NLP
            // pipeline Quick Add uses so the form picks up `tomorrow`, `#tag`,
            // `@project`, `!priority`, and recurrence hints typed into the
            // title field. Manual picks always win — NLP fills only fields
            // the user left empty. See
            // docs/audits/NLP_FOR_ALL_TASK_ADDITIONS_AUDIT.md for the design.
            val existing = existingTask
            if (existing == null) {
                enrichWithNlp(title.trim())?.let { applyNlpEnrichment(it) }
            }

            val trimmedTitle = title.trim()
            val trimmedDesc = description.trim().ifEmpty { null }
            val trimmedNotes = notes.trim().ifEmpty { null }
            val recurrenceJson = recurrenceRule?.let { RecurrenceConverter.toJson(it) }
            val resolvedLifeCategory = resolveLifeCategoryForSave()
            val resolvedTaskMode = resolveTaskModeForSave()
            val resolvedCognitiveLoad = resolveCognitiveLoadForSave()
            val savedId: Long
            if (existing != null) {
                taskRepository.updateTask(
                    existing.copy(
                        title = trimmedTitle,
                        description = trimmedDesc,
                        dueDate = dueDate,
                        dueTime = dueTime,
                        priority = priority,
                        projectId = projectId,
                        parentTaskId = parentTaskId,
                        reminderOffset = reminderOffset,
                        recurrenceRule = recurrenceJson,
                        estimatedDuration = estimatedDuration,
                        notes = trimmedNotes,
                        lifeCategory = resolvedLifeCategory,
                        taskMode = resolvedTaskMode,
                        cognitiveLoad = resolvedCognitiveLoad
                    )
                )
                savedId = existing.id
            } else {
                savedId = taskRepository.addTask(
                    title = trimmedTitle,
                    description = trimmedDesc,
                    dueDate = dueDate,
                    dueTime = dueTime,
                    priority = priority,
                    projectId = projectId,
                    parentTaskId = parentTaskId,
                    lifeCategory = resolvedLifeCategory,
                    taskMode = resolvedTaskMode,
                    cognitiveLoad = resolvedCognitiveLoad,
                    reminderOffset = reminderOffset,
                    recurrenceRule = recurrenceJson,
                    estimatedDuration = estimatedDuration
                )
            }

            // Save tags
            tagRepository.setTagsForTask(savedId, selectedTagIds.toList())

            // Flush any pending subtasks (e.g. from a template) into real
            // rows. Each subtask title also runs through the NLP pipeline so
            // typing `call vendor !2 #work` as a subtask picks up the same
            // priority + tags it would in Quick Add. Subtasks inherit the
            // parent's project implicitly via parentTaskId so we don't carry
            // an NLP-resolved projectId for them.
            if (pendingSubtasks.isNotEmpty()) {
                val now = System.currentTimeMillis()
                pendingSubtasks.forEachIndexed { index, sub ->
                    val subEnrichment = enrichWithNlp(sub.title)
                    val subtask = TaskEntity(
                        title = subEnrichment?.title ?: sub.title,
                        parentTaskId = savedId,
                        isCompleted = sub.isCompleted,
                        completedAt = if (sub.isCompleted) now else null,
                        sortOrder = index,
                        dueDate = subEnrichment?.dueDate,
                        dueTime = subEnrichment?.dueTime,
                        priority = subEnrichment?.priority ?: 0,
                        lifeCategory = subEnrichment?.lifeCategory,
                        taskMode = subEnrichment?.taskMode,
                        cognitiveLoad = subEnrichment?.cognitiveLoad,
                        createdAt = now,
                        updatedAt = now
                    )
                    val subId = taskRepository.insertTask(subtask)
                    val subTagIds = subEnrichment?.tagIds.orEmpty()
                    if (subTagIds.isNotEmpty()) {
                        tagRepository.setTagsForTask(subId, subTagIds)
                    }
                }
                pendingSubtasks.clear()
            }

            true
        } catch (e: Exception) {
            Log.e("AddEditTaskVM", "Failed to save task", e)
            _errorMessages.emit("Couldn't save task")
            false
        }
    }

    suspend fun deleteTask() {
        try {
            currentTaskId?.let { taskRepository.deleteTask(it) }
        } catch (e: Exception) {
            Log.e("AddEditTaskVM", "Failed to delete task", e)
            _errorMessages.emit("Couldn't delete task")
        }
    }

    /**
     * Duplicates the task currently open in the editor. On success, re-seeds
     * the form with the new copy so the sheet immediately shows the
     * duplicated task without the host having to dismiss-and-reopen. Returns
     * the id of the new task, or null if duplication failed (e.g. we were in
     * create mode or the original had already been deleted).
     */
    suspend fun duplicateCurrentTask(
        includeSubtasks: Boolean,
        copyDueDate: Boolean = false
    ): Long? {
        val id = currentTaskId ?: return null
        return try {
            val newId = taskRepository.duplicateTask(id, includeSubtasks, copyDueDate)
            if (newId <= 0L) {
                _errorMessages.emit("Couldn't duplicate task")
                null
            } else {
                // Reseed the form from the new copy. projectId / initialDate
                // are not needed since initialize() will load them from the
                // new task's persisted state.
                initialize(taskId = newId, projectId = null, initialDate = null)
                newId
            }
        } catch (e: Exception) {
            Log.e("AddEditTaskVM", "Failed to duplicate task", e)
            _errorMessages.emit("Couldn't duplicate task")
            null
        }
    }
}
