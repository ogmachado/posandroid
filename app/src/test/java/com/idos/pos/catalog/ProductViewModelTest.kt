package com.idos.pos.catalog

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.core.di.AppContainer
import com.idos.pos.permission.PinGate
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests written alongside (task 4.6; `ViewModel.kt` is excluded from strict
 * TDD per openspec/config.yaml `strict_tdd_scope`). Exercises
 * [ProductViewModel.submitUpdate]'s PIN-gating decision directly against a
 * real [PinGate] — NO Compose UI is rendered here, deliberately: rendering
 * [com.idos.pos.permission.PinGateDialog] (an `AlertDialog`) together with
 * [ProductFormScreen] was found to make Robolectric's Compose idle-detection
 * hang indefinitely in this environment (see this apply batch's apply-progress
 * notes and [ProductFormScreenTest]'s doc comment for the full writeup). This
 * test proves the exact same specs/permission-gate/spec.md "PIN Gates Product
 * Price Edits" contract at the layer that actually enforces it
 * ([ProductViewModel.submitUpdate]), without depending on that flaky
 * Dialog-rendering combination. [ProductFormScreenTest] additionally proves
 * the Screen wires into this method correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductViewModelTest {

    private val correctPin = "1234"

    private lateinit var container: AppContainer
    private lateinit var viewModel: ProductViewModel
    private lateinit var existingProduct: ProductEntity

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        container = AppContainer.createInMemory(context)
        container.database.openHelper.writableDatabase

        val unitMeasureId = container.unitMeasureDao.insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
        val productId = container.catalogRepository.createProduct(
            name = "Widget",
            code = "SKU-100",
            barcode = null,
            price = BigDecimal("100.00"),
            costPrice = BigDecimal("50.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        ).getOrThrow()
        existingProduct = container.productDao.findById(productId)!!

        viewModel = ProductViewModel(container)
    }

    @After
    fun tearDown() {
        container.database.close()
    }

    /**
     * [ProductViewModel]'s update/create methods fire-and-forget a
     * `viewModelScope.launch { }` (they are not `suspend`, matching the
     * `Screen.kt` call-site contract). Room's generated suspend DAO methods
     * hop onto Room's own query executor thread before resuming, so a single
     * `shadowOf(Looper.getMainLooper()).idle()` is not always enough to
     * observe the write from this test's `runBlocking` thread. Poll instead
     * of assuming a fixed number of dispatcher hops.
     */
    private suspend fun waitUntil(timeoutMs: Long = 2_000, block: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!block()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
    }

    // --- Scenario: Correct PIN allows a price edit ---

    @Test
    fun submitUpdate_withChangedPrice_requiresPinGate_andPersistsOnlyAfterCorrectPin() = runBlocking {
        val pinGate = PinGate(verifyPin = { it == correctPin })

        viewModel.submitUpdate(
            pinGate = pinGate,
            original = existingProduct,
            name = existingProduct.name,
            code = existingProduct.code,
            barcode = existingProduct.barcode,
            price = BigDecimal("120.00"),
            costPrice = existingProduct.costPrice,
            unitMeasureId = existingProduct.unitMeasureId,
            categoryId = existingProduct.categoryId,
        )

        // The gate must fire BEFORE anything is persisted.
        assertTrue(pinGate.isVisible)
        assertEquals(BigDecimal("100.00"), container.productDao.findById(existingProduct.id)?.price)

        pinGate.submit(correctPin)
        waitUntil { container.productDao.findById(existingProduct.id)?.price == BigDecimal("120.00") }

        assertFalse(pinGate.isVisible)
        assertEquals(BigDecimal("120.00"), container.productDao.findById(existingProduct.id)?.price)
    }

    // --- Scenario: Incorrect PIN blocks a price edit ---

    @Test
    fun submitUpdate_withChangedPrice_andIncorrectPin_leavesPriceUnchanged() = runBlocking {
        val pinGate = PinGate(verifyPin = { it == correctPin })

        viewModel.submitUpdate(
            pinGate = pinGate,
            original = existingProduct,
            name = existingProduct.name,
            code = existingProduct.code,
            barcode = existingProduct.barcode,
            price = BigDecimal("120.00"),
            costPrice = existingProduct.costPrice,
            unitMeasureId = existingProduct.unitMeasureId,
            categoryId = existingProduct.categoryId,
        )

        pinGate.submit("9999")

        // Rejected — gate stays open, product untouched.
        assertTrue(pinGate.isVisible)
        assertEquals(BigDecimal("100.00"), container.productDao.findById(existingProduct.id)?.price)
    }

    // --- Requirement: PIN Verification Is a Point-in-Time Check ---

    @Test
    fun submitUpdate_withUnchangedPrice_neverOpensTheGate_andPersistsDirectly() = runBlocking {
        val pinGate = PinGate(verifyPin = { it == correctPin })

        viewModel.submitUpdate(
            pinGate = pinGate,
            original = existingProduct,
            name = "Widget Renamed",
            code = existingProduct.code,
            barcode = existingProduct.barcode,
            price = existingProduct.price, // unchanged
            costPrice = existingProduct.costPrice,
            unitMeasureId = existingProduct.unitMeasureId,
            categoryId = existingProduct.categoryId,
        )

        waitUntil { container.productDao.findById(existingProduct.id)?.name == "Widget Renamed" }
        assertFalse(pinGate.isVisible)
        assertEquals("Widget Renamed", container.productDao.findById(existingProduct.id)?.name)
    }

    @Test
    fun createProduct_isNeverPinGated() = runBlocking {
        val pinGate = PinGate(verifyPin = { it == correctPin })

        viewModel.createProduct(
            name = "New Widget",
            code = "SKU-200",
            barcode = null,
            price = BigDecimal("30.00"),
            costPrice = BigDecimal("10.00"),
            unitMeasureId = existingProduct.unitMeasureId,
            categoryId = null,
        )

        waitUntil { container.productDao.findByCode("SKU-200") != null }
        assertFalse(pinGate.isVisible)
        assertNull(viewModel.lastError.value)
    }
}
