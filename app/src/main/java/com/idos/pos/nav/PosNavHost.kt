package com.idos.pos.nav

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.idos.pos.cashsession.CashSessionCloseScreen
import com.idos.pos.cashsession.CashSessionOpenScreen
import com.idos.pos.cashsession.CashSessionScreen
import com.idos.pos.cashsession.CashSessionViewModel
import com.idos.pos.catalog.ProductEntity
import com.idos.pos.catalog.ProductFormScreen
import com.idos.pos.catalog.ProductListScreen
import com.idos.pos.catalog.ProductViewModel
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.core.di.posViewModel
import com.idos.pos.inventory.InventoryListScreen
import com.idos.pos.inventory.InventoryMovementFormScreen
import com.idos.pos.inventory.InventoryViewModel
import com.idos.pos.inventory.ProductStockView
import com.idos.pos.permission.UserRole
import com.idos.pos.permission.rememberPinGate
import com.idos.pos.sales.PosScreen

/**
 * Bottom-navigation shell wiring the 10 previously-standalone POS screens
 * (the `catalog`, `inventory`, `sales.PosScreen`, and `cashsession` screens —
 * see `licensing.ActivationScreen`, which is wired separately by the license
 * gate in `MainActivity`, NOT here) into one navigable app. Fills [MainActivity]'s
 * `AppRoot()` content slot — this is the "later phase" every one of those
 * screens' own KDoc refers to ("no navigation graph exists yet... wired into
 * a NavHost by a later phase").
 *
 * **Root container is a plain [Column], not `Scaffold`** (matches this
 * codebase's established convention in `ProductFormScreen`/
 * `InventoryMovementFormScreen`/`CashSessionOpenScreen`, all of which avoid
 * `Scaffold` specifically because it was found to make Robolectric's Compose
 * idle-detection hang when composed alongside an `AlertDialog`, i.e.
 * [com.idos.pos.permission.PinGateDialog]). The product-edit and
 * inventory-movement routes below render exactly that dialog (via
 * [ProductFormScreen]/[InventoryMovementFormScreen]), now nested one level
 * deeper than those screens' own tests exercise (inside this shell's
 * `NavHost` + bottom bar).
 * [com.idos.pos.nav.PosNavHostTest.priceEditOnEditRoute_pinGateOpens_blocksPersistence_andKeepsScreenMounted]
 * actually drives a price-edit save through [ProductFormScreen] nested here
 * and opens the real dialog (with an explicit JUnit timeout as a safety net)
 * — the test passes, confirming the trigger itself does not hang at this
 * nesting depth. It asserts the outcomes reliably observable in this
 * environment (no persistence + screen stays mounted) rather than querying
 * the dialog's own rendered content — a companion diagnostic confirmed that
 * specific query does not reliably converge to a found node in this
 * Robolectric environment even in the FLAT (non-nested) case, so it is a
 * pre-existing, general limitation, not something this nesting depth
 * introduces. See that test's KDoc for the full reasoning.
 *
 * **This change also fixes two latent bugs this wiring exposed** (both in
 * `ProductFormScreen.kt`/`InventoryMovementFormScreen.kt` — pre-existing
 * `android-pos-mvp` screens, invisible before because every existing
 * test/usage passed `onSaved = {}`, a no-op):
 * 1. The Save/submit button used to call `onSaved()` unconditionally right
 *    after `submitUpdate`/`recordMovement`, even when that call had just
 *    OPENED the PIN gate (not yet confirmed). Under this shell's real
 *    `NavController`, `onSaved()` pops the route, which would tear the
 *    screen (and the just-opened dialog) down before the operator could
 *    ever answer the PIN prompt — silently discarding the pending gated
 *    action.
 * 2. A first fix attempt (`if (!pinGate.isVisible) onSaved()`) only patched
 *    (1) — it introduced a NEW gap: nothing ever called `onSaved()` after a
 *    gated save actually SUCCEEDED (the operator enters the correct PIN,
 *    [PinGate.submit] runs the pending action, but the screen never
 *    navigates away). Both screens now instead observe a
 *    `ProductViewModel.saveCompleted`/`InventoryViewModel.saveCompleted`
 *    one-shot-event `StateFlow<Int>`, bumped ONLY on a successful
 *    (non-error) result inside the actual persistence coroutine — covering
 *    both the immediate ungated path and the PIN-delayed path — via
 *    `LaunchedEffect(saveCompleted) { if (saveCompleted > 0) onSaved() }`.
 *    This also closes a possible save-vs-navigation race for the CREATE and
 *    ungated-UPDATE paths: `onSaved()` now only fires AFTER the coroutine's
 *    result is known, not synchronously in the same `onClick` — checked
 *    empirically, not assumed. For the ungated-UPDATE path, see
 *    [com.idos.pos.nav.PosNavHostTest.editingAProductWithNoPriceChange_throughTheRealShell_persistsToTheDatabase],
 *    which drives a real save through this actual shell. For CREATE, driving
 *    the real form hit an unrelated, pre-existing UI-testability limitation
 *    (see that test's own KDoc); the same race question is instead verified
 *    directly against a real `ViewModelStore` by
 *    [com.idos.pos.catalog.ProductViewModelTest.createProduct_survivesViewModelStoreClear_triggeredOnlyAfterSaveCompletedFires] —
 *    which also demonstrates the cancellation risk is REAL in the abstract
 *    (an earlier draft that cleared the store without waiting for the
 *    completion signal did lose the write), closed here only by the
 *    structural guarantee that `onSaved()` cannot run before the write
 *    commits.
 *
 * **Single flat [NavHost] graph, not one nested graph per tab.** Tab
 * switching uses the standard bottom-nav pattern (`popUpTo(startDestination)
 * { saveState = true }` + `launchSingleTop = true` + `restoreState = true`)
 * exactly as specified for this change. With a flat graph this pattern
 * de-duplicates repeat clicks on the same tab and bounds the back stack size
 * across tab switches; it does NOT restore a drilled-in sub-route (e.g.
 * `productos/edit/5`) when returning to a tab after navigating away — that
 * would require per-tab nested graphs, which is a materially bigger change
 * than what was asked here and is called out as a known limitation rather
 * than silently assumed away.
 *
 * **Role-gated tabs (`android-pos-auth` Phase 3, Decision H)**: [role]
 * determines which of [posTabs] the bottom bar renders — see
 * [visibleTabsFor]. Tab visibility is a UX filter only; it is never a
 * substitute for [com.idos.pos.permission.PinGate]'s action-level checks
 * (`role-based-navigation` spec "Tab Visibility Does Not Replace
 * Action-Level Gating").
 */
@Composable
fun PosNavHost(role: UserRole) {
    val navController = rememberNavController()
    PosNavHost(navController = navController, role = role)
}

/**
 * Test seam: [navController] defaults to [rememberNavController] for
 * production use; [com.idos.pos.nav.PosNavHostTest] passes a
 * `TestNavHostController` so it can drive navigation directly (e.g. to
 * simulate the barcode-scan "unknown barcode" hand-off, which otherwise goes
 * through [com.idos.pos.scan.BarcodeScanScreen]'s real-camera-bound
 * `CameraPreviewWithAnalysis` — untestable under Robolectric, per that
 * composable's own class doc).
 */
@Composable
internal fun PosNavHost(navController: NavHostController, role: UserRole) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            NavHost(navController = navController, startDestination = ROUTE_VENTA) {
                composable(ROUTE_VENTA) {
                    VentaRoute(onUnknownBarcode = { barcode ->
                        navController.navigate(productCreateRoute(barcode))
                    })
                }

                composable(ROUTE_PRODUCTOS) {
                    ProductListScreen(
                        onAddProduct = { navController.navigate(productCreateRoute(null)) },
                        onProductClick = { product -> navController.navigate(productEditRoute(product.id)) },
                    )
                }

                composable(
                    route = ROUTE_PRODUCTOS_CREATE,
                    arguments = listOf(navArgument(ARG_BARCODE) {
                        type = NavType.StringType
                        defaultValue = ""
                    }),
                ) { backStackEntry ->
                    // Navigation Compose already URI-decodes captured query
                    // arguments internally (NavDeepLink's argument matching)
                    // — do NOT decode again here. An earlier revision called
                    // Uri.decode() a second time on this already-decoded
                    // value, which corrupted barcodes containing `%`/`+`
                    // (double-decoding), confirmed empirically by
                    // `PosNavHostTest.navigatingToProductCreateRoute_withSpecialCharsInBarcode_roundTripsCorrectly`.
                    val barcode = backStackEntry.arguments?.getString(ARG_BARCODE).orEmpty()
                    val pinGate = rememberPinGate(LocalAppContainer.current.authRepository)
                    ProductFormScreen(
                        product = null,
                        pinGate = pinGate,
                        onSaved = { navController.popBackStack() },
                        initialBarcode = barcode.ifBlank { null },
                    )
                }

                composable(
                    route = ROUTE_PRODUCTOS_EDIT,
                    arguments = listOf(navArgument(ARG_PRODUCT_ID) { type = NavType.LongType }),
                ) { backStackEntry ->
                    val productId = backStackEntry.arguments!!.getLong(ARG_PRODUCT_ID)
                    ProductEditRoute(productId = productId, onSaved = { navController.popBackStack() })
                }

                composable(ROUTE_INVENTARIO) {
                    InventoryListScreen(
                        onProductClick = { row -> navController.navigate(inventoryMovementRoute(row.productId)) },
                    )
                }

                composable(
                    route = ROUTE_INVENTARIO_MOVEMENT,
                    arguments = listOf(navArgument(ARG_PRODUCT_ID) { type = NavType.LongType }),
                ) { backStackEntry ->
                    val productId = backStackEntry.arguments!!.getLong(ARG_PRODUCT_ID)
                    InventoryMovementRoute(productId = productId, onSaved = { navController.popBackStack() })
                }

                composable(ROUTE_CAJA) {
                    CajaRoute(onCloseRequested = { navController.navigate(ROUTE_CAJA_CLOSE) })
                }

                composable(ROUTE_CAJA_CLOSE) {
                    val viewModel: CashSessionViewModel = posViewModel(LocalAppContainer.current)
                    CashSessionCloseScreen(onClosed = { navController.popBackStack() }, viewModel = viewModel)
                }
            }
        }

        PosBottomNavigationBar(navController, role)
    }
}

@Composable
private fun VentaRoute(onUnknownBarcode: (barcode: String) -> Unit) {
    val viewModel: CashSessionViewModel = posViewModel(LocalAppContainer.current)
    val session by viewModel.currentSession.collectAsState()

    if (session == null) {
        CashSessionOpenScreen(onOpened = {}, viewModel = viewModel)
    } else {
        PosScreen(
            onSaleConfirmed = {},
            onUnknownBarcode = onUnknownBarcode,
        )
    }
}

@Composable
private fun CajaRoute(onCloseRequested: () -> Unit) {
    val viewModel: CashSessionViewModel = posViewModel(LocalAppContainer.current)
    val session by viewModel.currentSession.collectAsState()

    if (session == null) {
        CashSessionOpenScreen(onOpened = {}, viewModel = viewModel)
    } else {
        CashSessionScreen(onCloseRequested = onCloseRequested, viewModel = viewModel)
    }
}

/**
 * Resolves the `productId` nav argument into a [ProductEntity] before
 * rendering [ProductFormScreen] in edit mode — [ProductListScreen]'s click
 * callback only carries the full entity at click time, but the nav route
 * itself only carries the id, so a fresh lookup is needed here. See
 * [ProductViewModel.findById]'s doc for why this goes through the ViewModel
 * rather than the repository directly.
 *
 * **Guards `loaded && product != null` (matching [InventoryMovementRoute])**:
 * [ProductFormScreen] treats `product = null` as CREATE mode, so a stale or
 * deleted `productId` (by-id lookup returns `null`) must NOT fall through to
 * rendering the form anyway — that would silently swap a broken edit link
 * into a blank "create new product" form with no error and no indication the
 * mode changed, risking an operator accidentally creating a duplicate
 * product. When the lookup resolves to `null`, this route renders nothing
 * (same dead-end-but-safe behavior [InventoryMovementRoute] already has) —
 * the bottom nav bar remains visible/usable to leave the route.
 */
@Composable
private fun ProductEditRoute(productId: Long, onSaved: () -> Unit) {
    val viewModel: ProductViewModel = posViewModel(LocalAppContainer.current)
    var product by remember(productId) { mutableStateOf<ProductEntity?>(null) }
    var loaded by remember(productId) { mutableStateOf(false) }

    LaunchedEffect(productId) {
        product = viewModel.findById(productId)
        loaded = true
    }

    val current = product
    if (loaded && current != null) {
        val pinGate = rememberPinGate(LocalAppContainer.current.authRepository)
        ProductFormScreen(
            product = current,
            pinGate = pinGate,
            onSaved = onSaved,
            viewModel = viewModel,
        )
    }
}

/** Same by-id resolution as [ProductEditRoute], for [InventoryMovementFormScreen]'s `ProductStockView`. */
@Composable
private fun InventoryMovementRoute(productId: Long, onSaved: () -> Unit) {
    val viewModel: InventoryViewModel = posViewModel(LocalAppContainer.current)
    var stockView by remember(productId) { mutableStateOf<ProductStockView?>(null) }
    var loaded by remember(productId) { mutableStateOf(false) }

    LaunchedEffect(productId) {
        stockView = viewModel.findProductStockView(productId)
        loaded = true
    }

    val current = stockView
    if (loaded && current != null) {
        val pinGate = rememberPinGate(LocalAppContainer.current.authRepository)
        InventoryMovementFormScreen(
            product = current,
            pinGate = pinGate,
            onSaved = onSaved,
            viewModel = viewModel,
        )
    }
}

internal const val ROUTE_VENTA = "venta"
internal const val ROUTE_PRODUCTOS = "productos"
internal const val ROUTE_INVENTARIO = "inventario"
internal const val ROUTE_CAJA = "caja"
internal const val ROUTE_CAJA_CLOSE = "caja/close"

/** Internal (not private) so [VisibleTabsForTest] can assert on the filtered shape by route/label. */
internal data class PosTab(val route: String, val label: String)

private val posTabs = listOf(
    PosTab(ROUTE_VENTA, "Venta"),
    PosTab(ROUTE_PRODUCTOS, "Productos"),
    PosTab(ROUTE_INVENTARIO, "Inventario"),
    PosTab(ROUTE_CAJA, "Caja"),
)

/**
 * Filters [posTabs] by [role] (design.md Decision H, `role-based-navigation`
 * spec): `CASHIER` sees exactly Venta/Caja; `ADMIN` sees all four, in the
 * existing declared order. A plain `when` transform, not a stored
 * per-role/tab permission — tab visibility derives fresh from the session's
 * role at composition time every time (`role-based-navigation` "Tab
 * Visibility Derives From Session Role At Composition Time").
 */
internal fun visibleTabsFor(role: UserRole): List<PosTab> = when (role) {
    UserRole.CASHIER -> posTabs.filter { it.route == ROUTE_VENTA || it.route == ROUTE_CAJA }
    UserRole.ADMIN -> posTabs
}

@Composable
private fun PosBottomNavigationBar(navController: NavHostController, role: UserRole) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val tabs = visibleTabsFor(role)

    NavigationBar(modifier = Modifier.testTag(BOTTOM_NAV_TEST_TAG)) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {},
                label = { Text(tab.label) },
                modifier = Modifier.testTag(bottomNavItemTestTag(tab.route)),
            )
        }
    }
}

private const val ARG_BARCODE = "barcode"
private const val ARG_PRODUCT_ID = "productId"
private const val ROUTE_PRODUCTOS_CREATE = "productos/create?barcode={$ARG_BARCODE}"
private const val ROUTE_PRODUCTOS_EDIT = "productos/edit/{$ARG_PRODUCT_ID}"
private const val ROUTE_INVENTARIO_MOVEMENT = "inventario/movement/{$ARG_PRODUCT_ID}"

/**
 * `Uri.encode`s the barcode before it goes into the route string.
 * [com.idos.pos.scan.BarcodeAnalyzer] scans `FORMAT_CODE_128`, which can
 * legally encode arbitrary ASCII including `&`, `#`, `%`, `+` — unescaped,
 * any of those would inject a phantom query param, get misread as
 * percent-encoding, or get misread as a space when this route string is
 * parsed. The receiving composable `Uri.decode`s it back (see the
 * `ROUTE_PRODUCTOS_CREATE` destination in [PosNavHost]).
 */
internal fun productCreateRoute(barcode: String?): String = "productos/create?barcode=${Uri.encode(barcode.orEmpty())}"
internal fun productEditRoute(productId: Long): String = "productos/edit/$productId"
internal fun inventoryMovementRoute(productId: Long): String = "inventario/movement/$productId"

const val BOTTOM_NAV_TEST_TAG = "pos-bottom-nav"

fun bottomNavItemTestTag(route: String) = "pos-bottom-nav-item-$route"
