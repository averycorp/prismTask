package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.UserAiPreferenceDao
import com.averycorp.prismtask.data.local.entity.UserAiPreferenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory UserAiPreferenceDao for unit tests. Mirrors Room semantics:
 * `observeAll` emits whenever the row set changes, ordered by
 * `updated_at` descending (matching the DAO's `@Query`).
 */
class FakeUserAiPreferenceDao : UserAiPreferenceDao {
    private val state = MutableStateFlow<List<UserAiPreferenceEntity>>(emptyList())

    val rowsSnapshot: List<UserAiPreferenceEntity>
        get() = state.value.sortedByDescending { it.updatedAt }

    override suspend fun upsert(preference: UserAiPreferenceEntity) {
        state.value = state.value.filterNot { it.id == preference.id } + preference
    }

    override suspend fun upsertAll(preferences: List<UserAiPreferenceEntity>) {
        val keep = state.value.filterNot { row -> preferences.any { it.id == row.id } }
        state.value = keep + preferences
    }

    override fun observeAll(): Flow<List<UserAiPreferenceEntity>> =
        state.map { it.sortedByDescending { row -> row.updatedAt } }

    override suspend fun getAll(): List<UserAiPreferenceEntity> =
        state.value.sortedByDescending { it.updatedAt }

    override suspend fun getById(id: String): UserAiPreferenceEntity? =
        state.value.firstOrNull { it.id == id }

    override suspend fun count(): Int = state.value.size

    override suspend fun deleteById(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun deleteAll() {
        state.value = emptyList()
    }
}
