package com.averycorp.prismtask.data.export

import com.averycorp.prismtask.data.preferences.ApiNetworkConfig
import com.averycorp.prismtask.data.preferences.BatchUndoConfig
import com.averycorp.prismtask.data.preferences.BurnoutWeights
import com.averycorp.prismtask.data.preferences.EditorFieldRows
import com.averycorp.prismtask.data.preferences.EnergyPomodoroConfig
import com.averycorp.prismtask.data.preferences.ExtractorConfig
import com.averycorp.prismtask.data.preferences.GoodEnoughTimerConfig
import com.averycorp.prismtask.data.preferences.HabitReminderFallback
import com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords
import com.averycorp.prismtask.data.preferences.MoodCorrelationConfig
import com.averycorp.prismtask.data.preferences.MorningCheckInPromptCutoff
import com.averycorp.prismtask.data.preferences.OverloadCheckSchedule
import com.averycorp.prismtask.data.preferences.ProductivityWeights
import com.averycorp.prismtask.data.preferences.ProductivityWidgetThresholds
import com.averycorp.prismtask.data.preferences.QuickAddRows
import com.averycorp.prismtask.data.preferences.ReengagementConfig
import com.averycorp.prismtask.data.preferences.RefillUrgencyConfig
import com.averycorp.prismtask.data.preferences.SearchPreview
import com.averycorp.prismtask.data.preferences.SelfCareTierDefaults
import com.averycorp.prismtask.data.preferences.SmartDefaultsConfig
import com.averycorp.prismtask.data.preferences.SuggestionConfig
import com.averycorp.prismtask.data.preferences.UrgencyBands
import com.averycorp.prismtask.data.preferences.UrgencyWindows
import com.averycorp.prismtask.data.preferences.WeeklySummarySchedule
import com.averycorp.prismtask.data.preferences.WidgetRefreshConfig
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies every [AdvancedTuningPreferences] config data class round-trips
 * cleanly through Gson — the same path
 * [DataExporter.exportAdvancedTuningConfig] / [DataImporter.importAdvancedTuningConfig]
 * use to serialize each sub-key. Catches typos in field names and
 * non-Gson-friendly types added to any of the 25 configs.
 */
class AdvancedTuningRoundTripTest {
    private val gson = Gson()

    private inline fun <reified T> roundTrip(value: T): T =
        gson.fromJson(gson.toJsonTree(value), T::class.java)

    @Test
    fun urgencyBands_roundTrip() {
        val v = UrgencyBands(critical = 0.85f, high = 0.55f, medium = 0.25f)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun urgencyWindows_roundTrip() {
        val v = UrgencyWindows(overdueCeilingDays = 14, imminentWindowDays = 3)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun burnoutWeights_roundTrip() {
        val v = BurnoutWeights(
            workMax = 30,
            overdueMax = 25,
            selfCareMax = 18,
            medicationMax = 12,
            streakMax = 8,
            restDeficitMax = 7,
            restDeficitDays = 3
        )
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun productivityWeights_roundTrip() {
        val v = ProductivityWeights(
            taskWeight = 0.35f,
            onTimeWeight = 0.30f,
            habitWeight = 0.20f,
            estimationWeight = 0.15f,
            trendThreshold = 4.0f
        )
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun moodCorrelation_roundTrip() {
        val v = MoodCorrelationConfig(minObservations = 10, strongThreshold = 0.6f, moderateThreshold = 0.4f)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun refillUrgency_roundTrip() {
        val v = RefillUrgencyConfig(urgentDays = 5, upcomingDays = 14)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun energyPomodoro_roundTrip() {
        val v = EnergyPomodoroConfig(
            veryLowWork = 12, veryLowBreak = 8, veryLowLong = 18,
            lowWork = 18, lowBreak = 8, lowLong = 22,
            mediumWork = 28, mediumBreak = 6, mediumLong = 18,
            highWork = 40, highBreak = 5, highLong = 14,
            veryHighWork = 50, veryHighBreak = 4, veryHighLong = 12
        )
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun goodEnoughTimer_roundTrip() {
        val v =
            GoodEnoughTimerConfig(gracePeriodMinutes = 5, nudgeCooldownMinutes = 12, dialogCooldownMinutes = 18, extensionMinutes = 7)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun suggestion_roundTrip() {
        val v = SuggestionConfig(tagThreshold = 0.18f, projectThreshold = 0.32f, maxResults = 5)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun extractor_roundTrip() {
        val v = ExtractorConfig(maxInputChars = 8000, maxTitleChars = 100)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun smartDefaults_roundTrip() {
        val v = SmartDefaultsConfig(minHistory = 8, durationGranularityMinutes = 10)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun morningCheckInCutoff_roundTrip() {
        val v = MorningCheckInPromptCutoff(windowHours = 8)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun lifeCategoryKeywords_roundTrip() {
        val v =
            LifeCategoryCustomKeywords(work = "lab,paper", personal = "kid,grocery", selfCare = "yoga", health = "appt")
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun weeklySummary_roundTrip() {
        val v =
            WeeklySummarySchedule(
                dayOfWeek = 1,
                taskSummaryHour = 18,
                taskSummaryMinute = 15,
                habitSummaryHour = 18,
                habitSummaryMinute = 45,
                reviewHour = 19,
                reviewMinute = 30,
                eveningSummaryHour = 21,
                analyticsSummaryHour = 17,
                analyticsSummaryMinute = 45
            )
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun reengagement_roundTrip() {
        val v = ReengagementConfig(absenceDays = 3, maxNudges = 2)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun overloadCheck_roundTrip() {
        val v = OverloadCheckSchedule(hourOfDay = 17, minute = 30)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun batchUndo_roundTrip() {
        val v = BatchUndoConfig(tailDays = 14)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun habitReminderFallback_roundTrip() {
        val v = HabitReminderFallback(hour = 9, minute = 15)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun apiNetwork_roundTrip() {
        val v = ApiNetworkConfig(timeoutSeconds = 45, retryAttempts = 4)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun widgetRefresh_roundTrip() {
        val v = WidgetRefreshConfig(intervalMinutes = 60)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun productivityWidget_roundTrip() {
        val v = ProductivityWidgetThresholds(greenScore = 85, orangeScore = 65)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun editorFieldRows_roundTrip() {
        val v = EditorFieldRows(descriptionRows = 8, notesRows = 12)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun quickAddRows_roundTrip() {
        val v = QuickAddRows(maxLines = 7)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun searchPreview_roundTrip() {
        val v = SearchPreview(previewLines = 4)
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun selfCareTierDefaults_roundTrip() {
        val v =
            SelfCareTierDefaults(morning = "survival", bedtime = "full", medication = "essential", housework = "deep")
        assertEquals(v, roundTrip(v))
    }
}
