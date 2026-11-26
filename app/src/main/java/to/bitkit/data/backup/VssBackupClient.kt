package to.bitkit.data.backup

import com.synonym.vssclient.KeyVersion
import com.synonym.vssclient.VssItem
import com.synonym.vssclient.vssDelete
import com.synonym.vssclient.vssDeriveStoreId
import com.synonym.vssclient.vssGet
import com.synonym.vssclient.vssListKeys
import com.synonym.vssclient.vssNewClientWithLnurlAuth
import com.synonym.vssclient.vssStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class MnemonicNotAvailableException : Exception("Mnemonic not available")

internal class BackupClientSetupTimeout(cause: TimeoutCancellationException) :
    AppError("Backup client setup timed out", cause)

internal suspend fun VssBackupClient.ensureSetupForUpload() {
    val result = setup()
    currentCoroutineContext().ensureActive()
    val error = result.exceptionOrNull()
    if (error is TimeoutCancellationException) throw BackupClientSetupTimeout(error)
    result.getOrThrow()
}

@Singleton
class VssBackupClient @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val coordinator: VssClientCoordinator,
    private val keychain: Keychain,
) {
    suspend fun setup(walletIndex: Int = 0): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            coordinator.setup(VssClientCoordinator.Domain.APP) {
                val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
                    ?: throw MnemonicNotAvailableException()

                withTimeout(30.seconds) {
                    Logger.debug("VSS client setting up…", context = TAG)
                    val vssUrl = Env.vssServerUrl
                    val lnurlAuthServerUrl = Env.lnurlAuthServerUrl
                    Logger.verbose("Building VSS client with vssUrl: '$vssUrl'", context = TAG)
                    Logger.verbose("Building VSS client with lnurlAuthServerUrl: '$lnurlAuthServerUrl'", context = TAG)
                    if (lnurlAuthServerUrl.isBlank()) {
                        throw ServiceError.VssAuthRequired()
                    }
                    val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
                    val vssStoreId = vssDeriveStoreId(Env.vssStoreIdPrefix, mnemonic, passphrase)

                    vssNewClientWithLnurlAuth(
                        baseUrl = vssUrl,
                        storeId = vssStoreId,
                        mnemonic = mnemonic,
                        passphrase = passphrase,
                        lnurlAuthServerUrl = lnurlAuthServerUrl,
                    )
                    Logger.info("VSS client setup for wallet[$walletIndex] with server: '$vssUrl'", context = TAG)
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            if (it is CancellationException && it !is TimeoutCancellationException) throw it
            Logger.error("VSS client setup error", it, context = TAG)
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
        coordinator.withWallet {
            val log = SetupRetryLogger().apply(logger)
            var attempt = 0
            while (attempt < maxAttempts) {
                val result = setup()
                if (result.isSuccess) {
                    log.onSuccess(attempt + 1)
                    return@withWallet Result.success(Unit)
                }
                val exception = result.exceptionOrNull()
                if (exception != null && exception !is MnemonicNotAvailableException) {
                    return@withWallet result
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
    }

    suspend fun putObject(
        key: String,
        data: ByteArray,
    ): Result<VssItem> = withContext(ioDispatcher) {
        Logger.verbose("VSS 'putObject' call for '$key'", context = TAG)
        runSuspendCatching {
            coordinator.withClient(VssClientCoordinator.Domain.APP) {
                vssStore(
                    key = key,
                    value = data,
                )
            }
        }.onSuccess {
            Logger.verbose("VSS 'putObject' success for '$key' at version: '${it.version}'", context = TAG)
        }.onFailure {
            Logger.verbose("VSS 'putObject' error for '$key'", it, context = TAG)
        }
    }

    suspend fun getObject(key: String): Result<VssItem?> = withContext(ioDispatcher) {
        Logger.verbose("VSS 'getObject' call for '$key'", context = TAG)
        runSuspendCatching {
            coordinator.withClient(VssClientCoordinator.Domain.APP) {
                vssGet(
                    key = key,
                )
            }
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
        Logger.verbose("VSS 'listKeys' call with prefix: '$prefix'", context = TAG)
        runSuspendCatching {
            coordinator.withClient(VssClientCoordinator.Domain.APP) {
                vssListKeys(prefix = prefix)
            }
        }.onSuccess {
            Logger.verbose("VSS 'listKeys' success - found ${it.size} key(s)", context = TAG)
        }.onFailure {
            Logger.verbose("VSS 'listKeys' error", it, context = TAG)
        }
    }

    suspend fun deleteObject(key: String): Result<Boolean> = withContext(ioDispatcher) {
        Logger.verbose("VSS 'deleteObject' call for '$key'", context = TAG)
        runSuspendCatching {
            coordinator.withClient(VssClientCoordinator.Domain.APP) {
                vssDelete(key = key)
            }
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
        Logger.verbose("VSS 'deleteAllKeys' call", context = TAG)
        runSuspendCatching {
            coordinator.withClient(VssClientCoordinator.Domain.APP) {
                val keys = vssListKeys(prefix = null)
                var deletedCount = 0
                for (keyVersion in keys) {
                    val wasDeleted = vssDelete(key = keyVersion.key)
                    if (wasDeleted) deletedCount++
                }
                deletedCount
            }
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
