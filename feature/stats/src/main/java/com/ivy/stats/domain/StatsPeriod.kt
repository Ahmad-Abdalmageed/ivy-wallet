package com.ivy.stats.domain

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * The months from [from] to [to], both included.
 */
data class StatsPeriod(
    val from: YearMonth,
    val to: YearMonth,
) {
    companion object {
        fun lastMonths(currentMonth: YearMonth, count: Long): StatsPeriod = StatsPeriod(
            from = currentMonth.minusMonths(count - 1),
            to = currentMonth,
        )
    }
}

fun StatsPeriod.months(): List<YearMonth> = generateSequence(from) { it.plusMonths(1) }
    .takeWhile { it <= to }
    .toList()

fun StatsPeriod.start(zone: ZoneId): Instant = from.atDay(1).atStartOfDay(zone).toInstant()

fun StatsPeriod.end(zone: ZoneId): Instant = to.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
    .minusMillis(1)

enum class PeriodPreset {
    Last3Months,
    Last6Months,
    Last12Months,
    ThisYear,
    LastYear,
    Custom;

    /**
     * @return the period ending in the current month, null for [Custom].
     */
    @Suppress("MagicNumber")
    fun period(currentMonth: YearMonth): StatsPeriod? = when (this) {
        Last3Months -> StatsPeriod.lastMonths(currentMonth, 3)
        Last6Months -> StatsPeriod.lastMonths(currentMonth, DEFAULT_MONTHS)
        Last12Months -> StatsPeriod.lastMonths(currentMonth, 12)
        ThisYear -> StatsPeriod(from = currentMonth.withMonth(1), to = currentMonth)
        LastYear -> currentMonth.minusYears(1).let {
            StatsPeriod(from = it.withMonth(1), to = it.withMonth(12))
        }

        Custom -> null
    }

    companion object {
        val Default = Last6Months
        const val DEFAULT_MONTHS = 6L
    }
}
