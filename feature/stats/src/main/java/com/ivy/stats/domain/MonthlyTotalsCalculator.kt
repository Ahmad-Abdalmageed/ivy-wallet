package com.ivy.stats.domain

import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.Transaction
import com.ivy.data.model.primitive.AssetCode
import com.ivy.domain.usecase.exchange.CurrencyConverter
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class MonthlyTotals(
    val months: List<MonthTotal>,
    /**
     * Currencies of transactions that couldn't be converted because there's no exchange rate.
     * These transactions aren't counted.
     */
    val missingRates: Set<AssetCode>,
)

data class MonthTotal(
    val month: YearMonth,
    val expenses: Double,
    val income: Double,
)

/**
 * Sums the settled expenses and income of each month of a period in one currency.
 * Transfers between accounts aren't counted.
 */
class MonthlyTotalsCalculator @Inject constructor() {
    @Suppress("LongParameterList")
    fun calculate(
        transactions: List<Transaction>,
        filter: StatsFilter,
        period: StatsPeriod,
        currency: AssetCode,
        converter: CurrencyConverter,
        zone: ZoneId,
    ): MonthlyTotals {
        val months = period.months()
        val expenses = months.associateWith { 0.0 }.toMutableMap()
        val income = months.associateWith { 0.0 }.toMutableMap()
        val missingRates = mutableSetOf<AssetCode>()
        transactions
            .filter { it.settled && filter.matches(it) }
            .forEach { transaction ->
                val (totals, value) = when (transaction) {
                    is Expense -> expenses to transaction.value
                    is Income -> income to transaction.value
                    else -> return@forEach
                }
                val month = YearMonth.from(transaction.time.atZone(zone))
                val total = totals[month] ?: return@forEach
                val amount = value.convert(converter, currency)
                if (amount == null) {
                    missingRates += value.asset
                } else {
                    totals[month] = total + amount
                }
            }
        return MonthlyTotals(
            months = months.map {
                MonthTotal(month = it, expenses = expenses.getValue(it), income = income.getValue(it))
            },
            missingRates = missingRates,
        )
    }

    private fun PositiveValue.convert(converter: CurrencyConverter, currency: AssetCode): Double? =
        converter.convert(amount = amount.value, from = asset, to = currency)

    private fun StatsFilter.matches(transaction: Transaction): Boolean {
        val account = when (transaction) {
            is Expense -> transaction.account
            is Income -> transaction.account
            else -> return false
        }
        return account in accounts &&
            transaction.category in categories &&
            (transaction.tags.any { it in tags } || (transaction.tags.isEmpty() && null in tags))
    }
}
