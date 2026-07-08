package com.idos.pos.cashsession

import java.math.BigDecimal

/**
 * Single source of truth for the `expectedBalance` formula (specs/cash-session/spec.md
 * "Expected Balance Formula on Close"; design.md "Decision: `expectedBalance`
 * computed LIVE from a Room `Flow` aggregate; snapshot only at close"):
 *
 * `expectedBalance = openingBalance + Σ DEPOSIT − Σ WITHDRAWAL + Σ cash-affecting sale totals`
 *
 * Used by BOTH [CashSessionRepository.currentSessionFlow] (live, while OPEN)
 * and [CashSessionDao.closeSessionAtomic] (snapshot, at close) so the two
 * never drift apart — the exact defect design.md calls out avoiding
 * ("live-vs-frozen defect where an OPEN session's persisted balance columns
 * were null and had to be recomputed ad hoc").
 *
 * **PHASE 7 → PHASE 8 SEAM**: [cashSalesTotal] defaults to [BigDecimal.ZERO]
 * because the `orders` table / `SalesRepository` do not exist until Phase 8
 * (PR7) — Phase 7 (this PR/PR6) has no order data to sum, and inventing one
 * would violate Phase 8's ownership of its own schema (same reasoning as
 * `inventory/InventoryMovementEntity.kt`'s `orderId`-with-no-FK-yet note and
 * `catalog/CatalogRepository.createProduct`'s former Phase 4→5 TODO). Every
 * current caller passes the default (i.e. omits the argument). Once Phase 8
 * lands, it must:
 *   1. Add a `cashSalesFlow(sessionId): Flow<BigDecimal>` (or equivalent
 *      one-shot suspend query) to [CashSessionDao] / a query joining
 *      `orders`/`payment_method` filtered to `affectsCashBalance = true` and
 *      the given session — computed the same way [CashMovementEntity]'s net
 *      is computed here (in Kotlin over `BigDecimal`, NEVER via SQL
 *      `SUM()`/`REAL` arithmetic — SQLite would silently widen the TEXT-stored
 *      `BigDecimal` money columns to floating point, which is exactly what
 *      `core/db/Converters.kt`'s "Money ... never `Double`" convention forbids).
 *   2. Pass that real total as [cashSalesTotal] from both
 *      [CashSessionRepository.currentSessionFlow]'s live combine step and
 *      [CashSessionDao.closeSessionAtomic]'s snapshot — no other change to
 *      this function's shape is required; the seam is exactly this parameter.
 */
fun computeExpectedBalance(
    openingBalance: BigDecimal,
    movementsNet: BigDecimal,
    cashSalesTotal: BigDecimal = BigDecimal.ZERO,
): BigDecimal = openingBalance + movementsNet + cashSalesTotal

/**
 * Σ DEPOSIT − Σ WITHDRAWAL over a list of movements, computed in Kotlin over
 * [BigDecimal] (never SQL `SUM()`) for the same "never `Double`" reason
 * documented on [computeExpectedBalance]. Used by both the live
 * `Flow`-observed list ([CashSessionDao.movementsForSessionFlow]) and the
 * one-shot snapshot list ([CashSessionDao.movementsForSession]) so the live
 * and close-time values are computed identically.
 */
fun List<CashMovementEntity>.netAmount(): BigDecimal = fold(BigDecimal.ZERO) { acc, movement ->
    when (movement.type) {
        CashMovementType.DEPOSIT -> acc + movement.amount
        CashMovementType.WITHDRAWAL -> acc - movement.amount
    }
}
