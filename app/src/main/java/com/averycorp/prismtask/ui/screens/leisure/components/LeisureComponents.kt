package com.averycorp.prismtask.ui.screens.leisure.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismFonts

data class LeisureOption(val id: String, val label: String, val icon: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddActivityDialog(
    category: String,
    onDismiss: () -> Unit,
    onConfirm: (label: String, icon: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add $category Activity") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Activity Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = icon,
                    onValueChange = { if (it.length <= 2) icon = it },
                    label = { Text("Emoji Icon") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), icon.ifBlank { "\u2B50" }) },
                enabled = name.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
internal fun ProgressCard(doneCount: Int, target: Int, progress: Float, allDone: Boolean) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(400),
        label = "progress"
    )
    val progressColor by animateColorAsState(
        targetValue = if (allDone) LocalPrismColors.current.successColor else MaterialTheme.colorScheme.primary,
        animationSpec = tween(400),
        label = "progressColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$doneCount / $target daily minimum",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = progressColor
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
            if (allDone) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "\u2713 Leisure day complete. Nice work.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = LocalPrismColors.current.successColor,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
internal fun SectionHeader(icon: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun ActivitySection(
    options: List<LeisureOption>,
    picked: String?,
    done: Boolean,
    accentColor: Color,
    duration: String,
    thresholdMs: Long,
    elapsedMs: Long,
    timerRunning: Boolean,
    columns: Int,
    onPick: (String) -> Unit,
    onDone: () -> Unit,
    onClear: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onTimerReset: () -> Unit,
    onAdd: () -> Unit,
    onLongPressOption: (LeisureOption) -> Unit
) {
    if (picked == null) {
        // Grid picker — options + add button
        val totalItems = options.size + 1
        val rows = (totalItems + columns - 1) / columns
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height((rows * 90 + (rows - 1) * 8).dp)
        ) {
            items(options, key = { it.id }) { option ->
                OptionCard(
                    option = option,
                    onClick = { onPick(option.id) },
                    onLongClick = { onLongPressOption(option) }
                )
            }
            item(key = "_add") {
                AddOptionCard(onClick = onAdd)
            }
        }
    } else {
        val selected = options.find { it.id == picked } ?: return
        // Selected item with checkbox
        SelectedItem(
            option = selected,
            done = done,
            accentColor = accentColor,
            duration = duration,
            onDone = onDone
        )

        if (!done) {
            Spacer(Modifier.height(8.dp))
            // Inline timer
            SectionTimer(
                elapsedMs = elapsedMs,
                thresholdMs = thresholdMs,
                running = timerRunning,
                accentColor = accentColor,
                onPause = onPause,
                onResume = onResume,
                onReset = onTimerReset
            )
            TextButton(onClick = onClear) {
                Text(
                    "\u2190 Pick something else",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OptionCard(option: LeisureOption, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(option.icon, fontSize = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                option.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun AddOptionCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add activity",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Add",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun SelectedItem(
    option: LeisureOption,
    done: Boolean,
    accentColor: Color,
    duration: String,
    onDone: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (done) accentColor.copy(alpha = 0.27f) else MaterialTheme.colorScheme.outline,
        label = "border"
    )
    val bgColor by animateColorAsState(
        targetValue = if (done) accentColor.copy(alpha = 0.07f) else MaterialTheme.colorScheme.surfaceVariant,
        label = "bg"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDone)
            .border(1.dp, borderColor, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (done) accentColor else Color.Transparent)
                    .border(
                        2.dp,
                        if (done) accentColor else MaterialTheme.colorScheme.outline,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Done",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${option.icon} ${option.label}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None
                )
                if (!done) {
                    Text(
                        "Tap when done",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                duration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SectionTimer(
    elapsedMs: Long,
    thresholdMs: Long,
    running: Boolean,
    accentColor: Color,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onReset: () -> Unit
) {
    val elapsedMin = ((elapsedMs % 3_600_000) / 60_000).toInt()
    val elapsedSec = ((elapsedMs % 60_000) / 1_000).toInt()
    val thresholdMin = (thresholdMs / 60_000).toInt()
    val timerProgress = (elapsedMs.toFloat() / thresholdMs).coerceIn(0f, 1f)

    val animatedProgress by animateFloatAsState(
        targetValue = timerProgress,
        animationSpec = tween(200),
        label = "timerProgress"
    )

    val timeText = "${elapsedMin.toString().padStart(2, '0')}:${elapsedSec.toString().padStart(2, '0')} / $thresholdMin:00"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = accentColor.copy(alpha = 0.08f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                timeText,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = LocalPrismFonts.current.mono,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = accentColor,
                trackColor = accentColor.copy(alpha = 0.2f)
            )

            Spacer(Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onReset,
                    enabled = elapsedMs > 0,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("Reset", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
                if (running) {
                    Button(
                        onClick = onPause,
                        colors = ButtonDefaults.buttonColors(containerColor = LocalPrismColors.current.destructiveColor),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Pause", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = onResume,
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Resume", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
