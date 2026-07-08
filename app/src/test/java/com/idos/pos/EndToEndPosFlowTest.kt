package com.idos.pos

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.cashsession.CashMovementType
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.core.di.AppContainer
import com.idos.pos.inventory.MovementType
import com.idos.pos.sales.CartViewModel
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Task 10.1 — end-to-end integration pass (Robolectric + in-memory Room,
 * wired through the real [AppContainer], the same production DI graph
 * [PosApplication] builds). No Android SDK/emulator is configured in this
 * apply environment — confirmed via `local.properties`/absence of a running
 * `adb` device — so this exercises everything that CAN run on the JVM
 * (every repository together, the exact "single writer, single Room
 * transaction" path design.md describes) rather than a real
 * `connectedAndroidTest`. Camera/MLKit scanning itself is out of reach here
 * (same limitation already documented on
 * [com.idos.pos.scan.BarcodeScanPipelineTest], task 6.4) — this test
 * substitutes the scan step with [com.idos.pos.catalog.CatalogRepository.findByBarcode]
 * directly, which is exactly what [com.idos.pos.scan.ScanViewModel] calls once
 * MLKit hands it a decoded string, so the domain-level "scan resolves to a
 * product" behavior IS covered end-to-end; only the camera/MLKit decode step
 * itself is not.
 *
 * Exercises proposal.md's "Success Criteria" checklist:
 * - "Operator can ... look one up by camera barcode scan" (barcode-lookup
 *   half; see limitation above).
 * - "Stock changes only via ledger movements; a sale writes an atomic OUT +
 *   order in one transaction" — verified via [com.idos.pos.inventory.InventoryDao]
 *   state and movement count after [CartViewModel.confirmSale].
 * - "A shift opens, records sales/cash movements, and closes with a correct
 *   expectedBalance" — verified via [com.idos.pos.cashsession.CashSessionRepository].
 * - "Order total shows an alt-currency (USD) conversion row" — verified via
 *   [com.idos.pos.currency.CurrencyRepository.convert] against the order's
 *   persisted total.
 *
 * NOT exercised here (covered by their own dedicated test classes, listed for
 * traceability rather than re-tested redundantly):
 * - "Price edits and ADJUST movements are blocked without the manager PIN" —
 *   [com.idos.pos.catalog.ProductViewModelTest], [com.idos.pos.catalog.ProductFormScreenTest],
 *   [com.idos.pos.inventory.InventoryViewModelTest], [com.idos.pos.permission.PinGateDialogTest].
 * - "License enforcement is explicitly absent" — proposal-level statement
 *   about `android-pos-licensing` being a separate, not-yet-started change;
 *   there is no license code in this repository to assert against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EndToEndPosFlowTest {

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        container = AppContainer.createInMemory(context)
        container.database.openHelper.writableDatabase // force onCreate/seed
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

    @Test
    fun scanCartSaleStockCloseSession_endToEnd_producesConsistentState() = runBlocking {
        // Given: a seeded product with a barcode and 10 units of stock, and no
        // open cash session yet.
        val unitMeasureId = container.unitMeasureDao.insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
        val productId = container.catalogRepository.createProduct(
            name = "Widget",
            code = "SKU-E2E-1",
            barcode = "7501234567890",
            price = BigDecimal("30.00"),
            costPrice = BigDecimal("12.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        ).getOrThrow()
        container.inventoryRepository.applyMovement(productId, MovementType.IN, 10)

        // When: the operator scans the barcode (domain-level: findByBarcode
        // resolves the product, the same call ScanViewModel makes after MLKit
        // decodes a frame — see class doc)
        val scannedProduct = container.catalogRepository.findByBarcode("7501234567890")
        assertNotNull("Scan lookup must resolve the seeded product", scannedProduct)

        // AND the operator opens a shift before selling
        container.cashSessionRepository.open(BigDecimal("100.00"))

        // AND adds the scanned product to the cart and confirms the sale
        val cartViewModel = CartViewModel(container)
        cartViewModel.addToCart(scannedProduct!!, quantity = 2)
        cartViewModel.confirmSale(payment = BigDecimal("60.00"), paymentMethodId = null)
        waitUntil { cartViewModel.lastCompletedOrderId.value != null }

        // Then: the sale succeeded with no error, and the order is the single
        // atomic unit design.md describes (order + lines + stock decrement)
        assertNull(cartViewModel.lastError.value)
        val orderId = cartViewModel.lastCompletedOrderId.value!!
        val order = container.salesDao.findById(orderId)
        assertNotNull(order)
        assertEquals(BigDecimal("60.00"), order?.total)

        // AND stock decremented by exactly the sold quantity, via the ledger
        // (single mutation owner) — never any other path
        val inventoryRow = container.inventoryDao.findByProductId(productId)
        assertEquals(8, inventoryRow?.stock)
        val movements = container.inventoryDao.movementsForProductFlow(productId).first()
        assertEquals(2, movements.size) // IN (setup) + OUT (sale)
        assertTrue(movements.any { it.type == MovementType.OUT && it.orderId == orderId })

        // AND the order total shows an alt-currency (USD) conversion row,
        // computed at render time from the persisted total — never persisted
        // on the order itself
        val usd = container.currencyDao.findActiveFlow().first().single { it.code == "USD" }
        val convertedTotal = container.currencyRepository.convert(order!!.total, usd)
        assertEquals(BigDecimal("0.11"), convertedTotal) // 60.00 / 540 = 0.1111... -> HALF_UP 0.11

        // AND a manual cash deposit is recorded on the same open shift
        container.cashSessionRepository.addMovement(CashMovementType.DEPOSIT, BigDecimal("15.00"), "float top-up")

        // When: the shift is closed
        val closeResult = container.cashSessionRepository.close(BigDecimal("175.00"))

        // Then: expectedBalance = opening(100) + deposit(15) + cash sale(60) = 175,
        // and the counted amount matches exactly (difference = 0)
        assertTrue(closeResult.isSuccess)
        val closedSession = closeResult.getOrThrow()
        assertEquals(BigDecimal("175.00"), closedSession.expectedBalance)
        assertEquals(BigDecimal.ZERO.setScale(2), closedSession.difference)
    }
}
