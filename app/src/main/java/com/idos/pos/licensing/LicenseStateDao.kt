package com.idos.pos.licensing

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Single-row `license_state` persistence (design.md "Room schema (v2,
 * additive)"). [upsert] always targets [LicenseStateEntity.SINGLE_ROW_ID] via
 * `OnConflictStrategy.REPLACE` — inserts when no row exists yet, replaces the
 * one row when it already does — so the table never grows past one row.
 */
@Dao
interface LicenseStateDao {

    @Query("SELECT * FROM license_state WHERE id = :id")
    suspend fun find(id: Int = LicenseStateEntity.SINGLE_ROW_ID): LicenseStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LicenseStateEntity)
}
