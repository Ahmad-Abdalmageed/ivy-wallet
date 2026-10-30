package com.ivy.stats.ui

import com.ivy.stats.domain.PeriodPreset
import java.time.YearMonth

sealed interface StatsEvent {
    data object OnBackClick : StatsEvent
    data class OnPresetSelect(val preset: PeriodPreset) : StatsEvent
    data class OnMonthFieldClick(val field: MonthField) : StatsEvent
    data class OnMonthSelect(val month: YearMonth) : StatsEvent
    data object OnMonthPickerDismiss : StatsEvent
    data class OnBarClick(val month: YearMonth) : StatsEvent
    data class OnFilterClick(val type: StatsFilterType) : StatsEvent
    data object OnFilterDismiss : StatsEvent
    data class OnFilterOptionToggle(val type: StatsFilterType, val key: String) : StatsEvent
    data class OnFilterSelectAll(val type: StatsFilterType, val selected: Boolean) : StatsEvent
    data object OnCurrencyClick : StatsEvent
    data object OnCurrencyPickerDismiss : StatsEvent
    data class OnCurrencySelect(val currency: String) : StatsEvent
}
