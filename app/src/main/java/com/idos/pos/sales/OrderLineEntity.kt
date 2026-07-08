package com.idos.pos.sales

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.idos.pos.catalog.ProductEntity
import java.math.BigDecimal

/**
 * Sale-order line row (task 8.1; specs/sales-order/spec.md).
 *
 * [price]/[costPrice] **SNAPSHOT** [ProductEntity.price]/[ProductEntity.costPrice]
 * at the moment the line is added to the cart (design.md Room schema notes:
 * "`order_line.price` snapshots `product.price` at sale time") — they are
 * copied once by [com.idos.pos.sales.CartViewModel.addToCart] and never
 * re-read live from `product` afterward, including after the order is
 * persisted. This is why [productId] is RESTRICT rather than CASCADE: a
 * product cannot be deleted while any historical line still references it,
 * but its current `price`/`costPrice` may freely change without touching
 * past orders.
 *
 * Written EXCLUSIVELY through [SalesDao.insertOrderWithLines].
 */
@Entity(
    tableName = "order_line",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["orderId"]),
        Index(value = ["productId"]),
    ],
)
data class OrderLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long,
    val productId: Long,
    val quantity: Int,
    val price: BigDecimal,
    val costPrice: BigDecimal,
    val subtotal: BigDecimal,
)
