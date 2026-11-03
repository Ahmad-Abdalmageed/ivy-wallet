package com.ivy.domain.usecase.exchange

import com.ivy.data.model.ExchangeRate
import com.ivy.data.model.primitive.AssetCode

/**
 * Converts amounts using the stored exchange rates.
 * A rate means "1 [ExchangeRate.baseCurrency] = rate [ExchangeRate.currency]".
 * Rates are stored for the base currency they were synced for, so any of them
 * that knows both currencies can convert between them, the [preferredBase] first.
 */
class CurrencyConverter(
    rates: List<ExchangeRate>,
    private val preferredBase: AssetCode,
) {
    private val ratesByBase: Map<AssetCode, Map<AssetCode, Double>> = rates
        .groupBy(ExchangeRate::baseCurrency)
        .mapValues { (_, baseRates) ->
            baseRates.associate { it.currency to it.rate.value }
        }

    private val bases: List<AssetCode> =
        listOf(preferredBase) + ratesByBase.keys.filter { it != preferredBase }

    /**
     * @return the [amount] in [to] or null if no exchange rate is known.
     */
    fun convert(amount: Double, from: AssetCode, to: AssetCode): Double? {
        if (from == to) return amount
        return bases.firstNotNullOfOrNull { base ->
            val fromRate = rate(base, from)
            val toRate = rate(base, to)
            if (fromRate != null && toRate != null) amount / fromRate * toRate else null
        }
    }

    private fun rate(base: AssetCode, currency: AssetCode): Double? =
        if (base == currency) 1.0 else ratesByBase[base]?.get(currency)
}
