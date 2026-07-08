package com.idos.pos.currency

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.db.PosDatabaseSeeder
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED/GREEN (task 9.1, Robolectric + in-memory Room + Turbine):
 * validates [CurrencyRepository.activeCurrenciesFlow] (seeded-active-only,
 * per specs/currency-display/spec.md "Only Active Currencies Are Shown") and
 * [CurrencyRepository.convert] (`total / exchangeRate`, rounded HALF_UP to
 * scale 2, per specs/currency-display/spec.md "Conversion Row Is Computed,
 * Not Persisted").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CurrencyRepositoryTest {

    private lateinit var db: PosDatabase
    private lateinit var repository: CurrencyRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .addCallback(PosDatabaseSeeder.callback)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase // force onCreate/seed
        repository = CurrencyRepository(db.currencyDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Requirement: Seeded Currencies / Only Active Currencies Are Shown ---

    @Test
    fun activeCurrenciesFlow_onFreshInstall_emitsSeededActiveUsd() = runBlocking {
        repository.activeCurrenciesFlow.test {
            val currencies = awaitItem()
            assertEquals(1, currencies.size)
            assertEquals("USD", currencies.single().code)
            assertTrue(currencies.single().active)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun activeCurrenciesFlow_excludesInactiveCurrency() = runBlocking {
        val usd = db.currencyDao().findAll().single { it.code == "USD" }
        db.currencyDao().insert(usd.copy(id = 0, code = "EUR", active = false))

        repository.activeCurrenciesFlow.test {
            val currencies = awaitItem()
            assertEquals(1, currencies.size)
            assertEquals("USD", currencies.single().code)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Requirement: Exchange Rate Convention / Conversion Row Is Computed, Not Persisted ---

    @Test
    fun convert_dividesTotalByExchangeRate_roundedHalfUpToScale2() {
        val usd = CurrencyEntity(code = "USD", name = "US Dollar", symbol = "$", exchangeRate = BigDecimal("540"))

        val converted = repository.convert(BigDecimal("540"), usd)

        assertEquals(BigDecimal("1.00"), converted)
    }

    @Test
    fun convert_roundsHalfUp_whenDivisionIsNotExact() {
        val usd = CurrencyEntity(code = "USD", name = "US Dollar", symbol = "$", exchangeRate = BigDecimal("540"))

        // 100 / 540 = 0.185185... -> rounds HALF_UP to 0.19
        val converted = repository.convert(BigDecimal("100"), usd)

        assertEquals(BigDecimal("0.19"), converted)
    }

    @Test
    fun convert_recomputesLive_whenExchangeRateChanges() {
        val originalRate = CurrencyEntity(code = "USD", name = "US Dollar", symbol = "$", exchangeRate = BigDecimal("540"))
        val newRate = originalRate.copy(exchangeRate = BigDecimal("500"))

        val convertedAtOriginalRate = repository.convert(BigDecimal("540"), originalRate)
        val convertedAtNewRate = repository.convert(BigDecimal("540"), newRate)

        assertEquals(BigDecimal("1.00"), convertedAtOriginalRate)
        assertEquals(BigDecimal("1.08"), convertedAtNewRate)
    }
}
