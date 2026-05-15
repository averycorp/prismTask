package com.averycorp.prismtask.ui.screens.reflection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.ui.theme.ThemedSubScreenTitle
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/**
 * F4 Item 1 — End-of-day Reflection screen.
 *
 * Renders one positive-framed prompt ("What worked today?") and lets the
 * user save a short reflection scoped to today's logical date. Surfaces
 * the count of completed tasks today as encouragement, never the count
 * of unfinished items (Principle 1: forgiveness over punishment).
 *
 * Anti-patterns the screen avoids:
 *  - No completion percentage with negative framing.
 *  - No "X tasks unfinished" counter.
 *  - No streak/achievement language.
 *  - No AI inference on the reflection text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReflectionScreen(
    navController: NavController,
    viewModel: ReflectionViewModel = hiltViewModel()
) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    val current by viewModel.currentEntry.collectAsStateWithLifecycle()
    val completedCount by viewModel.completedTodayCount.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    LaunchedEffect(current?.date) {
        text = current?.text.orEmpty()
    }

    val dateFmt = remember { DateTimeFormatter.ofPattern("EEEE, MMM d") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ThemedSubScreenTitle("Today's Reflection") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = today.format(dateFmt),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "You completed $completedCount thing${if (completedCount == 1) "" else "s"} today.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "What worked today?",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 1000) text = it },
                placeholder = { Text("One thing — small wins count.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            )
            Button(
                onClick = {
                    viewModel.save(text)
                    scope.launch { snackbar.showSnackbar("Saved") }
                },
                enabled = text.isNotBlank()
            ) {
                Text(if (current == null) "Save Reflection" else "Update Reflection")
            }
            if (current != null) {
                TextButton(onClick = {
                    viewModel.clearToday()
                    text = ""
                    scope.launch { snackbar.showSnackbar("Cleared") }
                }) {
                    Text("Clear")
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
