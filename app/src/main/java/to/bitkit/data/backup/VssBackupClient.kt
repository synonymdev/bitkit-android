package to.bitkit.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.BackupCategory
import to.bitkit.utils.Logger
import uniffi.vss_rust_client_ffi.VssItem
import uniffi.vss_rust_client_ffi.vssGet
import uniffi.vss_rust_client_ffi.vssNewClient
import uniffi.vss_rust_client_ffi.vssStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class VssBackupClient @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val vssStoreIdProvider: VssStoreIdProvider,
) {
    private val isSetup = CompletableDeferred<Unit>()

    suspend fun setup() = withContext(bgDispatcher) {
        try {
            withTimeout(30.seconds) {
                Logger.debug("VSS client setting up…", context = TAG)
                vssNewClient(
                    baseUrl = Env.vssServerUrl,
                    storeId = vssStoreIdProvider.getVssStoreId(),
                )
                isSetup.complete(Unit)
                Logger.info("VSS client setup ok", context = TAG)
            }
        } catch (e: Exception) {
            isSetup.completeExceptionally(e)
            Logger.error("VSS client setup error", e = e, context = TAG)
        }
    }

    suspend fun putObject(
        key: String,
        data: ByteArray,
    ): Result<VssItem> = withContext(bgDispatcher) {
        isSetup.await()
        Logger.debug("VSS 'putObject' call for '$key'", context = TAG)
        runCatching {
            vssStore(
                key = key,
                value = data,
            )
        }.onSuccess {
            Logger.debug("VSS 'putObject' success for '$key' at version: ${it.version}", context = TAG)
        }.onFailure { e ->
            Logger.error("VSS 'putObject' error for '$key'", e = e, context = TAG)
        }
    }

    suspend fun getObject(key: String): Result<VssItem?> = withContext(bgDispatcher) {
        isSetup.await()
        Logger.debug("VSS 'getObject' call for '$key'", context = TAG)
        runCatching {
            vssGet(
                key = key,
            )
        }.onSuccess {
            if (it == null) {
                Logger.warn("VSS 'getObject' success null for '$key'", context = TAG)
            } else {
                Logger.debug("VSS 'getObject' success for '$key'", context = TAG)
            }
        }.onFailure { e ->
            Logger.error("VSS 'getObject' error for '$key'", e = e, context = TAG)
        }
    }

    companion object Companion {
        private const val TAG = "VssBackupClient"
    }
}
