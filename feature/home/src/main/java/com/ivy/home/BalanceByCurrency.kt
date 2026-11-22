package com.ivy.home

import androidx.compose.runtime.Immutable
import java.math.BigDecimal

@Immutable
data class CurrencyBalance(
    val currency: String,
    val amount: BigDecimal,
)

/**
 * Sums the [accountBalances] (currency code to balance, in that currency) per currency,
 * without exchanging between currencies.
 *
 * @return the [selectedCurrency] first (always present, 0 when no account holds it),
 * then the other currencies with a non-zero balance in alphabetical order
 */
fun balancesByCurrency(
    accountBalances: List<Pair<String, BigDecimal>>,
    selectedCurrency: String,
): List<CurrencyBalance> {
    val totals = accountBalances
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapValues { (_, amounts) -> amounts.fold(BigDecimal.ZERO, BigDecimal::add) }

    val selected = CurrencyBalance(
        currency = selectedCurrency,
        amount = totals[selectedCurrency] ?: BigDecimal.ZERO,
    )
    val others = totals
        .filterKeys { it != selectedCurrency }
        .filterValues { it.signum() != 0 }
        .toSortedMap()
        .map { (currency, amount) -> CurrencyBalance(currency, amount) }

    return listOf(selected) + others
}
