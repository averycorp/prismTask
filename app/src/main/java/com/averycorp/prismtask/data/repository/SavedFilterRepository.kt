package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.SavedFilterDao
import com.averycorp.prismtask.data.local.entity.SavedFilterEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.model.TaskFilter
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CRUD wrapper around [SavedFilterDao] that round-trips [TaskFilter] through
 * a Gson-serialized JSON string so future filter fields persist without a
 * schema change. Hooks the existing [SyncTracker] so presets ride the
 * `saved_filters` Firestore family wired in `SyncService`.
 */
@Singleton
class SavedFilterRepository
@Inject
constructor(
    private val savedFilterDao: SavedFilterDao,
    private val syncTracker: SyncTracker,
    private val gson: Gson
) {
    fun getAll(): Flow<List<SavedFilterEntity>> = savedFilterDao.getAll()

    suspend fun savePreset(name: String, filter: TaskFilter, iconEmoji: String? = null): Long {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Preset name must not be blank" }
        val now = System.currentTimeMillis()
        val nextSortOrder = (savedFilterDao.getAllOnce().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val existing = savedFilterDao.getByName(trimmed)
        return if (existing == null) {
            val id = savedFilterDao.insert(
                SavedFilterEntity(
                    name = trimmed,
                    filterJson = gson.toJson(filter),
                    iconEmoji = iconEmoji,
                    sortOrder = nextSortOrder,
                    createdAt = now,
                    updatedAt = now
                )
            )
            syncTracker.trackCreate(id, "saved_filter")
            id
        } else {
            val updated = existing.copy(
                filterJson = gson.toJson(filter),
                iconEmoji = iconEmoji ?: existing.iconEmoji,
                updatedAt = now
            )
            savedFilterDao.update(updated)
            syncTracker.trackUpdate(existing.id, "saved_filter")
            existing.id
        }
    }

    suspend fun deletePreset(id: Long) {
        syncTracker.trackDelete(id, "saved_filter")
        savedFilterDao.deleteById(id)
    }

    /**
     * Decode a preset's JSON payload back into a [TaskFilter]. Returns null
     * when the JSON is unparseable (e.g. dropped from a much newer schema)
     * so callers can show a "this preset is invalid" affordance instead of
     * crashing.
     */
    fun decode(entity: SavedFilterEntity): TaskFilter? = try {
        gson.fromJson(entity.filterJson, TaskFilter::class.java)
    } catch (_: Exception) {
        null
    }
}
