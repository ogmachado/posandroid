package com.idos.pos.business

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Single-row `business_profile` persistence (design.md "Room schema (v3,
 * additive)"). [upsert] always targets [BusinessProfileEntity.SINGLE_ROW_ID]
 * via `OnConflictStrategy.REPLACE` — inserts when no row exists yet, replaces
 * the one row when it already does — so the table never grows past one row
 * (`business-profile` "No second profile row can be created").
 */
@Dao
interface BusinessProfileDao {

    @Query("SELECT * FROM business_profile WHERE id = :id")
    suspend fun find(id: Int = BusinessProfileEntity.SINGLE_ROW_ID): BusinessProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BusinessProfileEntity)
}
