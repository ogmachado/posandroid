package com.idos.pos.permission

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * `app_user` persistence (design.md "Room schema (v3, additive)" +
 * "Interfaces"). [insert] uses `OnConflictStrategy.ABORT` so a duplicate
 * `username` (unique index) fails loudly rather than silently overwriting an
 * existing user (`user-identity` "no shared credential").
 */
@Dao
interface UserDao {

    @Query("SELECT COUNT(*) FROM app_user")
    suspend fun count(): Int

    @Query("SELECT * FROM app_user WHERE role = :role")
    suspend fun findByRole(role: String): List<UserEntity>

    @Query("SELECT * FROM app_user WHERE username = :username")
    suspend fun findByUsername(username: String): UserEntity?

    @Query("SELECT * FROM app_user")
    suspend fun findAll(): List<UserEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: UserEntity): Long

    @Query("UPDATE app_user SET pinSalt = :salt, pinHash = :hash WHERE id = :id")
    suspend fun updatePin(id: Long, salt: ByteArray, hash: ByteArray)
}
