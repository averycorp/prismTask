package com.averycorp.prismtask.domain.model

import com.averycorp.prismtask.data.local.entity.TaskEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single entry in the task card 3-dot context menu. Users can toggle entries
 * on/off and reorder them in Settings. Persisted as JSON in the
 * UserPreferencesDataStore under a single key.
 */
data class TaskMenuAction(val id: String, val enabled: Boolean, val order: Int) {
    companion object {
        const val QUICK_RESCHEDULE = "quick_reschedule"
        const val DUPLICATE = "duplicate"
        const val MOVE_TO_PROJECT = "move_to_project"
        const val CHANGE_PRIORITY = "change_priority"
        const val EDIT_TAGS = "edit_tags"
        const val FLAG = "flag"
        const val SHARE = "share"
        const val DELETE = "delete"

        /** The default action list, used on first launch and for reset-to-default. */
        fun defaults(): List<TaskMenuAction> = listOf(
            TaskMenuAction(QUICK_RESCHEDULE, enabled = true, order = 0),
            TaskMenuAction(DUPLICATE, enabled = true, order = 1),
            TaskMenuAction(MOVE_TO_PROJECT, enabled = true, order = 2),
            TaskMenuAction(CHANGE_PRIORITY, enabled = false, order = 3),
            TaskMenuAction(EDIT_TAGS, enabled = false, order = 4),
            TaskMenuAction(FLAG, enabled = false, order = 5),
            TaskMenuAction(SHARE, enabled = false, order = 6),
            TaskMenuAction(DELETE, enabled = true, order = 7)
        )

        /** Human-readable label for each known action id. */
        fun labelFor(id: String): String = when (id) {
            QUICK_RESCHEDULE -> "Quick Reschedule"
            DUPLICATE -> "Duplicate"
            MOVE_TO_PROJECT -> "Move to Project"
            CHANGE_PRIORITY -> "Change Priority"
            EDIT_TAGS -> "Add/Edit Tags"
            FLAG -> "Flag/Unflag"
            SHARE -> "Share Task"
            DELETE -> "Delete"
            else -> id
        }

        /**
         * Merges a user-provided list with the defaults, preserving enabled state
         * and order for known ids while appending any missing defaults at the end.
         * Unknown ids are dropped.
         */
        fun mergeWithDefaults(userList: List<TaskMenuAction>): List<TaskMenuAction> {
            val defaults = defaults()
            val defaultIds = defaults.map { it.id }.toSet()
            val valid = userList.filter { it.id in defaultIds }
            val presentIds = valid.map { it.id }.toSet()
            val missing = defaults.filter { it.id !in presentIds }
            val maxOrder = valid.maxOfOrNull { it.order } ?: -1
            val appended = missing.mapIndexed { i, m -> m.copy(order = maxOrder + 1 + i) }
            return (valid + appended).sortedBy { it.order }
        }

        /**
         * Formats a task for the Share intent payload:
         * "☐ [Title] | Due: [date] | Priority: [level] | Project: [name]"
         * Sections with no data (e.g. no project) are omitted.
         */
        fun formatShareText(
            task: TaskEntity,
            projectName: String?,
            locale: Locale = Locale.getDefault()
        ): String {
            val prefix = if (task.isCompleted) "\u2611" else "\u2610"
            val parts = mutableListOf("$prefix ${task.title}")
            task.dueDate?.let { due ->
                val formatter = SimpleDateFormat("yyyy-MM-dd", locale)
                parts.add("Due: ${formatter.format(Date(due))}")
            }
            if (task.priority > 0) {
                val priorityLabel = when (task.priority) {
                    1 -> "Low"
                    2 -> "Medium"
                    3 -> "High"
                    4 -> "Urgent"
                    else -> "None"
                }
                parts.add("Priority: $priorityLabel")
            }
            if (!projectName.isNullOrBlank()) {
                parts.add("Project: $projectName")
            }
            return parts.joinToString(" | ")
        }
    }
}
