package com.idos.pos.sales

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * `@Dao abstract class` (not `interface`) so [insertOrderWithLines] can be a
 * concrete `@Transaction`-annotated method calling the other abstract DAO
 * methods on `this` — the same Room+Kotlin pattern
 * `inventory/InventoryDao.applyMovementAtomic` and
 * `cashsession/CashSessionDao`'s atomic primitives already establish (task
 * 8.1).
 */
@Dao
abstract class SalesDao {

    @Query("SELECT * FROM orders WHERE id = :id")
    abstract suspend fun findById(id: Long): OrderEntity?

    @Query("SELECT * FROM orders ORDER BY createdAt DESC")
    abstract fun findAllFlow(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM order_line WHERE orderId = :orderId")
    abstract suspend fun findLinesForOrder(orderId: Long): List<OrderLineEntity>

    /**
     * Per-day sequential order number (task 8.3; specs/sales-order/spec.md
     * "Per-Day Sequential Order Number"). Deliberately takes an
     * already-computed `[dayStart, dayEndExclusive)` range rather than
     * resolving "today" itself — see [todayRange]'s doc for why day-boundary
     * arithmetic must never be computed inside the DAO/query layer. Returns
     * `null` when no order exists in the range (first order of the day).
     */
    @Query("SELECT MAX(orderNumber) FROM orders WHERE createdAt >= :dayStart AND createdAt < :dayEndExclusive")
    abstract suspend fun maxOrderNumberInRange(dayStart: Instant, dayEndExclusive: Instant): Int?

    @Insert
    abstract suspend fun insertOrder(entity: OrderEntity): Long

    @Insert
    abstract suspend fun insertLines(entities: List<OrderLineEntity>)

    /**
     * Persists the order header + every line as one DAO transaction (task 8.1;
     * design.md "Transaction boundaries"). Called from inside
     * [SalesRepository.createOrder]'s ambient `db.withTransaction { }` —
     * Room/SQLite nest this `@Transaction` inside that already-open outer
     * transaction rather than committing independently, so a LATER failure in
     * that same outer block (e.g.
     * [com.idos.pos.inventory.InventoryDao.applyMovementAtomic] throwing
     * `InsufficientStock` on a subsequent line) still rolls this insert back
     * too — see [SalesRepository]'s class doc for the full atomicity argument.
     */
    @Transaction
    open suspend fun insertOrderWithLines(order: OrderEntity, lines: List<CartLine>): Long {
        val orderId = insertOrder(order)
        if (lines.isNotEmpty()) {
            insertLines(
                lines.map { line ->
                    OrderLineEntity(
                        orderId = orderId,
                        productId = line.productId,
                        quantity = line.quantity,
                        price = line.price,
                        costPrice = line.costPrice,
                        subtotal = line.subtotal,
                    )
                },
            )
        }
        return orderId
    }
}
