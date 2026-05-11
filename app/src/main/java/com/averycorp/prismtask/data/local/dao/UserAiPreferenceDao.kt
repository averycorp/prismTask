package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.averycorp.prismtask.data.local.entity.UserAiPreferenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserAiPreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: UserAiPreferenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(preferences: List<UserAiPreferenceEntity>)

    @Query("SELECT * FROM user_ai_preferences ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<UserAiPreferenceEntity>>

    @Query("SELECT * FROM user_ai_preferences ORDER BY updated_at DESC")
    suspend fun getAll(): List<UserAiPreferenceEntity>

    @Query("SELECT * FROM user_ai_preferences WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): UserAiPreferenceEntity?

    @Query("SELECT COUNT(*) FROM user_ai_preferences")
    suspend fun count(): Int

    @Query("DELETE FROM user_ai_preferences WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM user_ai_preferences")
    suspend fun deleteAll()

    /**
     * Replace the entire local mirror in one transaction. Used by the
     * repository after every chat response (which carries the full
     * authoritative list) and on explicit refresh from the Settings
     * screen, so the local view never drifts from the server.
     */
    @Transaction
    suspend fun replaceAll(preferences: List<UserAiPreferenceEntity>) {
        deleteAll()
        if (preferences.isNotEmpty()) {
            upsertAll(preferences)
        }
    }
}
