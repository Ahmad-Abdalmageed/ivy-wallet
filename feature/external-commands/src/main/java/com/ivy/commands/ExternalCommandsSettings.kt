package com.ivy.commands

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether other apps may run commands and the access key they must send.
 * Stored in the app's private storage, which isn't backed up.
 */
@Singleton
class ExternalCommandsSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun isEnabled(): Boolean = dataStore.data.first()[EnabledKey] ?: false

    suspend fun setEnabled(enabled: Boolean) {
        if (enabled) accessKey()
        dataStore.edit { it[EnabledKey] = enabled }
    }

    /**
     * @return the access key, generating it the first time.
     */
    suspend fun accessKey(): String {
        dataStore.data.first()[AccessKey]?.let { return it }
        return regenerateAccessKey()
    }

    suspend fun regenerateAccessKey(): String {
        val key = newKey()
        dataStore.edit { it[AccessKey] = key }
        return key
    }

    suspend fun isValidKey(key: String): Boolean {
        val expected = dataStore.data.first()[AccessKey] ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), key.trim().toByteArray())
    }

    private fun newKey(): String {
        val bytes = ByteArray(KEY_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_BYTES = 16
        private val EnabledKey = booleanPreferencesKey("external_commands_enabled")
        private val AccessKey = stringPreferencesKey("external_commands_access_key")
    }
}
