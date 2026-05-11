package com.averycorp.prismtask.data.diagnostics

import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(val timestamp: Long, val tag: String, val level: LogLevel, val message: String)

@Singleton
class DiagnosticLogger
@Inject
constructor() {
    private val buffer = ConcurrentLinkedDeque<LogEntry>()

    fun log(tag: String, level: LogLevel, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            level = level,
            message = message
        )
        buffer.addLast(entry)
        trimToSize()
        pruneOldEntries()
        updateCrashlyticsContext()
    }

    private fun updateCrashlyticsContext() {
        try {
            val recent = buffer.toList().takeLast(10).joinToString("\n") {
                "[${it.level}] [${it.tag}] ${it.message}"
            }
            FirebaseCrashlytics.getInstance().setCustomKey("recent_events", recent)
        } catch (_: Exception) {
            // Ignore if Crashlytics not initialized (e.g., in tests)
        }
    }

    fun info(tag: String, message: String) = log(tag, LogLevel.INFO, message)

    fun warn(tag: String, message: String) = log(tag, LogLevel.WARN, message)

    fun error(tag: String, message: String) = log(tag, LogLevel.ERROR, message)

    fun debug(tag: String, message: String) = log(tag, LogLevel.DEBUG, message)

    fun getEntries(): List<LogEntry> = buffer.toList()

    fun getEntryCount(): Int = buffer.size

    fun getEarliestTimestamp(): Long? = buffer.peekFirst()?.timestamp

    fun exportAsText(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val entries = buffer.toList()
        val sb = StringBuilder()
        sb.appendLine("# PrismTask Diagnostic Log")
        sb.appendLine("# This log contains app navigation and operation events. No personal content is included.")
        sb.appendLine("# Entries: ${entries.size}")
        sb.appendLine()
        for (entry in entries) {
            val time = dateFormat.format(Date(entry.timestamp))
            sb.appendLine("[$time] [${entry.level}] [${entry.tag}] ${entry.message}")
        }
        return sb.toString()
    }

    fun clear() {
        buffer.clear()
    }

    private fun trimToSize() {
        while (buffer.size > MAX_ENTRIES) {
            buffer.pollFirst()
        }
    }

    private fun pruneOldEntries() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        while (true) {
            val first = buffer.peekFirst() ?: break
            if (first.timestamp < cutoff) {
                buffer.pollFirst()
            } else {
                break
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
        const val MAX_AGE_MS = 60 * 60 * 1000L // 1 hour
    }
}
