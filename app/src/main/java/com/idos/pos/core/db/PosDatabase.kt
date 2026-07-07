package com.idos.pos.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.idos.pos.catalog.CategoryDao
import com.idos.pos.catalog.CategoryEntity
import com.idos.pos.catalog.UnitMeasureDao
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.currency.CurrencyDao
import com.idos.pos.currency.CurrencyEntity
import com.idos.pos.sales.PaymentMethodDao
import com.idos.pos.sales.PaymentMethodEntity

/**
 * `@Database` v1 — no migration history (greenfield, see design.md "Migration /
 * Rollout"). Entities/DAOs are registered incrementally per capability phase;
 * this apply batch (Phases 1-3) registers the four seeded-catalog entities from
 * Phase 2 (task 2.7). Later phases (product, inventory, cash session, sales)
 * add their own entities/DAOs here.
 */
@Database(
    entities = [
        UnitMeasureEntity::class,
        CategoryEntity::class,
        PaymentMethodEntity::class,
        CurrencyEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PosDatabase : RoomDatabase() {
    abstract fun unitMeasureDao(): UnitMeasureDao
    abstract fun categoryDao(): CategoryDao
    abstract fun paymentMethodDao(): PaymentMethodDao
    abstract fun currencyDao(): CurrencyDao
}
