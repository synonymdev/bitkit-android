@file:Suppress("unused")

package to.bitkit.data.keychain

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import to.bitkit.data.AppDb
import to.bitkit.ext.fromBase64
import to.bitkit.ext.toBase64
import javax.inject.Inject

class KeychainStore @Inject constructor(
    @ApplicationContext private val context: Context,
    db: AppDb,
) {
    private val Context.prefs: DataStore<Preferences> by preferencesDataStore("keychain")
    private val prefs: DataStore<Preferences> by lazy { context.prefs }

    private val walletIndex by lazy { db.configDao().getAll().map { it.first().walletIndex }.toString() }

    suspend fun get(key: String): ByteArray {
        val prefKey = indexed(key)
        return prefs.data.map { it[prefKey].fromBase64() }.first()
    }

    suspend fun add(key: String, encryptedValue: ByteArray) {
        val prefKey = indexed(key)
        prefs.edit { it[prefKey] = encryptedValue.toBase64() }
    }

    suspend fun remove(key: String) {
        val prefKey = indexed(key)
        prefs.edit { it.remove(prefKey) }
    }

    /**
     * Generates a preferences key for storing a value associated with a specific wallet index.
     */
    private fun indexed(key: String) = "$walletIndex:$key".let(::stringPreferencesKey)
}
