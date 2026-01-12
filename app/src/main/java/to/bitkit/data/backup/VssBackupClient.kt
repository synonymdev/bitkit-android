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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class VssBackupClient @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val vssStoreIdProvider: VssStoreIdProvider,
    private val keychain: Keychain,
) {
    private var isSetup = CompletableDeferred<Unit>()

    suspend fun setup(walletIndex: Int = 0) = withContext(ioDispatcher) {
        try {
            withTimeout(30.seconds) {
                Logger.debug("VSS client setting up…", context = TAG)
                val vssUrl = Env.vssServerUrl
                val lnurlAuthServerUrl = Env.lnurlAuthServerUrl
                val vssStoreId = vssStoreIdProvider.getVssStoreId(walletIndex)
                Logger.verbose("Building VSS client with vssUrl: '$vssUrl'")
                Logger.verbose("Building VSS client with lnurlAuthServerUrl: '$lnurlAuthServerUrl'")
                if (lnurlAuthServerUrl.isNotEmpty()) {
                    val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
                        ?: throw ServiceError.MnemonicNotFound()
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
        } catch (e: Throwable) {
            isSetup.completeExceptionally(e)
            Logger.error("VSS client setup error", e = e, context = TAG)
        }
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
            Logger.verbose("VSS 'putObject' success for '$key' at version: ${it.version}", context = TAG)
        }.onFailure { e ->
            Logger.verbose("VSS 'putObject' error for '$key'", e = e, context = TAG)
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
        }.onFailure { e ->
            Logger.verbose("VSS 'getObject' error for '$key'", e = e, context = TAG)
        }
    }

    suspend fun listKeys(prefix: String? = null): Result<List<KeyVersion>> = withContext(ioDispatcher) {
        isSetup.await()
        Logger.verbose("VSS 'listKeys' call with prefix: '$prefix'", context = TAG)
        runCatching {
            vssListKeys(prefix = prefix)
        }.onSuccess {
            Logger.verbose("VSS 'listKeys' success - found ${it.size} key(s)", context = TAG)
        }.onFailure { e ->
            Logger.verbose("VSS 'listKeys' error", e = e, context = TAG)
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
        }.onFailure { e ->
            Logger.verbose("VSS 'deleteObject' error for '$key'", e = e, context = TAG)
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
        }.onFailure { e ->
            Logger.verbose("VSS 'deleteAllKeys' error", e = e, context = TAG)
        }
    }

    companion object Companion {
        private const val TAG = "VssBackupClient"
    }
}
