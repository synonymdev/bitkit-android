package to.bitkit.data.backup

import com.synonym.vssclient.KeyVersion
import com.synonym.vssclient.LdkNamespace
import com.synonym.vssclient.VssItem
import com.synonym.vssclient.vssLdkDelete
import com.synonym.vssclient.vssLdkGet
import com.synonym.vssclient.vssLdkListKeys
import com.synonym.vssclient.vssNewLdkClientWithLnurlAuth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

@Suppress("TooManyFunctions")
@Singleton
class VssBackupClientLdk @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val vssStoreIdProvider: VssStoreIdProvider,
    private val keychain: Keychain,
) {
    companion object {
        private const val TAG = "VssBackupClientLdk"

        private val NAMESPACES = listOf(
            LdkNamespace.Default,
            LdkNamespace.Monitors,
            LdkNamespace.ArchivedMonitors,
        )
    }

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
                    if (Env.lnurlAuthServerUrl.isBlank()) {
                        throw ServiceError.VssAuthRequired()
                    }
                    val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
                    vssNewLdkClientWithLnurlAuth(
                        baseUrl = Env.vssServerUrl,
                        storeId = vssStoreIdProvider.getVssStoreId(walletIndex),
                        mnemonic = mnemonic,
                        passphrase = passphrase,
                        lnurlAuthServerUrl = Env.lnurlAuthServerUrl,
                    )
                    isSetup.complete(Unit)
                    Logger.info("VSS LDK client setup", context = TAG)
                }
            }.onFailure {
                isSetup.completeExceptionally(it)
                Logger.error("VSS LDK client setup error", it, context = TAG)
            }
        }
    }

    fun reset() {
        synchronized(this) {
            isSetup.cancel()
            isSetup = CompletableDeferred()
        }
        Logger.debug("VSS LDK client reset", context = TAG)
    }

    suspend fun getObject(
        key: String,
        namespace: LdkNamespace = LdkNamespace.Default,
    ): Result<VssItem?> = withContext(ioDispatcher) {
        isSetup.await()
        Logger.verbose("VSS LDK 'getObject' call for '$key'", context = TAG)
        runCatching {
            vssLdkGet(key = key, namespace = namespace)
        }.onSuccess {
            if (it == null) {
                Logger.verbose("VSS LDK 'getObject' success null for '$key'", context = TAG)
            } else {
                Logger.verbose("VSS LDK 'getObject' success for '$key'", context = TAG)
            }
        }.onFailure {
            Logger.verbose("VSS LDK 'getObject' error for '$key'", it, context = TAG)
        }
    }

    suspend fun deleteObject(
        key: String,
        namespace: LdkNamespace = LdkNamespace.Default,
    ): Result<Boolean> = withContext(ioDispatcher) {
        isSetup.await()
        Logger.verbose("VSS LDK 'deleteObject' call for '$key'", context = TAG)
        runCatching {
            vssLdkDelete(key = key, namespace = namespace)
        }.onSuccess { wasDeleted ->
            if (wasDeleted) {
                Logger.verbose("VSS LDK 'deleteObject' success for '$key' - key was deleted", context = TAG)
            } else {
                Logger.verbose("VSS LDK 'deleteObject' success for '$key' - key did not exist", context = TAG)
            }
        }.onFailure {
            Logger.verbose("VSS LDK 'deleteObject' error for '$key'", it, context = TAG)
        }
    }

    suspend fun listAllKeysTagged(): Result<List<Pair<LdkNamespace, KeyVersion>>> = withContext(ioDispatcher) {
        isSetup.await()
        Logger.verbose("VSS LDK 'listAllKeysTagged' call", context = TAG)
        runCatching {
            NAMESPACES.flatMap { ns -> vssLdkListKeys(namespace = ns).map { ns to it } }
        }.onSuccess {
            Logger.verbose("VSS LDK 'listAllKeysTagged' success - found ${it.size} key(s)", context = TAG)
        }.onFailure {
            Logger.verbose("VSS LDK 'listAllKeysTagged' error", it, context = TAG)
        }
    }
}
