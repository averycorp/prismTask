package com.averycorp.prismtask.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismFonts

/**
 * A circular checkbox that follows Android/Material Design aesthetics.
 *
 * When unchecked: shows an empty circle outline.
 * When checked: shows a filled circle with a check mark.
 * When skipped: shows a filled grey circle with a skip glyph; tap clears the
 *               skip back to unchecked.
 *
 * Matrix theme ([PrismThemeAttrs.terminal]): renders `[x]` / `[ ]` / `[~]`
 * bracket notation in the mono font instead of the circle.
 *
 * The composable accepts an optional [onLongClick] callback. When provided,
 * tap and long-press are wired through a single pointer-input modifier; the
 * caller is responsible for haptic feedback (the gesture itself is silent).
 */
@Composable
fun CircularCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    skipped: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    checkedColor: Color = MaterialTheme.colorScheme.primary,
    uncheckedColor: Color = MaterialTheme.colorScheme.outline,
    skippedColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    checkmarkColor: Color = Color.White,
    size: Dp = 24.dp
) {
    val gestureModifier = if (enabled && (onCheckedChange != null || onLongClick != null)) {
        Modifier
            .semantics {
                role = Role.Checkbox
                contentDescription = when {
                    skipped -> "Skipped"
                    checked -> "Checked"
                    else -> "Unchecked"
                }
            }
            .pointerInput(checked, skipped, enabled, onLongClick != null) {
                detectTapGestures(
                    onTap = { onCheckedChange?.invoke(!checked) },
                    onLongPress = { onLongClick?.invoke() }
                )
            }
    } else {
        Modifier
    }

    val attrs = LocalPrismAttrs.current
    if (attrs.terminal) {
        val prismColors = LocalPrismColors.current
        val monoFont = LocalPrismFonts.current.mono
        val label = when {
            skipped -> "[~]"
            checked -> "[x]"
            else -> "[ ]"
        }
        val textColor = when {
            skipped -> prismColors.muted
            checked -> prismColors.primary
            else -> prismColors.muted
        }
        Box(
            modifier = modifier
                .size(size)
                .then(gestureModifier),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = textColor,
                fontFamily = monoFont,
                fontSize = (size.value * 0.55f).sp
            )
        }
        return
    }

    val backgroundColor by animateColorAsState(
        targetValue = when {
            skipped -> skippedColor
            checked -> checkedColor
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 150),
        label = "checkboxBg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            skipped -> skippedColor
            checked -> checkedColor
            else -> uncheckedColor
        },
        animationSpec = tween(durationMillis = 150),
        label = "checkboxBorder"
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(2.dp, borderColor, CircleShape)
            .then(gestureModifier),
        contentAlignment = Alignment.Center
    ) {
        when {
            skipped -> Icon(
                imageVector = Icons.Default.Redo,
                contentDescription = null,
                tint = checkmarkColor,
                modifier = Modifier.size(size * 0.6f)
            )
            checked -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = if (enabled) checkmarkColor else MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(size * 0.67f)
            )
        }
    }
}
