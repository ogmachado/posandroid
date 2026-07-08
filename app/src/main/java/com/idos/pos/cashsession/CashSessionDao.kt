package com.idos.pos.cashsession

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.idos.pos.core.domain.DomainError
import com.idos.pos.core.domain.DomainException
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * `@Dao abstract class` (not `interface`) so [openAtomic]/[addMovementAtomic]/
 * [closeSessionAtomic] can be concrete `@Transaction`-annotated methods calling
 * the other abstract DAO methods on `this` — the same Room+Kotlin pattern
 * `inventory/InventoryDao.applyMovementAtomic` established (task 5.3), applied
 * here for the cash-session invariants (task 7.1/7.3/7.4).
 */
@Dao
abstract class CashSessionDao {

    @Query("SELECT * FROM cash_session WHERE status = 'OPEN' LIMIT 1")
    abstract suspend fun findOpenSession(): CashSessionEntity?

    @Query("SELECT * FROM cash_session WHERE status = 'OPEN' LIMIT 1")
    abstract fun openSessionFlow(): Flow<CashSessionEntity?>

    @Query("SELECT * FROM cash_session WHERE id = :id")
    abstract suspend fun findById(id: Long): CashSessionEntity?

    @Insert
    abstract suspend fun insertSession(entity: CashSessionEntity): Long

    @Update
    abstract suspend fun updateSession(entity: CashSessionEntity)

    @Insert
    abstract suspend fun insertMovement(entity: CashMovementEntity): Long

    /**
     * One-shot counterpart of [movementsForSessionFlow] — same SQL, `suspend`
     * instead of `Flow`, used by [closeSessionAtomic] to compute the
     * close-time snapshot without collecting a `Flow` from inside a
     * `@Transaction` method body. Mirrors `InventoryDao.findByProductId`
     * (suspend) alongside `InventoryDao.stockFlow` (Flow) — task 5.1's
     * established "paired suspend/live query over the same SQL" idiom.
     */
    @Query("SELECT * FROM cash_movement WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    abstract suspend fun movementsForSession(sessionId: Long): List<CashMovementEntity>

    @Query("SELECT * FROM cash_movement WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    abstract fun movementsForSessionFlow(sessionId: Long): Flow<List<CashMovementEntity>>

    /**
     * Raw per-order totals (task 8.3; NOT a SQL `SUM()` — see [sumMoney]) for
     * cash-affecting sales linked to [sessionId]: joins `orders`/`payment_method`
     * filtered to `affectsCashBalance = true`. Closes the "PHASE 7 → PHASE 8
     * SEAM" `ExpectedBalance.kt` documents on [computeExpectedBalance]. Queried
     * by table name (not entity class) since `orders`/`payment_method` are
     * owned by `sales`, not `cashsession` — this DAO has no compile
     * dependency on [com.idos.pos.sales.OrderEntity]/
     * [com.idos.pos.sales.PaymentMethodEntity], only on the raw SQL schema,
     * mirroring [movementsForSessionFlow]'s "suspend + Flow paired query"
     * idiom.
     */
    @Query(
        """
        SELECT o.total FROM orders o
        INNER JOIN payment_method pm ON pm.id = o.paymentMethodId
        WHERE o.sessionId = :sessionId AND pm.affectsCashBalance = 1
        """,
    )
    abstract suspend fun cashSaleTotalsForSession(sessionId: Long): List<BigDecimal>

    @Query(
        """
        SELECT o.total FROM orders o
        INNER JOIN payment_method pm ON pm.id = o.paymentMethodId
        WHERE o.sessionId = :sessionId AND pm.affectsCashBalance = 1
        """,
    )
    abstract fun cashSaleTotalsForSessionFlow(sessionId: Long): Flow<List<BigDecimal>>

    /**
     * Opens a new session, enforcing "Single Open Session Per Device"
     * (specs/cash-session/spec.md) atomically: check-then-insert in one DAO
     * transaction so no other write can interleave. Throws
     * [DomainException] wrapping [DomainError.SessionAlreadyOpen] when a
     * session is already open — the existing open session is left untouched
     * (no insert is attempted).
     */
    @Transaction
    open suspend fun openAtomic(openingBalance: BigDecimal, openedAt: Instant = Instant.now()): Long {
        if (findOpenSession() != null) {
            throw DomainException(DomainError.SessionAlreadyOpen)
        }
        return insertSession(
            CashSessionEntity(openedAt = openedAt, openingBalance = openingBalance, status = CashSessionStatus.OPEN),
        )
    }

    /**
     * Records a DEPOSIT/WITHDRAWAL movement against the currently open
     * session (specs/cash-session/spec.md "Manual Cash Movements Only on an
     * Open Session"). Throws [DomainException] wrapping
     * [DomainError.NoOpenSession] when no session is open — this single
     * variant covers BOTH "no session was ever opened" and "the session
     * exists but is CLOSED" (there is no dedicated "session not open" variant
     * in [DomainError]; `NoOpenSession` already reads correctly for both:
     * "there is currently no OPEN session").
     */
    @Transaction
    open suspend fun addMovementAtomic(
        type: CashMovementType,
        amount: BigDecimal,
        reason: String?,
        createdAt: Instant = Instant.now(),
    ): Long {
        val session = findOpenSession() ?: throw DomainException(DomainError.NoOpenSession)
        return insertMovement(
            CashMovementEntity(sessionId = session.id, type = type, amount = amount, reason = reason, createdAt = createdAt),
        )
    }

    /**
     * Closes the currently open session, snapshotting `expectedBalance`/
     * `salesTotal`/`difference` exactly once (specs/cash-session/spec.md
     * "Expected Balance Formula on Close", "Session Close Is Terminal").
     * Throws [DomainException] wrapping [DomainError.NoOpenSession] when
     * there is no open session — this covers BOTH "never opened" and "already
     * closed" (a second close attempt finds no OPEN row and is rejected the
     * same way), matching [addMovementAtomic]'s reuse of the same variant.
     *
     * `salesTotal` is computed from [cashSaleTotalsForSession] (task 8.3) —
     * see `ExpectedBalance.kt`'s "PHASE 7 → PHASE 8 SEAM — CLOSED" doc on
     * [computeExpectedBalance].
     */
    @Transaction
    open suspend fun closeSessionAtomic(counted: BigDecimal, closedAt: Instant = Instant.now()): CashSessionEntity {
        val session = findOpenSession() ?: throw DomainException(DomainError.NoOpenSession)
        val movementsNet = movementsForSession(session.id).netAmount()
        val salesTotal = cashSaleTotalsForSession(session.id).sumMoney()
        val expectedBalance = computeExpectedBalance(session.openingBalance, movementsNet, salesTotal)
        val closed = session.copy(
            closedAt = closedAt,
            closingBalance = counted,
            expectedBalance = expectedBalance,
            salesTotal = salesTotal,
            difference = counted - expectedBalance,
            status = CashSessionStatus.CLOSED,
        )
        updateSession(closed)
        return closed
    }
}
