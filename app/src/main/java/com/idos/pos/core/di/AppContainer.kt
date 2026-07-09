package com.idos.pos.core.di

import android.content.Context
import androidx.room.Room
import com.idos.pos.R
import com.idos.pos.cashsession.CashSessionDao
import com.idos.pos.cashsession.CashSessionRepository
import com.idos.pos.catalog.CatalogRepository
import com.idos.pos.catalog.CategoryDao
import com.idos.pos.catalog.ProductDao
import com.idos.pos.catalog.UnitMeasureDao
import com.idos.pos.core.db.MIGRATION_1_2
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.db.PosDatabaseSeeder
import com.idos.pos.currency.CurrencyDao
import com.idos.pos.currency.CurrencyRepository
import com.idos.pos.inventory.InventoryDao
import com.idos.pos.inventory.InventoryRepository
import com.idos.pos.licensing.ClockRollbackDetector
import com.idos.pos.licensing.InstallationIdStore
import com.idos.pos.licensing.LicenseRepository
import com.idos.pos.licensing.LicenseStateDao
import com.idos.pos.licensing.LicenseVerifier
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
 * [currencyRepository]. `android-pos-licensing` Phase 3 (task 3.3) adds
 * [licenseStateDao]/[licenseRepository] and registers [MIGRATION_1_2] on the
 * production [create] builder (per `core/db/PosDatabase.kt` and
 * `core/db/Migrations.kt`'s own KDoc, which name this file+task explicitly).
 *
 * **[licenseRepository] wiring note**: [LicenseVerifier] is constructed from
 * `R.raw.idos_vendor_pub` — a **THROWAWAY placeholder DER public key**
 * generated for this phase only (no matching private key exists anywhere in
 * this repo). This MUST be replaced with the real vendor public key
 * (`idos-pos/license/src/main/resources/keys/idos-vendor.pub`, converted to
 * X.509 DER per design.md Decision B) before any signed/production build —
 * no task in `tasks.md` currently owns that swap; flagged as a follow-up.
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
    val licenseStateDao: LicenseStateDao get() = database.licenseStateDao()

    val pinRepository: PinRepository = PinRepository(context)
    val inventoryRepository: InventoryRepository by lazy { InventoryRepository(inventoryDao) }
    val catalogRepository: CatalogRepository by lazy { CatalogRepository(productDao, unitMeasureDao, inventoryRepository) }
    val cashSessionRepository: CashSessionRepository by lazy { CashSessionRepository(cashSessionDao) }
    val salesRepository: SalesRepository by lazy {
        SalesRepository(database, salesDao, inventoryDao, paymentMethodDao, cashSessionDao)
    }
    val currencyRepository: CurrencyRepository by lazy { CurrencyRepository(currencyDao) }
    val licenseRepository: LicenseRepository by lazy {
        val publicKey = LicenseVerifier.loadPublicKey(context.resources.openRawResource(R.raw.idos_vendor_pub).use { it.readBytes() })
        val verifier = LicenseVerifier(
            publicKey = publicKey,
            expectedIssuer = EXPECTED_ISSUER,
            graceDays = LicenseRepository.GRACE_PERIOD_DAYS,
        )
        val idStore = InstallationIdStore(context)
        val detector = ClockRollbackDetector(toleranceSec = LicenseRepository.CLOCK_TOLERANCE_SECONDS)
        LicenseRepository.create(licenseStateDao, verifier, idStore, detector)
    }

    companion object {
        private const val DATABASE_NAME = "idos-pos.db"

        /** Reuses the reference backend's `idos.license.expected-issuer` default (design.md "Claim shape"). */
        private const val EXPECTED_ISSUER = "idos-vendor-prod"

        /**
         * Production factory — file-backed database, seeded via [PosDatabaseSeeder].
         * Registers [MIGRATION_1_2] (v1 -> v2, additive `license_state` table) —
         * required so an existing on-device database upgrades instead of crashing.
         */
        fun create(context: Context): AppContainer {
            val db = Room.databaseBuilder(context, PosDatabase::class.java, DATABASE_NAME)
                .addCallback(PosDatabaseSeeder.callback)
                .addMigrations(MIGRATION_1_2)
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
