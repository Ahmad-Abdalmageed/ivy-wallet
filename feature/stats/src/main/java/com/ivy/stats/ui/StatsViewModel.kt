package com.ivy.stats.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
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
import com.ivy.stats.domain.BaseCurrencyChanger
import com.ivy.stats.domain.MonthlyExpenses
import com.ivy.stats.domain.MonthlyExpensesCalculator
import com.ivy.stats.domain.PeriodPreset
import com.ivy.stats.domain.StatsFilter
import com.ivy.stats.domain.StatsPeriod
import com.ivy.stats.domain.end
import com.ivy.stats.domain.start
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
import java.time.YearMonth
import javax.inject.Inject

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
    private val calculator: MonthlyExpensesCalculator,
    private val formatMoney: FormatMoneyUseCase,
    private val timeProvider: TimeProvider,
    private val dispatchers: DispatchersProvider,
    private val navigation: Navigation,
) : ComposeViewModel<StatsState, StatsEvent>() {
    private val currentMonth = YearMonth.from(timeProvider.localDateNow())

    private var loading by mutableStateOf(true)
    private var currency by mutableStateOf<AssetCode?>(null)
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
    private var selectedMonth by mutableStateOf<YearMonth?>(null)

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
            preset = preset,
            from = period.from,
            to = period.to,
            bars = bars,
            selectedBar = bars.find { it.month == selectedMonth },
            total = total,
            average = average,
            highest = bars.filter { it.fraction > 0f }.maxByOrNull { it.fraction },
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
            is StatsEvent.OnPresetSelect -> selectPreset(event.preset)
            is StatsEvent.OnMonthFieldClick -> monthPicker = event.field
            is StatsEvent.OnMonthSelect -> selectMonth(event.month)
            StatsEvent.OnMonthPickerDismiss -> monthPicker = null
            is StatsEvent.OnBarClick -> selectMonthBar(event.month)

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

    private fun selectMonthBar(month: YearMonth) {
        selectedMonth = month.takeIf { it != selectedMonth }
    }

    private fun changePeriod(newPeriod: StatsPeriod) {
        period = newPeriod
        selectedMonth = null
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

    private suspend fun show(result: MonthlyExpenses, currency: AssetCode) {
        val max = result.months.maxOfOrNull { it.amount }?.takeIf { it > 0.0 }
        bars = result.months.map {
            MonthBar(
                month = it.month,
                amount = formatAmount(it.amount, currency, shorten = false),
                shortAmount = formatMoney.format(it.amount, shortenAmount = true),
                fraction = if (max == null) 0f else (it.amount / max).toFloat(),
            )
        }.toImmutableList()
        val sum = result.months.sumOf { it.amount }
        total = formatAmount(sum, currency, shorten = false)
        average = formatAmount(sum / result.months.size.coerceAtLeast(1), currency, shorten = false)
        missingRates = result.missingRates.map(AssetCode::code).sorted().toImmutableList()
    }

    private suspend fun formatAmount(amount: Double, currency: AssetCode, shorten: Boolean): String =
        "${formatMoney.format(amount, shortenAmount = shorten)} ${currency.code}"

    private fun AccountId.key(): String = value.toString()

    private fun CategoryId?.key(): String = this?.value?.toString() ?: NONE_KEY

    private fun TagId?.key(): String = this?.value?.toString() ?: NONE_KEY

    companion object {
        const val NONE_KEY = "none"
    }
}
