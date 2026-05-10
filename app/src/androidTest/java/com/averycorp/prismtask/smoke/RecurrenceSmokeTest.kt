package com.averycorp.prismtask.smoke

import com.averycorp.prismtask.data.calendar.CalendarPushDispatcher
import com.averycorp.prismtask.data.local.converter.RecurrenceConverter
import com.averycorp.prismtask.data.local.database.DatabaseTransactionRunner
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.EisenhowerPrefs
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.remote.EisenhowerClassifier
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.data.repository.TaskCompletionRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.automation.AutomationEventBus
import com.averycorp.prismtask.domain.model.RecurrenceRule
import com.averycorp.prismtask.domain.model.RecurrenceType
import com.averycorp.prismtask.notifications.ReminderScheduler
import com.averycorp.prismtask.widget.WidgetUpdateManager
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Smoke tests for recurrence. Uses the real Room database (seeded by
 * [SmokeTestBase]) + a TaskRepository wired with mock side-effect
 * collaborators so the completeTask() path exercises RecurrenceEngine +
 * Room inserts end-to-end.
 */
@HiltAndroidTest
class RecurrenceSmokeTest : SmokeTestBase() {
    private fun buildRepository(): TaskRepository = TaskRepository(
        transactionRunner = DatabaseTransactionRunner(database),
        taskDao = database.taskDao(),
        tagDao = database.tagDao(),
        syncTracker = mockk<SyncTracker>(relaxed = true),
        calendarPushDispatcher = mockk<CalendarPushDispatcher>(relaxed = true),
        reminderScheduler = mockk<ReminderScheduler>(relaxed = true),
        widgetUpdateManager = mockk<WidgetUpdateManager>(relaxed = true),
        taskCompletionRepository = mockk<TaskCompletionRepository>(relaxed = true),
        eisenhowerClassifier = mockk<EisenhowerClassifier>(relaxed = true),
        userPreferences = mockk<UserPreferencesDataStore> {
            every { eisenhowerFlow } returns flowOf(EisenhowerPrefs(autoClassifyEnabled = false))
        },
        automationEventBus = mockk<AutomationEventBus>(relaxed = true),
        advancedTuningPreferences = mockk(relaxed = true) {
            every { getLifeCategoryCustomKeywords() } returns flowOf(
                com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords()
            )
        },
        habitRepositoryProvider = javax.inject.Provider { mockk(relaxed = true) }
    )

    @Test
    fun completingDailyRecurringTask_createsNextOccurrence() = runBlocking {
        val repo = buildRepository()
        val rule = RecurrenceRule(type = RecurrenceType.DAILY)
        val dueDate = 1_700_000_000_000L
        val id = database.taskDao().insert(
            TaskEntity(
                title = "Daily meditation",
                dueDate = dueDate,
                recurrenceRule = RecurrenceConverter.toJson(rule)
            )
        )

        repo.completeTask(id)

        val all = database
            .taskDao()
            .getAllTasks()
            .first()
            .filter { it.title == "Daily meditation" }
        assert(all.size == 2) {
            "Expected original + next occurrence for a daily recurring task"
        }
        val original = all.single { it.id == id }
        val next = all.single { it.id != id }
        assert(original.isCompleted)
        assert(!next.isCompleted)
        assert(next.dueDate != null && next.dueDate!! > dueDate)
    }

    @Test
    fun completingMaxOccurrencesTask_doesNotCreateNextOccurrence() = runBlocking {
        val repo = buildRepository()
        val rule = RecurrenceRule(
            type = RecurrenceType.DAILY,
            maxOccurrences = 3,
            occurrenceCount = 3
        )
        val id = database.taskDao().insert(
            TaskEntity(
                title = "Limited recurrence",
                dueDate = 1_700_000_000_000L,
                recurrenceRule = RecurrenceConverter.toJson(rule)
            )
        )

        repo.completeTask(id)

        val hits = database
            .taskDao()
            .getAllTasks()
            .first()
            .filter { it.title == "Limited recurrence" }
        assert(hits.size == 1) {
            "Expected max-occurrences cap to stop recurrence after final completion"
        }
        assert(hits.single().isCompleted)
    }

    @Test
    fun completingWeeklyRecurringTask_movesToNextActiveDay() = runBlocking {
        val repo = buildRepository()
        // Monday-only weekly rule — after completing, next due should land
        // on a Monday, which is 1–7 days out depending on today's day of
        // week. The computation is done via LocalDate in the production
        // code, so the device timezone determines "today" and therefore
        // the exact gap. Widen to 1–8 days to absorb timezone variance.
        val rule = RecurrenceRule(
            type = RecurrenceType.WEEKLY,
            daysOfWeek = listOf(1)
        )
        val dueDate = 1_700_000_000_000L
        val id = database.taskDao().insert(
            TaskEntity(
                title = "Monday ritual",
                dueDate = dueDate,
                recurrenceRule = RecurrenceConverter.toJson(rule)
            )
        )

        repo.completeTask(id)

        val next = database
            .taskDao()
            .getAllTasks()
            .first()
            .filter { it.title == "Monday ritual" }
            .single { it.id != id }
        assert(next.dueDate != null)
        val gap = next.dueDate!! - dueDate
        val day = 24L * 60 * 60 * 1000
        // Between "tomorrow" (1 day) and "next Monday after today's Monday"
        // (~8 days including timezone slack).
        assert(gap in day..(8 * day)) {
            "Next weekly occurrence should land 1–8 days after the original; got ${gap / day}d"
        }
    }

    @Test
    fun completingNonRecurringTask_doesNotCreateDuplicate() = runBlocking {
        val repo = buildRepository()
        val id = database.taskDao().insert(
            TaskEntity(title = "One-off", dueDate = 1_700_000_000_000L)
        )
        repo.completeTask(id)

        val hits = database
            .taskDao()
            .getAllTasks()
            .first()
            .filter { it.title == "One-off" }
        assert(hits.size == 1) { "Non-recurring completion must not fork the task" }
        assert(hits.single().isCompleted)
    }
}
