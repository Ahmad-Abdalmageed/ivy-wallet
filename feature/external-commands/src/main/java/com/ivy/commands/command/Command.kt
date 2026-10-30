package com.ivy.commands.command

/**
 * The commands other apps can run, with their arguments.
 * Shown by the `help` command, so AI assistants can discover them.
 */
enum class Command(
    val method: String,
    val description: String,
    val arguments: Map<String, String>,
) {
    Help(
        method = "help",
        description = "Lists the commands. Doesn't need the access key.",
        arguments = emptyMap(),
    ),
    ListAccounts(
        method = "list_accounts",
        description = "Lists the accounts with their currency and balance.",
        arguments = emptyMap(),
    ),
    ListCategories(
        method = "list_categories",
        description = "Lists the categories.",
        arguments = emptyMap(),
    ),
    ListTags(
        method = "list_tags",
        description = "Lists the tags.",
        arguments = emptyMap(),
    ),
    AddTransaction(
        method = "add_transaction",
        description = "Adds an expense, an income or a transfer between accounts.",
        arguments = mapOf(
            "type" to "expense (default), income or transfer",
            "amount" to "required, positive number",
            "account" to "account name or id; optional when there's only one account",
            "currency" to "optional; when it isn't the account's currency the amount is converted",
            "category" to "optional category name or id",
            "title" to "optional",
            "description" to "optional",
            "date" to "optional: YYYY-MM-DD, YYYY-MM-DDTHH:MM, today or yesterday; default now",
            "tags" to "optional tag names or ids, a list or comma separated",
            "to_account" to "transfers: the receiving account name or id",
            "to_amount" to "transfers between currencies: the received amount; converted when missing",
        ),
    ),
    ListTransactions(
        method = "list_transactions",
        description = "Lists transactions, newest first, with the expense and income totals " +
            "in the base currency.",
        arguments = mapOf(
            "from" to "optional first date, YYYY-MM-DD",
            "to" to "optional last date, YYYY-MM-DD",
            "type" to "optional: expense, income or transfer",
            "account" to "optional account name or id",
            "category" to "optional category name or id",
            "limit" to "optional, default 20, at most 200",
        ),
    ),
    DeleteTransaction(
        method = "delete_transaction",
        description = "Deletes a transaction.",
        arguments = mapOf("id" to "required transaction id"),
    );

    companion object {
        fun fromMethod(method: String): Command? = entries.find { it.method == method }
    }
}
