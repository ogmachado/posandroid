package com.idos.pos.currency

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CurrencyDao {
    @Insert
    suspend fun insert(entity: CurrencyEntity): Long

    @Query("SELECT * FROM currency WHERE active = 1 ORDER BY displayOrder")
    fun findActiveFlow(): Flow<List<CurrencyEntity>>

    @Query("SELECT * FROM currency")
    suspend fun findAll(): List<CurrencyEntity>

    @Query("SELECT * FROM currency WHERE id = :id")
    suspend fun findById(id: Long): CurrencyEntity?
}
