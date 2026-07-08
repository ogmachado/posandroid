package com.idos.pos.core.di

import android.content.Context
import androidx.room.Room
import com.idos.pos.cashsession.CashSessionDao
import com.idos.pos.cashsession.CashSessionRepository
import com.idos.pos.catalog.CatalogRepository
import com.idos.pos.catalog.CategoryDao
import com.idos.pos.catalog.ProductDao
import com.idos.pos.catalog.UnitMeasureDao
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.db.PosDatabaseSeeder
import com.idos.pos.currency.CurrencyDao
import com.idos.pos.currency.CurrencyRepository
import com.idos.pos.inventory.InventoryDao
import com.idos.pos.inventory.InventoryRepository
import com.idos.pos.permission.PinRepository
import com.idos.pos.sales.PaymentMethodDao
import com.idos.pos.sales.SalesDao
import com.idos.pos.sales.SalesRepository

/**
 * Manual DI service-locator (see design.md "Decision: Manual DI via AppContainer
 * service-locator + Compose bridge"). Built once in [com.idos.pos.PosApplication.onCreate]
 * and held for the app's lifetime; exposed to Compose through [LocalAppContainer].
 *
 * Repository slots are filled in per capability phase — Phases 1-3 expose the
 * seeded-catalog DAOs directly (no repository layer exists for them yet) plus
 * [PinRepository]. Phase 4 (task 4.7) added [productDao]/[catalogRepository].
 * Phase 5 (task 5.6) added [inventoryDao]/[inventoryRepository] — [catalogRepository]
 * now depends on [inventoryRepository] to seed a zero-stock row on product
 * creation (closes `CatalogRepository.createProduct`'s former Phase 4 TODO).
 * Phase 7 (task 7.6) adds [cashSessionDao]/[cashSessionRepository]. Phase 8
 * (task 8.5) adds [salesDao]/[salesRepository]. Phase 9 (task 9.1) adds
 * [currencyRepository].
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
    val cashSessionDao: CashSessionDao get() = database.cashSessionDao()
    val salesDao: SalesDao get() = database.salesDao()

    val pinRepository: PinRepository = PinRepository(context)
    val inventoryRepository: InventoryRepository by lazy { InventoryRepository(inventoryDao) }
    val catalogRepository: CatalogRepository by lazy { CatalogRepository(productDao, unitMeasureDao, inventoryRepository) }
    val cashSessionRepository: CashSessionRepository by lazy { CashSessionRepository(cashSessionDao) }
    val salesRepository: SalesRepository by lazy {
        SalesRepository(database, salesDao, inventoryDao, paymentMethodDao, cashSessionDao)
    }
    val currencyRepository: CurrencyRepository by lazy { CurrencyRepository(currencyDao) }

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
