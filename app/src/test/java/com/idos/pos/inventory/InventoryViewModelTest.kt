package com.idos.pos.inventory

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.core.di.AppContainer
import com.idos.pos.core.domain.DomainError
import com.idos.pos.permission.PinGate
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
 * Tests written alongside (task 5.5; `ViewModel.kt` is excluded from strict
 * TDD per openspec/config.yaml `strict_tdd_scope`). Exercises
 * [InventoryViewModel.recordMovement]'s ADJUST PIN-gating decision directly
 * against a real [PinGate] — mirrors [com.idos.pos.catalog.ProductViewModelTest]
 * (task 4.6), including the deliberate choice to not render
 * [com.idos.pos.permission.PinGateDialog] here (see that class's doc for the
 * Robolectric Compose idle-detection hang this avoids).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InventoryViewModelTest {

    private val correctPin = "1234"

    private lateinit var container: AppContainer
    private lateinit var viewModel: InventoryViewModel
    private var productId: Long = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        container = AppContainer.createInMemory(context)
        container.database.openHelper.writableDatabase

        val unitMeasureId = container.unitMeasureDao.insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
        productId = container.catalogRepository.createProduct(
            name = "Widget",
            code = "SKU-700",
            barcode = null,
            price = BigDecimal("10.00"),
            costPrice = BigDecimal("5.00"),
            unitMeasureId = unitMeasureId,
            categoryId = null,
        ).getOrThrow()

        viewModel = InventoryViewModel(container)
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

    // --- IN/OUT are never gated ---

    @Test
    fun recordMovement_withInType_neverOpensPinGate_andPersistsDirectly() = runBlocking {
        val pinGate = PinGate(verifyPin = { it == correctPin })

        viewModel.recordMovement(pinGate, productId, MovementType.IN, 20, description = null)

        waitUntil { container.inventoryRepository.stockFlowValue(productId) == 20 }
        assertFalse(pinGate.isVisible)
        assertNull(viewModel.lastError.value)
    }

    @Test
    fun recordMovement_withOutType_neverOpensPinGate() = runBlocking {
        val pinGate = PinGate(verifyPin = { it == correctPin })
        viewModel.recordMovement(pinGate, productId, MovementType.IN, 20, description = null)
        waitUntil { container.inventoryRepository.stockFlowValue(productId) == 20 }
        // InventoryViewModel.isSaving's guard (double-click regression fix)
        // is reset in a `finally` AFTER the DB write commits, on a
        // dispatcher hop back from Room's executor thread — the DB write
        // becoming visible (above) does not guarantee that reset has
        // already run. Wait for it explicitly before firing the next call,
        // or this second recordMovement could be silently treated as a
        // (legitimate-looking, but wrong here) still-in-flight duplicate.
        waitUntil { !viewModel.isSaving.value }

        viewModel.recordMovement(pinGate, productId, MovementType.OUT, 5, description = null)

        waitUntil { container.inventoryRepository.stockFlowValue(productId) == 15 }
        assertFalse(pinGate.isVisible)
    }

    // --- ADJUST is PIN-gated ---

    @Test
    fun recordMovement_withAdjustType_requiresPinGate_andPersistsOnlyAfterCorrectPin() = runBlocking {
        val pinGate = PinGate(verifyPin = { it == correctPin })
        viewModel.recordMovement(pinGate, productId, MovementType.IN, 15, description = null)
        waitUntil { container.inventoryRepository.stockFlowValue(productId) == 15 }
        waitUntil { !viewModel.isSaving.value }

        viewModel.recordMovement(pinGate, productId, MovementType.ADJUST, 8, description = null)

        // The gate must fire BEFORE anything is persisted.
        assertTrue(pinGate.isVisible)
        assertEquals(15, container.inventoryRepository.stockFlowValue(productId))

        pinGate.submit(correctPin)
        waitUntil { container.inventoryRepository.stockFlowValue(productId) == 8 }

        assertFalse(pinGate.isVisible)
    }

    @Test
    fun recordMovement_withAdjustType_andIncorrectPin_leavesStockUnchanged() = runBlocking {
        val pinGate = PinGate(verifyPin = { it == correctPin })
        viewModel.recordMovement(pinGate, productId, MovementType.IN, 15, description = null)
        waitUntil { container.inventoryRepository.stockFlowValue(productId) == 15 }
        waitUntil { !viewModel.isSaving.value }

        viewModel.recordMovement(pinGate, productId, MovementType.ADJUST, 8, description = null)
        pinGate.submit("9999")

        assertTrue(pinGate.isVisible)
        assertEquals(DomainError.PinIncorrect, pinGate.lastError)
        assertEquals(15, container.inventoryRepository.stockFlowValue(productId))
    }

    // --- Minimum stock is editable independent of a movement, never gated ---

    @Test
    fun setMinimumStock_neverOpensPinGate_andPersistsDirectly() = runBlocking {
        val pinGate = PinGate(verifyPin = { it == correctPin })

        viewModel.setMinimumStock(productId, 5)

        waitUntil { container.database.inventoryDao().findByProductId(productId)?.minimumStock == 5 }
        assertFalse(pinGate.isVisible)
    }

    // --- Double-click regression (coordinator review, final round) ---

    /**
     * [InventoryViewModel.isSaving]'s regression test. Without that guard, a
     * fast double-click on "Record movement" fires two concurrent
     * [recordMovement] calls for the same IN movement — both would apply,
     * double-counting the stock increase. The two calls below use no
     * dispatcher yield in between, simulating the fastest possible
     * double-click; only ONE `IN 20` must land.
     */
    @Test
    fun recordMovement_calledTwiceInQuickSuccession_secondCallIsANoOp() = runBlocking {
        val pinGate = PinGate(verifyPin = { it == correctPin })

        viewModel.recordMovement(pinGate, productId, MovementType.IN, 20, description = null)
        viewModel.recordMovement(pinGate, productId, MovementType.IN, 20, description = null)

        waitUntil { container.inventoryRepository.stockFlowValue(productId) == 20 }

        // Let any (incorrectly) concurrent second write a further chance to
        // land before asserting it didn't.
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(20)
        }
        assertEquals(20, container.inventoryRepository.stockFlowValue(productId))
        assertNull(viewModel.lastError.value)
    }
}

/** Test-only convenience: snapshot the current [InventoryRepository.stockFlow] emission. */
private suspend fun InventoryRepository.stockFlowValue(productId: Long): Int =
    stockFlow(productId).first()
