package com.idos.pos.currency

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.flow.Flow

/**
 * Display-only currency conversion (task 9.1; design.md "File Changes" ->
 * `currency/CurrencyRepository.kt`; specs/currency-display/spec.md).
 *
 * [activeCurrenciesFlow] exposes only currencies marked `active` (per
 * "Only Active Currencies Are Shown"), ordered by [CurrencyEntity.displayOrder] —
 * [CurrencyDao.findActiveFlow] already applies both the `active = 1` filter
 * and the ordering, so this repository is a thin pass-through, kept as its
 * own class (rather than exposing the DAO directly to the UI layer) to match
 * every other feature's Entity/DAO -> Repository -> ViewModel layering
 * (design.md "Layering per feature").
 *
 * [convert] is a pure function with NO Room/Flow dependency: it is called at
 * RENDER TIME from the POS/order-detail composable on every recomposition, so
 * a rate change is reflected immediately without any snapshot ever being
 * persisted on the order (per "Conversion Row Is Computed, Not Persisted" —
 * "the order record itself stores no USD amount and no rate").
 */
class CurrencyRepository(private val currencyDao: CurrencyDao) {

    val activeCurrenciesFlow: Flow<List<CurrencyEntity>> = currencyDao.findActiveFlow()

    /**
     * `total / exchangeRate`, rounded HALF_UP to scale 2 (display money
     * scale, matching every other money value in this codebase). No existing
     * rounding convention for division was found elsewhere in the codebase
     * (money elsewhere is only ever added/subtracted, never divided — see
     * [com.idos.pos.cashsession.sumMoney]/[com.idos.pos.cashsession.netAmount]),
     * so HALF_UP/scale 2 is introduced here per this task's own instruction.
     */
    fun convert(total: BigDecimal, currency: CurrencyEntity): BigDecimal =
        total.divide(currency.exchangeRate, 2, RoundingMode.HALF_UP)
}
