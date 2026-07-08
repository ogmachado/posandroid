package com.idos.pos.currency

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.idos.pos.core.di.LocalAppContainer
import java.math.BigDecimal

/**
 * One conversion row per active currency for a given [amount] (tasks
 * 9.1-9.3; specs/currency-display/spec.md). Reused below BOTH the order
 * total and the live change-amount preview in
 * [com.idos.pos.sales.PosScreen] — matching the reference backend's
 * convention (idos-pos `CLAUDE.md` "Payment methods + alternative
 * currencies": "the POS renders one conversion row per active currency
 * below the total" / "and below the cambio").
 *
 * Recomputes on every recomposition triggered by either [amount] changing OR
 * [repository]'s [CurrencyRepository.activeCurrenciesFlow] emitting (e.g. a
 * rate edit elsewhere) — there is no memoization/caching, so
 * specs/currency-display/spec.md's "Exchange rate changes after an order is
 * completed" scenario ("the conversion row recomputes using the current
 * rate") holds at every render, never a snapshot.
 *
 * [repository] defaults to [LocalAppContainer.current].currencyRepository —
 * the same "read the container, allow override for tests" pattern
 * [com.idos.pos.core.di.posViewModel] already establishes for its own
 * `container` parameter.
 */
@Composable
fun CurrencyConversionRows(
    amount: BigDecimal,
    modifier: Modifier = Modifier,
    repository: CurrencyRepository = LocalAppContainer.current.currencyRepository,
) {
    val currencies by repository.activeCurrenciesFlow.collectAsState(initial = emptyList())

    Column(modifier = modifier) {
        currencies.forEach { currency ->
            Text(
                text = "${repository.convert(amount, currency).toPlainString()} ${currency.code}",
                modifier = Modifier.testTag(currencyConversionRowTestTag(currency.code)),
            )
        }
    }
}

fun currencyConversionRowTestTag(currencyCode: String) = "currency-conversion-row-$currencyCode"
