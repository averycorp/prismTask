package com.averycorp.prismtask.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.billing.BillingPeriod
import com.averycorp.prismtask.data.billing.UserTier

/**
 * Pro-gated AI feature surfaces are uniform via this enum so each tap-time
 * paywall renders the same Composable with feature-specific copy. Labels
 * mirror the existing [ProFeature] enum where overlap exists; the rest are
 * added to cover the full inventory enumerated in the D11 finish bundle
 * audit (`docs/audits/D11_FINISH_BUNDLE_AUDIT.md`).
 */
enum class ProGatedFeature(
    val label: String,
    val description: String
) {
    AI_CHAT(
        label = "AI Coach",
        description = "Get personalized coaching and task help through natural conversation"
    ),
    AI_BRIEFING(
        label = "Daily Briefing",
        description = "Morning summary with top priorities and a suggested task order"
    ),
    SMART_POMODORO(
        label = "Smart Focus Sessions",
        description = "AI-planned Pomodoro sessions tailored to your task list"
    ),
    WEEKLY_PLANNER(
        label = "AI Weekly Planner",
        description = "Let AI plan your week distributing tasks across days"
    ),
    EISENHOWER(
        label = "Eisenhower Matrix",
        description = "AI-powered task categorization into urgency and importance quadrants"
    ),
    TIME_BLOCKING(
        label = "Time Blocking",
        description = "Auto-schedule your day with AI-optimized time blocks"
    ),
    PASTE_EXTRACT(
        label = "Extract Tasks From Text",
        description = "Paste a chat, email, or meeting note and let Claude pull tasks out of it"
    ),
    WEEKLY_REVIEW(
        label = "Weekly Review",
        description = "Guided end-of-week reflection on what shipped and what's next"
    )
}

/**
 * Unified Pro-gated upsell. Shown as a full-screen ModalBottomSheet when a
 * Free user taps any Pro-gated AI feature surface (AI Tools hub rows, Today
 * quick chips, AI Coach FAB). Replaces the missing-but-implied tap-time
 * paywall identified in the D11 finish bundle audit. The existing in-screen
 * `AlertDialog(UpgradePrompt)` paywalls remain as defense-in-depth for
 * downstream entry points (typing a chat message, etc.).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProUpsellSheet(
    feature: ProGatedFeature,
    currentTier: UserTier,
    onUpgrade: (BillingPeriod) -> Unit,
    onRestorePurchase: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            UpgradePrompt(
                currentTier = currentTier,
                feature = feature.label,
                description = feature.description,
                onUpgrade = onUpgrade,
                onRestorePurchase = onRestorePurchase,
                onDismiss = onDismiss
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
