package com.averycorp.prismtask.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
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
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.UrgencyBands
import com.averycorp.prismtask.data.preferences.UrgencyWindows
import com.averycorp.prismtask.data.preferences.WeeklySummarySchedule
import com.averycorp.prismtask.data.preferences.WidgetRefreshConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel that backs the [AdvancedTuningScreen] — exposes every
 * config flow on [AdvancedTuningPreferences] as a [StateFlow] and
 * forwards user edits back to the matching setter. Kept thin: no
 * derived state, no computation.
 */
@HiltViewModel
class AdvancedTuningViewModel
@Inject
constructor(
    private val prefs: AdvancedTuningPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences
) : ViewModel() {
    val urgencyBands: StateFlow<UrgencyBands> =
        prefs.getUrgencyBands().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UrgencyBands())

    val urgencyWindows: StateFlow<UrgencyWindows> =
        prefs.getUrgencyWindows().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UrgencyWindows())

    val burnoutWeights: StateFlow<BurnoutWeights> =
        prefs.getBurnoutWeights().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BurnoutWeights())

    val productivityWeights: StateFlow<ProductivityWeights> =
        prefs.getProductivityWeights()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProductivityWeights())

    val moodCorrelation: StateFlow<MoodCorrelationConfig> =
        prefs.getMoodCorrelationConfig()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MoodCorrelationConfig())

    val refillUrgency: StateFlow<RefillUrgencyConfig> =
        prefs.getRefillUrgencyConfig()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RefillUrgencyConfig())

    val energyPomodoro: StateFlow<EnergyPomodoroConfig> =
        prefs.getEnergyPomodoroConfig()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EnergyPomodoroConfig())

    val goodEnoughTimer: StateFlow<GoodEnoughTimerConfig> =
        prefs.getGoodEnoughTimerConfig()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GoodEnoughTimerConfig())

    val suggestion: StateFlow<SuggestionConfig> =
        prefs.getSuggestionConfig()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SuggestionConfig())

    val extractor: StateFlow<ExtractorConfig> =
        prefs.getExtractorConfig()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExtractorConfig())

    val smartDefaults: StateFlow<SmartDefaultsConfig> =
        prefs.getSmartDefaultsConfig()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SmartDefaultsConfig())

    val morningCheckIn: StateFlow<MorningCheckInPromptCutoff> =
        prefs.getMorningCheckInPromptCutoff()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MorningCheckInPromptCutoff())

    private val startOfDayHour: StateFlow<Int> =
        taskBehaviorPreferences.getStartOfDay()
            .map { it.hour }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 4)

    /**
     * Live validation for the persisted morning check-in cutoff against the
     * user's Start-of-Day. Drives the inline error caption in the Advanced
     * Tuning settings screen so users learn why an attempted slider value
     * didn't stick.
     */
    val morningCheckInValidation: StateFlow<MorningCheckInCutoffValidation> =
        combine(morningCheckIn, startOfDayHour) { cutoff, sod ->
            validateMorningCheckInCutoff(sodHour = sod, cutoffHour = cutoff.latestHour)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MorningCheckInCutoffValidation.Valid)

    val lifeCategoryKeywords: StateFlow<LifeCategoryCustomKeywords> =
        prefs.getLifeCategoryCustomKeywords()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LifeCategoryCustomKeywords())

    val weeklySummary: StateFlow<WeeklySummarySchedule> =
        prefs.getWeeklySummarySchedule()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeeklySummarySchedule())

    val reengagement: StateFlow<ReengagementConfig> =
        prefs.getReengagementConfig()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReengagementConfig())

    val overloadCheck: StateFlow<OverloadCheckSchedule> =
        prefs.getOverloadCheckSchedule()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverloadCheckSchedule())

    val batchUndo: StateFlow<BatchUndoConfig> =
        prefs.getBatchUndoConfig()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BatchUndoConfig())

    val habitReminderFallback: StateFlow<HabitReminderFallback> =
        prefs.getHabitReminderFallback()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HabitReminderFallback())

    val apiNetwork: StateFlow<ApiNetworkConfig> =
        prefs.getApiNetworkConfig()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ApiNetworkConfig())

    val widgetRefresh: StateFlow<WidgetRefreshConfig> =
        prefs.getWidgetRefreshConfig()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WidgetRefreshConfig())

    val productivityWidget: StateFlow<ProductivityWidgetThresholds> =
        prefs.getProductivityWidgetThresholds()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProductivityWidgetThresholds())

    val editorFieldRows: StateFlow<EditorFieldRows> =
        prefs.getEditorFieldRows()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EditorFieldRows())

    val quickAddRows: StateFlow<QuickAddRows> =
        prefs.getQuickAddRows()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), QuickAddRows())

    val searchPreview: StateFlow<SearchPreview> =
        prefs.getSearchPreview()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchPreview())

    val selfCareTierDefaults: StateFlow<SelfCareTierDefaults> =
        prefs.getSelfCareTierDefaults()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SelfCareTierDefaults())

    fun setUrgencyBands(value: UrgencyBands) = launchSet { prefs.setUrgencyBands(value) }
    fun setUrgencyWindows(value: UrgencyWindows) = launchSet { prefs.setUrgencyWindows(value) }
    fun setBurnoutWeights(value: BurnoutWeights) = launchSet { prefs.setBurnoutWeights(value) }
    fun setProductivityWeights(value: ProductivityWeights) = launchSet { prefs.setProductivityWeights(value) }
    fun setMoodCorrelation(value: MoodCorrelationConfig) = launchSet { prefs.setMoodCorrelationConfig(value) }
    fun setRefillUrgency(value: RefillUrgencyConfig) = launchSet { prefs.setRefillUrgencyConfig(value) }
    fun setEnergyPomodoro(value: EnergyPomodoroConfig) = launchSet { prefs.setEnergyPomodoroConfig(value) }
    fun setGoodEnoughTimer(value: GoodEnoughTimerConfig) = launchSet { prefs.setGoodEnoughTimerConfig(value) }
    fun setSuggestion(value: SuggestionConfig) = launchSet { prefs.setSuggestionConfig(value) }
    fun setExtractor(value: ExtractorConfig) = launchSet { prefs.setExtractorConfig(value) }
    fun setSmartDefaults(value: SmartDefaultsConfig) = launchSet { prefs.setSmartDefaultsConfig(value) }
    fun setMorningCheckIn(value: MorningCheckInPromptCutoff) {
        val sod = startOfDayHour.value
        if (validateMorningCheckInCutoff(sodHour = sod, cutoffHour = value.latestHour) !is MorningCheckInCutoffValidation.Valid) {
            return
        }
        launchSet { prefs.setMorningCheckInPromptCutoff(value) }
    }
    fun setLifeCategoryKeywords(value: LifeCategoryCustomKeywords) = launchSet {
        prefs.setLifeCategoryCustomKeywords(value)
    }
    fun setWeeklySummary(value: WeeklySummarySchedule) = launchSet { prefs.setWeeklySummarySchedule(value) }
    fun setReengagement(value: ReengagementConfig) = launchSet { prefs.setReengagementConfig(value) }
    fun setOverloadCheck(value: OverloadCheckSchedule) = launchSet { prefs.setOverloadCheckSchedule(value) }
    fun setBatchUndo(value: BatchUndoConfig) = launchSet { prefs.setBatchUndoConfig(value) }
    fun setHabitReminderFallback(value: HabitReminderFallback) = launchSet { prefs.setHabitReminderFallback(value) }
    fun setApiNetwork(value: ApiNetworkConfig) = launchSet { prefs.setApiNetworkConfig(value) }
    fun setWidgetRefresh(value: WidgetRefreshConfig) = launchSet { prefs.setWidgetRefreshConfig(value) }
    fun setProductivityWidget(value: ProductivityWidgetThresholds) = launchSet {
        prefs.setProductivityWidgetThresholds(value)
    }
    fun setEditorFieldRows(value: EditorFieldRows) = launchSet { prefs.setEditorFieldRows(value) }
    fun setQuickAddRows(value: QuickAddRows) = launchSet { prefs.setQuickAddRows(value) }
    fun setSearchPreview(value: SearchPreview) = launchSet { prefs.setSearchPreview(value) }
    fun setSelfCareTierDefaults(value: SelfCareTierDefaults) = launchSet { prefs.setSelfCareTierDefaults(value) }

    private inline fun launchSet(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

/**
 * Validation outcome for the morning check-in prompt cutoff against the
 * user's Start-of-Day. The Settings UI renders the [Invalid.reason]
 * inline so users see why a slider value didn't persist.
 */
sealed class MorningCheckInCutoffValidation {
    data object Valid : MorningCheckInCutoffValidation()
    data class Invalid(val reason: String) : MorningCheckInCutoffValidation()
}

/**
 * Pure-function validator for the morning check-in cutoff against SoD.
 *
 * Wrap-around windows (late SoD, early cutoff — e.g. SoD=22, cutoff=2)
 * are accepted because [MorningCheckInBannerDecider] handles them via
 * modular arithmetic. Same-hour values and cutoffs that fall before an
 * already-early SoD are rejected as user error: same-hour collapses
 * the morning window to nothing semantically (or 24h via the decider's
 * fallback, neither of which is a useful "morning" prompt), and an
 * early-SoD-with-earlier-cutoff almost certainly means the user typed
 * the wrong number rather than meaning a 23-hour wrap-around window.
 */
fun validateMorningCheckInCutoff(sodHour: Int, cutoffHour: Int): MorningCheckInCutoffValidation =
    when {
        cutoffHour == sodHour ->
            MorningCheckInCutoffValidation.Invalid("Invalid Cutoff — Must Come After Start-of-Day.")
        cutoffHour < sodHour && sodHour < EVENING_SOD_HOUR ->
            MorningCheckInCutoffValidation.Invalid("Invalid Cutoff — Must Come After Start-of-Day.")
        else -> MorningCheckInCutoffValidation.Valid
    }

private const val EVENING_SOD_HOUR = 12
