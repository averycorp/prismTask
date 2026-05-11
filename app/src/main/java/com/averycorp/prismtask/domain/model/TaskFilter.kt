package com.averycorp.prismtask.domain.model

data class TaskFilter(
    val selectedTagIds: List<Long> = emptyList(),
    val tagFilterMode: TagFilterMode = TagFilterMode.ANY,
    val selectedPriorities: List<Int> = emptyList(),
    val selectedProjectIds: List<Long?> = emptyList(),
    val dateRange: DateRange? = null,
    val showCompleted: Boolean = false,
    val showArchived: Boolean = false,
    val searchQuery: String = "",
    val showFlaggedOnly: Boolean = false,
    /** Work-Life Balance categories to filter by (empty = no category filter). */
    val selectedLifeCategories: List<LifeCategory> = emptyList()
) {
    fun isActive(): Boolean = selectedTagIds.isNotEmpty() ||
        selectedPriorities.isNotEmpty() ||
        selectedProjectIds.isNotEmpty() ||
        dateRange != null ||
        showCompleted ||
        showArchived ||
        searchQuery.isNotBlank() ||
        showFlaggedOnly ||
        selectedLifeCategories.isNotEmpty()

    fun activeFilterCount(): Int = listOf(
        selectedTagIds.isNotEmpty(),
        selectedPriorities.isNotEmpty(),
        selectedProjectIds.isNotEmpty(),
        dateRange != null,
        showCompleted,
        showArchived,
        searchQuery.isNotBlank(),
        showFlaggedOnly,
        selectedLifeCategories.isNotEmpty()
    ).count { it }
}

enum class TagFilterMode { ANY, ALL }

data class DateRange(val start: Long?, val end: Long?)
