package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.averycorp.prismtask.data.local.entity.RestDayEntity
import kotlinx.coroutines.flow.Flow

/**
 * Rest Day primitive DAO (Mental-Health-First audit G3).
 *
 * Date keys are ISO `yyyy-MM-dd` strings of the user's logical
 * (Start-of-Day-aware) day. Callers must compute the key via
 * [com.averycorp.prismtask.util.DayBoundary.currentLocalDateString] —
 * never via system midnight — so the row matches every other SoD-aware
 * surface (Today filter, habit completion, NLP date parsing).
 */
@Dao
interface RestDayDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsert(entity: RestDayEntity): Long

    @Query("SELECT * FROM rest_days WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): RestDayEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM rest_days WHERE date = :date)")
    suspend fun isRestDay(date: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM rest_days WHERE date = :date)")
    fun observeIsRestDay(date: String): Flow<Boolean>

    @Query("DELETE FROM rest_days WHERE date = :date")
    suspend fun deleteByDate(date: String)

    @Query("SELECT date FROM rest_days ORDER BY date DESC")
    suspend fun getAllDatesOnce(): List<String>

    @Query("SELECT date FROM rest_days ORDER BY date DESC")
    fun observeAllDates(): Flow<List<String>>

    @Query("SELECT * FROM rest_days ORDER BY date DESC")
    suspend fun getAllOnce(): List<RestDayEntity>

    @Query("DELETE FROM rest_days")
    suspend fun deleteAll()
}
