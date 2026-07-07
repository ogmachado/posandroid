package com.idos.pos.sales

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Read-only query surface for the POS selector — no edit screen in Slice A
 * (see design.md "Payment-method catalog is read-only (seed-only) in Slice A").
 * [insert] exists only for the seed callback, not for an admin CRUD path.
 */
@Dao
interface PaymentMethodDao {
    @Insert
    suspend fun insert(entity: PaymentMethodEntity): Long

    @Query("SELECT * FROM payment_method WHERE active = 1 ORDER BY name")
    fun findActiveFlow(): Flow<List<PaymentMethodEntity>>

    @Query("SELECT * FROM payment_method")
    suspend fun findAll(): List<PaymentMethodEntity>

    @Query("SELECT * FROM payment_method WHERE id = :id")
    suspend fun findById(id: Long): PaymentMethodEntity?

    @Query("SELECT * FROM payment_method WHERE code = :code")
    suspend fun findByCode(code: String): PaymentMethodEntity?
}
