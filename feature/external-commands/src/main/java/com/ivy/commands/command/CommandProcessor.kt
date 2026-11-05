package com.ivy.commands.command

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * Runs the [Command]s, each with the [CommandGroup] that handles it.
 */
class CommandProcessor @Inject constructor(
    transactions: TransactionCommands,
    accounts: AccountCommands,
    categories: CategoryCommands,
    budgets: BudgetCommands,
    plannedPayments: PlannedPaymentCommands,
    loans: LoanCommands,
    settings: SettingsCommands,
) {
    private val groups: Map<Command, CommandGroup> =
        listOf(transactions, accounts, categories, budgets, plannedPayments, loans, settings)
            .flatMap { group -> group.commands.map { it to group } }
            .toMap()

    /** The commands with a handler; every one but help. */
    internal val handled: Set<Command> get() = groups.keys

    suspend fun execute(method: String, args: CommandArgs): Either<String, JsonElement> = either {
        val command = ensureNotNull(Command.fromMethod(method)) {
            "Unknown command '$method'. Commands: ${Command.entries.joinToString { it.method }}."
        }
        if (command == Command.Help) return@either help()
        val group = ensureNotNull(groups[command]) { "'$method' isn't available." }
        with(group) { execute(command, args) }
    }

    private fun help(): JsonElement = buildJsonArray {
        Command.entries.forEach { command ->
            addJsonObject {
                put("command", command.method)
                put("description", command.description)
                put("changes_data", command.changesData)
                putJsonObject("arguments") {
                    command.arguments.forEach { put(it.name, it.description) }
                }
                put("parameters", command.parameters)
            }
        }
    }
}
