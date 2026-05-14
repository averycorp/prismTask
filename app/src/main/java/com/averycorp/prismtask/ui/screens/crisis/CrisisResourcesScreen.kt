package com.averycorp.prismtask.ui.screens.crisis

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/**
 * G1 — Crisis Resources surface (v1 US-only).
 *
 * Surfaces 988 Suicide & Crisis Lifeline and the Crisis Text Line up
 * front; offers a "tap for other regions" link that opens findahelpline.com
 * for the rest of the world (Phase 4 audit decision: v1 is US-only with
 * a clear escape hatch rather than wait on a multi-region matrix).
 *
 * Strings are descriptive, non-clinical, non-alarmist. Voice anchors on
 * the forgiveness-first language used by `ProductiveStreakPreferences.kt`
 * ("Take care of yourself today — start fresh tomorrow.") and the chat
 * system prompt's "you are here, talking to you, and that is enough"
 * posture.
 *
 * Entry points:
 *  - Settings → "If you need help now" row (under Wellbeing-adjacent
 *    section in `SettingsScreen`).
 *  - Mood & Energy screen footer ("If you need help now →" link).
 *
 * Phone-number taps open `tel:` intents; SMS taps open `sms:` intents.
 * Web link uses `Intent.ACTION_VIEW` — Custom Tabs isn't a dependency on
 * this codebase (per the audit), so the standard ACTION_VIEW handoff is
 * the right fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrisisResourcesScreen(navController: NavController) {
    val context = LocalContext.current

    fun safeStart(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No dialer / SMS / browser app available. We deliberately
            // don't surface a snackbar — the user is in a sensitive
            // moment and a vague error helps no one. The other two
            // contact methods on the screen remain visible.
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "If You Need Help Now",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "These resources are free and available 24/7.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Reaching out is enough. You don't have to know what to say.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ResourceCard(
                title = "988 Suicide & Crisis Lifeline",
                body = "Call or text 988 to talk with a trained counselor.",
                primaryLabel = "Call 988",
                primaryIcon = Icons.Default.Call,
                onPrimary = {
                    safeStart(
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:988"))
                    )
                },
                secondaryLabel = "Text 988",
                secondaryIcon = Icons.Default.Sms,
                onSecondary = {
                    safeStart(
                        Intent(Intent.ACTION_VIEW, Uri.parse("sms:988"))
                    )
                }
            )

            ResourceCard(
                title = "Crisis Text Line",
                body = "Text HOME to 741741 to message with a trained counselor.",
                primaryLabel = "Text HOME to 741741",
                primaryIcon = Icons.Default.Sms,
                onPrimary = {
                    safeStart(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("sms:741741?body=HOME")
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // International escape hatch. v1 is US-only; findahelpline.com
            // is a non-clinical directory maintained by ThroughLine that
            // routes to vetted local hotlines worldwide.
            OutlinedButton(
                onClick = {
                    safeStart(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://findahelpline.com")
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.OpenInBrowser,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Tap for Other Regions")
            }

            Text(
                text = "Take care of yourself — you matter more than any task.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ResourceCard(
    title: String,
    body: String,
    primaryLabel: String,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    secondaryIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onSecondary: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onPrimary) {
                    Icon(
                        primaryIcon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(primaryLabel)
                }
                if (secondaryLabel != null && onSecondary != null) {
                    TextButton(onClick = onSecondary) {
                        if (secondaryIcon != null) {
                            Icon(
                                secondaryIcon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                        }
                        Text(secondaryLabel)
                    }
                }
            }
        }
    }
}
