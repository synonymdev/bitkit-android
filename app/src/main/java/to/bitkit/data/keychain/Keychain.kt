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
import to.bitkit.ext.fromBase64
import to.bitkit.ext.toBase64
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private val Context.keychainDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "keychain"
)

@Singleton
class Keychain @Inject constructor(
    private val db: AppDb,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : BaseCoroutineScope(dispatcher) {
    companion object {
        private const val TAG = "Keychain"
        private const val CAUSE_CHAIN_DEPTH = 4
    }

    private val keyStore by lazy { AndroidKeyStore(alias = "keychain") }

    @Suppress("MemberNameEqualsClassName")
    private val keychain = context.keychainDataStore

    private val loadDiagnosticsEmitted: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    val snapshot get() = runBlocking(this.coroutineContext) { keychain.data.first() }

    fun loadString(key: String): String? = load(key)?.decodeToString()

    @Suppress("TooGenericExceptionCaught")
    fun load(key: String): ByteArray? {
        try {
            return snapshot[key.indexed]?.fromBase64()?.let {
                keyStore.decrypt(it)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            emitLoadDiagnosticsOnce(key, t)
            throw KeychainError.FailedToLoad(key, cause = t)
        }
    }

    suspend fun saveString(key: String, value: String) = save(key, value.toByteArray())

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend fun save(key: String, value: ByteArray) {
        if (exists(key)) throw KeychainError.FailedToSaveAlreadyExists(key)

        try {
            val encryptedValue = keyStore.encrypt(value)
            keychain.edit { it[key.indexed] = encryptedValue.toBase64() }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw KeychainError.FailedToSave(key, cause = t)
        }
        Logger.info("Saved to keychain: $key")
    }

    /** Inserts or replaces a string value associated with a given key in the keychain. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun upsertString(key: String, value: String) {
        try {
            val encryptedValue = keyStore.encrypt(value.toByteArray())
            keychain.edit { it[key.indexed] = encryptedValue.toBase64() }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw KeychainError.FailedToSave(key, cause = t)
        }
        Logger.info("Upsert in keychain: $key")
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun delete(key: String) {
        try {
            keychain.edit { it.remove(key.indexed) }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw KeychainError.FailedToDelete(key, cause = t)
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

    private fun emitLoadDiagnosticsOnce(key: String, cause: Throwable) {
        if (!loadDiagnosticsEmitted.add(key)) return

        val aliasPresent = runCatching { keyStore.containsAlias() }.getOrDefault(false)
        val entryPresent = runCatching { snapshot.contains(key.indexed) }.getOrDefault(false)
        val walletIndex = runCatching {
            runBlocking { db.configDao().getAll().first() }.firstOrNull()?.walletIndex ?: 0L
        }.getOrDefault(-1L)
        val causeChain = generateSequence(cause) { it.cause }
            .take(CAUSE_CHAIN_DEPTH)
            .joinToString(separator = " <- ") { it.javaClass.simpleName }

        Logger.warn(
            "Decrypt failed for key='$key' walletIndex='$walletIndex' " +
                "aliasPresent='$aliasPresent' entryPresent='$entryPresent' " +
                "causeChain='$causeChain'",
            context = TAG,
        )
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

sealed class KeychainError(message: String, cause: Throwable? = null) : AppError(message, cause) {
    class FailedToDelete(key: String, cause: Throwable? = null) :
        KeychainError("Failed to delete $key from keychain${cause.causeSuffix()}", cause)

    class FailedToLoad(val key: String, cause: Throwable? = null) :
        KeychainError("Failed to load $key from keychain${cause.causeSuffix()}", cause)

    class FailedToSave(key: String, cause: Throwable? = null) :
        KeychainError("Failed to save to $key keychain${cause.causeSuffix()}", cause)

    class FailedToSaveAlreadyExists(key: String) :
        KeychainError("Key $key already exists in keychain. Explicitly delete key before attempting to update value.")
}

private fun Throwable?.causeSuffix(): String =
    this?.let { " (cause='${it.javaClass.simpleName}')" } ?: "."
