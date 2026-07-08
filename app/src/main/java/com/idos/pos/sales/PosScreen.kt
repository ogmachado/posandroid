package com.idos.pos.sales

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.idos.pos.core.di.LocalAppContainer
import com.idos.pos.core.di.posViewModel
import com.idos.pos.scan.BarcodeScanScreen
import java.math.BigDecimal

/**
 * POS cart + confirm-sale screen (task 8.4). Two local states, toggled by
 * [isScanning] — no navigation graph exists yet in Slice A (same note every
 * other `Screen.kt` in this codebase carries):
 *
 * 1. Scanning: renders [BarcodeScanScreen] full-screen, with
 *    `onProductFound = { product -> viewModel.addToCart(product) }` — the
 *    concrete closure of the scan-to-cart forward-hook left open since Phase
 *    6/PR5 (see [CartViewModel]'s class doc and
 *    [com.idos.pos.scan.ScanViewModel]'s "Cart-wiring ordering deviation" doc
 *    for the hook this closes).
 * 2. Cart: lists the current [Cart] lines/total, a payment field, and
 *    "Confirm sale" driving [CartViewModel.confirmSale]. [DomainError]
 *    failures render as an inline text row rather than a real Material
 *    `Snackbar` — a `Snackbar`/`SnackbarHostState` needs a `Scaffold`, which
 *    [com.idos.pos.inventory.InventoryMovementFormScreen]'s and
 *    [com.idos.pos.catalog.ProductFormScreen]'s docs both flag as making
 *    Robolectric's Compose idle-detection hang when combined with an
 *    `AlertDialog` in this environment; this screen renders no dialog, but
 *    stays consistent with the plain-`Column` convention those establish.
 *
 * [paymentMethodId] is deliberately not exposed as a picker in this initial
 * cut — `null` always resolves to the seeded `CASH` method
 * (specs/sales-order/spec.md "Payment Method Resolution Defaults to CASH");
 * a payment-method selector is straightforward to add later without changing
 * [CartViewModel.confirmSale]'s signature.
 */
@Composable
fun PosScreen(
    onSaleConfirmed: (orderId: Long) -> Unit,
    onUnknownBarcode: (barcode: String) -> Unit = {},
    viewModel: CartViewModel = posViewModel(LocalAppContainer.current),
) {
    var isScanning by remember { mutableStateOf(false) }

    if (isScanning) {
        BarcodeScanScreen(
            onProductFound = { product ->
                viewModel.addToCart(product)
                isScanning = false
            },
            onUnknownBarcode = { barcode ->
                isScanning = false
                onUnknownBarcode(barcode)
            },
        )
        return
    }

    val cart by viewModel.cart.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val lastCompletedOrderId by viewModel.lastCompletedOrderId.collectAsState()

    var payment by remember { mutableStateOf("") }

    LaunchedEffect(lastCompletedOrderId) {
        lastCompletedOrderId?.let(onSaleConfirmed)
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Button(
            modifier = Modifier.testTag(SCAN_BUTTON_TEST_TAG),
            onClick = { isScanning = true },
        ) {
            Text("Scan product")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (cart.lines.isEmpty()) {
            Text("Cart is empty — scan a product to add it", modifier = Modifier.testTag(CART_EMPTY_TEST_TAG))
        }

        cart.lines.forEach { line ->
            Row(modifier = Modifier.fillMaxWidth().testTag(cartLineTestTag(line.productId))) {
                Text("${line.productName} x${line.quantity}")
                Text(line.subtotal.toPlainString())
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text("Total: ${cart.total}", modifier = Modifier.testTag(CART_TOTAL_TEST_TAG))

        OutlinedTextField(
            value = payment,
            onValueChange = { payment = it },
            label = { Text("Payment received") },
            modifier = Modifier.fillMaxWidth().testTag(PAYMENT_FIELD_TEST_TAG),
        )

        if (lastError != null) {
            Text(
                text = "Could not complete sale: $lastError",
                modifier = Modifier.testTag(SALE_ERROR_TEST_TAG),
            )
        }

        Button(
            modifier = Modifier.testTag(CONFIRM_SALE_BUTTON_TEST_TAG),
            enabled = cart.lines.isNotEmpty(),
            onClick = {
                val parsedPayment = payment.toBigDecimalOrNullSafe() ?: return@Button
                viewModel.confirmSale(parsedPayment, paymentMethodId = null)
                payment = ""
            },
        ) {
            Text("Confirm sale")
        }
    }
}

private fun String.toBigDecimalOrNullSafe(): BigDecimal? = try {
    BigDecimal(this)
} catch (e: NumberFormatException) {
    null
}

const val SCAN_BUTTON_TEST_TAG = "pos-scan-button"
const val CART_EMPTY_TEST_TAG = "pos-cart-empty"
const val CART_TOTAL_TEST_TAG = "pos-cart-total"
const val PAYMENT_FIELD_TEST_TAG = "pos-payment-field"
const val SALE_ERROR_TEST_TAG = "pos-sale-error"
const val CONFIRM_SALE_BUTTON_TEST_TAG = "pos-confirm-sale"

fun cartLineTestTag(productId: Long) = "pos-cart-line-$productId"
