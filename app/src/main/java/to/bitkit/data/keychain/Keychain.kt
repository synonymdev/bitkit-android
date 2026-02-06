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
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.data.AppDb
import to.bitkit.di.IoDispatcher
import to.bitkit.domain.models.Secret
import to.bitkit.domain.models.secretOf
import to.bitkit.ext.fromBase64
import to.bitkit.ext.toBase64
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.nio.ByteBuffer
import java.nio.CharBuffer
import javax.inject.Inject
import javax.inject.Singleton

private val Context.keychainDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "keychain"
)

@Suppress("TooManyFunctions")
@Singleton
class Keychain @Inject constructor(
    private val db: AppDb,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : BaseCoroutineScope(dispatcher) {
    private val keyStore by lazy { AndroidKeyStore(alias = "keychain") }

    @Suppress("MemberNameEqualsClassName")
    private val keychain = context.keychainDataStore

    val snapshot get() = runBlocking(this.coroutineContext) { keychain.data.first() }

    fun loadString(key: String): String? = load(key)?.decodeToString()

    fun loadSecret(key: String): Secret? {
        val bytes = load(key) ?: return null
        val chars = bytes.decodeToCharArray()
        bytes.fill(0)
        return secretOf(chars)
    }

    fun load(key: String): ByteArray? {
        return runCatching {
            snapshot[key.indexed]?.fromBase64()?.let {
                keyStore.decrypt(it)
            }
        }.getOrElse {
            throw KeychainError.FailedToLoad(key)
        }
    }

    suspend fun saveString(key: String, value: String) = save(key, value.toByteArray())

    suspend fun saveSecret(key: String, value: Secret) = value.use {
        val bytes = it.encodeToByteArray()
        try { save(key, bytes) } finally { bytes.fill(0) }
    }

    suspend fun save(key: String, value: ByteArray) {
        if (exists(key)) throw KeychainError.FailedToSaveAlreadyExists(key)

        runCatching {
            val encryptedValue = keyStore.encrypt(value)
            keychain.edit { it[key.indexed] = encryptedValue.toBase64() }
        }.onFailure {
            throw KeychainError.FailedToSave(key)
        }
        Logger.info("Saved to keychain: $key")
    }

    suspend fun upsertString(key: String, value: String) = upsert(key, value.toByteArray())

    suspend fun upsertSecret(key: String, value: Secret) = value.use { chars ->
        val bytes = chars.encodeToByteArray()
        try { upsert(key, bytes) } finally { bytes.fill(0) }
    }

    suspend fun upsert(key: String, value: ByteArray) {
        runCatching {
            val encryptedValue = keyStore.encrypt(value)
            keychain.edit { it[key.indexed] = encryptedValue.toBase64() }
        }.onFailure {
            throw KeychainError.FailedToSave(key)
        }
        Logger.info("Upsert in keychain: $key")
    }

    suspend fun delete(key: String) {
        runCatching {
            keychain.edit { it.remove(key.indexed) }
        }.onFailure {
            throw KeychainError.FailedToDelete(key)
        }
        Logger.debug("Deleted from keychain: $key")
    }

    fun exists(key: String): Boolean {
        return snapshot.contains(key.indexed)
    }

    suspend fun wipe() {
        val keys = snapshot.asMap().keys
        keychain.edit { it.clear() }
        keyStore.resetEncryptionKey()
        val count = keys.size

        Logger.info("Reset keychain encryption key and deleted all '$count' entries")
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

    private fun ByteArray.decodeToCharArray(): CharArray {
        val charBuffer = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(this))
        return CharArray(charBuffer.remaining()).also { charBuffer.get(it) }
    }

    private fun CharArray.encodeToByteArray(): ByteArray {
        val byteBuffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(this))
        return ByteArray(byteBuffer.remaining()).also { byteBuffer.get(it) }
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

sealed class KeychainError(message: String) : AppError(message) {
    class FailedToDelete(key: String) : KeychainError("Failed to delete $key from keychain.")
    class FailedToLoad(key: String) : KeychainError("Failed to load $key from keychain.")
    class FailedToSave(key: String) : KeychainError("Failed to save to $key keychain.")
    class FailedToSaveAlreadyExists(key: String) :
        KeychainError("Key $key already exists in keychain. Explicitly delete key before attempting to update value.")
}
