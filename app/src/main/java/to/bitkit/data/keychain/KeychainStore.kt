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
    private val alias = "keychain"

    private val Context.prefs: DataStore<Preferences> by preferencesDataStore(alias)
    private val prefs = context.prefs

    private val keyStore by lazy { AndroidKeyStore(alias) }
    private val walletIndex by lazy { db.configDao().getAll().map { it.first().walletIndex }.toString() }

    suspend fun loadString(key: String): String? = load(key)?.let { keyStore.decrypt(it) }

    // TODO throw if not found?
    private suspend fun load(key: String): ByteArray? {
        return prefs.data.map { it[key.indexed]?.fromBase64() }.first()
    }

    suspend fun saveString(key: String, value: String) = save(key, value.let { keyStore.encrypt(it) })

    private suspend fun save(key: String, encryptedValue: ByteArray) {
        require(!exists(key)) { "Entry $key exists. Explicitly delete it first to update value." }
        prefs.edit { it[key.indexed] = encryptedValue.toBase64() }

        Log.i(APP, "Saved $key to keychain")
    }

    suspend fun delete(key: String) {
        prefs.edit { it.remove(key.indexed) }

        Log.d(APP, "Deleted $key from keychain")
    }

    suspend fun exists(key: String): Boolean {
        return prefs.data.map { it.contains(key.indexed) }.first()
    }

    suspend fun wipe() {
        val keys = prefs.data.map { it.asMap().keys }.first()
        prefs.edit { it.clear() }
        Log.i(APP, "Deleted all entries from keychain: ${keys.joinToString()}")
    }

    /**
     * Generates a preferences key for storing a value associated with a specific wallet index.
     */
    private val String.indexed get() = "$walletIndex:$this".let(::stringPreferencesKey)
}
