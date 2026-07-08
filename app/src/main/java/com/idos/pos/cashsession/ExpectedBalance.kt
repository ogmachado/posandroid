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
 * **PHASE 7 → PHASE 8 SEAM — CLOSED (task 8.3)**: [cashSalesTotal] used to default to [BigDecimal.ZERO]
 * because the `orders` table / `SalesRepository` did not exist before Phase 8.
 * Now that they do, [CashSessionDao.cashSaleTotalsForSession] /
 * [CashSessionDao.cashSaleTotalsForSessionFlow] join `orders`/`payment_method`
 * filtered to `affectsCashBalance = true` and the given session, and both
 * [CashSessionRepository.currentSessionFlow] (live) and
 * [CashSessionDao.closeSessionAtomic] (snapshot) fold the result via
 * [sumMoney] — in Kotlin over `BigDecimal`, NEVER via SQL `SUM()`/`REAL`
 * arithmetic, which would silently widen the TEXT-stored `BigDecimal` money
 * columns to floating point (exactly what `core/db/Converters.kt`'s "Money
 * ... never `Double`" convention forbids). The default parameter value is
 * kept only so pre-Phase-8 callers/tests that omit the argument (e.g. some of
 * [com.idos.pos.cashsession.CashSessionDaoTest]'s arithmetic-in-isolation
 * cases) keep compiling unchanged.
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

/**
 * Σ of raw order totals (task 8.3) — the Kotlin-side fold that closes the
 * "PHASE 7 → PHASE 8 SEAM" documented on [computeExpectedBalance]. Used on
 * the lists returned by [CashSessionDao.cashSaleTotalsForSession] /
 * [CashSessionDao.cashSaleTotalsForSessionFlow], mirroring [netAmount]'s
 * "never SQL `SUM()`" reasoning for the exact same TEXT-stored-`BigDecimal`
 * money-column concern.
 */
fun List<BigDecimal>.sumMoney(): BigDecimal = fold(BigDecimal.ZERO) { acc, amount -> acc + amount }
