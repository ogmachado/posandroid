package com.idos.pos.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED/GREEN (task 2.5/2.6, Robolectric + in-memory Room): the `onCreate` seed
 * callback must yield CASH (affectsCashBalance=true) / TRANSFER (false) payment
 * methods and an active USD currency — see specs/payment-method-catalog/spec.md
 * "Fresh install has both seeded methods" and specs/currency-display/spec.md
 * "Fresh install has the seeded alternative currency".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PosDatabaseSeedTest {

    private fun buildDatabase(): PosDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .addCallback(PosDatabaseSeeder.callback)
            .build()
    }

    @Test
    fun onCreate_seedsCashAndTransferPaymentMethods() = runBlocking {
        val db = buildDatabase()

        // Force onCreate to run by touching the database.
        db.openHelper.writableDatabase

        val methods = db.paymentMethodDao().findAll()
        val cash = methods.find { it.code == "CASH" }
        val transfer = methods.find { it.code == "TRANSFER" }

        assertEquals(2, methods.size)
        assertNotNull(cash)
        assertNotNull(transfer)
        assertTrue(cash!!.affectsCashBalance)
        assertTrue(!transfer!!.affectsCashBalance)

        db.close()
    }

    @Test
    fun onCreate_seedsActiveUsdCurrency() = runBlocking {
        val db = buildDatabase()

        db.openHelper.writableDatabase

        val currencies = db.currencyDao().findAll()
        val usd = currencies.find { it.code == "USD" }

        assertNotNull(usd)
        assertTrue(usd!!.active)
        assertTrue(usd.exchangeRate.signum() > 0)

        db.close()
    }
}
