package com.idos.pos.core.di

import android.content.Context
import androidx.room.Room
import com.idos.pos.catalog.CategoryDao
import com.idos.pos.catalog.UnitMeasureDao
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.db.PosDatabaseSeeder
import com.idos.pos.currency.CurrencyDao
import com.idos.pos.permission.PinRepository
import com.idos.pos.sales.PaymentMethodDao

/**
 * Manual DI service-locator (see design.md "Decision: Manual DI via AppContainer
 * service-locator + Compose bridge"). Built once in [com.idos.pos.PosApplication.onCreate]
 * and held for the app's lifetime; exposed to Compose through [LocalAppContainer].
 *
 * Repository slots are filled in per capability phase — Phases 1-3 (this apply
 * batch) expose the seeded-catalog DAOs directly (no repository layer exists for
 * them yet) plus [PinRepository]. Product/inventory/sales/cash-session
 * repositories land with their own phases.
 */
class AppContainer private constructor(
    val database: PosDatabase,
    context: Context,
) {
    val unitMeasureDao: UnitMeasureDao get() = database.unitMeasureDao()
    val categoryDao: CategoryDao get() = database.categoryDao()
    val paymentMethodDao: PaymentMethodDao get() = database.paymentMethodDao()
    val currencyDao: CurrencyDao get() = database.currencyDao()

    val pinRepository: PinRepository = PinRepository(context)

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
