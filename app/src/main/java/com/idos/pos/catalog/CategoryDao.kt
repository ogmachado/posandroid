package com.idos.pos.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Insert
    suspend fun insert(entity: CategoryEntity): Long

    @Query("SELECT * FROM category ORDER BY name")
    fun findAllFlow(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun findById(id: Long): CategoryEntity?
}
