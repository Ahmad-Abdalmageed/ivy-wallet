package com.ivy.stats.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.base.model.TransactionType
import com.ivy.base.threading.DispatchersProvider
import com.ivy.base.time.TimeProvider
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.Tag
import com.ivy.data.model.TagId
import com.ivy.data.model.Transaction
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.ExchangeRatesRepository
import com.ivy.data.repository.TagRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.domain.usecase.exchange.CurrencyConverter
import com.ivy.navigation.Navigation
import com.ivy.navigation.ReportFilterArgs
import com.ivy.navigation.ReportScreen
import com.ivy.stats.domain.BaseCurrencyChanger
import com.ivy.stats.domain.MonthlyTotals
import com.ivy.stats.domain.MonthlyTotalsCalculator
import com.ivy.stats.domain.PeriodPreset
import com.ivy.stats.domain.StatsFilter
import com.ivy.stats.domain.StatsMetric
import com.ivy.stats.domain.StatsPeriod
import com.ivy.stats.domain.cashflowShare
import com.ivy.stats.domain.end
import com.ivy.stats.domain.start
import com.ivy.stats.domain.valueOf
import com.ivy.ui.ComposeViewModel
import com.ivy.ui.FormatMoneyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.YearMonth
import javax.inject.Inject
import kotlin.math.abs

@Suppress("TooManyFunctions")
@Stable
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val tagRepository: TagRepository,
    private val currencyRepository: CurrencyRepository,
    private val exchangeRatesRepository: ExchangeRatesRepository,
    private val baseCurrencyChanger: BaseCurrencyChanger,
    private val calculator: MonthlyTotalsCalculator,
    private val formatMoney: FormatMoneyUseCase,
    private val timeProvider: TimeProvider,
    private val dispatchers: DispatchersProvider,
    private val navigation: Navigation,
) : ComposeViewModel<StatsState, StatsEvent>() {
    private val currentMonth = YearMonth.from(timeProvider.localDateNow())

    private var loading by mutableStateOf(true)
    private var currency by mutableStateOf<AssetCode?>(null)
    private var metric by mutableStateOf(StatsMetric.Expenses)
    private var preset by mutableStateOf(PeriodPreset.Default)
    private var period by mutableStateOf(
        StatsPeriod.lastMonths(currentMonth, PeriodPreset.DEFAULT_MONTHS)
    )

    private var accounts by mutableStateOf<List<Account>>(emptyList())
    private var categories by mutableStateOf<List<Category>>(emptyList())
    private var tags by mutableStateOf<List<Tag>>(emptyList())
    private var selectedAccounts by mutableStateOf<Set<String>>(emptySet())
    private var selectedCategories by mutableStateOf<Set<String>>(emptySet())
    private var selectedTags by mutableStateOf<Set<String>>(emptySet())

    private var bars by mutableStateOf<ImmutableList<MonthBar>>(persistentListOf())
    private var total by mutableStateOf("")
    private var average by mutableStateOf("")
    private var missingRates by mutableStateOf<ImmutableList<String>>(persistentListOf())

    private var openFilter by mutableStateOf<StatsFilterType?>(null)
    private var monthPicker by mutableStateOf<MonthField?>(null)
    private var currencyPickerVisible by mutableStateOf(false)

    private var transactions: List<Transaction> = emptyList()
    private var loadedPeriod: StatsPeriod? = null
    private var recalculateJob: Job? = null

    @Composable
    override fun uiState(): StatsState {
        LaunchedEffect(Unit) {
            start()
        }

        return StatsState(
            loading = loading,
            currency = currency?.code.orEmpty(),
            metric = metric,
            preset = preset,
            from = period.from,
            to = period.to,
            bars = bars,
            total = total,
            average = average,
            highest = bars.filter { it.fraction != 0f }.maxByOrNull { it.fraction },
            missingRates = missingRates,
            accounts = accountsFilter(),
            categories = categoriesFilter(),
            tags = tagsFilter(),
            openFilter = openFilter,
            monthPicker = monthPicker,
            currencyPickerVisible = currencyPickerVisible,
        )
    }

    private fun accountsFilter(): FilterUi = FilterUi(
        type = StatsFilterType.Accounts,
        options = accounts.map {
            FilterOption(
                key = it.id.key(),
                name = it.name.value,
                color = it.color.value,
                selected = it.id.key() in selectedAccounts,
            )
        }.toImmutableList(),
    )

    private fun categoriesFilter(): FilterUi = FilterUi(
        type = StatsFilterType.Categories,
        options = (
            categories.map {
                FilterOption(
                    key = it.id.key(),
                    name = it.name.value,
                    color = it.color.value,
                    selected = it.id.key() in selectedCategories,
                )
            } + noneOption(selectedCategories)
            ).toImmutableList(),
    )

    private fun tagsFilter(): FilterUi = FilterUi(
        type = StatsFilterType.Tags,
        options = (
            tags.map {
                FilterOption(
                    key = it.id.key(),
                    name = it.name.value,
                    color = it.color.value,
                    selected = it.id.key() in selectedTags,
                )
            } + noneOption(selectedTags)
            ).toImmutableList(),
    )

    private fun noneOption(selected: Set<String>): FilterOption = FilterOption(
        key = NONE_KEY,
        name = null,
        color = null,
        selected = NONE_KEY in selected,
    )

    override fun onEvent(event: StatsEvent) {
        when (event) {
            StatsEvent.OnBackClick -> navigation.back()
            is StatsEvent.OnMetricSelect -> selectMetric(event.metric)
            is StatsEvent.OnPresetSelect -> selectPreset(event.preset)
            is StatsEvent.OnMonthFieldClick -> monthPicker = event.field
            is StatsEvent.OnMonthSelect -> selectMonth(event.month)
            StatsEvent.OnMonthPickerDismiss -> monthPicker = null
            is StatsEvent.OnMonthClick -> openReport(event.month)

            is StatsEvent.OnFilterClick -> openFilter = event.type
            StatsEvent.OnFilterDismiss -> openFilter = null
            is StatsEvent.OnFilterOptionToggle -> toggleOption(event.type, event.key)
            is StatsEvent.OnFilterSelectAll -> selectAll(event.type, event.selected)
            StatsEvent.OnCurrencyClick -> currencyPickerVisible = true
            StatsEvent.OnCurrencyPickerDismiss -> currencyPickerVisible = false
            is StatsEvent.OnCurrencySelect -> changeCurrency(event.currency)
        }
    }

    private suspend fun start() {
        currency = currencyRepository.getBaseCurrency()
        accounts = accountRepository.findAll()
        categories = categoryRepository.findAll()
        tags = tagRepository.findAll()
        selectedAccounts = accounts.map { it.id.key() }.toSet()
        selectedCategories = categories.map { it.id.key() }.toSet() + NONE_KEY
        selectedTags = tags.map { it.id.key() }.toSet() + NONE_KEY
        recalculate()
    }

    private fun selectMetric(newMetric: StatsMetric) {
        if (newMetric == metric) return
        metric = newMetric
        recalculate()
    }

    private fun selectPreset(newPreset: PeriodPreset) {
        preset = newPreset
        val newPeriod = newPreset.period(currentMonth)
        if (newPeriod == null) {
            monthPicker = MonthField.From
        } else {
            changePeriod(newPeriod)
        }
    }

    private fun selectMonth(month: YearMonth) {
        val field = monthPicker ?: return
        monthPicker = null
        preset = PeriodPreset.Custom
        changePeriod(
            when (field) {
                MonthField.From -> StatsPeriod(from = month, to = maxOf(month, period.to))
                MonthField.To -> StatsPeriod(from = minOf(month, period.from), to = month)
            }
        )
    }

    /**
     * Opens the month's expenses in Reports with the same filters.
     * Reports can't combine "no tags" with tags, so when "no tags" is checked
     * the unchecked tags are excluded instead.
     */
    private fun openReport(month: YearMonth) {
        val zone = timeProvider.getZoneId()
        val monthPeriod = StatsPeriod(from = month, to = month)
        val tagIds = tags.map { it.id.value }
        val checkedTagIds = tagIds.filter { it.toString() in selectedTags }
        val allTagsChecked = checkedTagIds.size == tagIds.size && NONE_KEY in selectedTags
        navigation.navigateTo(
            ReportScreen(
                initialFilter = ReportFilterArgs(
                    transactionTypes = when (metric) {
                        StatsMetric.Expenses -> listOf(TransactionType.EXPENSE)
                        StatsMetric.Income -> listOf(TransactionType.INCOME)
                        StatsMetric.Cashflow -> listOf(TransactionType.INCOME, TransactionType.EXPENSE)
                    },
                    from = monthPeriod.start(zone),
                    to = monthPeriod.end(zone),
                    accountIds = accounts.map { it.id.value }.filter { it.toString() in selectedAccounts },
                    categoryIds = categories.map { it.id.value }.filter { it.toString() in selectedCategories },
                    includeUncategorized = NONE_KEY in selectedCategories,
                    includedTagIds = when {
                        allTagsChecked || NONE_KEY in selectedTags -> emptyList()
                        else -> checkedTagIds
                    },
                    excludedTagIds = when {
                        allTagsChecked || NONE_KEY !in selectedTags -> emptyList()
                        else -> tagIds - checkedTagIds.toSet()
                    },
                ),
            )
        )
    }

    private fun changePeriod(newPeriod: StatsPeriod) {
        period = newPeriod
        recalculate()
    }

    private fun toggleOption(type: StatsFilterType, key: String) {
        updateSelection(type) { selected ->
            if (key in selected) selected - key else selected + key
        }
    }

    private fun selectAll(type: StatsFilterType, selected: Boolean) {
        val options = when (type) {
            StatsFilterType.Accounts -> accountsFilter()
            StatsFilterType.Categories -> categoriesFilter()
            StatsFilterType.Tags -> tagsFilter()
        }.options
        updateSelection(type) {
            if (selected) options.map(FilterOption::key).toSet() else emptySet()
        }
    }

    private fun updateSelection(type: StatsFilterType, update: (Set<String>) -> Set<String>) {
        when (type) {
            StatsFilterType.Accounts -> selectedAccounts = update(selectedAccounts)
            StatsFilterType.Categories -> selectedCategories = update(selectedCategories)
            StatsFilterType.Tags -> selectedTags = update(selectedTags)
        }
        recalculate()
    }

    private fun changeCurrency(code: String) {
        currencyPickerVisible = false
        val newCurrency = AssetCode.from(code).getOrNull() ?: return
        if (newCurrency == currency) return
        viewModelScope.launch {
            loading = true
            baseCurrencyChanger.change(newCurrency)
            currency = newCurrency
            recalculate()
        }
    }

    private fun recalculate() {
        val currency = currency ?: return
        recalculateJob?.cancel()
        recalculateJob = viewModelScope.launch {
            loading = true
            val zone = timeProvider.getZoneId()
            val period = period
            if (loadedPeriod != period) {
                transactions = transactionRepository.findAllBetween(
                    startDate = period.start(zone),
                    endDate = period.end(zone),
                )
                loadedPeriod = period
            }
            val converter = CurrencyConverter(
                rates = exchangeRatesRepository.findAll().first(),
                preferredBase = currencyRepository.getBaseCurrency(),
            )
            val result = withContext(dispatchers.default) {
                calculator.calculate(
                    transactions = transactions,
                    filter = filter(),
                    period = period,
                    currency = currency,
                    converter = converter,
                    zone = zone,
                )
            }
            show(result, currency)
            loading = false
        }
    }

    private fun filter(): StatsFilter = StatsFilter(
        accounts = accounts.map(Account::id).filter { it.key() in selectedAccounts }.toSet(),
        categories = categories.map<Category, CategoryId?>(Category::id)
            .plus(null)
            .filter { it.key() in selectedCategories }
            .toSet(),
        tags = tags.map<Tag, TagId?>(Tag::id)
            .plus(null)
            .filter { it.key() in selectedTags }
            .toSet(),
    )

    private suspend fun show(result: MonthlyTotals, currency: AssetCode) {
        val metric = metric
        val values = result.months.map { metric.valueOf(it) }
        val max = values.filterNotNull().maxOfOrNull { abs(it) }?.takeIf { it > 0.0 }
        bars = result.months.zip(values) { month, value ->
            MonthBar(
                month = month.month,
                amount = format(value, metric, currency, shorten = false),
                shortAmount = format(value, metric, currency = null, shorten = true),
                fraction = if (max == null || value == null) 0f else (value / max).toFloat(),
            )
        }.toImmutableList()
        if (metric == StatsMetric.Cashflow) {
            val shares = values.filterNotNull()
            total = format(
                cashflowShare(
                    income = result.months.sumOf { it.income },
                    expenses = result.months.sumOf { it.expenses },
                ),
                metric,
                currency,
                shorten = false,
            )
            average = format(shares.takeIf { it.isNotEmpty() }?.average(), metric, currency, shorten = false)
        } else {
            val sum = values.sumOf { it ?: 0.0 }
            total = format(sum, metric, currency, shorten = false)
            average = format(sum / result.months.size.coerceAtLeast(1), metric, currency, shorten = false)
        }
        missingRates = result.missingRates.map(AssetCode::code).sorted().toImmutableList()
    }

    /**
     * @param currency appended to amounts, null for none
     */
    private suspend fun format(
        value: Double?,
        metric: StatsMetric,
        currency: AssetCode?,
        shorten: Boolean,
    ): String = when {
        value == null -> NO_VALUE
        metric == StatsMetric.Cashflow -> percentFormat.format(value)
        currency == null -> formatMoney.format(value, shortenAmount = shorten)
        else -> formatAmount(value, currency, shorten)
    }

    private suspend fun formatAmount(amount: Double, currency: AssetCode, shorten: Boolean): String =
        "${formatMoney.format(amount, shortenAmount = shorten)} ${currency.code}"

    private fun AccountId.key(): String = value.toString()

    private fun CategoryId?.key(): String = this?.value?.toString() ?: NONE_KEY

    private fun TagId?.key(): String = this?.value?.toString() ?: NONE_KEY

    private val percentFormat = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }

    companion object {
        const val NONE_KEY = "none"
        private const val NO_VALUE = "–"
    }
}
