package com.idos.pos.business

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton row for the device's single business profile (design.md "Room
 * schema (v3, additive)"; `business-profile` spec "Exactly One Business
 * Profile Row Exists"). [BusinessProfileDao.upsert] always writes
 * [SINGLE_ROW_ID] via `OnConflictStrategy.REPLACE`, so this table never holds
 * more than one row — the same `SINGLE_ROW_ID` pattern
 * [com.idos.pos.licensing.LicenseStateEntity] already uses.
 */
@Entity(tableName = "business_profile")
data class BusinessProfileEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val name: String,
    val address: String,
    val phone: String,
) {
    companion object {
        const val SINGLE_ROW_ID = 1
    }
}
