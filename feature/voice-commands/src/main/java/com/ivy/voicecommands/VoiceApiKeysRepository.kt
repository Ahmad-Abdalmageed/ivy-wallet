package com.ivy.voicecommands

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import arrow.core.Either
import arrow.core.raise.either
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.model.primitive.NotBlankTrimmedString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class LlmConfig(
    val provider: VoiceApiProvider,
    val apiKey: NotBlankTrimmedString,
    val model: NotBlankTrimmedString,
)

/**
 * Stores the voice commands configuration (API keys, models, the active provider and the language)
 * in encrypted shared preferences. Keys never leave the device and aren't part of backups.
 */
@Singleton
class VoiceApiKeysRepository @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val dispatchers: DispatchersProvider,
) {
    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            PREFS_FILE_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    suspend fun findKey(
        provider: VoiceApiProvider
    ): Either<String, NotBlankTrimmedString?> = read(provider.keyPref())

    suspend fun saveKey(
        provider: VoiceApiProvider,
        key: NotBlankTrimmedString,
    ): Either<String, Unit> = write(provider.keyPref(), key.value)

    suspend fun removeKey(
        provider: VoiceApiProvider
    ): Either<String, Unit> = write(provider.keyPref(), null)

    /**
     * @return the model the user has set for the [provider] or null if they use the default one.
     */
    suspend fun findModel(
        provider: VoiceApiProvider
    ): Either<String, NotBlankTrimmedString?> = read(provider.modelPref())

    suspend fun saveModel(
        provider: VoiceApiProvider,
        model: NotBlankTrimmedString?,
    ): Either<String, Unit> = write(provider.modelPref(), model?.value)

    suspend fun findActiveProvider(): Either<String, VoiceApiProvider?> =
        read(ACTIVE_PROVIDER_PREF).map { name ->
            VoiceApiProvider.entries.firstOrNull { it.name == name?.value }
        }

    suspend fun saveActiveProvider(
        provider: VoiceApiProvider?
    ): Either<String, Unit> = write(ACTIVE_PROVIDER_PREF, provider?.name)

    suspend fun findLanguage(): Either<String, VoiceLanguage> = read(LANGUAGE_PREF).map { name ->
        VoiceLanguage.entries.firstOrNull { it.name == name?.value } ?: VoiceLanguage.Device
    }

    suspend fun saveLanguage(
        language: VoiceLanguage
    ): Either<String, Unit> = write(LANGUAGE_PREF, language.name)

    /**
     * @return the configuration of the active provider or null if voice commands
     * should use the offline parser.
     */
    suspend fun findActiveConfig(): Either<String, LlmConfig?> = either {
        val provider = findActiveProvider().bind() ?: return@either null
        val key = findKey(provider).bind() ?: return@either null
        val model = findModel(provider).bind()
            ?: NotBlankTrimmedString.unsafe(provider.defaultModel)
        LlmConfig(provider = provider, apiKey = key, model = model)
    }

    private suspend fun read(
        pref: String
    ): Either<String, NotBlankTrimmedString?> = withContext(dispatchers.io) {
        Either.catch {
            prefs.getString(pref, null)?.let { NotBlankTrimmedString.from(it).getOrNull() }
        }.mapLeft { it.message ?: "Failed to read the voice commands settings" }
    }

    private suspend fun write(
        pref: String,
        value: String?,
    ): Either<String, Unit> = withContext(dispatchers.io) {
        Either.catch {
            val editor = prefs.edit()
            if (value == null) editor.remove(pref) else editor.putString(pref, value)
            editor.apply()
        }.mapLeft { it.message ?: "Failed to save the voice commands settings" }
    }

    private fun VoiceApiProvider.keyPref(): String = "api_key_$name"

    private fun VoiceApiProvider.modelPref(): String = "model_$name"

    companion object {
        private const val PREFS_FILE_NAME = "voice_commands_api_keys"
        private const val ACTIVE_PROVIDER_PREF = "active_provider"
        private const val LANGUAGE_PREF = "language"
    }
}
