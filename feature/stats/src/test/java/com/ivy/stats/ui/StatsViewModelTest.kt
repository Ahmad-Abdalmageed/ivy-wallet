package com.ivy.stats.ui

import com.ivy.base.TestDispatchersProvider
import com.ivy.base.time.TimeProvider
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.ExchangeRatesRepository
import com.ivy.data.repository.TagRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.navigation.Navigation
import com.ivy.stats.domain.BaseCurrencyChanger
import com.ivy.stats.domain.MonthlyExpensesCalculator
import com.ivy.stats.domain.PeriodPreset
import com.ivy.stats.domain.Zone
import com.ivy.stats.domain.account
import com.ivy.stats.domain.category
import com.ivy.stats.domain.expense
import com.ivy.stats.domain.rate
import com.ivy.stats.domain.tag
import com.ivy.ui.FormatMoneyUseCase
import com.ivy.ui.testing.ComposeViewModelTest
import com.ivy.ui.testing.runTest
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class StatsViewModelTest : ComposeViewModelTest() {
    private val cash = account("Cash", "EUR")
    private val bank = account("Bank", "USD")
    private val food = category("Food")
    private val work = tag("Work")
    private val today = LocalDate.of(2026, 10, 30)

    private val transactionRepository = mockk<TransactionRepository>()
    private val accountRepository = mockk<AccountRepository>()
    private val categoryRepository = mockk<CategoryRepository>()
    private val tagRepository = mockk<TagRepository>()
    private val currencyRepository = mockk<CurrencyRepository>()
    private val exchangeRatesRepository = mockk<ExchangeRatesRepository>()
    private val baseCurrencyChanger = mockk<BaseCurrencyChanger>(relaxed = true)
    private val formatMoney = mockk<FormatMoneyUseCase>()
    private val timeProvider = mockk<TimeProvider>()

    private val transactions = listOf(
        expense(10.0, cash, LocalDate.of(2026, 10, 2), category = food),
        expense(20.0, bank, LocalDate.of(2026, 9, 5), tags = listOf(work)),
        expense(5.0, cash, LocalDate.of(2026, 6, 1)),
    )

    private lateinit var viewModel: StatsViewModel

    @Before
    fun setup() {
        every { timeProvider.localDateNow() } returns today
        every { timeProvider.getZoneId() } returns Zone
        coEvery { accountRepository.findAll() } returns listOf(cash, bank)
        coEvery { categoryRepository.findAll() } returns listOf(food)
        coEvery { tagRepository.findAll() } returns listOf(work)
        coEvery { currencyRepository.getBaseCurrency() } returns AssetCode.EUR
        every { exchangeRatesRepository.findAll() } returns flowOf(listOf(rate("EUR", "USD", 2.0)))
        coEvery { transactionRepository.findAllBetween(any(), any()) } answers {
            val start = firstArg<Instant>()
            val end = secondArg<Instant>()
            transactions.filter { it.time in start..end }
        }
        coEvery { formatMoney.format(any(), any()) } answers { firstArg<Double>().toInt().toString() }
        viewModel = StatsViewModel(
            transactionRepository = transactionRepository,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            tagRepository = tagRepository,
            currencyRepository = currencyRepository,
            exchangeRatesRepository = exchangeRatesRepository,
            baseCurrencyChanger = baseCurrencyChanger,
            calculator = MonthlyExpensesCalculator(),
            formatMoney = formatMoney,
            timeProvider = timeProvider,
            dispatchers = TestDispatchersProvider,
            navigation = Navigation(),
        )
    }

    @Test
    fun `shows the last 6 months of expenses in the base currency`() {
        viewModel.runTest {
            loading shouldBe false
            currency shouldBe "EUR"
            preset shouldBe PeriodPreset.Last6Months
            from shouldBe YearMonth.of(2026, 5)
            to shouldBe YearMonth.of(2026, 10)
            bars.map { it.amount } shouldBe listOf(
                "0 EUR", "5 EUR", "0 EUR", "0 EUR", "10 EUR", "10 EUR"
            )
            bars.last().fraction shouldBe 1f
            total shouldBe "25 EUR"
            average shouldBe "4 EUR"
            accounts.allSelected shouldBe true
            categories.options.size shouldBe 2
            tags.options.map { it.name } shouldBe listOf("Work", null)
        }
    }

    @Test
    fun `filters by account, category and tag`() {
        viewModel.runTest(
            events = listOf(StatsEvent.OnFilterOptionToggle(StatsFilterType.Accounts, bank.id.value.toString()))
        ) {
            accounts.selectedCount shouldBe 1
            total shouldBe "15 EUR"
        }
        viewModel.runTest(
            events = listOf(StatsEvent.OnFilterSelectAll(StatsFilterType.Categories, selected = false))
        ) {
            categories.selectedCount shouldBe 0
            total shouldBe "0 EUR"
        }
    }

    @Test
    fun `only tagged expenses`() {
        viewModel.runTest(
            events = listOf(StatsEvent.OnFilterOptionToggle(StatsFilterType.Tags, StatsViewModel.NONE_KEY))
        ) {
            total shouldBe "10 EUR"
        }
    }

    @Test
    fun `custom period`() {
        viewModel.runTest(
            events = listOf(
                StatsEvent.OnMonthFieldClick(MonthField.From),
                StatsEvent.OnMonthSelect(YearMonth.of(2026, 9)),
            )
        ) {
            preset shouldBe PeriodPreset.Custom
            monthPicker shouldBe null
            bars.map { it.month } shouldBe listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10))
            total shouldBe "20 EUR"
        }
    }

    @Test
    fun `changes the base currency`() {
        coEvery { currencyRepository.getBaseCurrency() } returns AssetCode.EUR andThen AssetCode.USD

        viewModel.runTest(
            events = listOf(
                StatsEvent.OnCurrencyClick,
                StatsEvent.OnCurrencySelect("USD"),
            )
        ) {
            currencyPickerVisible shouldBe false
            currency shouldBe "USD"
            total shouldBe "50 USD"
        }
        coVerify(exactly = 1) { baseCurrencyChanger.change(AssetCode.USD) }
    }

    @Test
    fun `selects a month`() {
        viewModel.runTest(
            events = listOf(StatsEvent.OnBarClick(YearMonth.of(2026, 9)))
        ) {
            selectedBar?.amount shouldBe "10 EUR"
        }
    }
}
