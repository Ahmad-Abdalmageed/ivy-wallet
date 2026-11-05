package com.ivy.stats.domain

/**
 * What the stats chart shows for each month.
 */
enum class StatsMetric {
    Expenses,
    Income,

    /** The share of the income left after expenses, e.g. 0.955 for 95.5%. */
    Cashflow,
}

/**
 * @return the month's expenses or income, or for [StatsMetric.Cashflow] the share of its income
 * left after expenses, null when it had no income
 */
fun StatsMetric.valueOf(total: MonthTotal): Double? = when (this) {
    StatsMetric.Expenses -> total.expenses
    StatsMetric.Income -> total.income
    StatsMetric.Cashflow -> cashflowShare(income = total.income, expenses = total.expenses)
}

/**
 * @return (income - expenses) / income, negative when more was spent than earned,
 * or null without income
 */
fun cashflowShare(income: Double, expenses: Double): Double? =
    if (income > 0.0) (income - expenses) / income else null
