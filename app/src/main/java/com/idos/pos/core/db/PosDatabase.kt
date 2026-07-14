package com.idos.pos.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.idos.pos.business.BusinessProfileDao
import com.idos.pos.business.BusinessProfileEntity
import com.idos.pos.cashsession.CashMovementEntity
import com.idos.pos.cashsession.CashSessionDao
import com.idos.pos.cashsession.CashSessionEntity
import com.idos.pos.catalog.CategoryDao
import com.idos.pos.catalog.CategoryEntity
import com.idos.pos.catalog.ProductDao
import com.idos.pos.catalog.ProductEntity
import com.idos.pos.catalog.UnitMeasureDao
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.currency.CurrencyDao
import com.idos.pos.currency.CurrencyEntity
import com.idos.pos.inventory.InventoryDao
import com.idos.pos.inventory.InventoryEntity
import com.idos.pos.inventory.InventoryMovementEntity
import com.idos.pos.licensing.LicenseStateDao
import com.idos.pos.licensing.LicenseStateEntity
import com.idos.pos.permission.UserDao
import com.idos.pos.permission.UserEntity
import com.idos.pos.sales.OrderEntity
import com.idos.pos.sales.OrderLineEntity
import com.idos.pos.sales.PaymentMethodDao
import com.idos.pos.sales.PaymentMethodEntity
import com.idos.pos.sales.SalesDao

/**
 * `@Database` v1-history + v2 (see design.md "Migration / Rollout"). Entities/
 * DAOs are registered incrementally per capability phase; Phases 1-3
 * registered the four seeded-catalog entities, Phase 4 (task 4.7) added
 * [ProductEntity]/[ProductDao], Phase 5 (task 5.6) added
 * [InventoryEntity]/[InventoryMovementEntity]/[InventoryDao], and Phase 7
 * (task 7.6) adds [CashSessionEntity]/[CashMovementEntity]/[CashSessionDao].
 * Phase 8 (task 8.5) adds [OrderEntity]/[OrderLineEntity]/[SalesDao]. Phase 2
 * of `android-pos-licensing` (task 2.7) is the first actual migration:
 * v1 -> v2 additively registers [LicenseStateEntity]/[LicenseStateDao] —
 * see `Migrations.kt`'s [MIGRATION_1_2]. Wiring `.addMigrations(MIGRATION_1_2)`
 * into the production `Room.databaseBuilder` call
 * (`core/di/AppContainer.kt`) is Phase 3 (PR3) — out of scope here.
 * Phase 1 of `android-pos-auth` (task 1.10) is the second migration: v2 -> v3
 * additively registers [UserEntity]/[UserDao] and
 * [BusinessProfileEntity]/[BusinessProfileDao] — see `Migrations.kt`'s
 * [MIGRATION_2_3], registered on the production `Room.databaseBuilder` call
 * in the same PR (task 1.17).
 */
@Database(
    entities = [
        UnitMeasureEntity::class,
        CategoryEntity::class,
        PaymentMethodEntity::class,
        CurrencyEntity::class,
        ProductEntity::class,
        InventoryEntity::class,
        InventoryMovementEntity::class,
        CashSessionEntity::class,
        CashMovementEntity::class,
        OrderEntity::class,
        OrderLineEntity::class,
        LicenseStateEntity::class,
        UserEntity::class,
        BusinessProfileEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PosDatabase : RoomDatabase() {
    abstract fun unitMeasureDao(): UnitMeasureDao
    abstract fun categoryDao(): CategoryDao
    abstract fun paymentMethodDao(): PaymentMethodDao
    abstract fun currencyDao(): CurrencyDao
    abstract fun productDao(): ProductDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun cashSessionDao(): CashSessionDao
    abstract fun salesDao(): SalesDao
    abstract fun licenseStateDao(): LicenseStateDao
    abstract fun userDao(): UserDao
    abstract fun businessProfileDao(): BusinessProfileDao
}
