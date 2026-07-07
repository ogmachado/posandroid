package com.idos.pos.catalog

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Product category catalog row. Referenced by `product.category_id` with
 * SET NULL semantics on delete (see design.md Room schema table).
 */
@Entity(
    tableName = "category",
    indices = [Index(value = ["code"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
)
