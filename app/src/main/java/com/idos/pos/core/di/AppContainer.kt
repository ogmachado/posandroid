package com.idos.pos.core.di

import android.content.Context
import androidx.room.Room
import com.idos.pos.catalog.CatalogRepository
import com.idos.pos.catalog.CategoryDao
import com.idos.pos.catalog.ProductDao
import com.idos.pos.catalog.UnitMeasureDao
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.db.PosDatabaseSeeder
import com.idos.pos.currency.CurrencyDao
import com.idos.pos.inventory.InventoryDao
import com.idos.pos.inventory.InventoryRepository
import com.idos.pos.permission.PinRepository
import com.idos.pos.sales.PaymentMethodDao

/**
 * Manual DI service-locator (see design.md "Decision: Manual DI via AppContainer
 * service-locator + Compose bridge"). Built once in [com.idos.pos.PosApplication.onCreate]
 * and held for the app's lifetime; exposed to Compose through [LocalAppContainer].
 *
 * Repository slots are filled in per capability phase — Phases 1-3 expose the
 * seeded-catalog DAOs directly (no repository layer exists for them yet) plus
 * [PinRepository]. Phase 4 (task 4.7) added [productDao]/[catalogRepository].
 * Phase 5 (task 5.6) adds [inventoryDao]/[inventoryRepository] — [catalogRepository]
 * now depends on [inventoryRepository] to seed a zero-stock row on product
 * creation (closes `CatalogRepository.createProduct`'s former Phase 4 TODO).
 * Sales/cash-session repositories land with their own phases.
 */
class AppContainer private constructor(
    val database: PosDatabase,
    context: Context,
) {
    val unitMeasureDao: UnitMeasureDao get() = database.unitMeasureDao()
    val categoryDao: CategoryDao get() = database.categoryDao()
    val paymentMethodDao: PaymentMethodDao get() = database.paymentMethodDao()
    val currencyDao: CurrencyDao get() = database.currencyDao()
    val productDao: ProductDao get() = database.productDao()
    val inventoryDao: InventoryDao get() = database.inventoryDao()

    val pinRepository: PinRepository = PinRepository(context)
    val inventoryRepository: InventoryRepository by lazy { InventoryRepository(inventoryDao) }
    val catalogRepository: CatalogRepository by lazy { CatalogRepository(productDao, unitMeasureDao, inventoryRepository) }

    companion object {
        private const val DATABASE_NAME = "idos-pos.db"

        /** Production factory — file-backed database, seeded via [PosDatabaseSeeder]. */
        fun create(context: Context): AppContainer {
            val db = Room.databaseBuilder(context, PosDatabase::class.java, DATABASE_NAME)
                .addCallback(PosDatabaseSeeder.callback)
                .build()
            return AppContainer(db, context)
        }

        /**
         * Test-double factory (task 1.7) — in-memory database, still seeded via the
         * same [PosDatabaseSeeder] callback so Robolectric repo tests see the same
         * seeded catalog a production run would. Reused by every later repo test.
         */
        fun createInMemory(context: Context): AppContainer {
            val db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
                .addCallback(PosDatabaseSeeder.callback)
                .allowMainThreadQueries()
                .build()
            return AppContainer(db, context)
        }
    }
}
