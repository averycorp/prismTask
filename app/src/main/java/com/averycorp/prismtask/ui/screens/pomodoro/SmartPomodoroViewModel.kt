package com.averycorp.prismtask.ui.screens.pomodoro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.TaskTimingEntity
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.data.remote.api.PomodoroRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.repository.MoodEnergyRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.data.repository.TaskTimingRepository
import com.averycorp.prismtask.domain.usecase.DefaultPomodoroConfig
import com.averycorp.prismtask.domain.usecase.EnergyAwarePomodoro
import com.averycorp.prismtask.domain.usecase.PomodoroAICoach
import com.averycorp.prismtask.domain.usecase.PomodoroSessionConfig
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.notifications.PomodoroTimerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class PomodoroState {
    PLANNING,
    SESSION_ACTIVE,
    ON_BREAK,
    COMPLETE
}

/**
 * Sealed UI state for the "plan my sessions" action. Orthogonal to the
 * [PomodoroState] session-flow machine (which tracks PLANNING / ACTIVE /
 * BREAK / COMPLETE); this one tracks what happened to the API call that
 * feeds the plan into that machine.
 *
 * Pre-sealed-state, we used parallel `_isLoading` / `_error` flags and
 * `_plan` could be a PomodoroPlan with zero sessions — which silently
 * rendered "0 sessions • 0 min work • 0 min breaks". [Empty] closes that
 * hole with a proper screen-level empty message.
 */
sealed interface PomodoroPlanUiState {
    data object Idle : PomodoroPlanUiState
    data object Loading : PomodoroPlanUiState
    data class Success(val plan: PomodoroPlan) : PomodoroPlanUiState
    data class Empty(val reason: String) : PomodoroPlanUiState
    data class Error(val message: String) : PomodoroPlanUiState
}

data class PomodoroConfig(
    val availableMinutes: Int = 120,
    val sessionLength: Int = 25,
    val breakLength: Int = 5,
    val longBreakLength: Int = 15,
    val focusPreference: String = "balanced"
)

data class SessionTask(
    val taskId: Long,
    val title: String,
    val allocatedMinutes: Int
)

data class PomodoroSession(
    val sessionNumber: Int,
    val tasks: List<SessionTask>,
    val rationale: String
)

data class SkippedTask(
    val taskId: Long,
    val reason: String
)

data class PomodoroPlan(
    val sessions: List<PomodoroSession>,
    val totalWorkMinutes: Int,
    val totalBreakMinutes: Int,
    val skippedTasks: List<SkippedTask>
)

data class FocusStats(
    val sessionsCompleted: Int = 0,
    val tasksCompleted: Int = 0,
    val totalFocusSeconds: Int = 0
)

/**
 * A2 Pomodoro+ AI coaching — UI state for the pre-session coaching modal.
 *
 *  - [Hidden]     : no modal on screen (default).
 *  - [Loading]    : RPC in flight; UI shows a spinner inside the modal. Timer
 *                   start is gated behind this by at most ~2s via a timeout
 *                   in [SmartPomodoroViewModel.requestPreSessionCoaching].
 *  - [Ready]      : Haiku replied, user can accept or dismiss.
 */
sealed interface PreSessionCoachingUiState {
    data object Hidden : PreSessionCoachingUiState
    data object Loading : PreSessionCoachingUiState
    data class Ready(val message: String) : PreSessionCoachingUiState
}

/**
 * Break-time AI suggestion. Null when there's nothing to show (coaching
 * disabled, API unreachable, or not on a break). Rendered as an in-screen
 * card on the [BreakView] — no blocking modal because the break timer is
 * already running.
 */
data class BreakSuggestion(val message: String)

/**
 * Post-session recap shown on the [PomodoroState.COMPLETE] screen. Null when
 * coaching disabled or RPC failed.
 */
data class SessionRecap(val message: String)

/**
 * Static fallback break suggestions, used when Haiku returns an empty or
 * duplicate message. Kept small on purpose — the AI path is the primary,
 * this is just a "don't show nothing" guard.
 */
private val BREAK_FALLBACKS = listOf(
    "Stand up and roll your shoulders back a few times.",
    "Drink a glass of water — eye-rest every 20 seconds while you do.",
    "Look at something 20 feet away for 20 seconds (20-20-20).",
    "Take a 2-minute walk away from the screen.",
    "Slow box-breathing: in 4, hold 4, out 4, hold 4."
)

@HiltViewModel
class SmartPomodoroViewModel
@Inject
constructor(
    @ApplicationContext private val appContext: Context,
    private val taskDao: TaskDao,
    private val taskRepository: TaskRepository,
    private val api: PrismTaskApi,
    private val proFeatureGate: ProFeatureGate,
    private val moodEnergyRepository: MoodEnergyRepository,
    private val timerPreferences: TimerPreferences,
    private val aiCoach: PomodoroAICoach,
    private val taskTimingRepository: TaskTimingRepository
) : ViewModel() {
    private val energyAwarePomodoro = EnergyAwarePomodoro()

    private val _energyAwareConfig = MutableStateFlow<PomodoroSessionConfig?>(null)
    val energyAwareConfig: StateFlow<PomodoroSessionConfig?> = _energyAwareConfig

    /**
     * True when a Pomodoro session has just completed and we should prompt
     * the user for a quick energy self-report. v1.4.0 V11: this passively
     * builds an energy-by-hour profile without requiring an explicit
     * check-in.
     */
    private val _showPostSessionEnergyPrompt = MutableStateFlow(false)
    val showPostSessionEnergyPrompt: StateFlow<Boolean> = _showPostSessionEnergyPrompt

    fun dismissPostSessionEnergyPrompt() {
        _showPostSessionEnergyPrompt.value = false
    }

    fun logPostSessionEnergy(energy: Int) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            moodEnergyRepository.upsertForDate(
                date = now - (now % (24L * 60 * 60 * 1000)),
                mood = 3,
                energy = energy,
                notes = "post-pomodoro",
                timeOfDay = "afternoon"
            )
            _showPostSessionEnergyPrompt.value = false
        }
    }

    val userTier: StateFlow<UserTier> = proFeatureGate.userTier

    private val _screenState = MutableStateFlow(PomodoroState.PLANNING)
    val screenState: StateFlow<PomodoroState> = _screenState

    // Pomodoro config is now sourced entirely from persisted user settings.
    val config: StateFlow<PomodoroConfig> = combine(
        timerPreferences.getPomodoroAvailableMinutes(),
        timerPreferences.getWorkDurationSeconds(),
        timerPreferences.getBreakDurationSeconds(),
        timerPreferences.getLongBreakDurationSeconds(),
        timerPreferences.getPomodoroFocusPreference()
    ) { available, workSec, breakSec, longBreakSec, focus ->
        PomodoroConfig(
            availableMinutes = available,
            sessionLength = workSec / 60,
            breakLength = breakSec / 60,
            longBreakLength = longBreakSec / 60,
            focusPreference = focus
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PomodoroConfig())

    private val _planUiState = MutableStateFlow<PomodoroPlanUiState>(PomodoroPlanUiState.Idle)
    val planUiState: StateFlow<PomodoroPlanUiState> = _planUiState

    // Back-compat derived views so the active-session / completion
    // rendering can keep reading "the current plan" and "loading in
    // flight" without knowing about the sealed type. These are
    // computed, not independently mutated.
    // Eagerly started so non-UI code paths (requestPreSessionCoaching,
    // requestSessionRecap, nextSession) that read plan.value synchronously
    // see the populated plan even before a composable subscribes.
    val plan: StateFlow<PomodoroPlan?> = _planUiState
        .map { (it as? PomodoroPlanUiState.Success)?.plan }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isLoading: StateFlow<Boolean> = _planUiState
        .map { it is PomodoroPlanUiState.Loading }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _currentSessionIndex = MutableStateFlow(0)
    val currentSessionIndex: StateFlow<Int> = _currentSessionIndex

    private val _timerSecondsRemaining = MutableStateFlow(0)
    val timerSecondsRemaining: StateFlow<Int> = _timerSecondsRemaining

    private val _isTimerRunning = MutableStateFlow(false)
    val isTimerRunning: StateFlow<Boolean> = _isTimerRunning

    private val _completedTaskIds = MutableStateFlow<Set<Long>>(emptySet())
    val completedTaskIds: StateFlow<Set<Long>> = _completedTaskIds

    private val _stats = MutableStateFlow(FocusStats())
    val stats: StateFlow<FocusStats> = _stats

    private val _incompleteTaskCount = MutableStateFlow(0)
    val incompleteTaskCount: StateFlow<Int> = _incompleteTaskCount

    /**
     * Listens for tick + completion broadcasts emitted by
     * [PomodoroTimerService]. The service runs independently of the app
     * process lifecycle so this is how we pipe the countdown back into the
     * UI while the user is in the app.
     */
    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            // Filter on owner so a TimerViewModel session running on the same
            // PomodoroTimerService instance doesn't bleed its ticks into our
            // smart-Pomodoro flow.
            val incomingOwner = intent.getStringExtra(PomodoroTimerService.EXTRA_OWNER)
                ?: PomodoroTimerService.OWNER_SMART_POMODORO
            if (incomingOwner != PomodoroTimerService.OWNER_SMART_POMODORO) return
            when (intent.action) {
                PomodoroTimerService.ACTION_TICK -> {
                    val seconds = intent.getIntExtra(
                        PomodoroTimerService.EXTRA_SECONDS_REMAINING,
                        -1
                    )
                    if (seconds >= 0) {
                        _timerSecondsRemaining.value = seconds
                    }
                }
                PomodoroTimerService.ACTION_STOPPED -> _isTimerRunning.value = false
                PomodoroTimerService.ACTION_COMPLETE -> onTimerComplete()
            }
        }
    }

    private var receiverRegistered = false

    init {
        viewModelScope.launch {
            taskDao.getIncompleteRootTasks().collect { tasks ->
                _incompleteTaskCount.value = tasks.size
            }
        }
        // v1.4.0 V11: on first load, look at today's mood/energy logs and
        // pre-fill the planner's session/break lengths with an energy-aware
        // config. Users who haven't opted into mood tracking get the
        // classic 25/5 defaults so the feature is invisible to them.
        viewModelScope.launch {
            val todayStart = System.currentTimeMillis() - 12L * 60 * 60 * 1000
            val logs = moodEnergyRepository.getRange(todayStart, System.currentTimeMillis())
            val current = config.value
            val planned = energyAwarePomodoro.planFromLogs(
                logs,
                DefaultPomodoroConfig(
                    workMinutes = current.sessionLength,
                    breakMinutes = current.breakLength,
                    longBreakMinutes = current.longBreakLength
                )
            )
            _energyAwareConfig.value = planned
        }
        registerTimerReceiver()
    }

    private fun registerTimerReceiver() {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(PomodoroTimerService.ACTION_TICK)
                addAction(PomodoroTimerService.ACTION_STOPPED)
                addAction(PomodoroTimerService.ACTION_COMPLETE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(timerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(timerReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
            // Test contexts and edge cases (e.g. early in Application lifecycle)
            // may reject receiver registration; the service still delivers
            // the completion notification via the system notification manager
            // so UI sync is best-effort.
        }
    }

    private fun unregisterTimerReceiver() {
        if (!receiverRegistered) return
        try {
            appContext.unregisterReceiver(timerReceiver)
        } catch (_: Exception) {
            // Already unregistered or context invalid.
        }
        receiverRegistered = false
    }

    private val _showUpgradePrompt = MutableStateFlow(false)
    val showUpgradePrompt: StateFlow<Boolean> = _showUpgradePrompt

    fun dismissUpgradePrompt() {
        _showUpgradePrompt.value = false
    }

    // ----- A2 Pomodoro+ AI Coaching -----

    private val _preSessionCoaching = MutableStateFlow<PreSessionCoachingUiState>(
        PreSessionCoachingUiState.Hidden
    )
    val preSessionCoaching: StateFlow<PreSessionCoachingUiState> = _preSessionCoaching

    /**
     * Upper bound for blocking the timer start on Haiku. Per spec: if the API
     * is slow, we surface the modal on what we have (or skip it) rather than
     * keep the user waiting past ~2s.
     */
    private val preSessionCoachingTimeoutMs = 2_000L

    /**
     * User accepts the coaching suggestion — closes the modal and kicks off
     * the actual timer for the current session index.
     */
    fun acceptPreSessionCoaching() {
        _preSessionCoaching.value = PreSessionCoachingUiState.Hidden
        beginCurrentSessionTimer()
    }

    /**
     * User dismisses the coaching suggestion — closes the modal and starts
     * the timer anyway. Same terminal behavior as accept; the distinction is
     * intentional for a future "remind me to do it" variant.
     */
    fun dismissPreSessionCoaching() {
        _preSessionCoaching.value = PreSessionCoachingUiState.Hidden
        beginCurrentSessionTimer()
    }

    // ----- Break-time suggestion -----

    private val _breakSuggestion = MutableStateFlow<BreakSuggestion?>(null)
    val breakSuggestion: StateFlow<BreakSuggestion?> = _breakSuggestion

    /**
     * Recent suggestions sent up to Haiku so it varies its answers. We keep
     * the last 3 — enough to keep rotation going, small enough to not bloat
     * the prompt.
     */
    private val recentBreakSuggestions: ArrayDeque<String> = ArrayDeque()
    private val recentBreakSuggestionsMax = 3

    fun dismissBreakSuggestion() {
        _breakSuggestion.value = null
    }

    private fun requestBreakSuggestion(elapsedMinutes: Int, isLongBreak: Boolean) {
        viewModelScope.launch {
            val enabled = timerPreferences
                .getPomodoroBreakCoachingEnabled()
                .first()
            if (!enabled) {
                _breakSuggestion.value = null
                return@launch
            }
            val kind = if (isLongBreak) {
                PomodoroAICoach.BREAK_TYPE_LONG
            } else {
                PomodoroAICoach.BREAK_TYPE_SHORT
            }
            val recent = recentBreakSuggestions.toList()
            val result = aiCoach.suggestBreakActivity(
                elapsedMinutes = elapsedMinutes,
                breakType = kind,
                recentSuggestions = recent
            ).getOrNull()
            val candidate = result?.trim()
            // Duplicate guard: if Haiku echoed something we just showed, swap
            // in a canned fallback instead so the break surface stays useful.
            val finalMessage = when {
                candidate.isNullOrEmpty() -> pickFallbackBreak(recent)
                candidate in recent -> pickFallbackBreak(recent + candidate)
                else -> candidate
            }
            if (finalMessage != null) {
                rememberBreakSuggestion(finalMessage)
                _breakSuggestion.value = BreakSuggestion(finalMessage)
            } else {
                // Nothing sensible to show and no unused fallback — keep
                // the break surface clean rather than repeating ourselves.
                _breakSuggestion.value = null
            }
        }
    }

    private fun pickFallbackBreak(excluded: List<String>): String? =
        BREAK_FALLBACKS.firstOrNull { it !in excluded } ?: BREAK_FALLBACKS.firstOrNull()

    private fun rememberBreakSuggestion(message: String) {
        recentBreakSuggestions.addLast(message)
        while (recentBreakSuggestions.size > recentBreakSuggestionsMax) {
            recentBreakSuggestions.removeFirst()
        }
    }

    // ----- Session recap -----

    private val _sessionRecap = MutableStateFlow<SessionRecap?>(null)
    val sessionRecap: StateFlow<SessionRecap?> = _sessionRecap

    fun dismissSessionRecap() {
        _sessionRecap.value = null
    }

    private fun requestSessionRecap() {
        viewModelScope.launch {
            val enabled = timerPreferences
                .getPomodoroRecapCoachingEnabled()
                .first()
            if (!enabled) {
                _sessionRecap.value = null
                return@launch
            }
            val currentPlan = plan.value ?: return@launch
            val completedIds = _completedTaskIds.value
            // Every task from every completed session in this plan; partition
            // into "done" and "started-but-unfinished".
            val sessionsPlayed = (_currentSessionIndex.value + 1)
                .coerceAtMost(currentPlan.sessions.size)
            val touchedTasks = currentPlan.sessions
                .take(sessionsPlayed)
                .flatMap { it.tasks }
                .distinctBy { it.taskId }
            val completed = touchedTasks.filter { it.taskId in completedIds }
            val started = touchedTasks.filter { it.taskId !in completedIds }
            val totalMinutes = _stats.value.totalFocusSeconds / 60
            val msg = aiCoach.recapSession(
                completed = completed,
                started = started,
                sessionDurationMinutes = totalMinutes
            ).getOrNull()?.trim()
            if (!msg.isNullOrEmpty()) {
                _sessionRecap.value = SessionRecap(msg)
            } else {
                _sessionRecap.value = null
            }
        }
    }

    fun generatePlan() {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO)) {
            _showUpgradePrompt.value = true
            return
        }
        viewModelScope.launch {
            _planUiState.value = PomodoroPlanUiState.Loading
            try {
                val cfg = config.value
                val response = api.planPomodoro(
                    PomodoroRequest(
                        availableMinutes = cfg.availableMinutes,
                        sessionLength = cfg.sessionLength,
                        breakLength = cfg.breakLength,
                        longBreakLength = cfg.longBreakLength,
                        focusPreference = cfg.focusPreference
                    )
                )
                if (response.sessions.isEmpty()) {
                    // Don't render the "0 sessions • 0 min" summary card;
                    // show an explicit empty state so the user can take
                    // action (add a task, re-sync, re-plan).
                    _planUiState.value = PomodoroPlanUiState.Empty(
                        "No tasks to plan around. Add a task or check that your tasks are synced."
                    )
                    return@launch
                }
                // Resolve Firestore doc IDs returned by the backend to local
                // Long task ids. Tasks not present locally (e.g. created on
                // another device and not yet synced down) get demoted into
                // the skipped list so the rest of the plan still renders.
                val unresolved = mutableListOf<SkippedTask>()
                val sessions = response.sessions.map { s ->
                    val resolved = s.tasks.mapNotNull { t ->
                        val localId = taskDao.getIdByCloudId(t.taskId)
                        if (localId == null) {
                            unresolved += SkippedTask(0L, "${t.title}: not synced to this device")
                            null
                        } else {
                            SessionTask(localId, t.title, t.allocatedMinutes)
                        }
                    }
                    PomodoroSession(
                        sessionNumber = s.sessionNumber,
                        tasks = resolved,
                        rationale = s.rationale
                    )
                }
                val skipped = response.skippedTasks.mapNotNull {
                    val localId = taskDao.getIdByCloudId(it.taskId) ?: return@mapNotNull null
                    SkippedTask(localId, it.reason)
                } + unresolved
                val built = PomodoroPlan(
                    sessions = sessions,
                    totalWorkMinutes = response.totalWorkMinutes,
                    totalBreakMinutes = response.totalBreakMinutes,
                    skippedTasks = skipped
                )
                _planUiState.value = PomodoroPlanUiState.Success(built)
            } catch (e: Exception) {
                Log.w("PomodoroVM", "Plan generation failed", e)
                _planUiState.value = PomodoroPlanUiState.Error(
                    e.message ?: "Couldn't generate plan"
                )
            }
        }
    }

    fun startSession() {
        _screenState.value = PomodoroState.SESSION_ACTIVE
        _currentSessionIndex.value = 0
        // Pre-session coaching runs in parallel; if it fails or is disabled,
        // the timer starts immediately via beginCurrentSessionTimer().
        requestPreSessionCoaching()
    }

    /**
     * If pre-session coaching is enabled, fire the Haiku call. Surface the
     * modal on success; silently skip (and start the timer) on failure or
     * if the user disabled the feature. The timer is gated on the modal
     * for at most [preSessionCoachingTimeoutMs] — after that we just start.
     */
    private fun requestPreSessionCoaching() {
        viewModelScope.launch {
            val enabled = timerPreferences
                .getPomodoroPreSessionCoachingEnabled()
                .first()
            val upcoming = plan.value
                ?.sessions
                ?.getOrNull(_currentSessionIndex.value)
                ?.tasks
                .orEmpty()
            if (!enabled || upcoming.isEmpty()) {
                beginCurrentSessionTimer()
                return@launch
            }
            _preSessionCoaching.value = PreSessionCoachingUiState.Loading
            val sessionLen = config.value.sessionLength
            val result = withTimeoutOrNull(preSessionCoachingTimeoutMs) {
                aiCoach.suggestPreSession(upcoming, sessionLen)
            }
            val msg = result?.getOrNull()
            if (msg != null) {
                _preSessionCoaching.value = PreSessionCoachingUiState.Ready(msg)
                // Modal is now onscreen; timer starts when the user taps
                // accept / dismiss via acceptPreSessionCoaching() /
                // dismissPreSessionCoaching().
            } else {
                // Silent skip: no network, timeout, disabled key, etc.
                _preSessionCoaching.value = PreSessionCoachingUiState.Hidden
                beginCurrentSessionTimer()
            }
        }
    }

    private fun beginCurrentSessionTimer() {
        val durationSeconds = config.value.sessionLength * 60
        // Visible debug output for field troubleshooting. Guarded because
        // Android framework stubs (Log, Toast) throw RuntimeException in
        // plain JVM unit tests that don't pull in Robolectric.
        try {
            Log.d("PomodoroVM", "beginCurrentSessionTimer: duration=${durationSeconds}s, config=${config.value}")
            Toast.makeText(appContext, "Starting timer: ${durationSeconds}s", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // No-op: unit-test environment without Android framework.
        }
        _timerSecondsRemaining.value = durationSeconds
        startTimer(durationSeconds, PomodoroTimerService.SESSION_TYPE_WORK)
    }

    fun pauseTimer() {
        _isTimerRunning.value = false
        // Stopping the service dismisses its ongoing notification; the
        // remaining seconds are preserved in _timerSecondsRemaining so
        // resumeTimer() can pick up where we left off.
        PomodoroTimerService.stop(appContext)
    }

    fun resumeTimer() {
        val remaining = _timerSecondsRemaining.value
        if (remaining <= 0) return
        val sessionType = if (_screenState.value == PomodoroState.ON_BREAK) {
            val isLongBreak = (_currentSessionIndex.value + 1) % 4 == 0
            if (isLongBreak) {
                PomodoroTimerService.SESSION_TYPE_LONG_BREAK
            } else {
                PomodoroTimerService.SESSION_TYPE_BREAK
            }
        } else {
            PomodoroTimerService.SESSION_TYPE_WORK
        }
        startTimer(remaining, sessionType)
    }

    // Routes through TaskRepository so recurring tasks spawn their next
    // occurrence, the active reminder is cancelled, and the completion is
    // mirrored to the sync tracker / calendar push / widgets — same path
    // every other complete entry point uses. Audit:
    // docs/audits/RECURRING_TASKS_DUPLICATE_DAILY_AUDIT.md (Item 3).
    fun completeTask(taskId: Long) {
        viewModelScope.launch {
            taskRepository.completeTask(taskId)
            _completedTaskIds.value = _completedTaskIds.value + taskId
            _stats.value = _stats.value.copy(tasksCompleted = _stats.value.tasksCompleted + 1)
        }
    }

    fun skipTask(taskId: Long) {
        // Just visual — doesn't affect task state
    }

    fun endEarly() {
        PomodoroTimerService.stop(appContext)
        _isTimerRunning.value = false
        _screenState.value = PomodoroState.COMPLETE
        val totalSeconds = config.value.sessionLength * 60 * (_currentSessionIndex.value + 1) -
            _timerSecondsRemaining.value
        _stats.value = _stats.value.copy(
            sessionsCompleted = _currentSessionIndex.value + 1,
            totalFocusSeconds = totalSeconds
        )
        requestSessionRecap()
    }

    fun nextSession() {
        val plan = plan.value ?: return
        val nextIndex = _currentSessionIndex.value + 1
        // Transitioning off the break — stale break card must go.
        _breakSuggestion.value = null
        if (nextIndex >= plan.sessions.size) {
            // All done
            _screenState.value = PomodoroState.COMPLETE
            _stats.value = _stats.value.copy(
                sessionsCompleted = plan.sessions.size,
                totalFocusSeconds = plan.totalWorkMinutes * 60
            )
            requestSessionRecap()
            return
        }
        _currentSessionIndex.value = nextIndex
        _screenState.value = PomodoroState.SESSION_ACTIVE
        // Re-run the pre-session coaching surface for the new session so the
        // modal fires on every work block, not just the first one. Falls
        // through to beginCurrentSessionTimer() when disabled / RPC fails.
        requestPreSessionCoaching()
    }

    fun resetToPlanning() {
        PomodoroTimerService.stop(appContext)
        _screenState.value = PomodoroState.PLANNING
        _planUiState.value = PomodoroPlanUiState.Idle
        _currentSessionIndex.value = 0
        _completedTaskIds.value = emptySet()
        _stats.value = FocusStats()
        _isTimerRunning.value = false
        _breakSuggestion.value = null
        _sessionRecap.value = null
        _preSessionCoaching.value = PreSessionCoachingUiState.Hidden
    }

    /**
     * Clear a transient Error/Empty message (snackbar auto-dismiss + banner
     * dismiss). Idempotent for Idle/Loading/Success.
     */
    fun dismissPlanUiMessage() {
        when (_planUiState.value) {
            is PomodoroPlanUiState.Error, is PomodoroPlanUiState.Empty -> {
                _planUiState.value = PomodoroPlanUiState.Idle
            }
            else -> Unit
        }
    }

    private fun startTimer(durationSeconds: Int, sessionType: String) {
        if (durationSeconds <= 0) return
        _isTimerRunning.value = true
        PomodoroTimerService.start(
            context = appContext,
            durationSeconds = durationSeconds,
            sessionIndex = _currentSessionIndex.value,
            sessionType = sessionType,
            owner = PomodoroTimerService.OWNER_SMART_POMODORO
        )
    }

    private fun onTimerComplete() {
        _isTimerRunning.value = false
        val plan = plan.value ?: return
        val sessionIndex = _currentSessionIndex.value
        _stats.value = _stats.value.copy(
            sessionsCompleted = sessionIndex + 1,
            totalFocusSeconds = _stats.value.totalFocusSeconds + config.value.sessionLength * 60
        )
        autoLogPomodoroSessionTime(plan, sessionIndex)

        // v1.4.0 V11: trigger the post-session energy prompt so the
        // planner can learn what hours are actually productive.
        _showPostSessionEnergyPrompt.value = true

        // Check if there are more sessions
        if (sessionIndex + 1 >= plan.sessions.size) {
            _screenState.value = PomodoroState.COMPLETE
            requestSessionRecap()
        } else {
            // Start break
            _screenState.value = PomodoroState.ON_BREAK
            val isLongBreak = (sessionIndex + 1) % 4 == 0
            val durationSeconds =
                if (isLongBreak) {
                    config.value.longBreakLength * 60
                } else {
                    config.value.breakLength * 60
                }
            _timerSecondsRemaining.value = durationSeconds
            val sessionType =
                if (isLongBreak) {
                    PomodoroTimerService.SESSION_TYPE_LONG_BREAK
                } else {
                    PomodoroTimerService.SESSION_TYPE_BREAK
                }
            startTimer(durationSeconds, sessionType)
            // Fire the break suggestion in parallel with the break timer.
            // Elapsed = minutes of work accumulated so far in this plan.
            val elapsedMinutes = (sessionIndex + 1) * config.value.sessionLength
            requestBreakSuggestion(elapsedMinutes, isLongBreak)
        }
    }

    override fun onCleared() {
        super.onCleared()
        unregisterTimerReceiver()
        PomodoroTimerService.stop(appContext)
    }

    /**
     * P2-D of the analytics C4/C5 time-tracking work
     * (`docs/audits/ANALYTICS_C4_C5_TIME_TRACKING_DESIGN.md`, Path 2). When a
     * focus session completes, write a `TaskTimingEntity` per session task so
     * the analytics time-tracking chart picks up Pomodoro work without the
     * user having to log it manually from the Schedule tab.
     *
     * Allocation strategy: each task gets credited its planned
     * `allocatedMinutes`. If the sum diverges from the actual session length
     * (e.g. config drift between planning and execution), the per-task
     * allocations are still the user-visible plan and are what users expect
     * to see in analytics. Logging failures are swallowed — the session UX
     * should not surface a "couldn't log time" error after a successful
     * focus block.
     *
     * Marked `internal` so unit tests can drive the auto-log without needing
     * to fire a real timer-complete broadcast.
     */
    @androidx.annotation.VisibleForTesting
    internal fun autoLogPomodoroSessionTime(plan: PomodoroPlan, sessionIndex: Int) {
        val session = plan.sessions.getOrNull(sessionIndex) ?: return
        if (session.tasks.isEmpty()) return
        viewModelScope.launch {
            session.tasks.forEach { task ->
                if (task.allocatedMinutes <= 0) return@forEach
                try {
                    taskTimingRepository.logTime(
                        taskId = task.taskId,
                        durationMinutes = task.allocatedMinutes,
                        source = TaskTimingEntity.SOURCE_POMODORO
                    )
                } catch (e: Exception) {
                    Log.w("SmartPomodoroVM", "auto-log pomodoro time failed", e)
                }
            }
        }
    }
}
