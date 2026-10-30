package com.ivy.stats.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ivy.navigation.screenScopedViewModel
import com.ivy.stats.domain.PeriodPreset
import com.ivy.ui.R
import com.ivy.ui.component.BackButton
import com.ivy.wallet.domain.data.IvyCurrency
import com.ivy.wallet.ui.theme.modal.CurrencyModal
import kotlinx.collections.immutable.ImmutableList
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Composable
fun StatsScreen() {
    val viewModel: StatsViewModel = screenScopedViewModel()
    StatsUi(
        state = viewModel.uiState(),
        onEvent = viewModel::onEvent,
    )
}

@Suppress("DEPRECATION")
@Composable
private fun StatsUi(
    state: StatsState,
    onEvent: (StatsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        StatsScaffold(state = state, onEvent = onEvent)

        val filter = when (state.openFilter) {
            StatsFilterType.Accounts -> state.accounts
            StatsFilterType.Categories -> state.categories
            StatsFilterType.Tags -> state.tags
            null -> null
        }
        if (filter != null) {
            FilterDialog(filter = filter, onEvent = onEvent)
        }

        when (state.monthPicker) {
            MonthField.From -> MonthPickerDialog(
                selectedYear = state.from.year,
                selectedMonth = state.from.monthValue,
                onEvent = onEvent,
            )

            MonthField.To -> MonthPickerDialog(
                selectedYear = state.to.year,
                selectedMonth = state.to.monthValue,
                onEvent = onEvent,
            )

            null -> {}
        }

        val currencyModalId = remember(state.currency) { UUID.randomUUID() }
        CurrencyModal(
            title = stringResource(R.string.stats_change_currency),
            initialCurrency = IvyCurrency.fromCode(state.currency),
            visible = state.currencyPickerVisible,
            dismiss = { onEvent(StatsEvent.OnCurrencyPickerDismiss) },
            id = currencyModalId,
            onSetCurrency = { onEvent(StatsEvent.OnCurrencySelect(it)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsScaffold(
    state: StatsState,
    onEvent: (StatsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.systemBarsPadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton(onClick = { onEvent(StatsEvent.OnBackClick) })
                },
                title = { Text(text = stringResource(R.string.stats_title)) },
                actions = {
                    TextButton(onClick = { onEvent(StatsEvent.OnCurrencyClick) }) {
                        Text(text = state.currency, fontWeight = FontWeight.Bold)
                        Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                },
            )
        },
        content = { padding ->
            StatsContent(
                state = state,
                onEvent = onEvent,
                modifier = Modifier.padding(padding),
            )
        },
    )
}

@Composable
private fun StatsContent(
    state: StatsState,
    onEvent: (StatsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "loading") {
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
        item(key = "period") {
            PeriodRow(state = state, onEvent = onEvent)
        }
        item(key = "filters") {
            FiltersRow(state = state, onEvent = onEvent)
        }
        item(key = "summary") {
            SummaryCard(state = state)
        }
        item(key = "chart") {
            ChartCard(
                bars = state.bars,
                selectedBar = state.selectedBar,
                onEvent = onEvent,
            )
        }
        if (state.missingRates.isNotEmpty()) {
            item(key = "missing_rates") {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    text = stringResource(
                        R.string.stats_missing_rates,
                        state.missingRates.joinToString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        items(items = state.bars.reversed(), key = { it.month.toString() }) { bar ->
            MonthRow(
                bar = bar,
                selected = bar == state.selectedBar,
                onEvent = onEvent,
            )
        }
    }
}

@Composable
private fun PeriodRow(
    state: StatsState,
    onEvent: (StatsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PeriodPreset.entries.forEach { preset ->
                FilterChip(
                    selected = preset == state.preset,
                    onClick = { onEvent(StatsEvent.OnPresetSelect(preset)) },
                    label = { Text(text = stringResource(preset.labelRes())) },
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = { onEvent(StatsEvent.OnMonthFieldClick(MonthField.From)) },
                label = { Text(text = stringResource(R.string.stats_from, state.from.label(locale))) },
            )
            AssistChip(
                onClick = { onEvent(StatsEvent.OnMonthFieldClick(MonthField.To)) },
                label = { Text(text = stringResource(R.string.stats_to, state.to.label(locale))) },
            )
        }
    }
}

@Composable
private fun FiltersRow(
    state: StatsState,
    onEvent: (StatsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(state.accounts, state.categories, state.tags).forEach { filter ->
            val name = stringResource(filter.type.labelRes())
            FilterChip(
                selected = !filter.allSelected,
                onClick = { onEvent(StatsEvent.OnFilterClick(filter.type)) },
                label = {
                    Text(
                        text = when {
                            filter.allSelected -> stringResource(R.string.stats_filter_all, name)
                            filter.selectedCount == 0 -> stringResource(R.string.stats_filter_none, name)
                            else -> stringResource(R.string.stats_filter_count, name, filter.selectedCount)
                        }
                    )
                },
                trailingIcon = {
                    Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
                },
            )
        }
    }
}

@Composable
private fun SummaryCard(
    state: StatsState,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.stats_total),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = state.total,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SummaryValue(
                    label = stringResource(R.string.stats_average),
                    value = state.average,
                    modifier = Modifier.weight(1f),
                )
                SummaryValue(
                    label = stringResource(R.string.stats_highest),
                    value = state.highest?.let { "${it.month.label(locale)}\n${it.amount}" } ?: "-",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.stats_currency_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SummaryValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ChartCard(
    bars: ImmutableList<MonthBar>,
    selectedBar: MonthBar?,
    onEvent: (StatsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp),
                text = selectedBar?.let { "${it.month.label(locale)}: ${it.amount}" }
                    ?: stringResource(R.string.stats_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (bars.all { it.fraction == 0f }) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = stringResource(R.string.stats_no_expenses),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                BarChart(
                    bars = bars,
                    selectedBar = selectedBar,
                    onEvent = onEvent,
                )
            }
        }
    }
}

@Composable
private fun BarChart(
    bars: ImmutableList<MonthBar>,
    selectedBar: MonthBar?,
    onEvent: (StatsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollable = bars.size > MaxFittingBars
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEach { bar ->
            Bar(
                bar = bar,
                selected = bar == selectedBar,
                showYear = bar.month.monthValue == 1 || bar == bars.first(),
                onEvent = onEvent,
                modifier = if (scrollable) Modifier.width(BarWidth.dp) else Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Bar(
    bar: MonthBar,
    selected: Boolean,
    showYear: Boolean,
    onEvent: (StatsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Column(
        modifier = modifier.clickable { onEvent(StatsEvent.OnBarClick(bar.month)) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.height(ChartHeight.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = bar.shortAmount,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                color = if (selected) color else MaterialTheme.colorScheme.outline,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Spacer(
                modifier = Modifier
                    .fillMaxWidth(BarWidthFraction)
                    .height((MinBarHeight + bar.fraction * BarMaxHeight).dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(color.copy(alpha = if (bar.fraction == 0f) EmptyBarAlpha else 1f)),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = bar.month.format(DateTimeFormatter.ofPattern("MMM", locale)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
        Text(
            text = if (showYear) bar.month.year.toString() else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
    }
}

@Composable
private fun MonthRow(
    bar: MonthBar,
    selected: Boolean,
    onEvent: (StatsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEvent(StatsEvent.OnBarClick(bar.month)) }
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = bar.month.label(locale),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = bar.amount,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun FilterDialog(
    filter: FilterUi,
    onEvent: (StatsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val noneLabel = stringResource(
        if (filter.type == StatsFilterType.Tags) R.string.stats_no_tags else R.string.stats_no_category
    )
    AlertDialog(
        modifier = modifier,
        onDismissRequest = { onEvent(StatsEvent.OnFilterDismiss) },
        confirmButton = {
            TextButton(onClick = { onEvent(StatsEvent.OnFilterDismiss) }) {
                Text(text = stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onEvent(StatsEvent.OnFilterSelectAll(filter.type, !filter.allSelected)) }
            ) {
                Text(
                    text = stringResource(
                        if (filter.allSelected) R.string.stats_clear else R.string.select_all
                    )
                )
            }
        },
        title = { Text(text = stringResource(filter.type.labelRes())) },
        text = {
            LazyColumn {
                items(items = filter.options, key = { it.key }) { option ->
                    FilterOptionRow(
                        option = option,
                        noneLabel = noneLabel,
                        onToggle = { onEvent(StatsEvent.OnFilterOptionToggle(filter.type, option.key)) },
                    )
                }
            }
        },
    )
}

@Composable
private fun FilterOptionRow(
    option: FilterOption,
    noneLabel: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = option.selected, onCheckedChange = { onToggle() })
        Spacer(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(option.color?.let { Color(it) } ?: Color.Transparent),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = option.name ?: noneLabel,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MonthPickerDialog(
    selectedYear: Int,
    selectedMonth: Int,
    onEvent: (StatsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    MonthPicker(
        modifier = modifier,
        selectedYear = selectedYear,
        selectedMonth = selectedMonth,
        onMonthSelect = { onEvent(StatsEvent.OnMonthSelect(it)) },
        onDismiss = { onEvent(StatsEvent.OnMonthPickerDismiss) },
    )
}

private fun YearMonth.label(locale: Locale): String =
    format(DateTimeFormatter.ofPattern("MMM yyyy", locale))

@StringRes
private fun PeriodPreset.labelRes(): Int = when (this) {
    PeriodPreset.Last3Months -> R.string.stats_last_3_months
    PeriodPreset.Last6Months -> R.string.stats_last_6_months
    PeriodPreset.Last12Months -> R.string.stats_last_12_months
    PeriodPreset.ThisYear -> R.string.stats_this_year
    PeriodPreset.LastYear -> R.string.stats_last_year
    PeriodPreset.Custom -> R.string.stats_custom
}

@StringRes
private fun StatsFilterType.labelRes(): Int = when (this) {
    StatsFilterType.Accounts -> R.string.accounts
    StatsFilterType.Categories -> R.string.categories
    StatsFilterType.Tags -> R.string.tags
}

private const val MaxFittingBars = 12
private const val BarWidth = 44
private const val BarWidthFraction = 0.6f
private const val ChartHeight = 200
private const val MinBarHeight = 2f
private const val BarMaxHeight = 160f
private const val EmptyBarAlpha = 0.3f
