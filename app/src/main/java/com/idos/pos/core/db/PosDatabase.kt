package com.idos.pos.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.idos.pos.catalog.CategoryDao
import com.idos.pos.catalog.CategoryEntity
import com.idos.pos.catalog.ProductDao
import com.idos.pos.catalog.ProductEntity
import com.idos.pos.catalog.UnitMeasureDao
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.currency.CurrencyDao
import com.idos.pos.currency.CurrencyEntity
import com.idos.pos.sales.PaymentMethodDao
import com.idos.pos.sales.PaymentMethodEntity

/**
 * `@Database` v1 — no migration history (greenfield, see design.md "Migration /
 * Rollout"). Entities/DAOs are registered incrementally per capability phase;
 * Phases 1-3 registered the four seeded-catalog entities, and Phase 4 (task 4.7)
 * adds [ProductEntity]/[ProductDao]. Later phases (inventory, cash session,
 * sales) add their own entities/DAOs here.
 */
@Database(
    entities = [
        UnitMeasureEntity::class,
        CategoryEntity::class,
        PaymentMethodEntity::class,
        CurrencyEntity::class,
        ProductEntity::class,
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
    abstract fun productDao(): ProductDao
}
