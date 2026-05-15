package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationDoseDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotOverrideDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.MedicationDoseEntity
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotOverrideEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.MedicationPreferences
import com.averycorp.prismtask.data.preferences.MedicationReminderMode
import com.averycorp.prismtask.data.preferences.MedicationReminderModePrefs
import com.averycorp.prismtask.data.preferences.MedicationScheduleMode
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.OverloadCheckSchedule
import com.averycorp.prismtask.data.preferences.ReengagementConfig
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.preferences.WeeklySummarySchedule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for [NotificationProjector]. All worker toggles default OFF
 * via [silenceWorkers] so each test only exercises the source it cares
 * about; tests that need a worker enabled override that toggle locally.
 */
class NotificationProjectorTest {
    private lateinit var taskDao: TaskDao
    private lateinit var habitDao: HabitDao
    private lateinit var habitCompletionDao: HabitCompletionDao
    private lateinit var medicationDao: MedicationDao
    private lateinit var medicationDoseDao: MedicationDoseDao
    private lateinit var medicationSlotDao: MedicationSlotDao
    private lateinit var medicationSlotOverrideDao: MedicationSlotOverrideDao
    private lateinit var notificationPreferences: NotificationPreferences
    private lateinit var advancedTuningPreferences: AdvancedTuningPreferences
    private lateinit var medicationPreferences: MedicationPreferences
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var userPreferencesDataStore: UserPreferencesDataStore
    private lateinit var projector: NotificationProjector

    @Before
    fun setUp() = runBlocking {
        taskDao = mockk()
        habitDao = mockk()
        habitCompletionDao = mockk()
        medicationDao = mockk()
        medicationDoseDao = mockk()
        medicationSlotDao = mockk()
        medicationSlotOverrideDao = mockk()
        notificationPreferences = mockk()
        advancedTuningPreferences = mockk()
        medicationPreferences = mockk()
        taskBehaviorPreferences = mockk()
        userPreferencesDataStore = mockk()
        projector = NotificationProjector(
            taskDao,
            habitDao,
            habitCompletionDao,
            medicationDao,
            medicationDoseDao,
            medicationSlotDao,
            medicationSlotOverrideDao,
            notificationPreferences,
            advancedTuningPreferences,
            medicationPreferences,
            taskBehaviorPreferences,
            userPreferencesDataStore
        )
        defaultEmptyDataSources()
        silenceWorkers()
    }

    private fun defaultEmptyDataSources() = runBlocking {
        coEvery { taskDao.getIncompleteTasksWithReminders() } returns emptyList()
        coEvery { habitDao.getHabitsWithDailyTimeReminder() } returns emptyList()
        coEvery { habitDao.getHabitsWithIntervalReminder() } returns emptyList()
        coEvery { medicationDao.getActiveOnce() } returns emptyList()
        coEvery { medicationSlotDao.getActiveOnce() } returns emptyList()
        coEvery { medicationSlotDao.getSlotIdsForMedicationOnce(any()) } returns emptyList()
        coEvery { medicationSlotDao.getMedicationIdsForSlotOnce(any()) } returns emptyList()
        coEvery { medicationSlotOverrideDao.getAllOnce() } returns emptyList()
        coEvery { medicationDoseDao.getMostRecentDoseAnyOnce() } returns null
        coEvery { medicationPreferences.getScheduleModeOnce() } returns MedicationScheduleMode.INTERVAL
        coEvery { medicationPreferences.getSpecificTimesOnce() } returns emptySet()
        every { taskBehaviorPreferences.getDayStartHour() } returns flowOf(0)
        every { userPreferencesDataStore.medicationReminderModeFlow } returns flowOf(
            MedicationReminderModePrefs(mode = MedicationReminderMode.CLOCK, intervalDefaultMinutes = 240)
        )
        // Per-channel global toggles default ON so tests that don't care
        // about gating still see their reminders. Tests that exercise the
        // disabled path re-stub the relevant flag locally.
        every { notificationPreferences.taskRemindersEnabled } returns flowOf(true)
        every { notificationPreferences.medicationRemindersEnabled } returns flowOf(true)
    }

    /**
     * All worker toggles OFF + default schedules. Tests that exercise a
     * specific worker re-stub its enabled flag to `true`.
     */
    private fun silenceWorkers() {
        every { notificationPreferences.dailyBriefingEnabled } returns flowOf(false)
        every { notificationPreferences.eveningSummaryEnabled } returns flowOf(false)
        every { notificationPreferences.reengagementEnabled } returns flowOf(false)
        every { notificationPreferences.weeklySummaryEnabled } returns flowOf(false)
        every { notificationPreferences.weeklyTaskSummaryEnabled } returns flowOf(false)
        every { notificationPreferences.overloadAlertsEnabled } returns flowOf(false)
        every { notificationPreferences.weeklyReviewAutoGenerateEnabled } returns flowOf(false)
        every { notificationPreferences.weeklyReviewNotificationEnabled } returns flowOf(false)
        every { notificationPreferences.weeklyAnalyticsNotificationEnabled } returns flowOf(false)
        every { notificationPreferences.briefingMorningHour } returns flowOf(8)
        every { advancedTuningPreferences.getWeeklySummarySchedule() } returns flowOf(
            WeeklySummarySchedule(
                dayOfWeek = 7,
                taskSummaryHour = 19,
                taskSummaryMinute = 30,
                habitSummaryHour = 19,
                habitSummaryMinute = 0,
                reviewHour = 20,
                reviewMinute = 0,
                eveningSummaryHour = 20,
                analyticsSummaryHour = 19,
                analyticsSummaryMinute = 0
            )
        )
        every { advancedTuningPreferences.getOverloadCheckSchedule() } returns flowOf(
            OverloadCheckSchedule(hourOfDay = 16, minute = 0)
        )
        every { advancedTuningPreferences.getReengagementConfig() } returns flowOf(
            ReengagementConfig(absenceDays = 2, maxNudges = 1)
        )
    }

    @Test
    fun `projects task reminder using dueDate minus reminderOffset`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        val due = baseInstant(2026, Calendar.MAY, 1, 14, 0)
        coEvery { taskDao.getIncompleteTasksWithReminders() } returns listOf(
            task(id = 1L, title = "Submit Report", dueDate = due, reminderOffset = 30 * 60 * 1000L)
        )

        val result = projector.projectAll(nowMillis = now)

        assertEquals(1, result.size)
        val n = result[0]
        assertEquals(due - 30 * 60 * 1000L, n.triggerAtMillis)
        assertEquals("Submit Report is coming up", n.title)
        assertEquals(ProjectedNotification.Source.TASK_REMINDER, n.source)
    }

    @Test
    fun `falls back to default body when task description is null`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        val due = baseInstant(2026, Calendar.MAY, 1, 14, 0)
        coEvery { taskDao.getIncompleteTasksWithReminders() } returns listOf(
            task(id = 1L, title = "T", dueDate = due, reminderOffset = 60_000L, description = null)
        )

        val result = projector.projectAll(nowMillis = now)

        assertEquals("Ready when you are.", result[0].body)
    }

    @Test
    fun `drops task reminders that are stale by more than 24 hours`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 3, 10, 0)
        val due = baseInstant(2026, Calendar.MAY, 1, 9, 0)
        coEvery { taskDao.getIncompleteTasksWithReminders() } returns listOf(
            task(id = 1L, title = "Stale", dueDate = due, reminderOffset = 60_000L)
        )

        val result = projector.projectAll(nowMillis = now)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `projects habit daily reminder for every occurrence within horizon`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 6, 0)
        val nineAm = (9 * 60 * 60 * 1000L)
        coEvery { habitDao.getHabitsWithDailyTimeReminder() } returns listOf(
            habit(id = 7L, name = "Take Vitamin", reminderTime = nineAm)
        )

        val result = projector.projectAll(nowMillis = now)

        assertEquals(7, result.size)
        result.forEach {
            assertEquals(ProjectedNotification.Source.HABIT_DAILY, it.source)
            assertEquals("Take Vitamin", it.title)
        }
    }

    @Test
    fun `projects habit interval reminder when last completion exists and cap not reached`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        val lastCompletion = baseInstant(2026, Calendar.MAY, 1, 8, 0)
        val intervalMs = 4 * 60 * 60 * 1000L
        coEvery { habitDao.getHabitsWithIntervalReminder() } returns listOf(
            habit(id = 9L, name = "Hydrate", reminderIntervalMillis = intervalMs, reminderTimesPerDay = 4)
        )
        coEvery { habitCompletionDao.getLastCompletionOnce(9L) } returns completion(9L, lastCompletion)
        coEvery { habitCompletionDao.getCompletionCountForDateLocalOnce(9L, any()) } returns 1

        val result = projector.projectAll(nowMillis = now)

        assertEquals(1, result.size)
        val n = result[0]
        assertEquals(lastCompletion + intervalMs, n.triggerAtMillis)
        assertEquals("Hydrate (dose 2 of 4)", n.title)
        assertEquals(ProjectedNotification.Source.HABIT_INTERVAL, n.source)
    }

    @Test
    fun `skips habit interval when daily cap already met`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        coEvery { habitDao.getHabitsWithIntervalReminder() } returns listOf(
            habit(id = 9L, name = "Hydrate", reminderIntervalMillis = 60_000L, reminderTimesPerDay = 2)
        )
        coEvery { habitCompletionDao.getCompletionCountForDateLocalOnce(9L, any()) } returns 2

        val result = projector.projectAll(nowMillis = now)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `skips habit interval when global mode is SPECIFIC_TIMES`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        coEvery { medicationPreferences.getScheduleModeOnce() } returns MedicationScheduleMode.SPECIFIC_TIMES
        coEvery { habitDao.getHabitsWithIntervalReminder() } returns listOf(
            habit(id = 9L, name = "Hydrate", reminderIntervalMillis = 60_000L)
        )

        val result = projector.projectAll(nowMillis = now)

        assertTrue(result.none { it.source == ProjectedNotification.Source.HABIT_INTERVAL })
    }

    @Test
    fun `projects medication TIMES_OF_DAY at canonical clock times`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 6, 0)
        coEvery { medicationDao.getActiveOnce() } returns listOf(
            medication(id = 11L, name = "Vitamin D", scheduleMode = "TIMES_OF_DAY", timesOfDay = "morning,evening")
        )

        val result = projector.projectAll(nowMillis = now)

        assertTrue(result.isNotEmpty())
        result.forEach {
            assertEquals(ProjectedNotification.Source.MEDICATION, it.source)
        }
        // Two slots × 7 days = 14 occurrences within horizon
        assertEquals(14, result.size)
    }

    @Test
    fun `projects medication INTERVAL using last dose plus interval`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        val lastDose = baseInstant(2026, Calendar.MAY, 1, 8, 0)
        val interval = 6 * 60 * 60 * 1000L
        coEvery { medicationDao.getActiveOnce() } returns listOf(
            medication(id = 12L, name = "Insulin", scheduleMode = "INTERVAL", intervalMillis = interval)
        )
        coEvery { medicationDoseDao.getLatestForMedOnce(12L) } returns dose(12L, lastDose)

        val result = projector.projectAll(nowMillis = now)

        assertEquals(1, result.size)
        assertEquals(lastDose + interval, result[0].triggerAtMillis)
    }

    @Test
    fun `slot CLOCK projects daily slot-level row titled '{slot} Medications'`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 6, 0)
        val morningSlot = slot(id = 21L, name = "Morning", idealTime = "07:30")
        coEvery { medicationSlotDao.getActiveOnce() } returns listOf(morningSlot)
        coEvery { medicationSlotDao.getMedicationIdsForSlotOnce(21L) } returns emptyList()

        val result = projector.projectAll(nowMillis = now)

        val slotRows = result.filter { it.source == ProjectedNotification.Source.MEDICATION_SLOT_CLOCK }
        assertEquals(7, slotRows.size)
        val expectedFirst = baseInstant(2026, Calendar.MAY, 1, 7, 30)
        assertEquals(expectedFirst, slotRows[0].triggerAtMillis)
        assertEquals("Morning Medications", slotRows[0].title)
    }

    @Test
    fun `slot CLOCK does NOT fan out per linked medication when no override or opt-in`() = runBlocking {
        // Mirrors MedicationClockRescheduler.rescheduleAll: with no
        // overrides and no per-med reminderMode opt-in, only the slot-level
        // alarm fires. The legacy projector behaviour was to emit one row
        // per linked med which over-reported the actual notification count.
        val now = baseInstant(2026, Calendar.MAY, 1, 6, 0)
        val morningSlot = slot(id = 22L, name = "Morning", idealTime = "08:15")
        val vitamin = medication(id = 101L, name = "Vitamin D", scheduleMode = "TIMES_OF_DAY")
        val statin = medication(id = 102L, name = "Statin", scheduleMode = "TIMES_OF_DAY")
        coEvery { medicationSlotDao.getActiveOnce() } returns listOf(morningSlot)
        coEvery { medicationSlotDao.getMedicationIdsForSlotOnce(22L) } returns listOf(101L, 102L)
        coEvery { medicationDao.getByIdOnce(101L) } returns vitamin
        coEvery { medicationDao.getByIdOnce(102L) } returns statin
        coEvery { medicationDao.getActiveOnce() } returns listOf(vitamin, statin)
        coEvery { medicationSlotDao.getSlotIdsForMedicationOnce(101L) } returns listOf(22L)
        coEvery { medicationSlotDao.getSlotIdsForMedicationOnce(102L) } returns listOf(22L)

        val result = projector.projectAll(nowMillis = now)

        val slotRows = result.filter { it.source == ProjectedNotification.Source.MEDICATION_SLOT_CLOCK }
        assertEquals(7, slotRows.size)
        assertTrue(slotRows.all { it.title == "Morning Medications" })
    }

    @Test
    fun `slot CLOCK projects per-(med,slot) row only when override differs from slot ideal time`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 6, 0)
        val morningSlot = slot(id = 23L, name = "Morning", idealTime = "08:00")
        val vitamin = medication(id = 201L, name = "Vitamin D", scheduleMode = "TIMES_OF_DAY")
        coEvery { medicationSlotDao.getActiveOnce() } returns listOf(morningSlot)
        coEvery { medicationSlotDao.getMedicationIdsForSlotOnce(23L) } returns listOf(201L)
        coEvery { medicationDao.getByIdOnce(201L) } returns vitamin
        coEvery { medicationDao.getActiveOnce() } returns listOf(vitamin)
        coEvery { medicationSlotDao.getSlotIdsForMedicationOnce(201L) } returns listOf(23L)
        coEvery { medicationSlotOverrideDao.getAllOnce() } returns listOf(
            override(medicationId = 201L, slotId = 23L, overrideIdealTime = "09:30")
        )

        val result = projector.projectAll(nowMillis = now)

        val slotLevel = result.filter {
            it.source == ProjectedNotification.Source.MEDICATION_SLOT_CLOCK && it.title == "Morning Medications"
        }
        val perMed = result.filter {
            it.source == ProjectedNotification.Source.MEDICATION_SLOT_CLOCK && it.title == "Vitamin D"
        }
        assertEquals(7, slotLevel.size)
        assertEquals(7, perMed.size)
        // Per-med row fires at the override time, not the slot ideal time.
        val expectedPerMedFirst = baseInstant(2026, Calendar.MAY, 1, 9, 30)
        assertEquals(expectedPerMedFirst, perMed[0].triggerAtMillis)
    }

    @Test
    fun `slot CLOCK skips archived linked medications when fanning out per-med rows`() = runBlocking {
        // getByIdOnce does not filter is_archived; the production
        // reschedulers and receiver guard with !med.isArchived. The
        // projector must mirror that filter so the log doesn't list
        // alarms for archived meds that will never fire.
        val now = baseInstant(2026, Calendar.MAY, 1, 6, 0)
        val morningSlot = slot(id = 24L, name = "Morning", idealTime = "08:00")
        val active = medication(id = 301L, name = "Active", scheduleMode = "TIMES_OF_DAY")
        val archived = medication(
            id = 302L,
            name = "Archived",
            scheduleMode = "TIMES_OF_DAY",
            isArchived = true
        )
        coEvery { medicationSlotDao.getActiveOnce() } returns listOf(morningSlot)
        coEvery { medicationSlotDao.getMedicationIdsForSlotOnce(24L) } returns listOf(301L, 302L)
        coEvery { medicationDao.getByIdOnce(301L) } returns active
        coEvery { medicationDao.getByIdOnce(302L) } returns archived
        coEvery { medicationDao.getActiveOnce() } returns listOf(active)
        coEvery { medicationSlotDao.getSlotIdsForMedicationOnce(301L) } returns listOf(24L)
        coEvery { medicationSlotOverrideDao.getAllOnce() } returns listOf(
            override(medicationId = 301L, slotId = 24L, overrideIdealTime = "09:00"),
            override(medicationId = 302L, slotId = 24L, overrideIdealTime = "09:00")
        )

        val result = projector.projectAll(nowMillis = now)

        val perMedTitles = result
            .filter { it.source == ProjectedNotification.Source.MEDICATION_SLOT_CLOCK }
            .mapNotNull { it.title.takeIf { t -> t != "Morning Medications" } }
            .distinct()
        assertEquals(listOf("Active"), perMedTitles)
    }

    @Test
    fun `slot-linked medication is skipped by legacy projection to avoid double-count`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 6, 0)
        val morningSlot = slot(id = 31L, name = "Morning", idealTime = "07:30")
        val vitamin = medication(
            id = 201L,
            name = "Vitamin D",
            scheduleMode = "TIMES_OF_DAY",
            timesOfDay = "morning"
        )
        coEvery { medicationDao.getActiveOnce() } returns listOf(vitamin)
        coEvery { medicationDao.getByIdOnce(201L) } returns vitamin
        coEvery { medicationSlotDao.getActiveOnce() } returns listOf(morningSlot)
        coEvery { medicationSlotDao.getSlotIdsForMedicationOnce(201L) } returns listOf(31L)
        coEvery { medicationSlotDao.getMedicationIdsForSlotOnce(31L) } returns listOf(201L)

        val result = projector.projectAll(nowMillis = now)

        assertTrue(result.none { it.source == ProjectedNotification.Source.MEDICATION })
        val slotRows = result.filter { it.source == ProjectedNotification.Source.MEDICATION_SLOT_CLOCK }
        assertEquals(7, slotRows.size)
        val expectedFirst = baseInstant(2026, Calendar.MAY, 1, 7, 30)
        assertEquals(expectedFirst, slotRows[0].triggerAtMillis)
    }

    @Test
    fun `slot INTERVAL anchors on most recent dose plus interval minutes`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        val lastDose = baseInstant(2026, Calendar.MAY, 1, 9, 0)
        val intervalSlot = slot(
            id = 41L,
            name = "Rolling",
            idealTime = "00:00",
            reminderMode = "INTERVAL",
            reminderIntervalMinutes = 90
        )
        coEvery { medicationSlotDao.getActiveOnce() } returns listOf(intervalSlot)
        coEvery { medicationSlotDao.getMedicationIdsForSlotOnce(41L) } returns emptyList()
        coEvery { medicationDoseDao.getMostRecentDoseAnyOnce() } returns dose(0L, lastDose)

        val result = projector.projectAll(nowMillis = now)

        val intervalRows = result.filter {
            it.source == ProjectedNotification.Source.MEDICATION_SLOT_INTERVAL
        }
        assertEquals(1, intervalRows.size)
        assertEquals(lastDose + 90L * 60_000L, intervalRows[0].triggerAtMillis)
    }

    @Test
    fun `slot INTERVAL bootstraps to now plus interval when no dose exists`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        val intervalSlot = slot(
            id = 42L,
            name = "Rolling",
            idealTime = "00:00",
            reminderMode = "INTERVAL",
            reminderIntervalMinutes = 60
        )
        coEvery { medicationSlotDao.getActiveOnce() } returns listOf(intervalSlot)
        coEvery { medicationSlotDao.getMedicationIdsForSlotOnce(42L) } returns emptyList()

        val result = projector.projectAll(nowMillis = now)

        val intervalRows = result.filter {
            it.source == ProjectedNotification.Source.MEDICATION_SLOT_INTERVAL
        }
        assertEquals(1, intervalRows.size)
        assertEquals(now + 60L * 60_000L, intervalRows[0].triggerAtMillis)
    }

    @Test
    fun `slot INTERVAL emits one row per slot regardless of linked medication count`() = runBlocking {
        // showSlotIntervalReminder fires exactly one notification per slot
        // titled "{slot} Medications" (it never fans out per linked med),
        // so the projector must collapse the per-(slot, med) cross product
        // it used to emit.
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        val intervalSlot = slot(
            id = 43L,
            name = "Rolling",
            idealTime = "00:00",
            reminderMode = "INTERVAL",
            reminderIntervalMinutes = 90
        )
        val a = medication(id = 401L, name = "Med A", scheduleMode = "INTERVAL")
        val b = medication(id = 402L, name = "Med B", scheduleMode = "INTERVAL")
        coEvery { medicationSlotDao.getActiveOnce() } returns listOf(intervalSlot)
        coEvery { medicationSlotDao.getMedicationIdsForSlotOnce(43L) } returns listOf(401L, 402L)
        coEvery { medicationDao.getByIdOnce(401L) } returns a
        coEvery { medicationDao.getByIdOnce(402L) } returns b

        val result = projector.projectAll(nowMillis = now)

        val intervalRows = result.filter {
            it.source == ProjectedNotification.Source.MEDICATION_SLOT_INTERVAL
        }
        assertEquals(1, intervalRows.size)
        assertEquals("Rolling Medications", intervalRows[0].title)
    }

    @Test
    fun `briefing worker projects daily occurrences when enabled`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 6, 0)
        every { notificationPreferences.dailyBriefingEnabled } returns flowOf(true)
        every { notificationPreferences.briefingMorningHour } returns flowOf(8)

        val result = projector.projectAll(nowMillis = now)

        assertEquals(7, result.size)
        result.forEach {
            assertEquals(ProjectedNotification.Source.BRIEFING, it.source)
            assertEquals("Good Morning", it.title)
        }
    }

    @Test
    fun `weekly review projects only when both auto-generate and notification flags enabled`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        // Auto-generate ON but notification OFF — should not project
        every { notificationPreferences.weeklyReviewAutoGenerateEnabled } returns flowOf(true)
        every { notificationPreferences.weeklyReviewNotificationEnabled } returns flowOf(false)

        val result1 = projector.projectAll(nowMillis = now)
        assertFalse(result1.any { it.source == ProjectedNotification.Source.WEEKLY_REVIEW })

        // Both ON — should project the next Sunday at 8 PM (within horizon)
        every { notificationPreferences.weeklyReviewNotificationEnabled } returns flowOf(true)
        val result2 = projector.projectAll(nowMillis = now)
        assertNotNull(result2.firstOrNull { it.source == ProjectedNotification.Source.WEEKLY_REVIEW })
    }

    @Test
    fun `taskRemindersEnabled disabled suppresses all task projections`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        val due = baseInstant(2026, Calendar.MAY, 1, 14, 0)
        coEvery { taskDao.getIncompleteTasksWithReminders() } returns listOf(
            task(id = 1L, title = "Submit Report", dueDate = due, reminderOffset = 30 * 60 * 1000L)
        )
        every { notificationPreferences.taskRemindersEnabled } returns flowOf(false)

        val result = projector.projectAll(nowMillis = now)

        assertTrue(result.none { it.source == ProjectedNotification.Source.TASK_REMINDER })
    }

    @Test
    fun `medicationRemindersEnabled disabled suppresses all medication-channel projections`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 6, 0)
        val morningSlot = slot(id = 51L, name = "Morning", idealTime = "07:30")
        val intervalSlot = slot(
            id = 52L,
            name = "Rolling",
            idealTime = "00:00",
            reminderMode = "INTERVAL",
            reminderIntervalMinutes = 60
        )
        val legacyMed = medication(
            id = 501L,
            name = "Vitamin D",
            scheduleMode = "TIMES_OF_DAY",
            timesOfDay = "morning"
        )
        coEvery { medicationDao.getActiveOnce() } returns listOf(legacyMed)
        coEvery { medicationSlotDao.getActiveOnce() } returns listOf(morningSlot, intervalSlot)
        coEvery { medicationPreferences.getScheduleModeOnce() } returns MedicationScheduleMode.SPECIFIC_TIMES
        coEvery { medicationPreferences.getSpecificTimesOnce() } returns setOf("09:00")
        every { notificationPreferences.medicationRemindersEnabled } returns flowOf(false)

        val result = projector.projectAll(nowMillis = now)

        val medChannelSources = setOf(
            ProjectedNotification.Source.MEDICATION,
            ProjectedNotification.Source.MEDICATION_SLOT_CLOCK,
            ProjectedNotification.Source.MEDICATION_SLOT_INTERVAL,
            ProjectedNotification.Source.MEDICATION_LEGACY_SPECIFIC_TIME
        )
        assertTrue(result.none { it.source in medChannelSources })
    }

    @Test
    fun `legacy medication projection title is bare med name without 'Heads Up' suffix`() = runBlocking {
        // showMedicationReminder builds the title as "$habitName$doseInfo"
        // — no "— Heads Up" suffix. The projector previously appended one,
        // misrepresenting what the user actually sees in the shade.
        val now = baseInstant(2026, Calendar.MAY, 1, 6, 0)
        coEvery { medicationDao.getActiveOnce() } returns listOf(
            medication(
                id = 511L,
                name = "Vitamin D",
                scheduleMode = "TIMES_OF_DAY",
                timesOfDay = "morning"
            )
        )

        val result = projector.projectAll(nowMillis = now)

        val legacyRows = result.filter { it.source == ProjectedNotification.Source.MEDICATION }
        assertTrue(legacyRows.isNotEmpty())
        assertTrue(legacyRows.all { it.title == "Vitamin D" })
    }

    @Test
    fun `legacy specific-times source is renamed to MEDICATION_LEGACY_SPECIFIC_TIME`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 6, 0)
        coEvery { medicationPreferences.getScheduleModeOnce() } returns MedicationScheduleMode.SPECIFIC_TIMES
        coEvery { medicationPreferences.getSpecificTimesOnce() } returns setOf("09:00")

        val result = projector.projectAll(nowMillis = now)

        assertTrue(result.any {
            it.source == ProjectedNotification.Source.MEDICATION_LEGACY_SPECIFIC_TIME
        })
    }

    @Test
    fun `cognitive load overload check projects same cadence as overload check`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        every { notificationPreferences.overloadAlertsEnabled } returns flowOf(true)

        val result = projector.projectAll(nowMillis = now)

        val balance = result.filter { it.source == ProjectedNotification.Source.OVERLOAD_CHECK }
        val cognitive = result.filter {
            it.source == ProjectedNotification.Source.COGNITIVE_LOAD_OVERLOAD_CHECK
        }
        assertEquals(7, balance.size)
        assertEquals(7, cognitive.size)
        // Both fire at the same wall-clock time per
        // NotificationWorkerScheduler.applyOverloadCheck.
        for (i in 0 until 7) {
            assertEquals(balance[i].triggerAtMillis, cognitive[i].triggerAtMillis)
        }
    }

    @Test
    fun `weekly analytics projects sunday at 7pm when notification enabled`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        every {
            notificationPreferences.weeklyAnalyticsNotificationEnabled
        } returns flowOf(true)

        val result = projector.projectAll(nowMillis = now)

        val analytics = result.filter {
            it.source == ProjectedNotification.Source.WEEKLY_ANALYTICS
        }
        assertTrue(analytics.isNotEmpty())
        // The default schedule fixture targets day=7 (Sunday) at 19:00.
        val cal = Calendar.getInstance().apply { timeInMillis = analytics[0].triggerAtMillis }
        assertEquals(Calendar.SUNDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(19, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun `worker projection respects disabled toggles`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 10, 0)
        // All workers OFF by default
        val result = projector.projectAll(nowMillis = now)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mixed sources are sorted chronologically`() = runBlocking {
        val now = baseInstant(2026, Calendar.MAY, 1, 6, 0)
        coEvery { habitDao.getHabitsWithDailyTimeReminder() } returns listOf(
            habit(id = 1L, name = "Morning", reminderTime = 9 * 60 * 60 * 1000L),
            habit(id = 2L, name = "Evening", reminderTime = 20 * 60 * 60 * 1000L)
        )
        every { notificationPreferences.dailyBriefingEnabled } returns flowOf(true)
        every { notificationPreferences.briefingMorningHour } returns flowOf(7)

        val result = projector.projectAll(nowMillis = now)

        assertTrue(result.size >= 14)
        for (i in 1 until result.size) {
            assertTrue(result[i].triggerAtMillis >= result[i - 1].triggerAtMillis)
        }
    }

    private fun baseInstant(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.clear()
        cal.set(year, month, day, hour, minute, 0)
        return cal.timeInMillis
    }

    private fun task(
        id: Long,
        title: String,
        dueDate: Long?,
        reminderOffset: Long?,
        description: String? = "Body $id"
    ): TaskEntity = TaskEntity(
        id = id,
        title = title,
        description = description,
        dueDate = dueDate,
        dueTime = null,
        reminderOffset = reminderOffset
    )

    private fun habit(
        id: Long,
        name: String,
        reminderTime: Long? = null,
        reminderIntervalMillis: Long? = null,
        reminderTimesPerDay: Int = 1
    ): HabitEntity = HabitEntity(
        id = id,
        name = name,
        description = null,
        reminderTime = reminderTime,
        reminderIntervalMillis = reminderIntervalMillis,
        reminderTimesPerDay = reminderTimesPerDay
    )

    private fun completion(habitId: Long, completedAt: Long): HabitCompletionEntity =
        HabitCompletionEntity(
            id = 0,
            habitId = habitId,
            completedDate = completedAt,
            completedDateLocal = "2026-05-01",
            completedAt = completedAt
        )

    private fun medication(
        id: Long,
        name: String,
        scheduleMode: String,
        timesOfDay: String? = null,
        specificTimes: String? = null,
        intervalMillis: Long? = null,
        isArchived: Boolean = false
    ): MedicationEntity = MedicationEntity(
        id = id,
        name = name,
        scheduleMode = scheduleMode,
        timesOfDay = timesOfDay,
        specificTimes = specificTimes,
        intervalMillis = intervalMillis,
        isArchived = isArchived
    )

    private fun override(
        medicationId: Long,
        slotId: Long,
        overrideIdealTime: String? = null
    ): MedicationSlotOverrideEntity = MedicationSlotOverrideEntity(
        medicationId = medicationId,
        slotId = slotId,
        overrideIdealTime = overrideIdealTime
    )

    private fun dose(medicationId: Long, takenAt: Long): MedicationDoseEntity =
        MedicationDoseEntity(
            id = 0,
            medicationId = medicationId,
            slotKey = "interval",
            takenAt = takenAt,
            takenDateLocal = "2026-05-01"
        )

    private fun slot(
        id: Long,
        name: String,
        idealTime: String,
        reminderMode: String? = null,
        reminderIntervalMinutes: Int? = null,
        isActive: Boolean = true
    ): MedicationSlotEntity = MedicationSlotEntity(
        id = id,
        name = name,
        idealTime = idealTime,
        reminderMode = reminderMode,
        reminderIntervalMinutes = reminderIntervalMinutes,
        isActive = isActive
    )
}
