package com.averycorp.prismtask.ui.screens.today.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.ui.components.ProGatedFeature
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.screens.settings.SettingsViewModel
import com.averycorp.prismtask.ui.screens.settings.sections.AiSection

/**
 * Today-screen bottom-sheet host for the AI hub. Replaces the former
 * Settings -> AI Features screen as the canonical surface for the master
 * Claude opt-out, the Eisenhower auto-classify toggle, and the nine AI
 * feature entry points. Reuses [AiSection] verbatim so toggle behavior
 * and privacy disclosure copy stay in lockstep with the prior settings
 * implementation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayAiHubSheet(
    navController: NavController,
    onDismiss: () -> Unit,
    onShowUpsell: (ProGatedFeature) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val eisenhowerPrefs by viewModel.eisenhowerPrefs.collectAsStateWithLifecycle()
    val aiFeaturePrefs by viewModel.aiFeaturePrefs.collectAsStateWithLifecycle()
    val perFeatureAiPrefs by viewModel.perFeatureAiPrefs.collectAsStateWithLifecycle()
    val userTier by viewModel.userTier.collectAsStateWithLifecycle()
    val isPro = userTier == UserTier.PRO

    val navigateAndDismiss: (String) -> Unit = { route ->
        onDismiss()
        navController.navigate(route)
    }

    val gatedNavigate: (ProGatedFeature, String) -> Unit = { feature, route ->
        if (isPro) {
            navigateAndDismiss(route)
        } else {
            onDismiss()
            onShowUpsell(feature)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "AI Tools",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
            )
            AiSection(
                onNavigateToEisenhower = { gatedNavigate(ProGatedFeature.EISENHOWER, PrismTaskRoute.EisenhowerMatrix.route) },
                onNavigateToSmartPomodoro = { gatedNavigate(ProGatedFeature.SMART_POMODORO, PrismTaskRoute.SmartPomodoro.route) },
                onNavigateToDailyBriefing = { gatedNavigate(ProGatedFeature.AI_BRIEFING, PrismTaskRoute.DailyBriefing.route) },
                onNavigateToWeeklyPlanner = { gatedNavigate(ProGatedFeature.WEEKLY_PLANNER, PrismTaskRoute.WeeklyPlanner.route) },
                onNavigateToTimeline = { gatedNavigate(ProGatedFeature.TIME_BLOCKING, PrismTaskRoute.Timeline.route) },
                onNavigateToPasteExtract = { gatedNavigate(ProGatedFeature.PASTE_EXTRACT, PrismTaskRoute.PasteConversation.route) },
                onNavigateToWeeklyReview = { gatedNavigate(ProGatedFeature.WEEKLY_REVIEW, PrismTaskRoute.WeeklyReview.route) },
                onNavigateToMoodAnalytics = { navigateAndDismiss(PrismTaskRoute.MoodAnalytics.route) },
                onNavigateToAiChat = { gatedNavigate(ProGatedFeature.AI_CHAT, PrismTaskRoute.AiChat.createRoute()) },
                eisenhowerAutoClassifyEnabled = eisenhowerPrefs.autoClassifyEnabled,
                onEisenhowerAutoClassifyChanged = viewModel::setEisenhowerAutoClassifyEnabled,
                aiFeaturesEnabled = aiFeaturePrefs.enabled,
                onAiFeaturesEnabledChanged = viewModel::setAiFeaturesEnabled,
                aiChatFeatureEnabled = perFeatureAiPrefs.chatEnabled,
                onAiChatFeatureEnabledChanged = viewModel::setAiChatFeatureEnabled,
                dailyBriefingFeatureEnabled = perFeatureAiPrefs.dailyBriefingEnabled,
                onDailyBriefingFeatureEnabledChanged = viewModel::setDailyBriefingFeatureEnabled,
                smartPomodoroFeatureEnabled = perFeatureAiPrefs.smartPomodoroEnabled,
                onSmartPomodoroFeatureEnabledChanged = viewModel::setSmartPomodoroFeatureEnabled,
                weeklyPlannerFeatureEnabled = perFeatureAiPrefs.weeklyPlannerEnabled,
                onWeeklyPlannerFeatureEnabledChanged = viewModel::setWeeklyPlannerFeatureEnabled,
                morningCheckInFeatureEnabled = perFeatureAiPrefs.morningCheckInEnabled,
                onMorningCheckInFeatureEnabledChanged = viewModel::setMorningCheckInFeatureEnabled
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
