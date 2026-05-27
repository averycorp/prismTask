package com.averycorp.prismtask.ui.screens.pomodoro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.repository.TaskRepository

/**
 * Dormancy Re-Entry: non-blocking "Where are you stopping?" capture sheet.
 *
 * Shown after a session ends (complete OR abandon). It must NOT block return
 * navigation — dismissing (back gesture / scrim tap / Skip) leaves
 * [com.averycorp.prismtask.data.local.entity.TaskEntity.reEntryContext]
 * untouched. Only Save persists, overwriting any prior value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReEntryContextSheet(
    prompt: ReEntryPrompt,
    onSave: (taskId: Long, context: String) -> Unit,
    onSkip: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onSkip,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Where Are You Stopping? (Optional)",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium
            )
            Text(
                text = prompt.taskTitle,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = text,
                onValueChange = {
                    if (it.length <= TaskRepository.RE_ENTRY_CONTEXT_MAX_LENGTH) text = it
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. Halfway through the outline") },
                supportingText = {
                    Text(
                        text = "${text.length}/${TaskRepository.RE_ENTRY_CONTEXT_MAX_LENGTH}",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSkip) { Text("Skip") }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { onSave(prompt.taskId, text) }) { Text("Save") }
            }
        }
    }
}
