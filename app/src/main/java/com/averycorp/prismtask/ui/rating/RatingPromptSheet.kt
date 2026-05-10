package com.averycorp.prismtask.ui.rating

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private const val FREE_TEXT_MAX = 1000
private const val SUCCESS_DISMISS_DELAY_MS = 1200L

/**
 * Custom "How's it going?" prompt rendered as a ModalBottomSheet (mirrors
 * the ProUpsellSheet pattern from PR #1219). Two-tap path:
 *  1. User taps thumbs up or thumbs down (sentiment selection).
 *  2. Optional free-text (capped at 1000 chars client-side, 4000
 *     server-side) → Send button posts to /api/v1/feedback/in-app.
 *
 * On success the sheet auto-dismisses after a brief acknowledgement; on
 * error the user can retry inline. See
 * `docs/audits/E2_IN_APP_RATINGS_AUDIT.md` § Item 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingPromptSheet(
    onDismiss: () -> Unit,
    viewModel: RatingPromptViewModel = hiltViewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sentiment by rememberSaveable { mutableStateOf<RatingSentiment?>(null) }
    var freeText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is RatingPromptUiState.Submitted) {
            delay(SUCCESS_DISMISS_DELAY_MS)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = "How's It Going?",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Quick check-in — your reactions help us tune PrismTask.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilterChip(
                    selected = sentiment == RatingSentiment.THUMB_UP,
                    onClick = { sentiment = RatingSentiment.THUMB_UP },
                    label = { Text("It's Great") },
                    leadingIcon = { Icon(Icons.Default.ThumbUp, contentDescription = null) }
                )
                FilterChip(
                    selected = sentiment == RatingSentiment.THUMB_DOWN,
                    onClick = { sentiment = RatingSentiment.THUMB_DOWN },
                    label = { Text("Could Be Better") },
                    leadingIcon = { Icon(Icons.Default.ThumbDown, contentDescription = null) }
                )
            }
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = freeText,
                onValueChange = { if (it.length <= FREE_TEXT_MAX) freeText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Anything Else? (Optional)") },
                placeholder = { Text("Stuck on something? Wishlist?") },
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences
                )
            )
            Spacer(Modifier.height(20.dp))

            when (val s = state) {
                is RatingPromptUiState.Error -> {
                    Text(
                        text = s.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                }
                else -> Unit
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Not Now") }
                Spacer(Modifier.height(0.dp))
                Button(
                    onClick = {
                        sentiment?.let {
                            viewModel.resetError()
                            viewModel.submit(it, freeText)
                        }
                    },
                    enabled = sentiment != null && state !is RatingPromptUiState.Submitting
                ) {
                    Text(
                        when (state) {
                            is RatingPromptUiState.Submitting -> "Sending…"
                            is RatingPromptUiState.Submitted -> "Thanks!"
                            else -> "Send"
                        }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
