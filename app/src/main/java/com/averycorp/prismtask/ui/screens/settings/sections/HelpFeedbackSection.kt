package com.averycorp.prismtask.ui.screens.settings.sections

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.ui.components.settings.SectionHeader
import com.averycorp.prismtask.ui.components.settings.SettingsRow
import com.averycorp.prismtask.ui.components.settings.SettingsRowWithSubtitle

@Composable
fun HelpFeedbackSection(
    onNavigateToBugReport: () -> Unit,
    onNavigateToFeatureRequest: () -> Unit
) {
    val context = LocalContext.current

    SectionHeader("Help & Feedback")

    SettingsRowWithSubtitle(
        title = "Report a Bug",
        subtitle = "Tell us what went wrong",
        onClick = onNavigateToBugReport
    )

    SettingsRowWithSubtitle(
        title = "Request a Feature",
        subtitle = "Tell us what you'd like to see",
        onClick = onNavigateToFeatureRequest
    )

    SettingsRow(
        title = "Contact Support",
        onClick = {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf("support@prismtask.app"))
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "PrismTask Feedback — v${BuildConfig.VERSION_NAME}"
                )
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                        "Android: ${android.os.Build.VERSION.SDK_INT}\n" +
                        "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n" +
                        "Feedback:\n"
                )
            }
            try {
                context.startActivity(Intent.createChooser(intent, "Contact Support"))
            } catch (_: Exception) {
                // No email client available
            }
        }
    )

    Spacer(modifier = Modifier.height(4.dp))
}
