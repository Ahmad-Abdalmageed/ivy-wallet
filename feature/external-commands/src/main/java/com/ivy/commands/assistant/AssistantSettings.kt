package com.ivy.commands.assistant

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Gemini API key and model of the in-app assistant,
 * stored in the app's private storage, which isn't backed up.
 */
@Singleton
class AssistantSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun config(): AssistantConfig? {
        val preferences = dataStore.data.first()
        val apiKey = preferences[ApiKey]?.takeIf { it.isNotBlank() } ?: return null
        return AssistantConfig(
            apiKey = apiKey,
            model = preferences[Model]?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
        )
    }

    suspend fun model(): String = dataStore.data.first()[Model] ?: ""

    suspend fun save(apiKey: String?, model: String) {
        dataStore.edit { preferences ->
            apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { preferences[ApiKey] = it }
            preferences[Model] = model.trim()
        }
    }

    suspend fun removeApiKey() {
        dataStore.edit { it.remove(ApiKey) }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        private val ApiKey = stringPreferencesKey("assistant_gemini_api_key")
        private val Model = stringPreferencesKey("assistant_gemini_model")
    }
}
