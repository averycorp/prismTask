package com.averycorp.prismtask.ui.screens.settings.sections

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.ui.components.settings.SectionHeader

private const val PHILOSOPHY_URL =
    "https://github.com/averycorp/prismTask/blob/main/docs/PHILOSOPHY.md"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AboutSection(
    onRefreshWidgets: (() -> Unit)? = null,
    onDebugReseed: (() -> Unit)? = null
) {
    SectionHeader("About")

    // Long-press on the version label re-runs the built-in template + self-care
    // step seeders. Debug-only: the release build gets a plain Text with no
    // gesture modifier so the pathway can't be discovered or triggered by end
    // users. The ViewModel also gates the action on BuildConfig.DEBUG, so even
    // a mis-wired caller can't wipe seeded data in a release build.
    val versionText = "PrismTask v${BuildConfig.VERSION_NAME}"
    val versionModifier = if (BuildConfig.DEBUG && onDebugReseed != null) {
        val interactionSource = remember { MutableInteractionSource() }
        Modifier
            .padding(vertical = 4.dp)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
                onLongClick = onDebugReseed
            )
    } else {
        Modifier.padding(vertical = 4.dp)
    }
    Text(
        text = versionText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = versionModifier
    )
    Text(
        text = "Made by Avery Karlin",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
        text = "In Honor of Andrei Karlin",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
        text = "In Memory of Morris and Tobi Isaac",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    val context = LocalContext.current
    TextButton(
        onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PHILOSOPHY_URL)))
        },
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Text("Our Design Philosophy")
    }

    // Hidden for v1.0 — widgets disabled until v1.2 re-enable
    if (BuildConfig.WIDGETS_ENABLED && onRefreshWidgets != null) {
        TextButton(onClick = onRefreshWidgets) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp)
            )
            Text("Refresh Widgets")
        }
    }
}
