package com.averycorp.prismtask.domain.rating

import com.averycorp.prismtask.data.preferences.RatingPromptPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Did we observe a crash recently?" proxy used by
 * [RatingPromptTriggerHelper] to suppress prompts after stability hiccups.
 *
 * Crashlytics SDK has no recent-crash query API — it's write-only. This
 * proxy resolves STOP-3B per `docs/audits/E2_IN_APP_RATINGS_AUDIT.md` §
 * Item 3 by writing a `last_crash_at` timestamp at known crash-recording
 * call sites:
 *  1. [PrismTaskApplication]'s global `Thread.setDefaultUncaughtExceptionHandler`
 *     (catches process-fatal crashes; the value is read on the *next*
 *     session boot).
 *  2. The `recordException` site at `BugReportViewModel` (manual non-fatal
 *     reports submitted via the in-app form).
 *
 * The 24h window in [RatingPromptTriggerHelper] reads from this signal.
 */
@Singleton
class RecentCrashSignal @Inject constructor(
    private val prefs: RatingPromptPreferences
) {
    suspend fun recordCrash() {
        prefs.setLastCrashAt(System.currentTimeMillis())
    }

    suspend fun hadRecentCrash(windowMs: Long): Boolean {
        val last = prefs.lastCrashAt().first()
        return last > 0L && (System.currentTimeMillis() - last) < windowMs
    }
}
