@file:Suppress("unused")

package to.bitkit.data.keychain

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import to.bitkit.Tag.APP
import to.bitkit.data.AppDb
import to.bitkit.ext.fromBase64
import to.bitkit.ext.toBase64
import javax.inject.Inject

class KeychainStore @Inject constructor(
    @ApplicationContext private val context: Context,
    db: AppDb,
) {
    private val Context.prefs: DataStore<Preferences> by preferencesDataStore("keychain")
    private val prefs = context.prefs

    private val walletIndex by lazy { db.configDao().getAll().map { it.first().walletIndex }.toString() }

    // TODO throw if not found?
    suspend fun load(key: String): ByteArray? {
        val prefKey = indexed(key)
        return prefs.data.map { it[prefKey]?.fromBase64() }.first()
    }

    suspend fun loadString(key: String): String? {
        // TODO decrypt
        return load(key)?.toString(Charsets.UTF_8)
    }

    suspend fun save(key: String, encryptedValue: ByteArray) {
        require(!exists(key)) { "Entry $key exists. Explicitly delete it first to update value." }
        val prefKey = indexed(key)
        prefs.edit { it[prefKey] = encryptedValue.toBase64() }

        Log.i(APP, "Saved $key to keychain")
    }

    suspend fun saveString(key: String, value: String) {
        // TODO encrypt
        save(key, value.toByteArray())
    }

    suspend fun delete(key: String) {
        val prefKey = indexed(key)
        prefs.edit { it.remove(prefKey) }

        Log.d(APP, "Deleted $key from keychain")
    }

    suspend fun exists(key: String): Boolean {
        val prefKey = indexed(key)
        return prefs.data.map { it.contains(prefKey) }.first()
    }

    suspend fun wipe() {
        val keys = prefs.data.map { it.asMap().keys }.first()
        prefs.edit { it.clear() }
        Log.i(APP, "Deleted all entries from keychain: ${keys.joinToString()}")
    }

    /**
     * Generates a preferences key for storing a value associated with a specific wallet index.
     */
    private fun indexed(key: String) = "$walletIndex:$key".let(::stringPreferencesKey)
}
