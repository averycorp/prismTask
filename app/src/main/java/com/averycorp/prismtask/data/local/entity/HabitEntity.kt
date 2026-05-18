package com.averycorp.prismtask.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "habits",
    indices = [Index(value = ["cloud_id"], unique = true)]
)
data class HabitEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "cloud_id")
    val cloudId: String? = null,
    val name: String,
    val description: String? = null,
    @ColumnInfo(name = "target_frequency")
    val targetFrequency: Int = 1,
    @ColumnInfo(name = "frequency_period")
    val frequencyPeriod: String = "daily",
    @ColumnInfo(name = "active_days")
    val activeDays: String? = null,
    val color: String = "#4A90D9",
    val icon: String = "\u2B50",
    @ColumnInfo(name = "reminder_time")
    val reminderTime: Long? = null,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,
    val category: String? = null,
    @ColumnInfo(name = "create_daily_task")
    val createDailyTask: Boolean = false,
    @ColumnInfo(name = "reminder_interval_millis")
    val reminderIntervalMillis: Long? = null,
    @ColumnInfo(name = "reminder_times_per_day", defaultValue = "1")
    val reminderTimesPerDay: Int = 1,
    @ColumnInfo(name = "has_logging", defaultValue = "0")
    val hasLogging: Boolean = false,
    @ColumnInfo(name = "track_booking", defaultValue = "0")
    val trackBooking: Boolean = false,
    @ColumnInfo(name = "track_previous_period", defaultValue = "0")
    val trackPreviousPeriod: Boolean = false,
    @ColumnInfo(name = "is_bookable", defaultValue = "0")
    val isBookable: Boolean = false,
    @ColumnInfo(name = "is_booked", defaultValue = "0")
    val isBooked: Boolean = false,
    @ColumnInfo(name = "booked_date")
    val bookedDate: Long? = null,
    @ColumnInfo(name = "booked_note")
    val bookedNote: String? = null,
    @ColumnInfo(name = "show_streak", defaultValue = "0")
    val showStreak: Boolean = false,
    @ColumnInfo(name = "nag_suppression_override_enabled", defaultValue = "0")
    val nagSuppressionOverrideEnabled: Boolean = false,
    @ColumnInfo(name = "nag_suppression_days_override", defaultValue = "-1")
    val nagSuppressionDaysOverride: Int = -1,
    /**
     * Per-habit override for the Today-screen "skip if completed within N days"
     * window. -1 = inherit the global default; 0 = explicitly disabled for this
     * habit; >=1 = use this many days as the window. See
     * [com.averycorp.prismtask.domain.usecase.HabitTodayVisibilityResolver].
     */
    @ColumnInfo(name = "today_skip_after_complete_days", defaultValue = "-1")
    val todaySkipAfterCompleteDays: Int = -1,
    /**
     * Per-habit override for the Today-screen "skip if next scheduled
     * occurrence is within N days" window. -1 = inherit the global default;
     * 0 = explicitly disabled for this habit; >=1 = use this many days.
     */
    @ColumnInfo(name = "today_skip_before_schedule_days", defaultValue = "-1")
    val todaySkipBeforeScheduleDays: Int = -1,
    /**
     * Per-habit override for the streak grace period (how many consecutive
     * missed days end a streak). -1 = inherit the global default from
     * [com.averycorp.prismtask.data.preferences.HabitListPreferences.getStreakMaxMissedDays];
     * >=1 = use this many days for this habit. Resolved via
     * [com.averycorp.prismtask.domain.usecase.HabitForgivenessResolver.resolveMaxMissedDays].
     */
    @ColumnInfo(name = "streak_max_missed_days", defaultValue = "-1")
    val streakMaxMissedDays: Int = -1,
    /**
     * Per-habit override for the forgiveness-first toggle (whether the
     * forgiveness window applies to this habit's streak). Three-state because
     * the global toggle is independent of the slider values:
     * -1 = inherit the global setting, 0 = force off, 1 = force on. Resolved
     * via [com.averycorp.prismtask.domain.usecase.HabitForgivenessResolver.resolveForgivenessConfig].
     */
    @ColumnInfo(name = "forgiveness_enabled", defaultValue = "-1")
    val forgivenessEnabled: Int = -1,
    /**
     * Per-habit override for the forgiveness "allowed misses" budget — how
     * many missed days the rolling grace window tolerates. -1 = inherit the
     * global default; >=0 = use this value (0 is a valid opt-in to a
     * zero-miss budget for this habit).
     */
    @ColumnInfo(name = "forgiveness_allowed_misses", defaultValue = "-1")
    val forgivenessAllowedMisses: Int = -1,
    /**
     * Per-habit override for the forgiveness grace period window length in
     * days. -1 = inherit the global default; >=1 = use this many days for
     * this habit.
     */
    @ColumnInfo(name = "forgiveness_grace_period_days", defaultValue = "-1")
    val forgivenessGracePeriodDays: Int = -1,
    @ColumnInfo(name = "is_built_in", defaultValue = "0")
    val isBuiltIn: Boolean = false,
    @ColumnInfo(name = "template_key")
    val templateKey: String? = null,
    /**
     * The BuiltInHabitVersionRegistry version this row was last reconciled
     * against. 0 means "never linked / pre-versioning"; the migration that
     * adds this column backfills 1 onto every row that already had
     * is_built_in = 1 and a non-null template_key.
     */
    @ColumnInfo(name = "source_version", defaultValue = "0")
    val sourceVersion: Int = 0,
    /**
     * Set true the first time the user edits any field on a built-in habit
     * row. The diff/approve UI uses it as a heuristic to default
     * field-level overwrites to unchecked. Never reset.
     */
    @ColumnInfo(name = "is_user_modified", defaultValue = "0")
    val isUserModified: Boolean = false,
    /**
     * Sticky cross-device flag — once true, BuiltInUpdateDetector ignores
     * this row forever. Sync uses logical-OR (either side true wins) so
     * detach is irreversible without manual intervention.
     */
    @ColumnInfo(name = "is_detached_from_template", defaultValue = "0")
    val isDetachedFromTemplate: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
