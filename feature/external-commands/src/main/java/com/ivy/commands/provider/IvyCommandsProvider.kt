package com.ivy.commands.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.ivy.commands.command.ExternalCommandHandler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

/**
 * Runs Ivy Wallet commands for other apps and for `adb`:
 * ```
 * adb shell content call --uri content://com.ivy.wallet.commands --method add_transaction \
 *   --arg '{"key":"<access key>","amount":25,"account":"Cash","category":"Food"}'
 * ```
 * The response is a JSON string in the "json" key of the returned bundle.
 * See `docs/External-Commands.md`.
 */
class IvyCommandsProvider : ContentProvider() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CommandsEntryPoint {
        fun commandHandler(): ExternalCommandHandler
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context).applicationContext
        val handler = EntryPointAccessors.fromApplication(context, CommandsEntryPoint::class.java)
            .commandHandler()
        val json = runBlocking {
            handler.handle(method = method, arguments = arg, extras = extras.toArguments())
        }
        return Bundle().apply { putString(RESULT_KEY, json) }
    }

    @Suppress("DEPRECATION")
    private fun Bundle?.toArguments(): Map<String, String> {
        val bundle = this ?: return emptyMap()
        return bundle.keySet()
            .mapNotNull { key -> bundle.get(key)?.let { key to it.toString() } }
            .toMap()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val RESULT_KEY = "json"
    }
}
