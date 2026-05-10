package com.averycorp.prismtask.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "courses",
    indices = [Index(value = ["cloud_id"], unique = true)]
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "cloud_id") val cloudId: String? = null,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "code") val code: String,
    @ColumnInfo(name = "color") val color: Int = 0,
    @ColumnInfo(name = "icon") val icon: String = "\uD83D\uDCDA",
    @ColumnInfo(name = "active") val active: Boolean = true,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
    @ColumnInfo(name = "create_daily_task", defaultValue = "0") val createDailyTask: Boolean = false,
    @ColumnInfo(name = "daily_task_id") val dailyTaskId: Long? = null
)
