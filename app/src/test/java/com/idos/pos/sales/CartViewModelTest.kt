package com.idos.pos.sales

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.core.di.AppContainer
import com.idos.pos.core.domain.DomainError
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Test written alongside (task 8.4; `ViewModel.kt` is excluded from strict
 * TDD per openspec/config.yaml `strict_tdd_scope`). Exercises
 * [CartViewModel.addToCart]/[CartViewModel.confirmSale] against a real
 * in-memory [AppContainer]/[SalesRepository] — same setup convention as
 * [com.idos.pos.scan.ScanViewModelTest]/[com.idos.pos.inventory.InventoryViewModelTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CartViewModelTest {

    private lateinit var container: AppContainer
    private lateinit var viewModel: CartViewModel
    private var productId: Long = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        container = AppContainer.createInMemory(context)
        container.database.openHelper.writableDatabase // force onCreate/seed

        val unitMeasureId = container.unitMeasureDao.insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
        productId = container.catalogRepository.createProduct(
            name = "Widget",
            code = "SKU-CART-1",
            barcode = null,
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("4.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        ).getOrThrow()
        container.inventoryRepository.applyMovement(productId, com.idos.pos.inventory.MovementType.IN, 10)

        viewModel = CartViewModel(container)
    }

    @After
    fun tearDown() {
        container.database.close()
    }

    private suspend fun waitUntil(timeoutMs: Long = 2_000, block: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!block()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
    }

    // --- addToCart ---

    @Test
    fun addToCart_snapshotsProductPriceAndCostPrice_intoTheCartLine() = runBlocking {
        val product = container.productDao.findById(productId)!!

        viewModel.addToCart(product)

        val line = viewModel.cart.value.lines.single()
        assertEquals(BigDecimal("10.00"), line.price)
        assertEquals(BigDecimal("4.00"), line.costPrice)
        assertEquals(1, line.quantity)
    }

    @Test
    fun addToCart_sameProductTwice_incrementsQuantity_insteadOfDuplicatingLine() = runBlocking {
        val product = container.productDao.findById(productId)!!

        viewModel.addToCart(product)
        viewModel.addToCart(product)

        assertEquals(1, viewModel.cart.value.lines.size)
        assertEquals(2, viewModel.cart.value.lines.single().quantity)
    }

    @Test
    fun addToCart_doesNotChangeAlreadyAddedLine_whenProductPriceChangesAfterward() = runBlocking {
        val product = container.productDao.findById(productId)!!
        viewModel.addToCart(product)

        // A later price edit must not retroactively change the already-added line
        container.catalogRepository.updateProduct(
            id = productId,
            name = "Widget",
            code = "SKU-CART-1",
            barcode = null,
            price = BigDecimal("99.00"),
            costPrice = BigDecimal("4.00"),
            unitMeasureId = product.unitMeasureId,
            categoryId = null,
        )

        assertEquals(BigDecimal("10.00"), viewModel.cart.value.lines.single().price)
    }

    // --- confirmSale ---

    @Test
    fun confirmSale_withOpenSessionAndValidCart_clearsCart_andExposesTheNewOrderId() = runBlocking {
        container.cashSessionRepository.open(BigDecimal("100.00"))
        val product = container.productDao.findById(productId)!!
        viewModel.addToCart(product)

        viewModel.confirmSale(payment = BigDecimal("10.00"), paymentMethodId = null)

        waitUntil { viewModel.lastCompletedOrderId.value != null }
        assertTrue(viewModel.cart.value.lines.isEmpty())
        assertNull(viewModel.lastError.value)
    }

    @Test
    fun confirmSale_withNoOpenSession_leavesCartIntact_andSurfacesNoOpenSessionError() = runBlocking {
        val product = container.productDao.findById(productId)!!
        viewModel.addToCart(product)

        viewModel.confirmSale(payment = BigDecimal("10.00"), paymentMethodId = null)

        waitUntil { viewModel.lastError.value != null }
        assertEquals(DomainError.NoOpenSession, viewModel.lastError.value)
        assertEquals(1, viewModel.cart.value.lines.size)
    }
}
