package com.idos.pos.cashsession

import com.idos.pos.core.domain.DomainException
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Read model exposed to the ViewModel/UI layer (task 7.5) — a [CashSessionEntity]
 * plus its always-correct [expectedBalance], which is LIVE while [status] is
 * OPEN and the snapshotted close-time value once CLOSED (design.md "Decision:
 * `expectedBalance` computed LIVE ..."). Callers must never read
 * [CashSessionEntity.expectedBalance] directly — go through this view or
 * [CashSessionRepository.currentSessionFlow] instead.
 */
data class CashSessionView(
    val id: Long,
    val openedAt: Instant,
    val closedAt: Instant?,
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal?,
    val expectedBalance: BigDecimal,
    val salesTotal: BigDecimal?,
    val difference: BigDecimal?,
    val status: CashSessionStatus,
)

/**
 * Cash-session repository (tasks 7.3/7.4; design.md "Interfaces / Contracts"
 * `CashSessionRepository`). Delegates every invariant (single-open-session,
 * movements-only-on-open, close-is-terminal) to [CashSessionDao]'s atomic
 * primitives and converts their thrown [DomainException] into `Result.failure`
 * — the same typed-failure convention `inventory/InventoryRepository` uses.
 *
 * Ported from the reference backend's `cash-register` module, dropping the
 * cash-register catalog and cashier-username scoping (single device, single
 * operator) per specs/cash-session/spec.md "Purpose".
 */
class CashSessionRepository(private val cashSessionDao: CashSessionDao) {

    /**
     * Live session view: while OPEN, [CashSessionView.expectedBalance] is
     * recomputed on every movement (task 7.4) by re-deriving from
     * [CashSessionEntity.openingBalance] + the live movements list — NEVER
     * from the (null, while open) persisted snapshot columns. Emits `null`
     * when no session is open.
     *
     * `flatMapLatest` re-subscribes to [CashSessionDao.movementsForSessionFlow]
     * whenever the open session itself changes (opened/closed) — cheap here
     * since at most one session is ever open at a time.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun currentSessionFlow(): Flow<CashSessionView?> =
        cashSessionDao.openSessionFlow().flatMapLatest { session ->
            if (session == null) {
                flowOf(null)
            } else {
                cashSessionDao.movementsForSessionFlow(session.id).map { movements ->
                    toView(session, computeExpectedBalance(session.openingBalance, movements.netAmount()))
                }
            }
        }

    fun movementsForSessionFlow(sessionId: Long): Flow<List<CashMovementEntity>> =
        cashSessionDao.movementsForSessionFlow(sessionId)

    /** Fails with [com.idos.pos.core.domain.DomainError.SessionAlreadyOpen]. */
    suspend fun open(opening: BigDecimal): Result<Long> = try {
        Result.success(cashSessionDao.openAtomic(opening))
    } catch (e: DomainException) {
        Result.failure(e)
    }

    /** Fails with [com.idos.pos.core.domain.DomainError.NoOpenSession] (none open, or already closed). */
    suspend fun close(counted: BigDecimal): Result<CashSessionView> = try {
        val closed = cashSessionDao.closeSessionAtomic(counted)
        Result.success(toView(closed, closed.expectedBalance ?: BigDecimal.ZERO))
    } catch (e: DomainException) {
        Result.failure(e)
    }

    /** Fails with [com.idos.pos.core.domain.DomainError.NoOpenSession] (none open, or the session is closed). */
    suspend fun addMovement(type: CashMovementType, amount: BigDecimal, reason: String?): Result<Unit> = try {
        cashSessionDao.addMovementAtomic(type, amount, reason)
        Result.success(Unit)
    } catch (e: DomainException) {
        Result.failure(e)
    }

    private fun toView(entity: CashSessionEntity, expectedBalance: BigDecimal): CashSessionView = CashSessionView(
        id = entity.id,
        openedAt = entity.openedAt,
        closedAt = entity.closedAt,
        openingBalance = entity.openingBalance,
        closingBalance = entity.closingBalance,
        expectedBalance = expectedBalance,
        salesTotal = entity.salesTotal,
        difference = entity.difference,
        status = entity.status,
    )
}
