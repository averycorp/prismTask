package com.averycorp.prismtask.domain.rating

import com.averycorp.prismtask.data.preferences.OnboardingPreferences
import com.averycorp.prismtask.data.preferences.RatingPromptPreferences
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized trigger heuristics for the in-app rating surfaces (E2). One
 * helper feeds both the Google Play in-app review API and the custom
 * "how's it going?" prompt; the gate logic + suppression rules live here
 * so the call site (MainActivity) is just an event collector.
 *
 * See `docs/audits/E2_IN_APP_RATINGS_AUDIT.md` § Item 3 for the full gate
 * order. Thresholds are build constants (no remote config).
 */
@Singleton
class RatingPromptTriggerHelper @Inject constructor(
    private val prefs: RatingPromptPreferences,
    private val crashSignal: RecentCrashSignal,
    private val onboardingPreferences: OnboardingPreferences,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile private var customPromptShownThisSession: Boolean = false
    @Volatile private var playReviewShownThisSession: Boolean = false

    /**
     * Bumps the session counter and stamps the install date if absent.
     * Call once from `MainActivity.onCreate` per cold start.
     */
    suspend fun onAppStart() {
        prefs.setFirstLaunchAtIfAbsent(clock())
        prefs.incrementSessionCount()
    }

    /**
     * Bumps the task-completion counter and returns the prompt decision.
     * Call from the `AutomationEventBus.TaskCompleted` collector in
     * MainActivity. Caller is responsible for invoking the correct
     * `record*Shown` after surfacing the prompt.
     */
    suspend fun onTaskCompleted(): RatingPromptDecision {
        prefs.incrementTasksCompletedCount()
        return evaluate()
    }

    suspend fun recordPlayReviewShown() {
        playReviewShownThisSession = true
        prefs.setLastPlayReviewShownAt(clock())
    }

    suspend fun recordCustomPromptShown() {
        customPromptShownThisSession = true
        prefs.setLastCustomPromptShownAt(clock())
    }

    private suspend fun evaluate(): RatingPromptDecision {
        if (!onboardingPreferences.hasCompletedOnboarding().first()) return RatingPromptDecision.None
        val sessions = prefs.sessionCount().first()
        if (sessions <= MIN_SESSIONS_BEFORE_PROMPT) return RatingPromptDecision.None
        if (crashSignal.hadRecentCrash(RECENT_CRASH_WINDOW_MS)) return RatingPromptDecision.None
        if (customPromptShownThisSession || playReviewShownThisSession) return RatingPromptDecision.None

        val now = clock()
        val firstLaunch = prefs.firstLaunchAt().first()
        val daysSinceFirstLaunch = if (firstLaunch == 0L) 0L else (now - firstLaunch) / DAY_MS
        val tasksCompleted = prefs.tasksCompletedCount().first()
        val lastPlay = prefs.lastPlayReviewShownAt().first()
        val lastCustom = prefs.lastCustomPromptShownAt().first()

        if (tasksCompleted >= N_PLAY &&
            daysSinceFirstLaunch >= M_PLAY_DAYS &&
            (now - lastPlay) >= PLAY_COOLDOWN_MS
        ) {
            return RatingPromptDecision.PlayReview
        }

        if (tasksCompleted >= N_CUSTOM &&
            daysSinceFirstLaunch >= M_CUSTOM_DAYS &&
            (now - lastCustom) >= CUSTOM_COOLDOWN_MS
        ) {
            return RatingPromptDecision.CustomPrompt
        }

        return RatingPromptDecision.None
    }

    companion object {
        const val N_PLAY: Long = 10
        const val M_PLAY_DAYS: Long = 7
        const val N_CUSTOM: Long = 5
        const val M_CUSTOM_DAYS: Long = 3
        const val MIN_SESSIONS_BEFORE_PROMPT: Long = 3

        val DAY_MS: Long = TimeUnit.DAYS.toMillis(1)
        val PLAY_COOLDOWN_MS: Long = TimeUnit.DAYS.toMillis(90)
        val CUSTOM_COOLDOWN_MS: Long = TimeUnit.DAYS.toMillis(30)
        val RECENT_CRASH_WINDOW_MS: Long = TimeUnit.HOURS.toMillis(24)
    }
}
