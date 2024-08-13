package to.bitkit.data.keychain

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class KeychainStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val Context.prefs: DataStore<Preferences> by preferencesDataStore("keychain")
    private val prefs: DataStore<Preferences> by lazy { context.prefs }

    suspend fun get(key: String): ByteArray? {
        val prefKey = stringPreferencesKey(key)
        return prefs.data.map { it[prefKey].fromBase64() }.first()
    }

    suspend fun add(key: String, encryptedValue: ByteArray) {
        val prefKey = stringPreferencesKey(key)
        prefs.edit { it[prefKey] = encryptedValue.toBase64() }
    }

    suspend fun remove(key: String) {
        val prefKey = stringPreferencesKey(key)
        prefs.edit { it.remove(prefKey) }
    }

    private fun ByteArray.toBase64(flags: Int = Base64.DEFAULT) = Base64.encodeToString(this, flags)
    private fun String?.fromBase64(flags: Int = Base64.DEFAULT) = Base64.decode(this, flags)
}
