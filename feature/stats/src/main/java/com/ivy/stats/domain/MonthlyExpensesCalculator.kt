package com.ivy.stats.domain

import com.ivy.data.model.Expense
import com.ivy.data.model.Transaction
import com.ivy.data.model.primitive.AssetCode
import com.ivy.domain.usecase.exchange.CurrencyConverter
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class MonthlyExpenses(
    val months: List<MonthTotal>,
    /**
     * Currencies of expenses that couldn't be converted because there's no exchange rate.
     * These expenses aren't counted.
     */
    val missingRates: Set<AssetCode>,
)

data class MonthTotal(
    val month: YearMonth,
    val amount: Double,
)

class MonthlyExpensesCalculator @Inject constructor() {
    @Suppress("LongParameterList")
    fun calculate(
        transactions: List<Transaction>,
        filter: StatsFilter,
        period: StatsPeriod,
        currency: AssetCode,
        converter: CurrencyConverter,
        zone: ZoneId,
    ): MonthlyExpenses {
        val totals = period.months().associateWith { 0.0 }.toMutableMap()
        val missingRates = mutableSetOf<AssetCode>()
        transactions
            .filterIsInstance<Expense>()
            .filter { it.settled && filter.matches(it) }
            .forEach { expense ->
                val month = YearMonth.from(expense.time.atZone(zone))
                val total = totals[month] ?: return@forEach
                val amount = converter.convert(
                    amount = expense.value.amount.value,
                    from = expense.value.asset,
                    to = currency,
                )
                if (amount == null) {
                    missingRates += expense.value.asset
                } else {
                    totals[month] = total + amount
                }
            }
        return MonthlyExpenses(
            months = totals.map { (month, amount) -> MonthTotal(month, amount) },
            missingRates = missingRates,
        )
    }

    private fun StatsFilter.matches(expense: Expense): Boolean =
        expense.account in accounts &&
            expense.category in categories &&
            (expense.tags.any { it in tags } || (expense.tags.isEmpty() && null in tags))
}
