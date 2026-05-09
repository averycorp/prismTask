package com.averycorp.prismtask.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors

/**
 * A labeled group header for the top-level settings list.
 * Uses [LocalPrismColors] for the group label and divider so every theme's
 * accent color is reflected in the section separators.
 *
 * - Void: a short decorative horizontal line precedes the label text
 * - Matrix: label is prefixed with `# ` and lowercased
 * - Others: label is uppercased in primary accent color
 */
@Composable
fun SettingsGroup(
    label: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val prismColors = LocalPrismColors.current
    val attrs = LocalPrismAttrs.current

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = prismColors.border,
            modifier = Modifier.padding(top = 16.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        ) {
            if (attrs.editorial) {
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .height(1.dp)
                        .background(prismColors.onSurface)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = when {
                    attrs.terminal -> "# ${label.lowercase()}"
                    else -> label.uppercase()
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = prismColors.primary,
                letterSpacing = if (attrs.editorial) 2.sp else 1.4.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            HorizontalDivider(
                color = prismColors.border,
                modifier = Modifier.weight(1f)
            )
        }
        content()
    }
}

/**
 * A single tappable row on the top-level settings list.
 * Shows an icon container, a title, an optional subtitle,
 * an optional Pro badge, and a trailing chevron.
 *
 * The icon badge corner radius respects the active theme's [PrismThemeAttrs.cardRadius].
 */
@Composable
fun SettingsNavRow(
    title: String,
    subtitle: String? = null,
    iconEmoji: String,
    iconBgColor: Color,
    isPro: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val prismColors = LocalPrismColors.current
    val attrs = LocalPrismAttrs.current
    val badgeRadius = (attrs.cardRadius / 2).coerceAtLeast(4).dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(badgeRadius))
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = iconEmoji,
                fontSize = 16.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (attrs.terminal) title.lowercase() else title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = prismColors.onBackground
                )
                if (isPro) {
                    Spacer(modifier = Modifier.width(6.dp))
                    ProPill()
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = prismColors.muted
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = prismColors.muted,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ProPill() {
    val prismColors = LocalPrismColors.current
    val attrs = LocalPrismAttrs.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(if (attrs.terminal) 0.dp else 6.dp))
            .background(prismColors.primary.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = if (attrs.terminal) "pro" else "Pro",
            fontSize = 10.sp,
            color = prismColors.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}
