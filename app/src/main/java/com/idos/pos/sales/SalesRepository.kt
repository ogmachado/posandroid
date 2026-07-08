package com.idos.pos.sales

import androidx.room.withTransaction
import com.idos.pos.cashsession.CashSessionDao
import com.idos.pos.core.db.PosDatabase
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.DomainException
import com.idos.pos.inventory.InventoryDao
import com.idos.pos.inventory.MovementType
import java.math.BigDecimal
import java.time.Clock
import kotlinx.coroutines.flow.Flow

/**
 * Sales-order repository — the critical atomic path (tasks 8.1/8.3;
 * design.md "Data Flow — order create (the critical atomic path)" +
 * "Interfaces / Contracts" `SalesRepository`).
 *
 * The entire order-create sequence runs inside ONE [PosDatabase.withTransaction]
 * (Room-ktx's suspend, multi-DAO transaction API), spanning
 * [SalesDao.insertOrderWithLines] for the `orders`/`order_line` rows and the
 * SAME [InventoryDao.applyMovementAtomic] primitive — the single stock-mutation
 * owner (design.md "Decision: Separate `inventory` table ... keep
 * `InventoryService` as the single mutation owner") — once per cart line for
 * the `OUT` movement. It never mutates `inventory.stock` or inserts
 * `inventory_movement` directly.
 *
 * **Why a mid-cart failure rolls back the WHOLE order, not just the failing
 * line** (specs/sales-order/spec.md "One line fails stock validation, whole
 * order is rolled back"): [InventoryDao.applyMovementAtomic] and
 * [SalesDao.insertOrderWithLines] are each `@Transaction`-annotated DAO
 * methods, but they are invoked here from INSIDE [database]'s already-open
 * outer transaction (`withTransaction { }`). Room/SQLite's nested-transaction
 * semantics mean a `@Transaction` method called while a transaction is
 * already active does not commit independently — it only marks its own
 * nesting level successful. If ANY line's [InventoryDao.applyMovementAtomic]
 * throws (e.g. [DomainError.InsufficientStock]), the exception propagates
 * past every earlier successful step in this same `withTransaction` block
 * (the order insert, every already-inserted line, and every already-applied
 * stock decrement for prior lines in the SAME cart) without those inner
 * levels ever reaching `setTransactionSuccessful()`. The outermost
 * `endTransaction()` then rolls back everything since the transaction opened
 * — no partial order, no partial stock decrement can survive, regardless of
 * which line in the cart failed or how many lines came before it.
 *
 * **Day-boundary correctness** (see [todayRange]'s doc): [clock] defaults to
 * [Clock.systemDefaultZone] in production but is an injectable constructor
 * parameter specifically so `SalesRepositoryTest` can freeze it at an exact
 * instant/zone to prove the daily order-number sequence
 * (specs/sales-order/spec.md "Per-Day Sequential Order Number") resolves the
 * DEVICE'S LOCAL calendar day, not a naive UTC truncation — the exact bug
 * class the sibling Java backend shipped and later fixed (`D:\idos-pos`
 * commit `0abefbf fix(reports): correct day-boundary comparisons across
 * timezones`).
 */
class SalesRepository(
    private val database: PosDatabase,
    private val salesDao: SalesDao,
    private val inventoryDao: InventoryDao,
    private val paymentMethodDao: PaymentMethodDao,
    private val cashSessionDao: CashSessionDao,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    fun findAllFlow(): Flow<List<OrderEntity>> = salesDao.findAllFlow()

    suspend fun linesForOrder(orderId: Long): List<OrderLineEntity> = salesDao.findLinesForOrder(orderId)

    /**
     * Creates an order + its lines + one `OUT` inventory movement per line, as
     * a single atomic unit (see class doc). [methodId] resolves the payment
     * method (`null` defaults to the seeded `CASH` method); requires a
     * currently open cash session.
     *
     * Fails with [DomainError.PaymentMethodNotFound] (explicit [methodId] with
     * no matching row), [DomainError.NoOpenSession] (no cash session open), or
     * [DomainError.InsufficientStock] (any line's requested quantity exceeds
     * available stock — the whole order is rolled back, see class doc).
     */
    suspend fun createOrder(cart: Cart, payment: BigDecimal, methodId: Long?): Result<Long> = try {
        val orderId = database.withTransaction {
            val paymentMethod = resolvePaymentMethod(methodId)
            val session = cashSessionDao.findOpenSession() ?: throw DomainException(DomainError.NoOpenSession)

            val range = todayRange(clock)
            val nextOrderNumber = (salesDao.maxOrderNumberInRange(range.start, range.endExclusive) ?: 0) + 1

            val order = OrderEntity(
                orderNumber = nextOrderNumber,
                sessionId = session.id,
                paymentMethodId = paymentMethod.id,
                total = cart.total,
                payment = payment,
                changeAmount = payment - cart.total,
                createdAt = clock.instant(),
            )

            val id = salesDao.insertOrderWithLines(order, cart.lines)

            cart.lines.forEach { line ->
                inventoryDao.applyMovementAtomic(
                    productId = line.productId,
                    type = MovementType.OUT,
                    quantity = line.quantity,
                    orderId = id,
                )
            }

            id
        }
        Result.success(orderId)
    } catch (e: DomainException) {
        Result.failure(e)
    }

    private suspend fun resolvePaymentMethod(methodId: Long?): PaymentMethodEntity = if (methodId != null) {
        paymentMethodDao.findById(methodId) ?: throw DomainException(DomainError.PaymentMethodNotFound(methodId))
    } else {
        paymentMethodDao.findByCode("CASH")
            ?: error("Seeded CASH payment method is missing — PosDatabaseSeeder invariant violated")
    }
}
