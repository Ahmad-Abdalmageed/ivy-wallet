package com.ivy.home

import java.text.NumberFormat
import java.util.Locale

/**
 * @return the cashflow (income - expenses) as a share of the income, e.g. 0.955 when 95.5% of
 * the income is left, negative when more was spent than earned, or null without income
 */
fun cashflowShareOfIncome(income: Double, expenses: Double): Double? =
    if (income > 0.0) (income - expenses) / income else null

/**
 * @return the [share] as a percentage with at most one decimal, e.g. "95.5%"
 */
fun formatPercent(share: Double, locale: Locale): String =
    NumberFormat.getPercentInstance(locale)
        .apply { maximumFractionDigits = 1 }
        .format(share)
