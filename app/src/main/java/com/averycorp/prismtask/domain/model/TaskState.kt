package com.averycorp.prismtask.domain.model

/**
 * Per-task lifecycle state used by the Today / project surfaces. The
 * forgiveness-first streak engine treats [BlockedByDependency] as a
 * third state that is neither "overdue" (which would break the streak)
 * nor "met" (which would count toward it).
 *
 * Added in v1.8.x as part of the PrismTask-timeline-class scope (PR-2).
 *
 * Pure-domain enum: storage layer keeps using the existing
 * `tasks.is_completed` / `tasks.due_date` columns; this state is
 * computed at read time from those columns plus the
 * `task_dependencies` graph.
 */
enum class TaskState {
    /** Open task with no due date or due in the future. */
    Pending,

    /** Open task whose due date has passed and no blockers prevent action. */
    Overdue,

    /**
     * Open task with at least one un-completed blocker. Surfaces with a
     * "waiting on …" hint and is excluded from the overdue/missed pool
     * that the forgiveness streak walker sees.
     */
    BlockedByDependency,

    /** Task carries `is_completed = 1`. */
    Completed,

    /** Task carries an `archived_at` value. */
    Archived
}
