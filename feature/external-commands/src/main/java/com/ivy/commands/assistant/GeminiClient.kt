package com.ivy.commands.assistant

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Calls the Gemini API (`generateContent`) with function declarations.
 * The API key goes in a header, never in the URL.
 */
class GeminiClient @Inject constructor(
    httpClient: HttpClient,
) {
    private val client = httpClient.config {
        install(HttpTimeout) {
            requestTimeoutMillis = TIMEOUT_MILLIS
            socketTimeoutMillis = TIMEOUT_MILLIS
        }
    }

    /**
     * @return the model's reply content (role "model"), to append to [contents] as is:
     * it can carry thought signatures that Gemini needs back.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun generate(
        config: AssistantConfig,
        system: String,
        contents: List<JsonObject>,
        tools: JsonArray,
    ): Either<String, JsonObject> = either {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            put("contents", JsonArray(contents))
            put("tools", tools)
        }
        val response = try {
            client.post("$BASE_URL/models/${config.model}:generateContent") {
                header("x-goog-api-key", config.apiKey)
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            raise("Gemini isn't reachable: ${e.message ?: e::class.simpleName}")
        }
        val text = response.bodyAsText()
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        ensure(response.status.isSuccess()) {
            "Gemini: ${json?.errorMessage() ?: response.status.toString()}"
        }
        ensureNotNull(json) { "Gemini sent an unexpected response." }
        reply(json).bind()
    }

    private fun reply(json: JsonObject): Either<String, JsonObject> = either {
        val candidate = (json["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        ensureNotNull(candidate) {
            val reason = (json["promptFeedback"] as? JsonObject)?.get("blockReason")?.jsonPrimitive?.contentOrNull
            "Gemini didn't answer${reason?.let { " ($it)" }.orEmpty()}."
        }
        val content = ensureNotNull(
            (candidate["content"] as? JsonObject)?.takeIf { it["parts"] is JsonArray }
        ) {
            val reason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
            "Gemini didn't answer${reason?.let { " ($it)" }.orEmpty()}."
        }
        if (content["role"] == null) JsonObject(content + ("role" to JsonPrimitive("model"))) else content
    }

    private fun JsonObject.errorMessage(): String? =
        (this["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val TIMEOUT_MILLIS = 90_000L
    }
}
