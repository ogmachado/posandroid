package com.idos.pos.core.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.math.BigDecimal

/**
 * `RoomDatabase.Callback.onCreate` seeder (task 2.6). Runs exactly once, the first
 * time the database file is created, and seeds:
 * - `payment_method`: CASH (`affectsCashBalance = true`) and TRANSFER (`false`),
 *   per specs/payment-method-catalog/spec.md "Seeded Payment Methods".
 * - `currency`: one active USD row, per specs/currency-display/spec.md "Seeded
 *   Currencies". CUP is the implicit primary currency and is NOT a row here.
 *
 * Raw SQL (not DAOs) is used deliberately: `onCreate` runs on the [SupportSQLiteDatabase]
 * before Room has finished wiring the `RoomDatabase` instance that would expose the
 * generated DAOs, so DAO access is not available at this callback point.
 */
object PosDatabaseSeeder {

    /**
     * Placeholder default USD exchange rate: units of CUP per 1 USD. This is a
     * real-world financial value that changes over time — see
     * specs/currency-display/spec.md "Design-Level / Data-Level Open Questions".
     * It MUST be treated as an editable configuration seed, never as a permanent
     * hardcoded business rule. Reconfigure it (or expose an edit path) before
     * relying on it in a live deployment.
     */
    const val DEFAULT_USD_EXCHANGE_RATE = "540"

    val callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            seedPaymentMethods(db)
            seedCurrencies(db)
        }
    }

    private fun seedPaymentMethods(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO payment_method (code, name, affectsCashBalance, active)
            VALUES ('CASH', 'Efectivo', 1, 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO payment_method (code, name, affectsCashBalance, active)
            VALUES ('TRANSFER', 'Transferencia', 0, 1)
            """.trimIndent(),
        )
    }

    private fun seedCurrencies(db: SupportSQLiteDatabase) {
        val rate = BigDecimal(DEFAULT_USD_EXCHANGE_RATE).toPlainString()
        db.execSQL(
            """
            INSERT INTO currency (code, name, symbol, exchangeRate, active, displayOrder)
            VALUES ('USD', 'US Dollar', '$', '$rate', 1, 0)
            """.trimIndent(),
        )
    }
}
