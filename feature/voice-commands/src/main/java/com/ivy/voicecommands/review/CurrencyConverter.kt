package com.ivy.voicecommands.review

/**
 * Converts amounts between currencies with the app's exchange rates.
 *
 * @param baseCurrency the currency the [rates] are relative to
 * @param rates how much of each currency 1 [baseCurrency] is worth
 */
class CurrencyConverter(
    private val baseCurrency: String,
    private val rates: Map<String, Double>,
) {
    /**
     * @return the converted amount, or null when a rate is missing
     */
    fun convert(amount: Double, from: String, to: String): Double? {
        if (from == to) return amount
        val fromRate = rate(from)
        val toRate = rate(to)
        return if (fromRate != null && toRate != null) amount / fromRate * toRate else null
    }

    private fun rate(currency: String): Double? =
        if (currency == baseCurrency) 1.0 else rates[currency]?.takeIf { it > 0 }
}
