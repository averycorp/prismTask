package com.averycorp.prismtask.ui.screens.settings.sections

import android.app.Activity
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.billing.BillingPeriod
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.ui.components.settings.SectionHeader

@Composable
fun SubscriptionSection(
    userTier: UserTier,
    billingPeriod: BillingPeriod,
    onLaunchUpgrade: (Activity, BillingPeriod) -> Unit,
    onRestorePurchases: () -> Unit
) {
    val context = LocalContext.current
    SectionHeader("Subscription")
    when (userTier) {
        UserTier.PRO -> {
            Text(
                text = "PrismTask Pro",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            val periodLabel = when (billingPeriod) {
                BillingPeriod.ANNUAL -> "Billed Annually \u2014 \$5/mo (\$59.99/year)"
                BillingPeriod.MONTHLY -> "Billed Monthly \u2014 \$7.99/mo"
                BillingPeriod.NONE -> "Subscription Active"
            }
            Text(
                text = periodLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "All features unlocked. Thanks for supporting PrismTask.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            OutlinedButton(
                onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("https://play.google.com/store/account/subscriptions")
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: android.content.ActivityNotFoundException) {
                        // No browser / Play Store installed — silent no-op.
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Manage Subscription")
            }
        }
        UserTier.FREE -> {
            Text(
                text = "PrismTask Free",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Upgrade to Pro to unlock cloud sync, AI tools, integrations, and more.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            SubscriptionComparisonCard()
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    val activity = context as? Activity ?: return@Button
                    onLaunchUpgrade(activity, BillingPeriod.ANNUAL)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(24.dp)
                    )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Annual \u2014 \$5/mo (\$59.99/year, Save 37%)",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Best Value \u2022 7-Day Free Trial",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val activity = context as? Activity ?: return@OutlinedButton
                    onLaunchUpgrade(activity, BillingPeriod.MONTHLY)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Monthly \u2014 \$7.99/mo",
                    fontWeight = FontWeight.SemiBold
                )
            }
            TextButton(onClick = onRestorePurchases) {
                Text("Restore Purchase")
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun SubscriptionComparisonCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "",
                    modifier = Modifier.weight(1.8f),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Free",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Pro",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            ComparisonRow("Core Tasks & Habits", free = true, pro = true)
            ComparisonRow("All Views (Today/Week/Month/Timeline/Eisenhower)", free = true, pro = true)
            ComparisonRow("Templates, NLP, Widgets (soon)", free = true, pro = true)
            ComparisonRow("Google Calendar Sync", free = true, pro = true)
            ComparisonRow("Quick-Start Mode & Calm Mode", free = true, pro = true)
            ComparisonRow("Cloud Sync & Template Sync", free = false, pro = true)
            ComparisonRow("AI Eisenhower, Pomodoro, Briefing", free = false, pro = true)
            ComparisonRow("AI Weekly Planner (Sonnet)", free = false, pro = true)
            ComparisonRow("Analytics & Time Tracking", free = false, pro = true)
            ComparisonRow("Collaboration & Integrations", free = false, pro = true)
            ComparisonRow("Google Drive Backup", free = false, pro = true)
        }
    }
}

@Composable
private fun ComparisonRow(
    feature: String,
    free: Boolean,
    pro: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = feature,
            modifier = Modifier.weight(1.8f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TierCheck(enabled = free, modifier = Modifier.weight(1f))
        TierCheck(enabled = pro, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TierCheck(enabled: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = if (enabled) "\u2705" else "\u2014",
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
