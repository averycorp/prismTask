package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.ui.components.settings.SectionHeader
import com.averycorp.prismtask.ui.theme.LocalPrismShapes

@Composable
fun DebugTierSection(
    debugTierOverride: UserTier?,
    onSetDebugTier: (UserTier) -> Unit,
    onClearDebugTier: () -> Unit,
    adminUseSonnet: Boolean,
    onSetAdminUseSonnet: (Boolean) -> Unit
) {
    Spacer(modifier = Modifier.height(24.dp))
    HorizontalDivider()
    SectionHeader("\uD83D\uDEE0 Admin")

    Text(
        text = "Admin only \u2014 visible to admin users",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(bottom = 12.dp)
    )

    Text(
        text = "Override Tier:",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            UserTier.FREE to "Free",
            UserTier.PRO to "Pro"
        ).forEach { (tier, label) ->
            val selected = debugTierOverride == tier
            FilterChip(
                selected = selected,
                onClick = { onSetDebugTier(tier) },
                label = { Text(label) },
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (debugTierOverride != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(LocalPrismShapes.current.chip)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "OVERRIDE: ${debugTierOverride.name}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onClearDebugTier,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset Override")
        }
    } else {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No override active \u2014 using real billing state",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = "AI Model:",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = adminUseSonnet,
            onCheckedChange = onSetAdminUseSonnet
        )
        Spacer(modifier = Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Use Sonnet instead of Haiku",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Routes all AI requests (chat, classify, NLP) to Sonnet. Backend enforces admin gate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
