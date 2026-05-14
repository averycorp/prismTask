package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.ui.components.settings.SectionHeader
import com.averycorp.prismtask.ui.components.settings.SettingsRowWithSubtitle

/**
 * Settings → Privacy section exposing the partial mental-health-data
 * wipe (Mental-Health-First Audit § G5).
 *
 * Distinct from [DeleteAccountSection] — the account-delete path tears
 * down everything (tasks, habits, projects, auth). This action leaves
 * tasks / habits / projects intact and only removes mood, check-in,
 * weekly-review, boundary, and focus-release data.
 *
 * The dialog is intentionally explicit about what gets deleted (no
 * euphemisms like "reset wellness data") per the audit voice exemplar
 * at `ProductiveStreakPreferences.kt:52`. The "Delete" button stays
 * disabled until the user ticks the "I understand" checkbox.
 */
@Composable
fun DeleteMentalHealthDataSection(
    isWiping: Boolean,
    onConfirmWipe: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        DeleteMentalHealthDataDialog(
            isWiping = isWiping,
            onConfirm = {
                showDialog = false
                onConfirmWipe()
            },
            onDismiss = { showDialog = false }
        )
    }

    SectionHeader("Privacy")

    SettingsRowWithSubtitle(
        title = if (isWiping) "Deleting Mental Health Data…" else "Delete Mental Health Data",
        subtitle = "Permanently removes mood logs, check-ins, weekly " +
            "reviews, boundary rules, and clinical-report cache. Tasks, " +
            "habits, and projects are unaffected.",
        onClick = { if (!isWiping) showDialog = true }
    )

    HorizontalDivider()
}

/**
 * Confirmation dialog. The "Delete" button is destructive-colored and
 * disabled until the checkbox is ticked. "Keep" dismisses without
 * touching anything.
 */
@Composable
private fun DeleteMentalHealthDataDialog(
    isWiping: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var acknowledged by remember { mutableStateOf(false) }
    val deleteEnabled = acknowledged && !isWiping

    AlertDialog(
        onDismissRequest = { if (!isWiping) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "Warning",
                    tint = Color(0xFFF57C00),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete Mental Health Data?")
            }
        },
        text = {
            Column {
                Text(
                    text = "This permanently deletes:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                BulletLine("Mood and energy logs")
                BulletLine("Morning check-ins (including streak history)")
                BulletLine("Weekly review entries")
                BulletLine("Boundary rules")
                BulletLine("Focus & Release event history")
                BulletLine("Cached clinical-report data")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tasks, habits, and projects are not affected. " +
                        "If you're signed in, the same rows will be removed " +
                        "from your synced cloud copy.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                        enabled = !isWiping
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "I Understand This Can't Be Undone",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = deleteEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                if (isWiping) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Deleting…")
                    }
                } else {
                    Text("Delete")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isWiping) {
                Text("Keep")
            }
        }
    )
}

@Composable
private fun BulletLine(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Text("• ", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
