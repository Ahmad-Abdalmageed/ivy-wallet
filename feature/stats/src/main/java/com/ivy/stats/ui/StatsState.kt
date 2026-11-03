package com.ivy.stats.ui

import androidx.compose.runtime.Immutable
import com.ivy.stats.domain.PeriodPreset
import kotlinx.collections.immutable.ImmutableList
import java.time.YearMonth

@Immutable
data class StatsState(
    val loading: Boolean,
    val currency: String,
    val preset: PeriodPreset,
    val from: YearMonth,
    val to: YearMonth,
    val bars: ImmutableList<MonthBar>,
    val total: String,
    val average: String,
    val highest: MonthBar?,
    val missingRates: ImmutableList<String>,
    val accounts: FilterUi,
    val categories: FilterUi,
    val tags: FilterUi,
    val openFilter: StatsFilterType?,
    val monthPicker: MonthField?,
    val currencyPickerVisible: Boolean,
)

@Immutable
data class MonthBar(
    val month: YearMonth,
    val amount: String,
    val shortAmount: String,
    /** Bar height relative to the highest month, from 0 to 1. */
    val fraction: Float,
)

@Immutable
data class FilterUi(
    val type: StatsFilterType,
    val options: ImmutableList<FilterOption>,
)

val FilterUi.selectedCount: Int
    get() = options.count { it.selected }

val FilterUi.allSelected: Boolean
    get() = options.all { it.selected }

@Immutable
data class FilterOption(
    val key: String,
    /** null for "no category" / "no tags". */
    val name: String?,
    val color: Int?,
    val selected: Boolean,
)

enum class StatsFilterType {
    Accounts,
    Categories,
    Tags,
}

enum class MonthField {
    From,
    To,
}
