package com.ivy.commands.command

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The kind of value an [Argument] takes.
 * @param schema the JSON schema type
 */
enum class ArgumentType(val schema: String) {
    Text("string"),
    Number("number"),
    Integer("integer"),
    Bool("boolean"),
    TextList("array"),
}

class Argument(
    val name: String,
    val description: String,
    val type: ArgumentType = ArgumentType.Text,
    val required: Boolean = false,
    val options: List<String> = emptyList(),
)

private fun text(name: String, description: String, required: Boolean = false): Argument =
    Argument(name, description, ArgumentType.Text, required)

private fun number(name: String, description: String, required: Boolean = false): Argument =
    Argument(name, description, ArgumentType.Number, required)

private fun integer(name: String, description: String): Argument = Argument(name, description, ArgumentType.Integer)

private fun bool(name: String, description: String): Argument = Argument(name, description, ArgumentType.Bool)

private fun list(name: String, description: String): Argument = Argument(name, description, ArgumentType.TextList)

private fun choice(name: String, description: String, options: List<String>, required: Boolean = false): Argument =
    Argument(name, description, ArgumentType.Text, required, options)

private const val NAME_OR_ID = "name or id"
private const val DATE = "YYYY-MM-DD, YYYY-MM-DDTHH:MM, today or yesterday"
private const val COLOR = "optional color, #RRGGBB"
private const val ICON = "optional Ivy icon name, e.g. food, car, home"
private val TRANSACTION_TYPES = listOf("expense", "income", "transfer")
private val PLANNED_TYPES = listOf("expense", "income")
private val REPEATS = listOf("once", "day", "week", "month", "year")

/**
 * The commands other apps and the in-app assistant can run, with their arguments.
 * Shown by the `help` command, so AI assistants can discover them.
 * @param changesData false for commands that only read
 */
@Suppress("LargeClass")
enum class Command(
    val method: String,
    val description: String,
    val arguments: List<Argument> = emptyList(),
    val changesData: Boolean = true,
) {
    Help(
        method = "help",
        description = "Lists the commands with their arguments. Doesn't need the access key.",
        changesData = false,
    ),

    // Accounts
    ListAccounts(
        method = "list_accounts",
        description = "Lists the accounts with their currency, balance and whether they count in the total balance.",
        changesData = false,
    ),
    AddAccount(
        method = "add_account",
        description = "Adds an account.",
        arguments = listOf(
            text("name", "required, unique account name", required = true),
            text("currency", "optional currency code; default the base currency"),
            number("balance", "optional starting balance; recorded as an 'Adjust balance' transaction"),
            bool("include_in_balance", "optional; false hides it from the total balance; default true"),
            text("color", COLOR),
            text("icon", ICON),
        ),
    ),
    UpdateAccount(
        method = "update_account",
        description = "Changes an account. Only the given fields change.",
        arguments = listOf(
            text("account", "required account $NAME_OR_ID", required = true),
            text("name", "optional new name"),
            text("currency", "optional new currency code; existing amounts aren't converted"),
            number(
                "balance",
                "optional new current balance; the difference is recorded as an 'Adjust balance' transaction",
            ),
            bool("include_in_balance", "optional"),
            text("color", COLOR),
            text("icon", ICON),
        ),
    ),
    DeleteAccount(
        method = "delete_account",
        description = "Deletes an account with all its transactions and planned payments. Can't be undone.",
        arguments = listOf(text("account", "required account $NAME_OR_ID", required = true)),
    ),

    // Categories
    ListCategories(
        method = "list_categories",
        description = "Lists the categories.",
        changesData = false,
    ),
    AddCategory(
        method = "add_category",
        description = "Adds a category.",
        arguments = listOf(
            text("name", "required, unique category name", required = true),
            text("color", COLOR),
            text("icon", ICON),
        ),
    ),
    UpdateCategory(
        method = "update_category",
        description = "Changes a category. Only the given fields change.",
        arguments = listOf(
            text("category", "required category $NAME_OR_ID", required = true),
            text("name", "optional new name"),
            text("color", COLOR),
            text("icon", ICON),
        ),
    ),
    DeleteCategory(
        method = "delete_category",
        description = "Deletes a category. Its transactions stay, without a category.",
        arguments = listOf(text("category", "required category $NAME_OR_ID", required = true)),
    ),

    // Tags
    ListTags(
        method = "list_tags",
        description = "Lists the tags.",
        changesData = false,
    ),
    AddTag(
        method = "add_tag",
        description = "Adds a tag.",
        arguments = listOf(
            text("name", "required, unique tag name", required = true),
            text("description", "optional"),
            text("color", COLOR),
        ),
    ),
    UpdateTag(
        method = "update_tag",
        description = "Changes a tag. Only the given fields change.",
        arguments = listOf(
            text("tag", "required tag $NAME_OR_ID", required = true),
            text("name", "optional new name"),
            text("description", "optional; 'none' clears it"),
            text("color", COLOR),
        ),
    ),
    DeleteTag(
        method = "delete_tag",
        description = "Deletes a tag and removes it from its transactions.",
        arguments = listOf(text("tag", "required tag $NAME_OR_ID", required = true)),
    ),

    // Transactions
    AddTransaction(
        method = "add_transaction",
        description = "Adds an expense, an income or a transfer between accounts.",
        arguments = listOf(
            choice("type", "expense (default), income or transfer", TRANSACTION_TYPES),
            number("amount", "required, positive number", required = true),
            text("account", "account $NAME_OR_ID; optional when there's only one account"),
            text("currency", "optional; when it isn't the account's currency the amount is converted"),
            text("category", "optional category $NAME_OR_ID"),
            text("title", "optional"),
            text("description", "optional"),
            text("date", "optional: $DATE; default now"),
            list("tags", "optional tag names or ids, a list or comma separated"),
            text("to_account", "transfers: the receiving account $NAME_OR_ID"),
            number("to_amount", "transfers between currencies: the received amount; converted when missing"),
        ),
    ),
    ListTransactions(
        method = "list_transactions",
        description = "Lists transactions, newest first, with the paid expense and income totals " +
            "in the base currency.",
        arguments = listOf(
            text("from", "optional first date, YYYY-MM-DD"),
            text("to", "optional last date, YYYY-MM-DD"),
            choice("type", "optional: expense, income or transfer", TRANSACTION_TYPES),
            text("account", "optional account $NAME_OR_ID"),
            text("category", "optional category $NAME_OR_ID; 'none' for uncategorized"),
            text("tag", "optional tag $NAME_OR_ID"),
            choice(
                "status",
                "optional: paid, or planned for the upcoming transactions of planned payments; default both",
                listOf("paid", "planned"),
            ),
            text("search", "optional text to find in the title or description"),
            integer("limit", "optional, default 20, at most 200"),
        ),
        changesData = false,
    ),
    UpdateTransaction(
        method = "update_transaction",
        description = "Changes a transaction. Only the given fields change.",
        arguments = listOf(
            text("id", "required transaction id", required = true),
            choice("type", "optional new type: expense, income or transfer", TRANSACTION_TYPES),
            number("amount", "optional new amount"),
            text("account", "optional account $NAME_OR_ID"),
            text("currency", "optional currency of the new amount; converted to the account's currency"),
            text("category", "optional category $NAME_OR_ID; 'none' removes it"),
            text("title", "optional; 'none' clears it"),
            text("description", "optional; 'none' clears it"),
            text("date", "optional: $DATE"),
            list("tags", "optional, replaces the tags; an empty list removes them"),
            text("to_account", "transfers: the receiving account $NAME_OR_ID"),
            number("to_amount", "transfers: the received amount"),
        ),
    ),
    DeleteTransaction(
        method = "delete_transaction",
        description = "Deletes a transaction.",
        arguments = listOf(text("id", "required transaction id", required = true)),
    ),
    PayTransaction(
        method = "pay_transaction",
        description = "Marks a planned (upcoming) transaction as paid now, or skips it.",
        arguments = listOf(
            text("id", "required id of a planned transaction (list_transactions with status planned)", true),
            bool("skip", "optional; true skips this payment instead of paying it"),
        ),
    ),
    CategoryTotals(
        method = "category_totals",
        description = "Totals per category of the paid expenses (or income) in a period, in the base " +
            "currency, biggest first. Uncategorized is listed as 'none'.",
        arguments = listOf(
            text("from", "optional first date, YYYY-MM-DD; default the start of the current budget month"),
            text("to", "optional last date, YYYY-MM-DD; default today"),
            choice("type", "optional: expense (default) or income", PLANNED_TYPES),
            text("account", "optional account $NAME_OR_ID"),
        ),
        changesData = false,
    ),

    // Budgets
    ListBudgets(
        method = "list_budgets",
        description = "Lists the monthly budgets with what's spent and left in the current budget month. " +
            "A budget counts the paid expenses in its categories and accounts; none means all.",
        changesData = false,
    ),
    AddBudget(
        method = "add_budget",
        description = "Adds a monthly budget.",
        arguments = listOf(
            text("name", "required", required = true),
            number("amount", "required monthly limit in the base currency", required = true),
            list("categories", "optional category names or ids; none means all categories"),
            list("accounts", "optional account names or ids; none means all accounts"),
        ),
    ),
    UpdateBudget(
        method = "update_budget",
        description = "Changes a budget. Only the given fields change.",
        arguments = listOf(
            text("budget", "required budget $NAME_OR_ID", required = true),
            text("name", "optional new name"),
            number("amount", "optional new monthly limit in the base currency"),
            list("categories", "optional, replaces the categories; an empty list means all"),
            list("accounts", "optional, replaces the accounts; an empty list means all"),
        ),
    ),
    DeleteBudget(
        method = "delete_budget",
        description = "Deletes a budget. Transactions aren't changed.",
        arguments = listOf(text("budget", "required budget $NAME_OR_ID", required = true)),
    ),

    // Planned payments
    ListPlannedPayments(
        method = "list_planned_payments",
        description = "Lists the planned payments (one-time and recurring) with their next due date.",
        changesData = false,
    ),
    AddPlannedPayment(
        method = "add_planned_payment",
        description = "Adds a planned payment. Its upcoming transactions are created as planned and " +
            "paid with pay_transaction.",
        arguments = listOf(
            choice("type", "expense (default) or income", PLANNED_TYPES),
            number("amount", "required, in the account's currency", required = true),
            text("account", "account $NAME_OR_ID; optional when there's only one account"),
            text("category", "optional category $NAME_OR_ID"),
            text("title", "optional, e.g. 'Rent'"),
            text("description", "optional"),
            text("start_date", "required first due date: $DATE", required = true),
            choice("repeat", "once (default), day, week, month or year", REPEATS),
            integer("every", "optional: repeat every N days/weeks/months/years; default 1"),
        ),
    ),
    UpdatePlannedPayment(
        method = "update_planned_payment",
        description = "Changes a planned payment and recreates its upcoming transactions. " +
            "Only the given fields change.",
        arguments = listOf(
            text("planned_payment", "required planned payment id or title", required = true),
            choice("type", "optional: expense or income", PLANNED_TYPES),
            number("amount", "optional"),
            text("account", "optional account $NAME_OR_ID"),
            text("category", "optional category $NAME_OR_ID; 'none' removes it"),
            text("title", "optional; 'none' clears it"),
            text("description", "optional; 'none' clears it"),
            text("start_date", "optional: $DATE"),
            choice("repeat", "optional: once, day, week, month or year", REPEATS),
            integer("every", "optional"),
        ),
    ),
    DeletePlannedPayment(
        method = "delete_planned_payment",
        description = "Deletes a planned payment and its upcoming transactions. Paid ones stay.",
        arguments = listOf(
            text("planned_payment", "required planned payment id or title", required = true),
        ),
    ),

    // Loans
    ListLoans(
        method = "list_loans",
        description = "Lists the loans with what's paid and left, and their records.",
        changesData = false,
    ),
    AddLoan(
        method = "add_loan",
        description = "Adds a loan: money borrowed from or lent to someone.",
        arguments = listOf(
            text("name", "required, e.g. the person's name", required = true),
            choice("type", "required: borrow or lend", listOf("borrow", "lend"), required = true),
            number("amount", "required", required = true),
            text(
                "account",
                "optional account $NAME_OR_ID; when given, the money moving in or out is " +
                    "recorded there and the loan is in its currency",
            ),
            text("note", "optional"),
            text("date", "optional: $DATE; default now"),
            text("color", COLOR),
        ),
    ),
    UpdateLoan(
        method = "update_loan",
        description = "Changes a loan. Only the given fields change.",
        arguments = listOf(
            text("loan", "required loan $NAME_OR_ID", required = true),
            text("name", "optional"),
            number("amount", "optional"),
            text("note", "optional; 'none' clears it"),
            text("color", COLOR),
        ),
    ),
    DeleteLoan(
        method = "delete_loan",
        description = "Deletes a loan with its records and their transactions.",
        arguments = listOf(text("loan", "required loan $NAME_OR_ID", required = true)),
    ),
    AddLoanRecord(
        method = "add_loan_record",
        description = "Records a payment on a loan (decrease) or more money on it (increase).",
        arguments = listOf(
            text("loan", "required loan $NAME_OR_ID", required = true),
            number("amount", "required", required = true),
            choice("type", "decrease (a payment, default) or increase", listOf("decrease", "increase")),
            text("account", "optional account $NAME_OR_ID; when given, the money is recorded there"),
            text("note", "optional"),
            text("date", "optional: $DATE; default now"),
            bool("interest", "optional; true when this is interest"),
        ),
    ),
    DeleteLoanRecord(
        method = "delete_loan_record",
        description = "Deletes a loan record and its transaction.",
        arguments = listOf(text("id", "required loan record id", required = true)),
    ),

    // Settings
    GetSettings(
        method = "get_settings",
        description = "Shows the base currency and the day the budget month starts.",
        changesData = false,
    ),
    UpdateSettings(
        method = "update_settings",
        description = "Changes the base currency or the day the budget month starts.",
        arguments = listOf(
            text("base_currency", "optional currency code; exchange rates are synced for it"),
            integer("start_day_of_month", "optional, 1 to 31"),
        ),
    ),
    ListExchangeRates(
        method = "list_exchange_rates",
        description = "Lists the exchange rates from the base currency.",
        changesData = false,
    ),
    SetExchangeRate(
        method = "set_exchange_rate",
        description = "Sets a manual exchange rate between the base currency and another one. " +
            "Give rate or price.",
        arguments = listOf(
            text("currency", "required currency code", required = true),
            number("rate", "how much of the currency 1 unit of the base currency buys"),
            number("price", "how much 1 unit of the currency costs in the base currency"),
        ),
    ),
    DeleteExchangeRate(
        method = "delete_exchange_rate",
        description = "Removes a manual exchange rate; the next sync brings back the market rate.",
        arguments = listOf(text("currency", "required currency code", required = true)),
    );

    /** The arguments as a JSON schema object, for MCP and Gemini function declarations. */
    val parameters: JsonObject
        get() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                arguments.forEach { argument ->
                    putJsonObject(argument.name) {
                        put("type", argument.type.schema)
                        if (argument.type == ArgumentType.TextList) {
                            putJsonObject("items") { put("type", "string") }
                        }
                        if (argument.options.isNotEmpty()) {
                            putJsonArray("enum") { argument.options.forEach { add(it) } }
                        }
                        put("description", argument.description)
                    }
                }
            }
            val required = arguments.filter(Argument::required)
            if (required.isNotEmpty()) {
                putJsonArray("required") { required.forEach { add(it.name) } }
            }
        }

    companion object {
        fun fromMethod(method: String): Command? = entries.find { it.method == method }
    }
}
