package com.idos.pos.inventory

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.core.di.AppContainer
import com.idos.pos.permission.PinGate
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * UI test written alongside (task 5.5; `Screen.kt`/`ViewModel.kt` are
 * excluded from strict TDD per openspec/config.yaml `strict_tdd_scope`).
 * Confirms [InventoryMovementFormScreen]'s submit button wires into
 * [InventoryViewModel.recordMovement] correctly — mirrors
 * [com.idos.pos.catalog.ProductFormScreenTest] (task 4.6) exactly, including
 * its scope note: this class does NOT drive the PIN dialog's confirm/cancel
 * flow inside a rendered screen (see that class's doc for the Robolectric
 * `AlertDialog` + Compose idle-detection hang this avoids). The
 * correct-PIN / incorrect-PIN scenarios are covered without that hang by
 * [InventoryViewModelTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class InventoryMovementFormScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val correctPin = "1234"

    private lateinit var container: AppContainer
    private lateinit var viewModel: InventoryViewModel
    private var productId: Long = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        container = AppContainer.createInMemory(context)
        container.database.openHelper.writableDatabase // force onCreate/seed

        val unitMeasureId = container.unitMeasureDao.insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
        productId = container.catalogRepository.createProduct(
            name = "Widget",
            code = "SKU-800",
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

    @Test
    fun submittingInMovement_persistsDirectly_withoutOpeningPinGate() = runBlocking {
        val pinGate = PinGate(verifyPin = { it == correctPin })
        val product = ProductStockView(productId, "Widget", "SKU-800", stock = 0, minimumStock = 0)

        composeTestRule.setContent {
            InventoryMovementFormScreen(product = product, pinGate = pinGate, onSaved = {}, viewModel = viewModel)
        }

        // IN is the default selection — just fill quantity and submit.
        composeTestRule.onNodeWithTag(QUANTITY_FIELD_TEST_TAG).performTextInput("20")
        composeTestRule.onNodeWithTag(SUBMIT_MOVEMENT_BUTTON_TEST_TAG).performClick()

        assertFalse(pinGate.isVisible)
        waitUntil { container.inventoryRepository.stockFlow(productId).first() == 20 }
        assertEquals(20, container.inventoryRepository.stockFlow(productId).first())
    }

    @Test
    fun selectingAdjust_andSubmitting_opensPinGate_withoutPersisting() {
        val pinGate = PinGate(verifyPin = { it == correctPin })
        val product = ProductStockView(productId, "Widget", "SKU-800", stock = 0, minimumStock = 0)

        composeTestRule.setContent {
            InventoryMovementFormScreen(product = product, pinGate = pinGate, onSaved = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag(movementTypeOptionTestTag(MovementType.ADJUST)).performClick()
        composeTestRule.onNodeWithTag(QUANTITY_FIELD_TEST_TAG).performTextInput("8")
        composeTestRule.onNodeWithTag(SUBMIT_MOVEMENT_BUTTON_TEST_TAG).performClick()

        // Read the gate's own state directly (no further Compose-test-rule
        // interaction — see class doc for why interacting with the rendered
        // dialog itself is unreliable in this environment).
        assertTrue(pinGate.isVisible)
        runBlocking {
            assertEquals(0, container.inventoryRepository.stockFlow(productId).first())
        }
    }

    @Test
    fun savingMinimumStock_persistsDirectly_withoutOpeningPinGate() = runBlocking {
        val pinGate = PinGate(verifyPin = { it == correctPin })
        val product = ProductStockView(productId, "Widget", "SKU-800", stock = 0, minimumStock = 0)

        composeTestRule.setContent {
            InventoryMovementFormScreen(product = product, pinGate = pinGate, onSaved = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag(MINIMUM_STOCK_FIELD_TEST_TAG).performTextClearance()
        composeTestRule.onNodeWithTag(MINIMUM_STOCK_FIELD_TEST_TAG).performTextInput("5")
        composeTestRule.onNodeWithTag(SAVE_MINIMUM_STOCK_BUTTON_TEST_TAG).performClick()

        assertFalse(pinGate.isVisible)
        waitUntil { container.database.inventoryDao().findByProductId(productId)?.minimumStock == 5 }
    }
}
