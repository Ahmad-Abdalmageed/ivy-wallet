package com.ivy.voicecommands

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import arrow.core.Either
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.model.primitive.NotBlankTrimmedString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the API keys used by voice commands in encrypted shared preferences.
 * Keys never leave the device and aren't part of backups.
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
    ): Either<String, NotBlankTrimmedString?> = withContext(dispatchers.io) {
        Either.catch {
            prefs.getString(provider.prefKey(), null)
                ?.let { NotBlankTrimmedString.from(it).getOrNull() }
        }.mapLeft { it.message ?: "Failed to read the API key" }
    }

    suspend fun saveKey(
        provider: VoiceApiProvider,
        key: NotBlankTrimmedString,
    ): Either<String, Unit> = withContext(dispatchers.io) {
        Either.catch {
            prefs.edit().putString(provider.prefKey(), key.value).apply()
        }.mapLeft { it.message ?: "Failed to save the API key" }
    }

    suspend fun removeKey(
        provider: VoiceApiProvider
    ): Either<String, Unit> = withContext(dispatchers.io) {
        Either.catch {
            prefs.edit().remove(provider.prefKey()).apply()
        }.mapLeft { it.message ?: "Failed to remove the API key" }
    }

    private fun VoiceApiProvider.prefKey(): String = "api_key_$name"

    companion object {
        private const val PREFS_FILE_NAME = "voice_commands_api_keys"
    }
}
