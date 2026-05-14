package com.averycorp.prismtask.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Rest Day primitive (Mental-Health-First audit G3).
 *
 * One row per calendar date the user has marked as a rest day. Rest days
 * compose with the forgiveness-first streak core: they are treated as
 * "kept" by definition — they do NOT consume the grace window and do NOT
 * count as misses against the resilient walk.
 *
 * Behaviorally a rest day also suppresses non-medication notifications.
 * Medication reminders are unaffected (audit § G3: "only non-medication").
 *
 * Day boundary semantics: the [date] field is the ISO date string
 * ("yyyy-MM-dd") of the user's *logical* day, resolved via
 * [com.averycorp.prismtask.util.DayBoundary] at the caller — NOT the
 * system midnight. Storing as a string keeps the row stable across
 * timezone shifts (which is also why streak storage avoids epoch-millis
 * day keys; see CheckInLogEntity for the contrasting `Long` shape that
 * pays for a different unique-index story).
 *
 * Cloud sync: not wired in this PR. Schema follows the
 * [com.averycorp.prismtask.data.local.entity.CheckInLogEntity] precedent
 * so a later additive migration can mirror this into Firestore the same
 * way without a second schema bump.
 */
@Entity(
    tableName = "rest_days",
    indices = [
        Index(value = ["date"], unique = true),
        Index(value = ["cloud_id"], unique = true)
    ]
)
data class RestDayEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** ISO ("yyyy-MM-dd") of the user's logical (SoD-aware) day. */
    @ColumnInfo(name = "date")
    val date: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "cloud_id")
    val cloudId: String? = null,
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L
)
