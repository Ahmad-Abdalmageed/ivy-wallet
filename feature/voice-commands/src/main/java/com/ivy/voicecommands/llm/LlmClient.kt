package com.ivy.voicecommands.llm

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.right
import com.ivy.voicecommands.LlmConfig
import com.ivy.voicecommands.VoiceApiProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

data class LlmPrompt(
    val system: String,
    val user: String,
    /** JSON schema of the expected response, used by providers that support it. */
    val schema: String,
)

/**
 * Minimal HTTP client for the supported AI providers.
 * Sends a system + user prompt and returns the text of the model's reply.
 */
class LlmClient @Inject constructor(
    private val httpClient: dagger.Lazy<HttpClient>,
) {
    suspend fun complete(
        config: LlmConfig,
        prompt: LlmPrompt,
    ): Either<String, String> = catch({
        when (config.provider) {
            VoiceApiProvider.Google -> gemini(config, prompt)
            VoiceApiProvider.Groq -> openAiCompatible(GROQ_BASE_URL, config, prompt)
            VoiceApiProvider.OpenAI -> openAiCompatible(OPENAI_BASE_URL, config, prompt)
            VoiceApiProvider.Anthropic -> anthropic(config, prompt)
        }
    }) { e ->
        "${config.provider.displayName}: ${e.message ?: "request failed"}".left()
    }

    private suspend fun gemini(
        config: LlmConfig,
        prompt: LlmPrompt,
    ): Either<String, String> {
        val model = config.model.value.removePrefix("models/")
        val response = post("$GEMINI_BASE_URL/models/$model:generateContent") {
            header("x-goog-api-key", config.apiKey.value)
            setBody(
                buildJsonObject {
                    putJsonObject("systemInstruction") {
                        putJsonArray("parts") { addJsonObject { put("text", prompt.system) } }
                    }
                    putJsonArray("contents") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("parts") { addJsonObject { put("text", prompt.user) } }
                        }
                    }
                    putJsonObject("generationConfig") {
                        put("responseMimeType", "application/json")
                    }
                }
            )
        }
        return response.toText(config) { json ->
            json["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray
                ?.textOf("text")
        }
    }

    /**
     * OpenAI Chat Completions API, also used by Groq.
     */
    private suspend fun openAiCompatible(
        baseUrl: String,
        config: LlmConfig,
        prompt: LlmPrompt,
    ): Either<String, String> {
        val response = post("$baseUrl/chat/completions") {
            bearerAuth(config.apiKey.value)
            setBody(
                buildJsonObject {
                    put("model", config.model.value)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "system")
                            put("content", prompt.system)
                        }
                        addJsonObject {
                            put("role", "user")
                            put("content", prompt.user)
                        }
                    }
                    putJsonObject("response_format") { put("type", "json_object") }
                }
            )
        }
        return response.toText(config) { json ->
            json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull
        }
    }

    /**
     * Anthropic Messages API with structured outputs (output_config.format).
     */
    private suspend fun anthropic(
        config: LlmConfig,
        prompt: LlmPrompt,
    ): Either<String, String> {
        val model = config.model.value
        val useFallbacks = model in anthropicModelsWithFallbacks
        val response = post("$ANTHROPIC_BASE_URL/messages") {
            header("x-api-key", config.apiKey.value)
            header("anthropic-version", "2023-06-01")
            if (useFallbacks) {
                // Retries a request declined by the safety classifiers on another model
                header("anthropic-beta", "server-side-fallback-2026-07-01")
            }
            setBody(
                buildJsonObject {
                    put("model", model)
                    put("max_tokens", ANTHROPIC_MAX_TOKENS)
                    put("system", prompt.system)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            put("content", prompt.user)
                        }
                    }
                    putJsonObject("output_config") {
                        if (anthropicModelsWithEffort.any { model.startsWith(it) }) {
                            // A one-sentence extraction doesn't need deep thinking - keeps it fast
                            put("effort", "low")
                        }
                        putJsonObject("format") {
                            put("type", "json_schema")
                            put("schema", Json.parseToJsonElement(prompt.schema))
                        }
                    }
                    if (useFallbacks) put("fallbacks", "default")
                }
            )
        }
        return response.toText(config) { json ->
            if (json["stop_reason"]?.jsonPrimitive?.contentOrNull == "refusal") {
                null
            } else {
                json["content"]?.jsonArray
                    ?.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    ?.let(::JsonArray)
                    ?.textOf("text")
            }
        }
    }

    private suspend fun post(
        url: String,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse = httpClient.get().post(url) {
        contentType(ContentType.Application.Json)
        block()
    }

    private suspend fun HttpResponse.toText(
        config: LlmConfig,
        extractText: (JsonObject) -> String?,
    ): Either<String, String> {
        val name = config.provider.displayName
        if (!status.isSuccess()) {
            return "$name: ${errorMessage(bodyAsText())} (HTTP ${status.value})".left()
        }
        val text = extractText(body<JsonObject>())
        return if (text.isNullOrBlank()) {
            "$name returned an empty response".left()
        } else {
            text.right()
        }
    }

    /**
     * All supported providers return errors as {"error": {"message": "..."}}.
     */
    private fun errorMessage(body: String): String = Either.catch {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
            ?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: body.take(MAX_ERROR_LENGTH)

    private fun JsonArray.textOf(key: String): String = mapNotNull { part: JsonElement ->
        part.jsonObject[key]?.jsonPrimitive?.contentOrNull
    }.joinToString("")

    companion object {
        private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val GROQ_BASE_URL = "https://api.groq.com/openai/v1"
        private const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        private const val ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"
        private const val ANTHROPIC_MAX_TOKENS = 4096
        private const val MAX_ERROR_LENGTH = 200

        private val anthropicModelsWithFallbacks = setOf(
            "claude-fable-5-1",
            "claude-opus-5-5",
            "claude-opus-5",
            "claude-sonnet-5-5",
        )
        private val anthropicModelsWithEffort = listOf(
            "claude-fable-5",
            "claude-opus-5",
            "claude-opus-4-6",
            "claude-opus-4-7",
            "claude-opus-4-8",
            "claude-sonnet-5",
            "claude-sonnet-4-6",
            "claude-haiku-5",
        )
    }
}
