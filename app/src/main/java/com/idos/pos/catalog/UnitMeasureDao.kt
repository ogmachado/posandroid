package com.idos.pos.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UnitMeasureDao {
    @Insert
    suspend fun insert(entity: UnitMeasureEntity): Long

    @Query("SELECT * FROM unit_measure ORDER BY name")
    fun findAllFlow(): Flow<List<UnitMeasureEntity>>

    @Query("SELECT * FROM unit_measure WHERE id = :id")
    suspend fun findById(id: Long): UnitMeasureEntity?
}
