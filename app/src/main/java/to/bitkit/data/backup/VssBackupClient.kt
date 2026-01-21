package to.bitkit.data.backup

import com.synonym.vssclient.KeyVersion
import com.synonym.vssclient.VssItem
import com.synonym.vssclient.vssDelete
import com.synonym.vssclient.vssGet
import com.synonym.vssclient.vssListKeys
import com.synonym.vssclient.vssNewClient
import com.synonym.vssclient.vssNewClientWithLnurlAuth
import com.synonym.vssclient.vssStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

class MnemonicNotAvailableException : Exception("Mnemonic not available")

@Singleton
class VssBackupClient @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val vssStoreIdProvider: VssStoreIdProvider,
    private val keychain: Keychain,
) {
    private var isSetup = CompletableDeferred<Unit>()
    private val setupMutex = Mutex()

    suspend fun setup(walletIndex: Int = 0): Result<Unit> = withContext(ioDispatcher) {
        setupMutex.withLock {
            runCatching {
                if (isSetup.isCompleted && !isSetup.isCancelled) {
                    runCatching { isSetup.await() }.onSuccess { return@runCatching }
                }

                val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
                    ?: throw MnemonicNotAvailableException()

                withTimeout(30.seconds) {
                    Logger.debug("VSS client setting up…", context = TAG)
                    val vssUrl = Env.vssServerUrl
                    val lnurlAuthServerUrl = Env.lnurlAuthServerUrl
                    val vssStoreId = vssStoreIdProvider.getVssStoreId(walletIndex)
                    Logger.verbose("Building VSS client with vssUrl: '$vssUrl'", context = TAG)
                    Logger.verbose("Building VSS client with lnurlAuthServerUrl: '$lnurlAuthServerUrl'", context = TAG)
                    if (lnurlAuthServerUrl.isNotEmpty()) {
                        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)

                        vssNewClientWithLnurlAuth(
                            baseUrl = vssUrl,
                            storeId = vssStoreId,
                            mnemonic = mnemonic,
                            passphrase = passphrase,
                            lnurlAuthServerUrl = lnurlAuthServerUrl,
                        )
                    } else {
                        vssNewClient(
                            baseUrl = vssUrl,
                            storeId = vssStoreId,
                        )
                    }
                    isSetup.complete(Unit)
                    Logger.info("VSS client setup with server: '$vssUrl'", context = TAG)
                }
            }.onFailure {
                isSetup.completeExceptionally(it)
                Logger.error("VSS client setup error", it, context = TAG)
            }
        }
    }

    class SetupRetryLogger {
        var onSuccess: (attempt: Int) -> Unit = {}
        var onRetry: (attempt: Int, maxAttempts: Int, delayMs: Long) -> Unit = { _, _, _ -> }
        var onExhausted: (maxAttempts: Int) -> Unit = {}
    }

    suspend fun setupWithRetry(
        maxAttempts: Int = 10,
        baseDelayMs: Long = 1000L,
        logger: SetupRetryLogger.() -> Unit,
    ): Result<Unit> = withContext(ioDispatcher) {
        val log = SetupRetryLogger().apply(logger)
        var attempt = 0
        while (attempt < maxAttempts) {
            val result = setup()
            if (result.isSuccess) {
                log.onSuccess(attempt + 1)
                return@withContext Result.success(Unit)
            }
            val exception = result.exceptionOrNull()
            if (exception != null && exception !is MnemonicNotAvailableException) {
                return@withContext result
            }
            attempt++
            if (attempt < maxAttempts) {
                val delayMs = baseDelayMs * attempt
                log.onRetry(attempt, maxAttempts, delayMs)
                delay(delayMs)
            }
        }
        log.onExhausted(maxAttempts)
        Result.failure(MnemonicNotAvailableException())
    }

    fun reset() {
        synchronized(this) {
            isSetup.cancel()
            isSetup = CompletableDeferred()
        }
        vssStoreIdProvider.clearCache()
        Logger.debug("VSS client reset", context = TAG)
    }
    suspend fun putObject(
        key: String,
        data: ByteArray,
    ): Result<VssItem> = withContext(ioDispatcher) {
        isSetup.await()
        Logger.verbose("VSS 'putObject' call for '$key'", context = TAG)
        runCatching {
            vssStore(
                key = key,
                value = data,
            )
        }.onSuccess {
            Logger.verbose("VSS 'putObject' success for '$key' at version: '${it.version}'", context = TAG)
        }.onFailure {
            Logger.verbose("VSS 'putObject' error for '$key'", it, context = TAG)
        }
    }

    suspend fun getObject(key: String): Result<VssItem?> = withContext(ioDispatcher) {
        isSetup.await()
        Logger.verbose("VSS 'getObject' call for '$key'", context = TAG)
        runCatching {
            vssGet(
                key = key,
            )
        }.onSuccess {
            if (it == null) {
                Logger.verbose("VSS 'getObject' success null for '$key'", context = TAG)
            } else {
                Logger.verbose("VSS 'getObject' success for '$key'", context = TAG)
            }
        }.onFailure {
            Logger.verbose("VSS 'getObject' error for '$key'", it, context = TAG)
        }
    }

    suspend fun listKeys(prefix: String? = null): Result<List<KeyVersion>> = withContext(ioDispatcher) {
        isSetup.await()
        Logger.verbose("VSS 'listKeys' call with prefix: '$prefix'", context = TAG)
        runCatching {
            vssListKeys(prefix = prefix)
        }.onSuccess {
            Logger.verbose("VSS 'listKeys' success - found ${it.size} key(s)", context = TAG)
        }.onFailure {
            Logger.verbose("VSS 'listKeys' error", it, context = TAG)
        }
    }

    suspend fun deleteObject(key: String): Result<Boolean> = withContext(ioDispatcher) {
        isSetup.await()
        Logger.verbose("VSS 'deleteObject' call for '$key'", context = TAG)
        runCatching {
            vssDelete(key = key)
        }.onSuccess { wasDeleted ->
            if (wasDeleted) {
                Logger.verbose("VSS 'deleteObject' success for '$key' - key was deleted", context = TAG)
            } else {
                Logger.verbose("VSS 'deleteObject' success for '$key' - key did not exist", context = TAG)
            }
        }.onFailure {
            Logger.verbose("VSS 'deleteObject' error for '$key'", it, context = TAG)
        }
    }

    suspend fun deleteAllKeys(): Result<Int> = withContext(ioDispatcher) {
        isSetup.await()
        Logger.verbose("VSS 'deleteAllKeys' call", context = TAG)
        runCatching {
            val keys = vssListKeys(prefix = null)
            var deletedCount = 0
            for (keyVersion in keys) {
                val wasDeleted = vssDelete(key = keyVersion.key)
                if (wasDeleted) deletedCount++
            }
            deletedCount
        }.onSuccess {
            Logger.verbose("VSS 'deleteAllKeys' success - deleted $it key(s)", context = TAG)
        }.onFailure {
            Logger.verbose("VSS 'deleteAllKeys' error", it, context = TAG)
        }
    }

    companion object {
        private const val TAG = "VssBackupClient"
    }
}
