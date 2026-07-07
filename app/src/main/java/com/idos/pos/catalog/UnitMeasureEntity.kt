package com.idos.pos.catalog

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Unit-of-measure catalog row (e.g. "unit", "kg", "liter"). Referenced by
 * `product.unit_measure_id` with RESTRICT semantics (see design.md Room schema table).
 */
@Entity(
    tableName = "unit_measure",
    indices = [Index(value = ["code"], unique = true)],
)
data class UnitMeasureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
)
