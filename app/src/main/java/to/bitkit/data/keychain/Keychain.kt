package to.bitkit.data.keychain

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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

private val Context.keychainDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "keychain"
)

@Singleton
class Keychain @Inject constructor(
    private val db: AppDb,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : BaseCoroutineScope(dispatcher) {
    private val keyStore by lazy { AndroidKeyStore(alias = "keychain") }
    private val keychain = context.keychainDataStore

    val snapshot get() = runBlocking(this.coroutineContext) { keychain.data.first() }

    fun loadString(key: String): String? = load(key)?.decodeToString()

    fun load(key: String): ByteArray? {
        try {
            return snapshot[key.indexed]?.fromBase64()?.let {
                keyStore.decrypt(it)
            }
        } catch (_: Exception) {
            throw KeychainError.FailedToLoad(key)
        }
    }

    suspend fun saveString(key: String, value: String) = save(key, value.toByteArray())

    suspend fun save(key: String, value: ByteArray) {
        if (exists(key)) throw KeychainError.FailedToSaveAlreadyExists(key)

        try {
            val encryptedValue = keyStore.encrypt(value)
            keychain.edit { it[key.indexed] = encryptedValue.toBase64() }
        } catch (_: Exception) {
            throw KeychainError.FailedToSave(key)
        }
        Logger.info("Saved to keychain: $key")
    }

    /** Inserts or replaces a string value associated with a given key in the keychain. */
    suspend fun upsertString(key: String, value: String) {
        try {
            val encryptedValue = keyStore.encrypt(value.toByteArray())
            keychain.edit { it[key.indexed] = encryptedValue.toBase64() }
        } catch (_: Exception) {
            throw KeychainError.FailedToSave(key)
        }
        Logger.info("Upsert in keychain: $key")
    }

    suspend fun delete(key: String) {
        try {
            keychain.edit { it.remove(key.indexed) }
        } catch (_: Exception) {
            throw KeychainError.FailedToDelete(key)
        }
        Logger.debug("Deleted from keychain: $key")
    }

    fun exists(key: String): Boolean {
        return snapshot.contains(key.indexed)
    }

    suspend fun wipe() {
        if (Env.network != Network.REGTEST) throw KeychainError.KeychainWipeNotAllowed()

        val keys = snapshot.asMap().keys
        keychain.edit { it.clear() }

        Logger.info("Deleted all keychain entries: ${keys.joinToString()}")
    }

    private val String.indexed: Preferences.Key<String>
        get() {
            val walletIndex = runBlocking { db.configDao().getAll().first() }.firstOrNull()?.walletIndex ?: 0
            return "${this}_$walletIndex".let(::stringPreferencesKey)
        }

    fun pinAttemptsRemaining(): Flow<Int?> {
        return keychain.data
            .map { it[Key.PIN_ATTEMPTS_REMAINING.name.indexed] }
            .distinctUntilChanged()
            .map { encrypted ->
                encrypted?.fromBase64()?.let { bytes ->
                    keyStore.decrypt(bytes).decodeToString()
                }
            }
            .map { string -> string?.toIntOrNull() }
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
