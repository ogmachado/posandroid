package com.idos.pos.sales

import java.math.BigDecimal

/**
 * In-memory cart line (task 8.1/8.4) — the pre-persistence shape
 * [SalesRepository.createOrder] consumes and [SalesDao.insertOrderWithLines]
 * turns into an [OrderLineEntity] per line.
 *
 * [price]/[costPrice] are captured (snapshotted) by
 * [com.idos.pos.sales.CartViewModel.addToCart] at add-to-cart time straight
 * from [com.idos.pos.catalog.ProductEntity] — see [OrderLineEntity]'s class
 * doc for why this snapshot must never be re-derived live later.
 * [productName] is display-only (never persisted) so the cart UI can render a
 * line without a second catalog lookup.
 */
data class CartLine(
    val productId: Long,
    val productName: String,
    val quantity: Int,
    val price: BigDecimal,
    val costPrice: BigDecimal,
) {
    val subtotal: BigDecimal get() = price.multiply(BigDecimal(quantity))
}

/** In-memory cart (task 8.1/8.4) — owned by [com.idos.pos.sales.CartViewModel] until confirm-sale. */
data class Cart(val lines: List<CartLine> = emptyList()) {
    val total: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, line -> acc + line.subtotal }
}
