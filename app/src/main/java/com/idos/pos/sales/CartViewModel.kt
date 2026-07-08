package com.idos.pos.sales

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idos.pos.catalog.ProductEntity
import com.idos.pos.core.di.AppContainer
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.domainErrorOrNull
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Cart-building + confirm-sale ViewModel (task 8.4). Owns the in-memory
 * [Cart] the operator builds via barcode scan / product pick before
 * confirming a sale through [SalesRepository.createOrder]. Constructed via
 * [com.idos.pos.core.di.posViewModel] with the sole [AppContainer]
 * constructor argument, matching every other ViewModel in this codebase.
 *
 * **Scan-to-cart forward-hook, CLOSED (task 8.4)**: [com.idos.pos.scan.ScanViewModel]'s
 * `onProductFound`/[com.idos.pos.scan.BarcodeScanScreen]'s `onProductFound`
 * callback was left as an explicit hook since Phase 6 (PR5) specifically for
 * this phase to wire "add to cart" — [PosScreen] is the concrete call site:
 * it renders [com.idos.pos.scan.BarcodeScanScreen] with
 * `onProductFound = { product -> viewModel.addToCart(product) }`, i.e.
 * [addToCart] below.
 *
 * [ProductEntity.price]/[ProductEntity.costPrice] are SNAPSHOTTED into the
 * resulting [CartLine] at add-to-cart time (design.md Room schema notes:
 * "`order_line.price` snapshots `product.price` at sale time") — never
 * re-read live from the catalog afterward, so a price edit mid-cart-building
 * does not retroactively change an already-added line.
 */
class CartViewModel(container: AppContainer) : ViewModel() {

    private val salesRepository = container.salesRepository

    private val _cart = MutableStateFlow(Cart())
    val cart: StateFlow<Cart> = _cart.asStateFlow()

    private val _lastError = MutableStateFlow<DomainError?>(null)
    val lastError: StateFlow<DomainError?> = _lastError.asStateFlow()

    private val _lastCompletedOrderId = MutableStateFlow<Long?>(null)
    val lastCompletedOrderId: StateFlow<Long?> = _lastCompletedOrderId.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    /**
     * Adds [product] to the cart, snapshotting its current price/costPrice
     * (see class doc). Adding a product already in the cart increases its
     * line's quantity instead of creating a second line for the same product.
     */
    fun addToCart(product: ProductEntity, quantity: Int = 1) {
        val currentLines = _cart.value.lines
        val existing = currentLines.find { it.productId == product.id }
        _cart.value = if (existing != null) {
            _cart.value.copy(
                lines = currentLines.map {
                    if (it.productId == product.id) it.copy(quantity = it.quantity + quantity) else it
                },
            )
        } else {
            _cart.value.copy(
                lines = currentLines + CartLine(
                    productId = product.id,
                    productName = product.name,
                    quantity = quantity,
                    price = product.price,
                    costPrice = product.costPrice,
                ),
            )
        }
    }

    fun removeLine(productId: Long) {
        _cart.value = _cart.value.copy(lines = _cart.value.lines.filterNot { it.productId == productId })
    }

    fun clearCart() {
        _cart.value = Cart()
    }

    /**
     * Confirms the sale for the current cart via [SalesRepository.createOrder].
     * On success, clears the cart and exposes the new order id through
     * [lastCompletedOrderId]; on failure, the cart is left untouched (so the
     * operator can correct the payment/session and retry) and the failure is
     * exposed through [lastError].
     */
    fun confirmSale(payment: BigDecimal, paymentMethodId: Long?) {
        val cartSnapshot = _cart.value
        viewModelScope.launch {
            val result = salesRepository.createOrder(cartSnapshot, payment, paymentMethodId)
            _lastError.value = result.domainErrorOrNull()
            result.getOrNull()?.let { orderId ->
                _lastCompletedOrderId.value = orderId
                _cart.value = Cart()
            }
        }
    }
}
