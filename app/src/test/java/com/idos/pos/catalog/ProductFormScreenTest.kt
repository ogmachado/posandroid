package com.idos.pos.catalog

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.core.di.AppContainer
import com.idos.pos.permission.PinGate
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * UI test written alongside (task 4.6; `Screen.kt`/`ViewModel.kt` are excluded
 * from strict TDD per openspec/config.yaml `strict_tdd_scope`). Confirms
 * [ProductFormScreen]'s Save button wires into
 * [ProductViewModel.submitUpdate] correctly — a price change opens the PIN
 * gate synchronously and does NOT persist; a non-price change persists
 * directly without ever touching the gate.
 *
 * **Scope note**: this class deliberately does NOT drive the PIN dialog's
 * confirm/cancel flow (typing a PIN, clicking confirm) inside a rendered
 * [ProductFormScreen]. Two real environment issues were found while writing
 * this test:
 * 1. `@Config(sdk = [34])` alone resolves to a zero-size Robolectric test
 *    window for a full-screen composable, so a plain `performClick()` on the
 *    Save button silently missed (`boundsInRoot` was `(0,0,0,0)`). Fixed with
 *    the explicit `qualifiers` screen size below.
 * 2. Rendering [com.idos.pos.permission.PinGateDialog] (an `AlertDialog`)
 *    together with this screen's content makes Robolectric's Compose
 *    idle-detection (`waitForIdle` / any further `onNodeWithTag` query) hang
 *    indefinitely (`AppNotIdleException`) — reproduced even after removing
 *    `Scaffold` in favor of a plain `Column`, so it is not Scaffold-specific.
 *    A single `performClick()` that *opens* the dialog returns fine; it is
 *    any *subsequent* Compose-test-rule interaction while the dialog is
 *    composed that never converges.
 *
 * The correct-PIN / incorrect-PIN / no-carry-over scenarios from
 * specs/permission-gate/spec.md are covered without that hang by
 * [ProductViewModelTest] (exercises [ProductViewModel.submitUpdate] against a
 * real [PinGate] with no Compose rendering) and by [com.idos.pos.permission.PinGateDialogTest]
 * (Phase 3 — exercises [com.idos.pos.permission.PinGateDialog] alone, which
 * renders and settles fine in isolation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class ProductFormScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val correctPin = "1234"

    private lateinit var container: AppContainer
    private lateinit var viewModel: ProductViewModel
    private lateinit var existingProduct: ProductEntity

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        container = AppContainer.createInMemory(context)
        container.database.openHelper.writableDatabase // force onCreate/seed

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

    private suspend fun waitUntil(timeoutMs: Long = 2_000, block: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!block()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
    }

    @Test
    fun changingPrice_andSaving_opensPinGate_withoutPersisting() {
        val pinGate = PinGate(verifyPin = { it == correctPin }, scope = CoroutineScope(Dispatchers.Unconfined))

        composeTestRule.setContent {
            ProductFormScreen(product = existingProduct, pinGate = pinGate, onSaved = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag(PRICE_FIELD_TEST_TAG).performTextClearance()
        composeTestRule.onNodeWithTag(PRICE_FIELD_TEST_TAG).performTextInput("120.00")
        composeTestRule.onNodeWithTag(SAVE_BUTTON_TEST_TAG).performClick()

        // Read the gate's own state directly (no further Compose-test-rule
        // interaction — see class doc for why interacting with the rendered
        // dialog itself is unreliable in this environment).
        assertTrue(pinGate.isVisible)
        runBlocking {
            assertEquals(BigDecimal("100.00"), container.productDao.findById(existingProduct.id)?.price)
        }
    }

    @Test
    fun changingOnlyName_savesDirectly_withoutOpeningPinGate() = runBlocking {
        val pinGate = PinGate(verifyPin = { it == correctPin }, scope = CoroutineScope(Dispatchers.Unconfined))

        composeTestRule.setContent {
            ProductFormScreen(product = existingProduct, pinGate = pinGate, onSaved = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag(NAME_FIELD_TEST_TAG).performTextClearance()
        composeTestRule.onNodeWithTag(NAME_FIELD_TEST_TAG).performTextInput("Widget Renamed")
        composeTestRule.onNodeWithTag(SAVE_BUTTON_TEST_TAG).performClick()

        assertFalse(pinGate.isVisible)
        waitUntil { container.productDao.findById(existingProduct.id)?.name == "Widget Renamed" }
        assertEquals("Widget Renamed", container.productDao.findById(existingProduct.id)?.name)
    }
}
