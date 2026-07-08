package com.idos.pos.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Insert
    suspend fun insert(entity: ProductEntity): Long

    @Update
    suspend fun update(entity: ProductEntity)

    @Query("SELECT * FROM product ORDER BY name")
    fun findAllFlow(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM product WHERE id = :id")
    suspend fun findById(id: Long): ProductEntity?

    @Query("SELECT * FROM product WHERE code = :code")
    suspend fun findByCode(code: String): ProductEntity?

    @Query("SELECT * FROM product WHERE barcode = :barcode")
    suspend fun findByBarcode(barcode: String): ProductEntity?
}
