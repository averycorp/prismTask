package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationDoseDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.MedicationPreferences
import com.averycorp.prismtask.data.preferences.MedicationReminderMode
import com.averycorp.prismtask.data.preferences.MedicationScheduleMode
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.notifications.HabitReminderScheduler
import com.averycorp.prismtask.notifications.MEDICATION_TIME_OF_DAY_CLOCK
import com.averycorp.prismtask.notifications.MedicationClockRescheduler
import com.averycorp.prismtask.notifications.ReminderScheduler
import com.averycorp.prismtask.util.DayBoundary
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single notification we expect to deliver in the future. Used by the
 * admin projected-notification log to surface what's queued up without
 * having to inspect AlarmManager (which Android does not expose to apps).
 */
data class ProjectedNotification(val triggerAtMillis: Long, val title: String, val body: String, val source: Source, val sourceId: Long?) {
    enum class Source(val label: String) {
        TASK_REMINDER("Task Reminder"),
        HABIT_DAILY("Habit Reminder (Daily)"),
        HABIT_INTERVAL("Habit Reminder (Interval)"),
        HABIT_LEGACY_SPECIFIC_TIME("Medication Reminder (Legacy)"),
        MEDICATION("Medication"),
        MEDICATION_SLOT_CLOCK("Medication (Slot, Clock)"),
        MEDICATION_SLOT_INTERVAL("Medication (Slot, Interval)"),
        BRIEFING("Daily Briefing"),
        EVENING_SUMMARY("Evening Summary"),
        REENGAGEMENT("Re-Engagement Nudge"),
        WEEKLY_HABIT_SUMMARY("Weekly Habit Summary"),
        WEEKLY_TASK_SUMMARY("Weekly Task Summary"),
        OVERLOAD_CHECK("Overload Check"),
        WEEKLY_REVIEW("Weekly Review")
    }
}

/**
 * Computes the upcoming notifications the app expects to fire within a
 * configurable horizon (default 7 days), derived from the same scheduling
 * logic the production schedulers and workers use. Mirrors:
 *
 *  - [ReminderScheduler.computeEffectiveTrigger] for task reminders
 *  - [HabitReminderScheduler.computeNextDailyTrigger] for habit daily-time reminders
 *  - [HabitReminderScheduler.scheduleNext] interval math for habit-interval reminders
 *  - [HabitReminderScheduler.timeStringToNextTrigger] for legacy medication specific-times
 *  - [com.averycorp.prismtask.notifications.MedicationReminderScheduler] for legacy
 *    `MedicationEntity.scheduleMode`-driven meds (no linked active slot)
 *  - [com.averycorp.prismtask.notifications.MedicationClockRescheduler] for slot-driven
 *    CLOCK reminders (`MedicationSlotEntity.idealTime`)
 *  - [com.averycorp.prismtask.notifications.MedicationIntervalRescheduler] for slot-driven
 *    INTERVAL reminders (rolling, anchored on most-recent dose)
 *  - [com.averycorp.prismtask.notifications.NotificationWorkerScheduler] for periodic workers
 *
 * The slot-driven schedulers are the source of truth whenever a medication
 * has at least one linked active slot — the legacy
 * [com.averycorp.prismtask.notifications.MedicationReminderScheduler] still
 * fires for medications with no slot links (legacy migrations, sync-pulled
 * rows that predate the slot system). The projector mirrors that split so
 * a slot-linked medication's row appears once at the slot's wall-clock,
 * not also at the legacy hard-coded bucket clock.
 *
 * Reactive sources — escalation chains and habit follow-up nags — are
 * intentionally NOT projected: their trigger times are only known after an
 * upstream reminder fires (escalation needs profile resolution at fire-time,
 * follow-up needs the initial nag to determine suppression). Including them
 * pre-fire would be guessing.
 */
@Singleton
class NotificationProjector @Inject constructor(
    private val taskDao: TaskDao,
    private val habitDao: HabitDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val medicationDao: MedicationDao,
    private val medicationDoseDao: MedicationDoseDao,
    private val medicationSlotDao: MedicationSlotDao,
    private val notificationPreferences: NotificationPreferences,
    private val advancedTuningPreferences: AdvancedTuningPreferences,
    private val medicationPreferences: MedicationPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val userPreferencesDataStore: UserPreferencesDataStore
) {
    suspend fun projectAll(
        horizonMillis: Long = DEFAULT_HORIZON_MILLIS,
        nowMillis: Long = System.currentTimeMillis()
    ): List<ProjectedNotification> {
        val horizonEnd = nowMillis + horizonMillis
        val candidates = buildList {
            addAll(projectTasks(nowMillis))
            addAll(projectHabitsDaily(nowMillis, horizonEnd))
            addAll(projectHabitsInterval(nowMillis))
            addAll(projectHabitsLegacySpecificTimes(nowMillis, horizonEnd))
            addAll(projectMedications(nowMillis, horizonEnd))
            addAll(projectMedicationSlotsClock(nowMillis, horizonEnd))
            addAll(projectMedicationSlotsInterval(nowMillis))
            addAll(projectBriefing(nowMillis, horizonEnd))
            addAll(projectEveningSummary(nowMillis, horizonEnd))
            addAll(projectReengagement(nowMillis, horizonEnd))
            addAll(projectWeeklyHabitSummary(nowMillis, horizonEnd))
            addAll(projectWeeklyTaskSummary(nowMillis, horizonEnd))
            addAll(projectOverloadCheck(nowMillis, horizonEnd))
            addAll(projectWeeklyReview(nowMillis, horizonEnd))
        }
        return candidates
            .filter { it.triggerAtMillis in (nowMillis + 1)..horizonEnd }
            .sortedBy { it.triggerAtMillis }
    }

    // --- Task reminders -------------------------------------------------

    private suspend fun projectTasks(now: Long): List<ProjectedNotification> {
        return taskDao.getIncompleteTasksWithReminders().mapNotNull { task ->
            val dueDate = task.dueDate ?: return@mapNotNull null
            val offset = task.reminderOffset ?: return@mapNotNull null
            val effectiveDue = ReminderScheduler.combineDateAndTime(dueDate, task.dueTime)
            val rawTrigger = ReminderScheduler.computeTriggerTime(effectiveDue, offset)
            val effectiveTrigger =
                ReminderScheduler.computeEffectiveTrigger(rawTrigger, now) ?: return@mapNotNull null
            ProjectedNotification(
                triggerAtMillis = effectiveTrigger,
                title = "${task.title} is coming up",
                body = task.description ?: "Ready when you are.",
                source = ProjectedNotification.Source.TASK_REMINDER,
                sourceId = task.id
            )
        }
    }

    // --- Habits ---------------------------------------------------------

    private suspend fun projectHabitsDaily(now: Long, horizonEnd: Long): List<ProjectedNotification> {
        return habitDao.getHabitsWithDailyTimeReminder().flatMap { habit ->
            val reminderTime = habit.reminderTime ?: return@flatMap emptyList()
            expandDailyOccurrences(reminderTime, now, horizonEnd).map { trigger ->
                ProjectedNotification(
                    triggerAtMillis = trigger,
                    title = habit.name,
                    body = habit.description ?: "${habit.name} — whenever you're ready.",
                    source = ProjectedNotification.Source.HABIT_DAILY,
                    sourceId = habit.id
                )
            }
        }
    }

    /**
     * Projects the *next* fire for each interval-mode habit. Subsequent
     * fires depend on user completion timestamps we can't predict, so we
     * only project the upcoming dose. Mirrors [HabitReminderScheduler.rescheduleAll]:
     * skip when today's completion count has already met the per-day cap,
     * skip when the global medication mode is SPECIFIC_TIMES (which
     * disables interval scheduling), require a recorded last completion to
     * anchor the interval math.
     */
    private suspend fun projectHabitsInterval(now: Long): List<ProjectedNotification> {
        if (medicationPreferences.getScheduleModeOnce() == MedicationScheduleMode.SPECIFIC_TIMES) {
            return emptyList()
        }
        val dayStartHour = taskBehaviorPreferences.getDayStartHour().first()
        val todayLocal = DayBoundary.currentLocalDateString(dayStartHour)
        return habitDao.getHabitsWithIntervalReminder().mapNotNull { habit ->
            val interval = habit.reminderIntervalMillis ?: return@mapNotNull null
            val timesPerDay = habit.reminderTimesPerDay
            val todayCount = habitCompletionDao.getCompletionCountForDateLocalOnce(habit.id, todayLocal)
            if (todayCount >= timesPerDay) return@mapNotNull null
            val lastCompletion = habitCompletionDao.getLastCompletionOnce(habit.id) ?: return@mapNotNull null
            val trigger = maxOf(lastCompletion.completedAt + interval, now + 1_000L)
            val doseInfo = if (timesPerDay > 1) " (dose ${todayCount + 1} of $timesPerDay)" else ""
            ProjectedNotification(
                triggerAtMillis = trigger,
                title = "${habit.name}$doseInfo",
                body = habit.description ?: "${habit.name} — whenever you're ready.",
                source = ProjectedNotification.Source.HABIT_INTERVAL,
                sourceId = habit.id
            )
        }
    }

    /**
     * Legacy specific-times medication path that lives under
     * [HabitReminderScheduler.scheduleSpecificTimes] — only fires when the
     * global mode is SPECIFIC_TIMES.
     */
    private suspend fun projectHabitsLegacySpecificTimes(now: Long, horizonEnd: Long): List<ProjectedNotification> {
        if (medicationPreferences.getScheduleModeOnce() != MedicationScheduleMode.SPECIFIC_TIMES) {
            return emptyList()
        }
        val times = medicationPreferences.getSpecificTimesOnce().sorted()
        return times.flatMap { hhmm ->
            val firstTrigger = HabitReminderScheduler.timeStringToNextTrigger(
                timeStr = hhmm,
                now = now
            )
            expandDailyOccurrencesFromAbsolute(firstTrigger, horizonEnd).map { trigger ->
                ProjectedNotification(
                    triggerAtMillis = trigger,
                    title = "Medication Reminder",
                    body = "Scheduled medication time ($hhmm).",
                    source = ProjectedNotification.Source.HABIT_LEGACY_SPECIFIC_TIME,
                    sourceId = null
                )
            }
        }
    }

    // --- Medications (top-level v1.4+) ----------------------------------

    /**
     * Legacy `MedicationEntity.scheduleMode`-driven projection. Skipped for
     * any medication that has at least one linked active slot — those
     * alarms are owned by [com.averycorp.prismtask.notifications.MedicationClockRescheduler]
     * and [com.averycorp.prismtask.notifications.MedicationIntervalRescheduler],
     * which read [MedicationSlotEntity.idealTime] /
     * [MedicationSlotEntity.reminderIntervalMinutes] respectively. Without
     * the skip, slot-linked meds with a populated legacy
     * `scheduleMode` (sync-pulled rows that predate the slot system) would
     * project a phantom row at the hard-coded MEDICATION_TIME_OF_DAY_CLOCK time even
     * though the real alarm fires from the slot.
     */
    private suspend fun projectMedications(now: Long, horizonEnd: Long): List<ProjectedNotification> {
        val active = medicationDao.getActiveOnce()
        return active.flatMap { med ->
            if (medicationSlotDao.getSlotIdsForMedicationOnce(med.id).isNotEmpty()) {
                return@flatMap emptyList()
            }
            when (med.scheduleMode) {
                "TIMES_OF_DAY" -> projectMedicationFixedTimes(
                    med = med,
                    clocks = parseTimesOfDay(med.timesOfDay).mapNotNull { MEDICATION_TIME_OF_DAY_CLOCK[it] },
                    now = now,
                    horizonEnd = horizonEnd
                )
                "SPECIFIC_TIMES" -> projectMedicationFixedTimes(
                    med = med,
                    clocks = parseSpecificTimes(med.specificTimes),
                    now = now,
                    horizonEnd = horizonEnd
                )
                "INTERVAL" -> projectMedicationInterval(med, now)
                else -> emptyList()
            }
        }
    }

    // --- Slot-driven medication reminders -------------------------------

    /**
     * Mirrors [com.averycorp.prismtask.notifications.MedicationClockRescheduler.rescheduleAll]:
     * walks active slots, resolves the reminder mode against the global
     * default via [MedicationReminderModeResolver], and projects daily
     * occurrences at `slot.idealTime` for any slot resolving to CLOCK
     * mode.
     *
     * Linked-medication-name fanout: a slot can link to multiple
     * medications. We emit one projected row per `(slot, medication)`
     * pair so the log labels stay specific. Slots with no linked meds
     * still project one row labelled with the slot name so the user can
     * verify timing.
     */
    private suspend fun projectMedicationSlotsClock(
        now: Long,
        horizonEnd: Long
    ): List<ProjectedNotification> {
        val global = userPreferencesDataStore.medicationReminderModeFlow.first()
        val slots = medicationSlotDao.getActiveOnce()
        if (slots.isEmpty()) return emptyList()

        return slots.flatMap { slot ->
            val mode = MedicationReminderModeResolver.resolveReminderMode(
                medication = null,
                slot = slot,
                global = global
            )
            if (mode != MedicationReminderMode.CLOCK) return@flatMap emptyList()
            val firstTrigger = MedicationClockRescheduler.nextTriggerForClock(slot.idealTime, now)
                ?: return@flatMap emptyList()
            val occurrences = expandDailyOccurrencesFromAbsolute(firstTrigger, horizonEnd)
            if (occurrences.isEmpty()) return@flatMap emptyList()

            val medIds = medicationSlotDao.getMedicationIdsForSlotOnce(slot.id)
            val medsForSlot = medIds.mapNotNull { medicationDao.getByIdOnce(it) }
            slotProjectionsForSlot(slot, medsForSlot, occurrences)
        }
    }

    /**
     * Mirrors [com.averycorp.prismtask.notifications.MedicationIntervalRescheduler.rescheduleAll]
     * for the slot path: anchors on the most-recent dose row across all
     * medications, falling back to `now` when no dose exists. Projects
     * one upcoming row per INTERVAL-mode slot. Subsequent rolls depend on
     * future user logging we can't predict, so only the next fire is
     * projected.
     */
    private suspend fun projectMedicationSlotsInterval(now: Long): List<ProjectedNotification> {
        val global = userPreferencesDataStore.medicationReminderModeFlow.first()
        val slots = medicationSlotDao.getActiveOnce()
        if (slots.isEmpty()) return emptyList()

        val anchorMillis = medicationDoseDao.getMostRecentDoseAnyOnce()?.takenAt

        return slots.flatMap { slot ->
            val mode = MedicationReminderModeResolver.resolveReminderMode(
                medication = null,
                slot = slot,
                global = global
            )
            if (mode != MedicationReminderMode.INTERVAL) return@flatMap emptyList()
            val intervalMinutes = MedicationReminderModeResolver.resolveIntervalMinutes(
                medication = null,
                slot = slot,
                global = global
            )
            val intervalMillis = intervalMinutes * MILLIS_PER_MINUTE
            val baseMillis = anchorMillis ?: now
            val trigger = maxOf(baseMillis + intervalMillis, now + MIN_LEAD_MILLIS)

            val medIds = medicationSlotDao.getMedicationIdsForSlotOnce(slot.id)
            val medsForSlot = medIds.mapNotNull { medicationDao.getByIdOnce(it) }
            if (medsForSlot.isEmpty()) {
                listOf(
                    ProjectedNotification(
                        triggerAtMillis = trigger,
                        title = "${slot.name} — Heads Up",
                        body = "Next interval-scheduled dose for ${slot.name}.",
                        source = ProjectedNotification.Source.MEDICATION_SLOT_INTERVAL,
                        sourceId = slot.id
                    )
                )
            } else {
                medsForSlot.map { med ->
                    ProjectedNotification(
                        triggerAtMillis = trigger,
                        title = "${med.displayLabel ?: med.name} — Heads Up",
                        body = "Next interval-scheduled dose of ${med.name} (${slot.name}).",
                        source = ProjectedNotification.Source.MEDICATION_SLOT_INTERVAL,
                        sourceId = med.id
                    )
                }
            }
        }
    }

    private fun slotProjectionsForSlot(
        slot: MedicationSlotEntity,
        medsForSlot: List<MedicationEntity>,
        occurrences: List<Long>
    ): List<ProjectedNotification> {
        if (medsForSlot.isEmpty()) {
            return occurrences.map { trigger ->
                ProjectedNotification(
                    triggerAtMillis = trigger,
                    title = "${slot.name} — Heads Up",
                    body = "Time for your ${slot.name} dose (${slot.idealTime}).",
                    source = ProjectedNotification.Source.MEDICATION_SLOT_CLOCK,
                    sourceId = slot.id
                )
            }
        }
        return occurrences.flatMap { trigger ->
            medsForSlot.map { med ->
                ProjectedNotification(
                    triggerAtMillis = trigger,
                    title = "${med.displayLabel ?: med.name} — Heads Up",
                    body = "Time for your ${med.name} dose (${slot.idealTime}, ${slot.name}).",
                    source = ProjectedNotification.Source.MEDICATION_SLOT_CLOCK,
                    sourceId = med.id
                )
            }
        }
    }

    private fun projectMedicationFixedTimes(
        med: MedicationEntity,
        clocks: List<String>,
        now: Long,
        horizonEnd: Long
    ): List<ProjectedNotification> = clocks.flatMap { hhmm ->
        val first = HabitReminderScheduler.timeStringToNextTrigger(
            timeStr = hhmm,
            now = now
        )
        expandDailyOccurrencesFromAbsolute(first, horizonEnd).map { trigger ->
            ProjectedNotification(
                triggerAtMillis = trigger,
                title = "${med.displayLabel ?: med.name} — Heads Up",
                body = "Time for your ${med.name} dose ($hhmm).",
                source = ProjectedNotification.Source.MEDICATION,
                sourceId = med.id
            )
        }
    }

    private suspend fun projectMedicationInterval(med: MedicationEntity, now: Long): List<ProjectedNotification> {
        val interval = med.intervalMillis?.takeIf { it > 0 } ?: return emptyList()
        val lastDose = medicationDoseDao.getLatestForMedOnce(med.id)
        val baseMillis = lastDose?.takenAt ?: now
        val trigger = maxOf(baseMillis + interval, now + 1_000L)
        return listOf(
            ProjectedNotification(
                triggerAtMillis = trigger,
                title = "${med.displayLabel ?: med.name} — Heads Up",
                body = "Next interval-scheduled dose of ${med.name}.",
                source = ProjectedNotification.Source.MEDICATION,
                sourceId = med.id
            )
        )
    }

    // --- Periodic workers -----------------------------------------------

    private suspend fun projectBriefing(now: Long, horizonEnd: Long): List<ProjectedNotification> {
        if (!notificationPreferences.dailyBriefingEnabled.first()) return emptyList()
        val hour = notificationPreferences.briefingMorningHour.first()
        val first = nextOccurrenceAt(hour, 0, now)
        return expandDailyOccurrencesFromAbsolute(first, horizonEnd).map { trigger ->
            ProjectedNotification(
                triggerAtMillis = trigger,
                title = "Good Morning",
                body = "Daily briefing — content generated when delivered.",
                source = ProjectedNotification.Source.BRIEFING,
                sourceId = null
            )
        }
    }

    private suspend fun projectEveningSummary(now: Long, horizonEnd: Long): List<ProjectedNotification> {
        if (!notificationPreferences.eveningSummaryEnabled.first()) return emptyList()
        val schedule = advancedTuningPreferences.getWeeklySummarySchedule().first()
        val first = nextOccurrenceAt(schedule.eveningSummaryHour, 0, now)
        return expandDailyOccurrencesFromAbsolute(first, horizonEnd).map { trigger ->
            ProjectedNotification(
                triggerAtMillis = trigger,
                title = "Today's Wrap-Up",
                body = "Evening summary — content generated when delivered.",
                source = ProjectedNotification.Source.EVENING_SUMMARY,
                sourceId = null
            )
        }
    }

    private suspend fun projectReengagement(now: Long, horizonEnd: Long): List<ProjectedNotification> {
        if (!notificationPreferences.reengagementEnabled.first()) return emptyList()
        val config = advancedTuningPreferences.getReengagementConfig().first()
        // Reengagement runs daily but only delivers when the user has been
        // absent ≥ absenceDays AND nudgeCount < maxNudges. Project a single
        // entry at the next 24-hour-from-now check; admins can read the
        // body to see this is conditional, not guaranteed.
        val firstCheck = now + 24L * 60 * 60 * 1000L
        return expandDailyOccurrencesFromAbsolute(firstCheck, horizonEnd).map { trigger ->
            ProjectedNotification(
                triggerAtMillis = trigger,
                title = "PrismTask",
                body = "Re-engagement check (delivers only after ${config.absenceDays}d absence, " +
                    "max ${config.maxNudges} nudges).",
                source = ProjectedNotification.Source.REENGAGEMENT,
                sourceId = null
            )
        }
    }

    private suspend fun projectWeeklyHabitSummary(now: Long, horizonEnd: Long): List<ProjectedNotification> {
        if (!notificationPreferences.weeklySummaryEnabled.first()) return emptyList()
        val schedule = advancedTuningPreferences.getWeeklySummarySchedule().first()
        val calendarDow = isoToCalendarDayOfWeek(schedule.dayOfWeek)
        val first = nextWeeklyOccurrence(
            calendarDayOfWeek = calendarDow,
            hour = schedule.habitSummaryHour,
            minute = schedule.habitSummaryMinute,
            now = now
        )
        return expandWeeklyOccurrences(first, horizonEnd).map { trigger ->
            ProjectedNotification(
                triggerAtMillis = trigger,
                title = "Your Week in Review",
                body = "Weekly habit summary — content generated when delivered.",
                source = ProjectedNotification.Source.WEEKLY_HABIT_SUMMARY,
                sourceId = null
            )
        }
    }

    private suspend fun projectWeeklyTaskSummary(now: Long, horizonEnd: Long): List<ProjectedNotification> {
        if (!notificationPreferences.weeklyTaskSummaryEnabled.first()) return emptyList()
        val schedule = advancedTuningPreferences.getWeeklySummarySchedule().first()
        val calendarDow = isoToCalendarDayOfWeek(schedule.dayOfWeek)
        val first = nextWeeklyOccurrence(
            calendarDayOfWeek = calendarDow,
            hour = schedule.taskSummaryHour,
            minute = schedule.taskSummaryMinute,
            now = now
        )
        return expandWeeklyOccurrences(first, horizonEnd).map { trigger ->
            ProjectedNotification(
                triggerAtMillis = trigger,
                title = "Your Task Week in Review",
                body = "Weekly task summary — content generated when delivered.",
                source = ProjectedNotification.Source.WEEKLY_TASK_SUMMARY,
                sourceId = null
            )
        }
    }

    private suspend fun projectOverloadCheck(now: Long, horizonEnd: Long): List<ProjectedNotification> {
        if (!notificationPreferences.overloadAlertsEnabled.first()) return emptyList()
        val schedule = advancedTuningPreferences.getOverloadCheckSchedule().first()
        val first = nextOccurrenceAt(schedule.hourOfDay, schedule.minute, now)
        return expandDailyOccurrencesFromAbsolute(first, horizonEnd).map { trigger ->
            ProjectedNotification(
                triggerAtMillis = trigger,
                title = "Work-life balance is skewing",
                body = "Overload check (delivers only when balance threshold is exceeded).",
                source = ProjectedNotification.Source.OVERLOAD_CHECK,
                sourceId = null
            )
        }
    }

    private suspend fun projectWeeklyReview(now: Long, horizonEnd: Long): List<ProjectedNotification> {
        if (!notificationPreferences.weeklyReviewAutoGenerateEnabled.first()) return emptyList()
        if (!notificationPreferences.weeklyReviewNotificationEnabled.first()) return emptyList()
        val schedule = advancedTuningPreferences.getWeeklySummarySchedule().first()
        val calendarDow = isoToCalendarDayOfWeek(schedule.dayOfWeek)
        val first = nextWeeklyOccurrence(
            calendarDayOfWeek = calendarDow,
            hour = schedule.reviewHour,
            minute = schedule.reviewMinute,
            now = now
        )
        return expandWeeklyOccurrences(first, horizonEnd).map { trigger ->
            ProjectedNotification(
                triggerAtMillis = trigger,
                title = "Your Weekly Review Is Ready",
                body = "Tap to read this week's recap and plan ahead.",
                source = ProjectedNotification.Source.WEEKLY_REVIEW,
                sourceId = null
            )
        }
    }

    // --- Time helpers ---------------------------------------------------

    /**
     * Expand a habit's [HabitEntity.reminderTime] (millis-since-midnight)
     * into every absolute fire instant within `[now, horizonEnd]`.
     */
    private fun expandDailyOccurrences(
        reminderMillisSinceMidnight: Long,
        now: Long,
        horizonEnd: Long
    ): List<Long> {
        val first = HabitReminderScheduler.computeNextDailyTrigger(reminderMillisSinceMidnight, now)
        return expandDailyOccurrencesFromAbsolute(first, horizonEnd)
    }

    /**
     * Step through a daily-recurring event in 24h increments, returning
     * every fire-time within `[firstTrigger, horizonEnd]`.
     */
    private fun expandDailyOccurrencesFromAbsolute(firstTrigger: Long, horizonEnd: Long): List<Long> {
        if (firstTrigger > horizonEnd) return emptyList()
        val out = mutableListOf<Long>()
        val cal = Calendar.getInstance().apply { timeInMillis = firstTrigger }
        while (cal.timeInMillis <= horizonEnd) {
            out.add(cal.timeInMillis)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    private fun expandWeeklyOccurrences(firstTrigger: Long, horizonEnd: Long): List<Long> {
        if (firstTrigger > horizonEnd) return emptyList()
        val out = mutableListOf<Long>()
        val cal = Calendar.getInstance().apply { timeInMillis = firstTrigger }
        while (cal.timeInMillis <= horizonEnd) {
            out.add(cal.timeInMillis)
            cal.add(Calendar.DAY_OF_YEAR, 7)
        }
        return out
    }

    private fun nextOccurrenceAt(hour: Int, minute: Int, now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun nextWeeklyOccurrence(
        calendarDayOfWeek: Int,
        hour: Int,
        minute: Int,
        now: Long
    ): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val daysUntil = ((calendarDayOfWeek - cal.get(Calendar.DAY_OF_WEEK)) + 7) % 7
        cal.add(Calendar.DAY_OF_YEAR, daysUntil)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 7)
        return cal.timeInMillis
    }

    private fun parseTimesOfDay(raw: String?): List<String> =
        raw.orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it in MEDICATION_TIME_OF_DAY_CLOCK }
            .distinct()

    private fun parseSpecificTimes(raw: String?): List<String> =
        raw.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { isValidClockString(it) }
            .distinct()
            .sorted()

    private fun isValidClockString(raw: String): Boolean {
        val parts = raw.split(':')
        if (parts.size != 2) return false
        val hour = parts[0].toIntOrNull() ?: return false
        val minute = parts[1].toIntOrNull() ?: return false
        return hour in 0..23 && minute in 0..59
    }

    /** Maps the user-facing ISO day (1=Mon..7=Sun) onto Calendar (SUN=1..SAT=7). */
    private fun isoToCalendarDayOfWeek(isoDay: Int): Int = when (isoDay.coerceIn(1, 7)) {
        7 -> Calendar.SUNDAY
        else -> isoDay + 1
    }

    companion object {
        const val DEFAULT_HORIZON_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MIN_LEAD_MILLIS = 1_000L
    }
}
