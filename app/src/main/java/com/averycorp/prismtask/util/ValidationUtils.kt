package com.averycorp.prismtask.util

object ValidationUtils {
    const val MAX_TASK_TITLE_LENGTH = 500
    const val MAX_TASK_DESCRIPTION_LENGTH = 10_000
    const val MAX_TAG_NAME_LENGTH = 100
    const val MAX_PROJECT_NAME_LENGTH = 200

    fun validateTaskTitle(title: String): String? {
        if (title.isBlank()) return "Task title cannot be empty"
        if (title.length > MAX_TASK_TITLE_LENGTH) return "Task title too long (max $MAX_TASK_TITLE_LENGTH characters)"
        return null
    }

    fun validateTaskDescription(description: String?): String? {
        if (description != null && description.length > MAX_TASK_DESCRIPTION_LENGTH) {
            return "Description too long (max $MAX_TASK_DESCRIPTION_LENGTH characters)"
        }
        return null
    }

    fun validateTagName(name: String): String? {
        if (name.isBlank()) return "Tag name cannot be empty"
        if (name.length > MAX_TAG_NAME_LENGTH) return "Tag name too long (max $MAX_TAG_NAME_LENGTH characters)"
        return null
    }

    fun validateProjectName(name: String): String? {
        if (name.isBlank()) return "Project name cannot be empty"
        if (name.length > MAX_PROJECT_NAME_LENGTH) return "Project name too long (max $MAX_PROJECT_NAME_LENGTH characters)"
        return null
    }

    fun validatePriority(priority: Int): Boolean = priority in 0..4

    fun sanitizeTitle(title: String): String = title.trim()
}
