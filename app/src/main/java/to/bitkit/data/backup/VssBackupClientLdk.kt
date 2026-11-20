package to.bitkit.data.backup

import com.synonym.vssclient.KeyVersion
import com.synonym.vssclient.LdkNamespace
import com.synonym.vssclient.VssItem
import com.synonym.vssclient.vssDeriveStoreId
import com.synonym.vssclient.vssLdkDelete
import com.synonym.vssclient.vssLdkGet
import com.synonym.vssclient.vssLdkListKeys
import com.synonym.vssclient.vssNewLdkClientWithLnurlAuth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

@Singleton
class VssBackupClientLdk @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val coordinator: VssClientCoordinator,
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

    suspend fun setup(walletIndex: Int = 0): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            coordinator.setup(VssClientCoordinator.Domain.LDK) {
                val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
                    ?: throw MnemonicNotAvailableException()

                withTimeout(30.seconds) {
                    if (Env.lnurlAuthServerUrl.isBlank()) {
                        throw ServiceError.VssAuthRequired()
                    }
                    val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
                    vssNewLdkClientWithLnurlAuth(
                        baseUrl = Env.vssServerUrl,
                        storeId = vssDeriveStoreId(Env.vssStoreIdPrefix, mnemonic, passphrase),
                        mnemonic = mnemonic,
                        passphrase = passphrase,
                        lnurlAuthServerUrl = Env.lnurlAuthServerUrl,
                    )
                    Logger.info("VSS LDK client setup for wallet[$walletIndex]", context = TAG)
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            if (it is CancellationException && it !is TimeoutCancellationException) throw it
            Logger.error("VSS LDK client setup error", it, context = TAG)
        }
    }

    suspend fun getObject(
        key: String,
        namespace: LdkNamespace = LdkNamespace.Default,
    ): Result<VssItem?> = withContext(ioDispatcher) {
        Logger.verbose("VSS LDK 'getObject' call for '$key'", context = TAG)
        runSuspendCatching {
            coordinator.withClient(VssClientCoordinator.Domain.LDK) {
                vssLdkGet(key = key, namespace = namespace)
            }
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
        Logger.verbose("VSS LDK 'deleteObject' call for '$key'", context = TAG)
        runSuspendCatching {
            coordinator.withClient(VssClientCoordinator.Domain.LDK) {
                vssLdkDelete(key = key, namespace = namespace)
            }
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

    suspend fun deleteObjects(keys: List<String>, walletIndex: Int = 0): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            coordinator.withWallet {
                setup(walletIndex).getOrThrow()
                coordinator.withClient(VssClientCoordinator.Domain.LDK) {
                    keys.forEach { vssLdkDelete(key = it, namespace = LdkNamespace.Default) }
                }
            }
        }
    }

    suspend fun listAllKeysTagged(): Result<List<Pair<LdkNamespace, KeyVersion>>> = withContext(ioDispatcher) {
        Logger.verbose("VSS LDK 'listAllKeysTagged' call", context = TAG)
        runSuspendCatching {
            coordinator.withClient(VssClientCoordinator.Domain.LDK) {
                NAMESPACES.flatMap { ns -> vssLdkListKeys(namespace = ns).map { ns to it } }
            }
        }.onSuccess {
            Logger.verbose("VSS LDK 'listAllKeysTagged' success - found ${it.size} key(s)", context = TAG)
        }.onFailure {
            Logger.verbose("VSS LDK 'listAllKeysTagged' error", it, context = TAG)
        }
    }
}
