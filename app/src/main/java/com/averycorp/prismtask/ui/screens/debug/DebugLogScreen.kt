package com.averycorp.prismtask.ui.screens.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.averycorp.prismtask.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_LOG_LINES = 2000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        isLoading = true
        errorMessage = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { readLogcat() }
            logLines = result.lines
            errorMessage = result.error
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Debug Log", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = buildHeader(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { refresh() },
                    modifier = Modifier.weight(1f)
                ) { Text("Refresh") }

                OutlinedButton(
                    onClick = {
                        val text = logLines.joinToString("\n")
                        copyToClipboard(context, text)
                        scope.launch { snackbarHostState.showSnackbar("Copied log to clipboard") }
                    },
                    enabled = logLines.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Copy") }

                OutlinedButton(
                    onClick = {
                        val text = logLines.joinToString("\n")
                        shareLog(context, text)
                    },
                    enabled = logLines.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Share") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    isLoading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Reading logs…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    errorMessage != null && logLines.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Could Not Read Logs",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                errorMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    logLines.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "No Log Entries",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "logcat returned no output for this process.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = logLines.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(bottom = 24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class LogReadResult(val lines: List<String>, val error: String?)

/**
 * Reads the current process's logcat output. On modern Android (API 16+),
 * third-party apps can only read their own process's log entries, which is
 * exactly what we want for admin debugging.
 */
private fun readLogcat(): LogReadResult = try {
    val pid = android.os.Process.myPid()
    val process = Runtime.getRuntime().exec(
        arrayOf("logcat", "-d", "-v", "time", "--pid=$pid")
    )
    val lines = ArrayDeque<String>()
    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
        var line = reader.readLine()
        while (line != null) {
            lines.addLast(line)
            if (lines.size > MAX_LOG_LINES) lines.removeFirst()
            line = reader.readLine()
        }
    }
    process.waitFor()
    LogReadResult(lines.toList(), null)
} catch (e: Exception) {
    LogReadResult(emptyList(), e.message ?: e.javaClass.simpleName)
}

private fun buildHeader(): String {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    return "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
        "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
        "Android: ${android.os.Build.VERSION.SDK_INT}\n" +
        "Captured: $timestamp"
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("PrismTask debug log", text))
}

private fun shareLog(context: Context, text: String) {
    try {
        val cacheDir = File(context.cacheDir, "debug_logs").apply { mkdirs() }
        val file = File(cacheDir, "prismtask_log.txt")
        file.writeText(buildHeader() + "\n\n" + text)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PrismTask Debug Log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Debug Log"))
    } catch (_: Exception) {
        // Fallback: share as plain text
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PrismTask Debug Log")
            putExtra(Intent.EXTRA_TEXT, buildHeader() + "\n\n" + text)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Share Debug Log"))
        } catch (_: Exception) {
            // no share targets
        }
    }
}
