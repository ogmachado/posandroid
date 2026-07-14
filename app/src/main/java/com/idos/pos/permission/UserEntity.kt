package com.idos.pos.permission

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Row for the `app_user` table (design.md "Room schema (v3, additive)"): a
 * named, individually-credentialed user with exactly one role
 * (`user-identity` spec). `username` carries a unique index — no two users
 * may share an identifier, and [UserDao.insert] uses
 * `OnConflictStrategy.ABORT` so a duplicate insert fails loudly rather than
 * silently overwriting an existing row.
 *
 * `role` is stored as [UserRole.name] (a plain `TEXT` column, no
 * `Converters.kt` change) and mapped to [UserRole] at the repository
 * boundary. `pinSalt`/`pinHash` are [com.idos.pos.permission.PinHasher]
 * output — PINs are never stored in plaintext (`user-identity` "PIN
 * Credentials Are Hashed At Rest").
 */
@Entity(tableName = "app_user", indices = [Index(value = ["username"], unique = true)])
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val role: String,
    val pinSalt: ByteArray,
    val pinHash: ByteArray,
)
