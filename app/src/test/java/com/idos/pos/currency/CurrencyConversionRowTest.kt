package com.idos.pos.currency

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.db.PosDatabaseSeeder
import java.math.BigDecimal
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI test written alongside (task 9.2; `Screen.kt`-adjacent Compose
 * UI is excluded from strict TDD per openspec/config.yaml `strict_tdd_scope`
 * — this composable lives in a non-`Screen.kt`/non-`ViewModel.kt` file but is
 * pure presentational Compose, so it follows the same "written alongside"
 * convention as every other Compose UI test in this codebase, e.g.
 * [com.idos.pos.catalog.ProductFormScreenTest]).
 *
 * Covers specs/currency-display/spec.md's two render-time scenarios:
 * "Order total shows a USD conversion row" (recomputes live when [amount]
 * changes) and "Inactive currency produces no row".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class CurrencyConversionRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: PosDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun conversionRow_recomputesLive_whenAmountChanges() {
        runBlockingInsert(CurrencyEntity(code = "USD", name = "US Dollar", symbol = "$", exchangeRate = BigDecimal("540"), active = true))
        val repository = CurrencyRepository(db.currencyDao())
        var amount by mutableStateOf(BigDecimal("540"))

        composeTestRule.setContent {
            CurrencyConversionRows(amount = amount, repository = repository)
        }

        composeTestRule.onNodeWithTag(currencyConversionRowTestTag("USD")).assertTextEquals("1.00 USD")

        amount = BigDecimal("1080")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(currencyConversionRowTestTag("USD")).assertTextEquals("2.00 USD")
    }

    @Test
    fun conversionRow_inactiveCurrency_rendersNoRow() {
        runBlockingInsert(CurrencyEntity(code = "USD", name = "US Dollar", symbol = "$", exchangeRate = BigDecimal("540"), active = false))
        val repository = CurrencyRepository(db.currencyDao())

        composeTestRule.setContent {
            CurrencyConversionRows(amount = BigDecimal("540"), repository = repository)
        }

        composeTestRule.onNodeWithTag(currencyConversionRowTestTag("USD")).assertDoesNotExist()
    }

    private fun runBlockingInsert(entity: CurrencyEntity) = kotlinx.coroutines.runBlocking {
        db.currencyDao().insert(entity)
    }
}
