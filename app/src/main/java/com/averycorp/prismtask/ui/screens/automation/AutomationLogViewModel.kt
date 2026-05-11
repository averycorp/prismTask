package com.averycorp.prismtask.ui.screens.automation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.AutomationLogEntity
import com.averycorp.prismtask.data.repository.AutomationRuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AutomationLogViewModel @Inject constructor(private val ruleRepository: AutomationRuleRepository, savedStateHandle: SavedStateHandle) :
    ViewModel() {

    private val ruleIdFilter: Long? = savedStateHandle.get<String?>("ruleId")?.toLongOrNull()

    val rows: StateFlow<List<AutomationLogRow>> = combine(
        ruleRepository.observeAll(),
        if (ruleIdFilter != null) {
            ruleRepository.observeLogsForRule(ruleIdFilter)
        } else {
            ruleRepository.observeRecentLogs()
        }
    ) { rules, logs ->
        val ruleNameById = rules.associateBy({ it.id }, { it.name })
        logs.map { it.toRow(ruleNameById[it.ruleId] ?: "Rule #${it.ruleId}") }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun AutomationLogEntity.toRow(ruleName: String): AutomationLogRow = AutomationLogRow(
        id = id,
        ruleId = ruleId,
        ruleName = ruleName,
        firedAt = firedAt,
        conditionPassed = conditionPassed,
        durationMs = durationMs,
        chainDepth = chainDepth,
        actionsExecutedJson = actionsExecutedJson,
        errorsJson = errorsJson
    )
}

data class AutomationLogRow(
    val id: Long,
    val ruleId: Long,
    val ruleName: String,
    val firedAt: Long,
    val conditionPassed: Boolean,
    val durationMs: Long,
    val chainDepth: Int,
    val actionsExecutedJson: String?,
    val errorsJson: String?
)
