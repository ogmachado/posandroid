package com.idos.pos.catalog

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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

    // --- Double-click regression (coordinator review, final round) ---

    /**
     * [ProductViewModel.isSaving]'s regression test. Without that guard, a
     * fast double-click on Save fires two concurrent [createProduct] calls;
     * [CatalogRepository.createProduct]'s uniqueness check is a non-atomic
     * check-then-insert (only backstopped by the DB's unique index on
     * `code`), so both calls could pass the pre-check and race to insert —
     * the loser crashing with an uncaught `SQLiteConstraintException` rather
     * than a graceful [DomainError]. The two calls below deliberately use
     * DIFFERENT `name`/`price` so the test can tell which one (if either)
     * actually ran: only the FIRST call's data must land — the second must
     * be a silent no-op, not a crash and not a second attempt that failed
     * with `DomainError.DuplicateCode`.
     */
    @Test
    fun createProduct_calledTwiceInQuickSuccession_secondCallIsANoOp() = runBlocking {
        viewModel.createProduct(
            name = "Double",
            code = "SKU-DBL-1",
            barcode = null,
            price = BigDecimal("5.00"),
            costPrice = BigDecimal("2.00"),
            unitMeasureId = existingProduct.unitMeasureId,
            categoryId = null,
        )
        // Simulate a fast double-click: call again immediately, with no
        // dispatcher yield in between — before the first call's coroutine
        // could possibly have completed its write.
        viewModel.createProduct(
            name = "Double Duplicate Attempt",
            code = "SKU-DBL-1",
            barcode = null,
            price = BigDecimal("9.00"),
            costPrice = BigDecimal("3.00"),
            unitMeasureId = existingProduct.unitMeasureId,
            categoryId = null,
        )

        waitUntil { container.productDao.findByCode("SKU-DBL-1") != null }

        assertEquals("Double", container.productDao.findByCode("SKU-DBL-1")?.name)
        assertEquals(BigDecimal("5.00"), container.productDao.findByCode("SKU-DBL-1")?.price)
        assertNull(viewModel.lastError.value)
    }

    // --- Gap 2 (coordinator review, nav-shell wiring): does immediate
    // navigation-away after a save risk cancelling the just-launched
    // coroutine before it persists? ---

    /**
     * Reproduces `nav/PosNavHost.kt`'s real risk directly against a real
     * [ViewModelStore] (bypassing only [ProductFormScreen]'s
     * `PickerDropdown` UI, which a companion investigation in
     * `nav/PosNavHostTest.kt` found is not reliably driveable via
     * `onNodeWithText` after a mid-test click in this Robolectric
     * environment — the same class of Popup-based-component limitation
     * already documented for `PinGateDialog`/`AlertDialog`, unrelated to the
     * race being tested here):
     *
     * `PosNavHost`'s `productos/create` route observes
     * `ProductViewModel.saveCompleted` and calls `onSaved()` (→
     * `navController.popBackStack()`) ONLY as a reaction to
     * [createProduct]'s coroutine bumping it — which only happens AFTER the
     * write already committed, sequentially, inside that same coroutine.
     * `popBackStack()` clears the `NavBackStackEntry`'s `ViewModelStore`,
     * which cancels [ProductViewModel]'s `viewModelScope`. This test obtains
     * a real [ProductViewModel] from a real [ViewModelStore] (the same
     * [ViewModelProvider.Factory] shape [com.idos.pos.core.di.posViewModel]
     * uses), calls [createProduct], waits for [ProductViewModel.saveCompleted]
     * to actually change (mirroring `LaunchedEffect(saveCompleted)`'s real
     * trigger condition — it never runs before that), and only THEN clears
     * the store — exactly what `popBackStack()` does to the real one.
     *
     * **An earlier draft of this test clearing the store IMMEDIATELY after
     * calling [createProduct], with no wait for `saveCompleted`, genuinely
     * lost the write** (the polling assertion below timed out) — confirming
     * `viewModelScope` cancellation is a REAL risk in the abstract. But that
     * scenario cannot happen in the actual app: `onSaved()` structurally
     * cannot run before `saveCompleted` changes, and `saveCompleted` cannot
     * change before the write commits (same coroutine, sequential). This
     * test verifies that real, structurally-enforced ordering, not just the
     * database write's raw speed.
     */
    @Test
    fun createProduct_survivesViewModelStoreClear_triggeredOnlyAfterSaveCompletedFires() = runBlocking {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return modelClass.getConstructor(AppContainer::class.java).newInstance(container) as T
            }
        }
        val storeScopedViewModel = ViewModelProvider(store, factory)[ProductViewModel::class.java]

        storeScopedViewModel.createProduct(
            name = "Gadget",
            code = "SKU-RACE-1",
            barcode = null,
            price = BigDecimal("15.00"),
            costPrice = BigDecimal("7.00"),
            unitMeasureId = existingProduct.unitMeasureId,
            categoryId = null,
        )

        // Mirror LaunchedEffect(saveCompleted)'s real trigger condition —
        // onSaved()/popBackStack() only ever runs in reaction to this
        // changing, which the ViewModel only does AFTER createProduct's
        // write already committed.
        waitUntil { storeScopedViewModel.saveCompleted.value > 0 }

        // The exact action popBackStack() performs on the real
        // NavBackStackEntry's ViewModelStore — cancels viewModelScope via
        // ViewModel.onCleared().
        store.clear()

        // The write must already be there — saveCompleted only fires after
        // it commits, so there is nothing left for the cancellation above to
        // interrupt.
        assertEquals("Gadget", container.productDao.findByCode("SKU-RACE-1")?.name)
        assertEquals(BigDecimal("15.00"), container.productDao.findByCode("SKU-RACE-1")?.price)
    }
}
