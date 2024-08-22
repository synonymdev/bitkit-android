@file:Suppress("unused")

package to.bitkit.data.keychain

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import to.bitkit.Tag.APP
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.data.AppDb
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.fromBase64
import to.bitkit.ext.toBase64
import javax.inject.Inject

class KeychainStore @Inject constructor(
    private val db: AppDb,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : BaseCoroutineScope(dispatcher) {
    private val alias = "keychain"
    private val keyStore by lazy { AndroidKeyStore(alias) }

    private val Context.keychain by preferencesDataStore(alias, scope = this)
    val snapshot get() = runBlocking { context.keychain.data.first() }

    fun loadString(key: String): String? = load(key)?.let { keyStore.decrypt(it) }

    private fun load(key: String): ByteArray? {
        return snapshot[key.indexed]?.fromBase64()
    }

    suspend fun saveString(key: String, value: String) = save(key, value.let { keyStore.encrypt(it) })

    private suspend fun save(key: String, encryptedValue: ByteArray) {
        require(!exists(key)) { "Entry $key exists. Explicitly delete it first to update value." }
        context.keychain.edit { it[key.indexed] = encryptedValue.toBase64() }

        Log.i(APP, "Saved to keychain: $key")
    }

    suspend fun delete(key: String) {
        context.keychain.edit { it.remove(key.indexed) }

        Log.d(APP, "Deleted from keychain: $key ")
    }

    fun exists(key: String): Boolean {
        return snapshot.contains(key.indexed)
    }

    suspend fun wipe() {
        val keys = snapshot.asMap().keys
        context.keychain.edit { it.clear() }

        Log.i(APP, "Deleted all keychain entries: ${keys.joinToString()}")
    }

    private val String.indexed: Preferences.Key<String>
        get() {
            val walletIndex = runBlocking { db.configDao().getAll().first() }.first().walletIndex
            return "${this}_$walletIndex".let(::stringPreferencesKey)
        }
}
