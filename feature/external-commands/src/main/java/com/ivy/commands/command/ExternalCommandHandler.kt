package com.ivy.commands.command

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.ivy.commands.ExternalCommandsSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import javax.inject.Inject

/**
 * Checks that external commands are on and the access key is right, then runs the command.
 * The response is JSON: `{"ok":true,"result":...}` or `{"ok":false,"error":"..."}`.
 */
class ExternalCommandHandler @Inject constructor(
    private val settings: ExternalCommandsSettings,
    private val processor: CommandProcessor,
) {
    /**
     * @param arguments a JSON object with the command's arguments and the access `key`
     * @param extras more arguments, sent one by one, e.g. with `adb shell content call --extra`
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun handle(method: String, arguments: String?, extras: Map<String, String>): String {
        val response: Either<String, JsonElement> = try {
            either {
                val args = parseArguments(arguments, extras).bind()
                if (method != Command.Help.method) {
                    ensure(settings.isEnabled()) {
                        "External commands are off. Turn them on in Ivy Wallet: Settings → External commands."
                    }
                    val key = CommandArgs(args).string(KEY).orEmpty()
                    ensure(settings.isValidKey(key)) {
                        "Wrong or missing access key. Copy it from Ivy Wallet: Settings → External commands."
                    }
                }
                processor.execute(method, CommandArgs(args)).bind()
            }
        } catch (e: Exception) {
            Timber.e(e, "External command '$method' failed")
            Either.Left("Ivy Wallet failed to run '$method': ${e.message}")
        }
        return response.fold(
            ifLeft = { error ->
                buildJsonObject {
                    put("ok", false)
                    put("error", error)
                }
            },
            ifRight = { result ->
                buildJsonObject {
                    put("ok", true)
                    put("result", result)
                }
            },
        ).toString()
    }

    private fun parseArguments(
        arguments: String?,
        extras: Map<String, String>,
    ): Either<String, JsonObject> = either {
        val json = if (arguments.isNullOrBlank()) {
            JsonObject(emptyMap())
        } else {
            val element = try {
                Json.parseToJsonElement(arguments)
            } catch (_: SerializationException) {
                raise("The arguments must be a JSON object, e.g. {\"amount\": 25}.")
            }
            element as? JsonObject ?: raise("The arguments must be a JSON object, e.g. {\"amount\": 25}.")
        }
        JsonObject(json + extras.mapValues { JsonPrimitive(it.value) })
    }

    companion object {
        const val KEY = "key"
    }
}
