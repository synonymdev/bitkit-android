package to.bitkit.repositories

import com.synonym.bitkitcore.PreActivityMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.nowMillis
import to.bitkit.ext.nowTimestamp
import to.bitkit.services.CoreService
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Singleton
class PreActivityMetadataRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val clock: Clock,
) {
    private val _preActivityMetadataChanged = MutableStateFlow(0L)
    val preActivityMetadataChanged: StateFlow<Long> = _preActivityMetadataChanged.asStateFlow()

    private fun notifyChanged() = _preActivityMetadataChanged.update { nowMillis(clock) }

    suspend fun getAllPreActivityMetadata(): Result<List<PreActivityMetadata>> = withContext(ioDispatcher) {
        return@withContext runCatching {
            coreService.activity.getAllPreActivityMetadata()
        }.onFailure { e ->
            Logger.error("getAllPreActivityMetadata error", e, context = TAG)
        }
    }

    suspend fun upsertPreActivityMetadata(list: List<PreActivityMetadata>): Result<Unit> = withContext(ioDispatcher) {
        return@withContext runCatching {
            coreService.activity.upsertPreActivityMetadata(list)
            notifyChanged()
        }.onFailure { e ->
            Logger.error("upsertPreActivityMetadata error", e, context = TAG)
        }
    }

    suspend fun addPreActivityMetadata(metadata: PreActivityMetadata): Result<Unit> = withContext(ioDispatcher) {
        return@withContext runCatching {
            coreService.activity.addPreActivityMetadata(metadata)
            notifyChanged()
        }.onFailure { e ->
            Logger.error("addPreActivityMetadata error", e, context = TAG)
        }
    }

    suspend fun addPreActivityMetadataTags(
        paymentId: String,
        tags: List<String>,
    ): Result<Unit> = withContext(ioDispatcher) {
        return@withContext runCatching {
            coreService.activity.addPreActivityMetadataTags(paymentId, tags)
            notifyChanged()
            Logger.debug("Added tags to pre-activity metadata: paymentId=$paymentId, tags=$tags", context = TAG)
        }.onFailure { e ->
            Logger.error("addPreActivityMetadataTags error for paymentId: $paymentId", e, context = TAG)
        }
    }

    suspend fun removePreActivityMetadataTags(
        paymentId: String,
        tags: List<String>,
    ): Result<Unit> = withContext(ioDispatcher) {
        return@withContext runCatching {
            coreService.activity.removePreActivityMetadataTags(paymentId, tags)
            notifyChanged()
            Logger.debug("Removed tags from pre-activity metadata: paymentId=$paymentId, tags=$tags", context = TAG)
        }.onFailure { e ->
            Logger.error("removePreActivityMetadataTags error for paymentId: $paymentId", e, context = TAG)
        }
    }

    suspend fun resetPreActivityMetadataTags(paymentId: String): Result<Unit> = withContext(ioDispatcher) {
        return@withContext runCatching {
            coreService.activity.resetPreActivityMetadataTags(paymentId)
            notifyChanged()
            Logger.debug("Reset tags for pre-activity metadata: paymentId=$paymentId", context = TAG)
        }.onFailure { e ->
            Logger.error("resetPreActivityMetadataTags error for paymentId: $paymentId", e, context = TAG)
        }
    }

    suspend fun getPreActivityMetadata(
        searchKey: String,
        searchByAddress: Boolean = false,
    ): Result<PreActivityMetadata?> = withContext(ioDispatcher) {
        return@withContext runCatching {
            coreService.activity.getPreActivityMetadata(searchKey, searchByAddress)
        }.onFailure { e ->
            Logger.error(
                "getPreActivityMetadata error for searchKey: $searchKey, searchByAddress: $searchByAddress",
                e,
                context = TAG
            )
        }
    }

    suspend fun deletePreActivityMetadata(paymentId: String): Result<Unit> = withContext(ioDispatcher) {
        return@withContext runCatching {
            coreService.activity.deletePreActivityMetadata(paymentId)
            notifyChanged()
            Logger.debug("Deleted pre-activity metadata: paymentId=$paymentId", context = TAG)
        }.onFailure { e ->
            Logger.error("deletePreActivityMetadata error for paymentId: $paymentId", e, context = TAG)
        }
    }

    @Suppress("LongParameterList")
    suspend fun savePreActivityMetadata(
        id: String,
        paymentHash: String? = null,
        txId: String? = null,
        address: String,
        isReceive: Boolean,
        tags: List<String>,
        feeRate: ULong? = null,
        isTransfer: Boolean = false,
        channelId: String? = null,
    ): Result<Unit> = withContext(ioDispatcher) {
        return@withContext runCatching {
            require(tags.isNotEmpty() || isTransfer)

            val preActivityMetadata = PreActivityMetadata(
                paymentId = id,
                createdAt = nowTimestamp().toEpochMilli().toULong(),
                tags = tags,
                paymentHash = paymentHash,
                txId = txId,
                address = address,
                isReceive = isReceive,
                feeRate = feeRate ?: 0u,
                isTransfer = isTransfer,
                channelId = channelId ?: "",
            )
            coreService.activity.upsertPreActivityMetadata(listOf(preActivityMetadata))
            notifyChanged()
            Logger.debug("Pre-activity metadata saved: $preActivityMetadata", context = TAG)
        }.onFailure { e ->
            Logger.error("savePreActivityMetadata error", e, context = TAG)
        }
    }

    private companion object {
        const val TAG = "PreActivityMetadataRepo"
    }
}
