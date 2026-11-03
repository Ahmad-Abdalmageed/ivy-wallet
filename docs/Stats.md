# Monthly expenses

**Home → More → Statistics** (`feature/stats`, `StatsScreen`) shows a bar chart of the total expenses of each month in a period.

- **Period:** 3, 6 (default) or 12 months, this year, last year, or custom **From** / **To** months.
- **Filters:** accounts, categories (and "No category") and tags (and "No tags"). An expense counts when its account, its category and one of its tags are checked; all are checked at first.
- **Currency:** the currency button in the top bar changes the app's base currency, the same as in Settings (saves it and syncs the exchange rates), and the chart shows the totals in it.
- Tap a bar or a month to open **Reports** filtered to that month's expenses with the same accounts, categories and tags, to see the transactions. Reports can't combine "No tags" with tags, so when "No tags" is checked the unchecked tags are excluded instead (an expense with a checked and an unchecked tag shows in the chart but not in Reports).
- The summary shows the total, the monthly average and the highest month.
- Only settled expenses count; transfers and income don't. Expenses in currencies without an exchange rate are left out and listed under the chart.

## Code

- `MonthlyExpensesCalculator`: filters the expenses and sums them per month, converting them with `CurrencyConverter` (`shared/domain`, uses the stored exchange rates of any base currency that knows both currencies).
- `StatsPeriod` / `PeriodPreset`: the months and the time range loaded with `TransactionRepository.findAllBetween`.
- `BaseCurrencyChanger`: saves the base currency and syncs the exchange rates like `SettingsViewModel`.
- `ReportScreen(initialFilter = ReportFilterArgs(...))` (`shared/ui/navigation`): Reports applies the filter once per navigation (`ReportViewModel.applyInitialFilter`).
- `StatsViewModel` / `StatsScreen`: Material 3 screen with a Compose bar chart (no chart library), filter dialogs, a month picker and the app's currency picker.
