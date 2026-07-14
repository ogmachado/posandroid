package com.idos.pos.nav

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.idos.pos.cashsession.EXPECTED_BALANCE_TEST_TAG
import com.idos.pos.cashsession.OPENING_BALANCE_FIELD_TEST_TAG
import com.idos.pos.catalog.ADD_PRODUCT_BUTTON_TEST_TAG
import com.idos.pos.catalog.BARCODE_FIELD_TEST_TAG
import com.idos.pos.catalog.NAME_FIELD_TEST_TAG
import com.idos.pos.catalog.PRICE_FIELD_TEST_TAG
import com.idos.pos.catalog.PRODUCT_LIST_TEST_TAG
import com.idos.pos.catalog.SAVE_BUTTON_TEST_TAG
import com.idos.pos.catalog.UnitMeasureEntity
import com.idos.pos.catalog.productListItemTestTag
import com.idos.pos.core.di.AppContainer
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.inventory.INVENTORY_LIST_TEST_TAG
import com.idos.pos.inventory.QUANTITY_FIELD_TEST_TAG
import com.idos.pos.inventory.inventoryListItemTestTag
import com.idos.pos.permission.UserRole
import com.idos.pos.sales.CART_EMPTY_TEST_TAG
import com.idos.pos.sales.CONFIRM_SALE_BUTTON_TEST_TAG
import com.idos.pos.sales.SCAN_BUTTON_TEST_TAG
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * UI test for the bottom-navigation shell (`nav/PosNavHost.kt`) wiring the
 * `android-pos-mvp` standalone screens into a navigable app.
 *
 * **Root-container empirical verification (task brief's explicit risk
 * flag)**: [ProductFormScreen]/[InventoryMovementFormScreen] (both rendered
 * here, nested one level deeper than their own `*ScreenTest`s exercise — this
 * shell's bottom-bar + `NavHost` sit above them, where their own tests render
 * them directly) contain [com.idos.pos.permission.PinGateDialog], and
 * `ProductFormScreenTest`'s class doc documents that rendering that
 * `AlertDialog` alongside ANY further `composeTestRule` interaction hangs
 * Robolectric's Compose idle-detection (`AppNotIdleException`) — confirmed
 * there to reproduce independent of `Scaffold` (it still hung after removing
 * `Scaffold` in favor of a `Column` in that investigation). Every test below
 * follows the SAME safe pattern those tests already use — never interacting
 * with the compose tree again after a PIN-gated action would open the dialog.
 *
 * [priceEditOnEditRoute_pinGateOpens_blocksPersistence_andKeepsScreenMounted]
 * is the one test that actually DRIVES a real PIN-gate trigger (a
 * price-changing save) through [ProductFormScreen] nested inside this
 * shell's `NavHost` + bottom bar — the exact nesting depth this class exists
 * to verify. It asserts the two outcomes reliably observable in this
 * environment (no persistence + screen stays mounted — see that test's own
 * KDoc) rather than querying [com.idos.pos.permission.PIN_INPUT_TEST_TAG]
 * directly.
 *
 * **A dedicated, temporary diagnostic test (since removed) confirmed why**:
 * querying an `AlertDialog`'s own rendered content via `onNodeWithTag` after
 * a MID-TEST state flip (as opposed to being visible from the very first
 * composition, which is the only pattern
 * [com.idos.pos.permission.PinGateDialogTest] exercises) does not converge
 * to a found node in this Robolectric environment — bounded (a few seconds,
 * not an infinite hang), but never finds the node. The diagnostic reproduced
 * this IDENTICALLY with [ProductFormScreen] rendered flat, with no nav shell
 * involved at all — so this is a pre-existing, general Robolectric/Compose
 * `AlertDialog` limitation in this codebase, not something nesting one level
 * deeper under [PosNavHost] introduces or makes worse. [PosNavHost] itself
 * uses a plain `Column` (not `Scaffold`) as its root, matching this
 * codebase's established convention; running this whole class under
 * Robolectric with `forkEvery = 1` (see `app/build.gradle.kts`) confirms
 * nesting these dialog-bearing screens under the new shell does not
 * introduce a hang beyond what already exists in the flat case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class PosNavHostTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var container: AppContainer
    private lateinit var navController: TestNavHostController
    private var seededProductId: Long = 0

    @Before
    fun setUp() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            container = AppContainer.createInMemory(context)
            container.database.openHelper.writableDatabase // force onCreate/seed

            val unitMeasureId = container.unitMeasureDao.insert(UnitMeasureEntity(code = "UNIT", name = "Unit"))
            seededProductId = container.catalogRepository.createProduct(
                name = "Widget",
                code = "SKU-NAV-1",
                barcode = null,
                price = BigDecimal("10.00"),
                costPrice = BigDecimal("5.00"),
                unitMeasureId = unitMeasureId,
                categoryId = null,
            ).getOrThrow()

            navController = TestNavHostController(context)
            navController.navigatorProvider.addNavigator(ComposeNavigator())
        }
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

    /**
     * Renders as `UserRole.ADMIN` (`android-pos-auth` Phase 3 task 3.4's
     * `role` parameter) — this class drives Productos/Inventario tab clicks
     * directly, both ADMIN-only per `role-based-navigation`'s `visibleTabsFor`
     * filtering, and none of this shell-navigation-focused suite's scenarios
     * are about role-based tab visibility itself (that is
     * [VisibleTabsForTest]'s job).
     */
    private fun setNavHostContent() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                PosNavHost(navController = navController, role = UserRole.ADMIN)
            }
        }
    }

    // --- Venta tab gates on cash-session-open state ---

    @Test
    fun ventaTab_showsCashSessionOpenScreen_whenNoSessionOpen() {
        setNavHostContent()

        composeTestRule.onNodeWithTag(OPENING_BALANCE_FIELD_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(CONFIRM_SALE_BUTTON_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun ventaTab_showsPosScreen_whenSessionIsOpen() {
        runBlocking {
            container.cashSessionRepository.open(BigDecimal("50.00")).getOrThrow()

            setNavHostContent()
            waitUntil { runCatching { composeTestRule.onNodeWithTag(SCAN_BUTTON_TEST_TAG).assertExists() }.isSuccess }

            composeTestRule.onNodeWithTag(SCAN_BUTTON_TEST_TAG).assertExists()
            composeTestRule.onNodeWithTag(CART_EMPTY_TEST_TAG).assertExists()
            composeTestRule.onNodeWithTag(OPENING_BALANCE_FIELD_TEST_TAG).assertDoesNotExist()

            // PosScreen composes CashSessionViewModel.currentSession (a
            // Room-backed StateFlow) and CurrencyConversionRows'
            // activeCurrenciesFlow — both collected on a background
            // dispatcher independent of shadowOf(Looper).idle() above. Wait
            // for a direct suspend read against the same underlying table to
            // resolve, giving that background collection machinery real
            // wall-clock time to settle before @After's tearDown() closes the
            // in-memory database — otherwise a still-in-flight Flow query can
            // intermittently throw "Cannot perform this operation because the
            // connection pool has been closed" (mirrors AuthGateTest's
            // seed-write-before-close pattern).
            waitUntil(timeoutMs = 5_000) { container.cashSessionRepository.currentSessionFlow().first() != null }
        }
    }

    // --- Caja tab gates on the SAME cash-session-open state as Venta ---

    @Test
    fun cajaTab_showsCashSessionOpenScreen_whenNoSessionOpen() {
        setNavHostContent()

        composeTestRule.onNodeWithTag(bottomNavItemTestTag(ROUTE_CAJA)).performClick()

        composeTestRule.onNodeWithTag(OPENING_BALANCE_FIELD_TEST_TAG).assertExists()
    }

    @Test
    fun cajaTab_showsCashSessionScreen_whenSessionIsOpen() {
        runBlocking {
            container.cashSessionRepository.open(BigDecimal("50.00")).getOrThrow()

            setNavHostContent()
            composeTestRule.onNodeWithTag(bottomNavItemTestTag(ROUTE_CAJA)).performClick()
            waitUntil { runCatching { composeTestRule.onNodeWithTag(EXPECTED_BALANCE_TEST_TAG).assertExists() }.isSuccess }

            composeTestRule.onNodeWithTag(EXPECTED_BALANCE_TEST_TAG).assertExists()
        }
    }

    // --- Productos / Inventario tabs render their list screens ---

    @Test
    fun productosTab_rendersProductList() {
        setNavHostContent()

        composeTestRule.onNodeWithTag(bottomNavItemTestTag(ROUTE_PRODUCTOS)).performClick()

        composeTestRule.onNodeWithTag(PRODUCT_LIST_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(productListItemTestTag(seededProductId)).assertExists()
    }

    @Test
    fun inventarioTab_rendersInventoryList() {
        setNavHostContent()

        composeTestRule.onNodeWithTag(bottomNavItemTestTag(ROUTE_INVENTARIO)).performClick()

        composeTestRule.onNodeWithTag(INVENTORY_LIST_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(inventoryListItemTestTag(seededProductId)).assertExists()
    }

    // --- Add-product / product-click navigate into ProductFormScreen ---

    @Test
    fun addProductButton_navigatesToCreateRoute_withNoPrefilledBarcode() {
        setNavHostContent()

        composeTestRule.onNodeWithTag(bottomNavItemTestTag(ROUTE_PRODUCTOS)).performClick()
        composeTestRule.onNodeWithTag(ADD_PRODUCT_BUTTON_TEST_TAG).performClick()

        composeTestRule.onNodeWithTag(NAME_FIELD_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(BARCODE_FIELD_TEST_TAG).assertExists()
    }

    // --- Gap 2 (coordinator review): does popBackStack()-driven onSaved()
    // risk cancelling the just-launched save coroutine (viewModelScope is
    // cancelled once the NavBackStackEntry's ViewModelStore is cleared)
    // before the write actually reaches Room?
    //
    // The UPDATE (ungated) test below drives a real save through the ACTUAL
    // PosNavHost shell (fill the form, click Save — which now navigates away
    // via the saveCompleted-driven LaunchedEffect, not synchronously in
    // onClick) and reads the database directly afterward. Answer: NOT a
    // race — see its own note on why.
    //
    // **CREATE path — could not be exercised the same way here**: a real
    // create-flow save requires selecting a unit of measure from
    // `ProductFormScreen`'s `PickerDropdown` (a `DropdownMenu`, backed by a
    // `Popup`). Confirmed empirically (bounded polling, not a single query)
    // that a `DropdownMenu` opened via a mid-test click does not reliably
    // become queryable in this Robolectric environment — the SAME class of
    // limitation this codebase's `PinGateDialog`/`AlertDialog` investigation
    // already documents, just for a different Popup-based component. This is
    // a UI-testability gap in the pre-existing `PickerDropdown`, unrelated to
    // the save/navigation race being verified here, so overclaiming a passing
    // UI test for it would repeat the earlier mistake this review caught.
    // [com.idos.pos.catalog.ProductViewModelTest.createProduct_survivesViewModelStoreClear_triggeredOnlyAfterSaveCompletedFires]
    // verifies the CREATE path's actual race question directly (bypassing
    // only the untestable dropdown UI, not the risk mechanism): it obtains a
    // real `ProductViewModel` from a real `ViewModelStore`, calls
    // `createProduct`, and immediately clears that store exactly as
    // `popBackStack()` does to the real `NavBackStackEntry` — then confirms
    // the write still lands.

    /**
     * UPDATE path with NO price change — takes the immediate (ungated)
     * branch of `submitUpdate`, the identical save-vs-navigation shape as
     * CREATE (same `ProductViewModel`, same `viewModelScope.launch` +
     * `saveCompleted` + `LaunchedEffect` + `popBackStack` chain — only the
     * repository method differs). Not a race in practice: `onSaved()` only
     * fires from `LaunchedEffect(saveCompleted)`, which only runs after
     * `updateProduct`'s coroutine has already completed successfully — by
     * the time `onSaved()` (and therefore `popBackStack()`) can possibly
     * run, the write is already committed, so there is nothing left for a
     * subsequently cancelled `viewModelScope` to interrupt.
     */
    @Test
    fun editingAProductWithNoPriceChange_throughTheRealShell_persistsToTheDatabase() {
        runBlocking {
            setNavHostContent()

            composeTestRule.onNodeWithTag(bottomNavItemTestTag(ROUTE_PRODUCTOS)).performClick()
            composeTestRule.onNodeWithTag(productListItemTestTag(seededProductId)).performClick()
            waitUntil { runCatching { composeTestRule.onNodeWithTag(NAME_FIELD_TEST_TAG).assertTextContains("Widget", substring = true) }.isSuccess }

            composeTestRule.onNodeWithTag(NAME_FIELD_TEST_TAG).performTextClearance()
            composeTestRule.onNodeWithTag(NAME_FIELD_TEST_TAG).performTextInput("Widget Renamed")
            composeTestRule.onNodeWithTag(SAVE_BUTTON_TEST_TAG).performScrollTo()
            composeTestRule.onNodeWithTag(SAVE_BUTTON_TEST_TAG).performClick()

            // The save -> saveCompleted -> onSaved -> popBackStack chain
            // needs more than the default 2s budget to fully settle in this
            // environment — confirmed via a temporary diagnostic (removed)
            // that the DB write and navigation DO complete correctly, just
            // slower than 2000ms.
            waitUntil(timeoutMs = 5_000) { runCatching { composeTestRule.onNodeWithTag(PRODUCT_LIST_TEST_TAG).assertExists() }.isSuccess }
            composeTestRule.onNodeWithTag(PRODUCT_LIST_TEST_TAG).assertExists()

            val persisted = container.catalogRepository.findById(seededProductId) ?: error("product vanished")
            assertEquals("Widget Renamed", persisted.name)
            assertEquals(BigDecimal("10.00"), persisted.price)
        }
    }

    @Test
    fun clickingProductRow_navigatesToEditRoute_withPrefilledFields() {
        runBlocking {
            setNavHostContent()

            composeTestRule.onNodeWithTag(bottomNavItemTestTag(ROUTE_PRODUCTOS)).performClick()
            composeTestRule.onNodeWithTag(productListItemTestTag(seededProductId)).performClick()

            waitUntil { runCatching { composeTestRule.onNodeWithTag(NAME_FIELD_TEST_TAG).assertTextContains("Widget", substring = true) }.isSuccess }
            composeTestRule.onNodeWithTag(NAME_FIELD_TEST_TAG).assertTextContains("Widget", substring = true)
        }
    }

    /**
     * Regression test for a stale/deleted `productId` on the edit route: the
     * by-id lookup resolves to `null`, and [ProductFormScreen] treats
     * `product = null` as CREATE mode — so without a guard, this route would
     * silently render a blank "create new product" form instead of erroring
     * or leaving. Asserts NAME_FIELD never appears (the route renders
     * nothing for an unresolved id, matching
     * `InventoryMovementRoute`'s handling).
     */
    @Test
    fun navigatingToProductEditRoute_withStaleId_rendersNothing_notACreateForm() {
        runBlocking {
            setNavHostContent()

            val staleProductId = seededProductId + 999_999L
            composeTestRule.runOnIdle {
                navController.navigate(productEditRoute(staleProductId))
            }

            // Let the by-id lookup's LaunchedEffect actually resolve (to null)
            // before asserting absence — otherwise this would trivially pass
            // during the brief pre-`loaded` window even without the fix.
            repeat(20) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }

            composeTestRule.onNodeWithTag(NAME_FIELD_TEST_TAG).assertDoesNotExist()
            composeTestRule.onNodeWithTag(SAVE_BUTTON_TEST_TAG).assertDoesNotExist()
        }
    }

    /**
     * Drives a real price-edit save (the actual PIN-gate trigger, mirroring
     * `ProductFormScreenTest.changingPrice_andSaving_opensPinGate_withoutPersisting`)
     * through [ProductFormScreen] nested inside the new shell's `NavHost` +
     * bottom bar, and asserts the two outcomes that ARE reliably observable
     * in this environment: (1) the save did NOT persist the price change —
     * meaning `ProductViewModel.submitUpdate` took the `pinGate.require`
     * branch rather than updating directly — and (2) the edit screen is
     * still mounted (`onSaved`'s `popBackStack` did NOT fire — this exercises
     * this change's own `ProductFormScreen.kt` fix: `onSaved()` is now
     * skipped while `pinGate.isVisible`, otherwise a real `NavController`
     * would tear the screen down mid-gate before the operator could ever
     * answer the PIN prompt).
     *
     * **What this test does NOT assert, and why**: it does not query
     * [com.idos.pos.permission.PIN_INPUT_TEST_TAG] (the dialog's own
     * rendered content). A dedicated diagnostic confirmed that querying an
     * `AlertDialog`'s content via `onNodeWithTag` after a MID-TEST state flip
     * (as opposed to being visible from the first composition, which is the
     * only pattern `PinGateDialogTest` exercises) fails to converge to a
     * found node in this Robolectric environment — and reproduces
     * IDENTICALLY when [ProductFormScreen] is rendered flat, with NO nav
     * shell involved at all. So this is a pre-existing, general
     * Robolectric/Compose `AlertDialog` limitation in this codebase, not
     * something nesting one level deeper under [PosNavHost] introduces or
     * makes worse. `@Test(timeout = ...)` still bounds the risk that nesting
     * changes this into a true infinite hang instead of a bounded, converging
     * outcome — it does not.
     */
    @Test(timeout = 10_000)
    fun priceEditOnEditRoute_pinGateOpens_blocksPersistence_andKeepsScreenMounted() {
        runBlocking {
            setNavHostContent()

            composeTestRule.onNodeWithTag(bottomNavItemTestTag(ROUTE_PRODUCTOS)).performClick()
            composeTestRule.onNodeWithTag(productListItemTestTag(seededProductId)).performClick()
            waitUntil { runCatching { composeTestRule.onNodeWithTag(PRICE_FIELD_TEST_TAG).assertExists() }.isSuccess }

            composeTestRule.onNodeWithTag(PRICE_FIELD_TEST_TAG).performTextClearance()
            composeTestRule.onNodeWithTag(PRICE_FIELD_TEST_TAG).performTextInput("999.00")
            composeTestRule.onNodeWithTag(SAVE_BUTTON_TEST_TAG).performClick()

            // The edit screen must still be mounted — onSaved()'s popBackStack
            // must NOT have fired while the PIN gate is pending.
            composeTestRule.onNodeWithTag(PRICE_FIELD_TEST_TAG).assertExists()

            // The price must not have persisted — the PIN was never submitted.
            assertEquals(BigDecimal("10.00"), container.catalogRepository.findById(seededProductId)?.price)
        }
    }

    // --- Barcode round-trips through Uri.encode/decode for special Code-128 chars ---

    @Test
    fun navigatingToProductCreateRoute_withSpecialCharsInBarcode_roundTripsCorrectly() {
        setNavHostContent()

        val specialBarcode = "AB&12%3+4#5"
        composeTestRule.runOnIdle {
            navController.navigate(productCreateRoute(specialBarcode))
        }

        composeTestRule.onNodeWithTag(BARCODE_FIELD_TEST_TAG).assertTextContains(specialBarcode, substring = true)
    }

    // --- Inventory row click navigates into InventoryMovementFormScreen ---

    @Test
    fun clickingInventoryRow_navigatesToMovementForm() {
        runBlocking {
            setNavHostContent()

            composeTestRule.onNodeWithTag(bottomNavItemTestTag(ROUTE_INVENTARIO)).performClick()
            composeTestRule.onNodeWithTag(inventoryListItemTestTag(seededProductId)).performClick()

            waitUntil { runCatching { composeTestRule.onNodeWithTag(QUANTITY_FIELD_TEST_TAG).assertExists() }.isSuccess }
            composeTestRule.onNodeWithTag(QUANTITY_FIELD_TEST_TAG).assertExists()
        }
    }

    // --- Unknown-barcode hand-off from Venta -> product creation, pre-filled ---

    /**
     * [PosScreen]'s real "unknown barcode" path goes through
     * [com.idos.pos.scan.BarcodeScanScreen]'s camera-bound analyzer, which
     * cannot run under Robolectric (see that file's class doc). This test
     * instead drives [navController] directly with the exact route
     * [PosNavHost]'s `onUnknownBarcode` callback would build
     * ([productCreateRoute]) — verifying the nav-shell's OWN responsibility
     * (route argument decoding -> [ProductFormScreen]'s `initialBarcode`
     * pre-fill), which is what this change actually adds.
     */
    @Test
    fun navigatingToProductCreateRoute_withBarcode_prefillsBarcodeField() {
        setNavHostContent()

        composeTestRule.runOnIdle {
            navController.navigate(productCreateRoute("1234567890123"))
        }

        composeTestRule.onNodeWithTag(BARCODE_FIELD_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(NAME_FIELD_TEST_TAG).assertExists()
    }

    // --- Back-stack hygiene across repeated tab switches ---

    @Test
    fun switchingTabsRepeatedly_doesNotAccumulateBackStack() {
        setNavHostContent()

        val routesToClick = listOf(ROUTE_PRODUCTOS, ROUTE_INVENTARIO, ROUTE_CAJA, ROUTE_VENTA)
        repeat(3) {
            routesToClick.forEach { route ->
                composeTestRule.onNodeWithTag(bottomNavItemTestTag(route)).performClick()
            }
        }

        // 12 clicks across 4 top-level tabs with popUpTo(start){saveState=true}
        // + launchSingleTop + restoreState must not grow the back stack
        // linearly with click count — it stays bounded to (roughly) one entry
        // per distinct tab ever visited, plus the NavHost's own graph root.
        assertTrue(
            "expected a bounded back stack, got ${navController.currentBackStack.value.size} entries",
            navController.currentBackStack.value.size <= routesToClick.size + 2,
        )
    }
}
