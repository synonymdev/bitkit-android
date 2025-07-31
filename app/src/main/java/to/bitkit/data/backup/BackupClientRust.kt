package to.bitkit.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import to.bitkit.data.dto.VssListDto
import to.bitkit.data.dto.VssObjectDto
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.BackupCategory
import to.bitkit.utils.Logger
import uniffi.vss_rust_client_ffi.vssDelete
import uniffi.vss_rust_client_ffi.vssGet
import uniffi.vss_rust_client_ffi.vssList
import uniffi.vss_rust_client_ffi.vssNewClient
import uniffi.vss_rust_client_ffi.vssStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class BackupClientRust @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val vssStoreIdProvider: VssStoreIdProvider,
) : BackupClient {
    private val isSetup = CompletableDeferred<Unit>()

    override suspend fun setup() = withContext(bgDispatcher) {
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

    override suspend fun putObject(
        category: BackupCategory,
        data: ByteArray,
    ): Result<VssObjectDto> = withContext(bgDispatcher) {
        isSetup.await()

        val key = category.name.lowercase()

        Logger.debug("VSS 'putObject' call for '$key'", context = TAG)

        runCatching {
            val item = vssStore(
                key = key,
                value = data,
            )

            Logger.debug("VSS 'putObject' success for '$key' at version: ${item.version}", context = TAG)

            VssObjectDto(
                key = item.key,
                version = item.version,
                data = item.value,
            )
        }.onFailure { e ->
            Logger.error("VSS 'putObject' error for '$key'", e = e, context = TAG)
        }
    }

    override suspend fun getObject(category: BackupCategory): Result<VssObjectDto?> = withContext(bgDispatcher) {
        isSetup.await()

        val key = category.name.lowercase()

        Logger.debug("VSS 'getObject' call for '$key'", context = TAG)

        runCatching {
            val item = vssGet(
                key = key,
            )

            if (item == null) {
                Logger.warn("VSS 'getObject' found no backup for '$key'", context = TAG)
                return@runCatching null
            }

            VssObjectDto(
                key = item.key,
                version = item.version,
                data = item.value,
            )
        }.onFailure { e ->
            Logger.error("VSS 'getObject' error for '$key'", e = e, context = TAG)
        }
    }

    // TODO remove
    override suspend fun deleteObject(category: BackupCategory, version: Long): Result<Unit> =
        withContext(bgDispatcher) {
            isSetup.await()

            val key = category.name.lowercase()

            Logger.debug("VSS 'deleteObject' call for '$key'", context = TAG)

            runCatching {

                val wasDeleted = vssDelete(
                    key = category.name.lowercase(),
                )

                if (!wasDeleted) throw IllegalStateException("VSS found no backup to delete for '$key'")

                Logger.debug("VSS deleted backup '$key'", context = TAG)
            }
                .onFailure { e ->
                    Logger.error("VSS deleteObject error for '$key'", e = e, context = TAG)
                }
        }

    // TODO remove
    override suspend fun listObjects(
        keyPrefix: String?,
        pageSize: Int?,
        pageToken: String?,
    ): Result<VssListDto> = withContext(bgDispatcher) {
        isSetup.await()

        runCatching {
            val items = vssList(prefix = keyPrefix)

            VssListDto(
                objects = items.map { item ->
                    VssObjectDto(
                        key = item.key,
                        version = item.version,
                        data = item.value,
                    )
                },
                nextPageToken = null,
                globalVersion = null
            )
        }
    }

    companion object {
        private const val TAG = "BackupClientRust"
    }
}
