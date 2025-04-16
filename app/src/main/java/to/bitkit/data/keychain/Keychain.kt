package to.bitkit.data.keychain

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.lightningdevkit.ldknode.Network
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.data.AppDb
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.fromBase64
import to.bitkit.ext.toBase64
import to.bitkit.utils.KeychainError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Keychain @Inject constructor(
    private val db: AppDb,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : BaseCoroutineScope(dispatcher) {
    private val alias = "keychain"
    private val keyStore by lazy { AndroidKeyStore(alias) }

    private val Context.keychain by preferencesDataStore(alias, scope = this)
    val snapshot get() = runBlocking(this.coroutineContext) { context.keychain.data.first() }

    fun loadString(key: String): String? = load(key)?.decodeToString()

    fun load(key: String): ByteArray? {
        try {
            return snapshot[key.indexed]?.fromBase64()?.let {
                keyStore.decrypt(it)
            }
        } catch (e: Exception) {
            throw KeychainError.FailedToLoad(key)
        }
    }

    suspend fun saveString(key: String, value: String) = save(key, value.toByteArray())

    suspend fun save(key: String, value: ByteArray) {
        if (exists(key)) throw KeychainError.FailedToSaveAlreadyExists(key)

        try {
            val encryptedValue = keyStore.encrypt(value)
            context.keychain.edit { it[key.indexed] = encryptedValue.toBase64() }
        } catch (e: Exception) {
            throw KeychainError.FailedToSave(key)
        }
        Logger.info("Saved to keychain: $key")
    }

    suspend fun delete(key: String) {
        try {
            context.keychain.edit { it.remove(key.indexed) }
        } catch (e: Exception) {
            throw KeychainError.FailedToDelete(key)
        }
        Logger.debug("Deleted from keychain: $key")
    }

    fun exists(key: String): Boolean {
        return snapshot.contains(key.indexed)
    }

    fun observeExists(key: Key): Flow<Boolean> = context.keychain.data.map { it.contains(key.name.indexed) }

    suspend fun wipe() {
        if (Env.network != Network.REGTEST) throw KeychainError.KeychainWipeNotAllowed()

        val keys = snapshot.asMap().keys
        context.keychain.edit { it.clear() }

        Logger.info("Deleted all keychain entries: ${keys.joinToString()}")
    }

    private val String.indexed: Preferences.Key<String>
        get() {
            val walletIndex = runBlocking { db.configDao().getAll().first() }.firstOrNull()?.walletIndex ?: 0
            return "${this}_$walletIndex".let(::stringPreferencesKey)
        }

    enum class Key {
        PUSH_NOTIFICATION_TOKEN,
        PUSH_NOTIFICATION_PRIVATE_KEY,
        BIP39_MNEMONIC,
        BIP39_PASSPHRASE,
        PIN,
        PIN_ATTEMPTS_REMAINING,
    }
}
